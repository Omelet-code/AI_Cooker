package com.cooker.conversation.service.impl;

import com.cooker.common.ApiException;
import com.cooker.conversation.dto.*;
import com.cooker.conversation.dto.MealRows.*;
import com.cooker.conversation.mapper.*;
import com.cooker.conversation.service.MealPersistenceService;
import com.cooker.conversation.vo.PageView;
import com.cooker.conversation.vo.MealDecimalProjection;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.cooker.recipe.dto.RecipeEligibilityFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 所有写入先锁同一conversation父行，与旧轮次协调但不共享消息或需求。 */
@Service
public class MealPersistenceServiceImpl implements MealPersistenceService {
    private final MealMapper meals;
    private final ConversationMapper conversations;
    private final RecipeMealFactsService recipes;
    private final MealGuideMapper guides;
    private final ShoppingSourceMapper shoppingSources;
    private final com.cooker.policy.MealPolicy safety;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public MealPersistenceServiceImpl(MealMapper meals, ConversationMapper conversations, RecipeMealFactsService recipes,
            MealGuideMapper guides,com.cooker.policy.MealPolicy safety,ShoppingSourceMapper shoppingSources) {
        this.meals=meals; this.conversations=conversations; this.recipes=recipes;this.guides=guides;this.safety=safety;
        this.shoppingSources=shoppingSources;
    }
    @Override @Transactional
    public MealRegistration register(long user,long conversation,Long meal,String type,String requestId,
            String content,String parameters,Integer profile,Integer expected,boolean enabled) {
        parent(user,conversation,true);
        var old=meals.request(user,conversation,requestId);
        if(old!=null) {
            if(!node(old.parameters).equals(node(parameters)) || !Objects.equals(old.inputText,content)
                    || !old.inputHash.equals(hash(parameters))) throw conflict();
            var session=required(user,conversation,old.mealId,false);
            return registration(old,session,false,old.result==null?session.viewJson:old.result);
        }
        Session session=null;
        String previous="{}";
        if(meal!=null) {
            session=required(user,conversation,meal,true);
            recover(session);
            if(meals.active(meal)!=null) throw conflict();
            if(expected==null || session.currentVersion!=expected || expected==Integer.MAX_VALUE) throw conflict();
            previous=session.viewJson;
        }
        // 同键重放优先于开关；关闭只拒绝新的执行，不登记无人执行的动作。
        if(!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AGENT_EXECUTION_DISABLED","餐单执行功能当前未启用");
        long id=meal==null?meals.create(user,conversation,profile):meal;
        if(session==null) session=required(user,conversation,id,true);
        int version=meal==null?1:session.currentVersion+1;
        var pending=base(session,version);
        if(meal!=null) {
            pending.set("requirements",node(previous).path("requirements").deepCopy());
            pending.set("diningCount",node(previous).path("diningCount").deepCopy());
            pending.set("diningCountSource",node(previous).path("diningCountSource").deepCopy());
        }
        pending.put("status","PENDING");
        save(session,version,pending,null);
        long seed=new java.security.SecureRandom().nextLong();
        long action=meals.register(user,conversation,id,version,requestId,type,content,parameters,hash(parameters),seed);
        conversations.touch(conversation,user);
        return new MealRegistration(action,id,version,seed,session.profileDiningCount,previous,true,"PENDING");
    }
    @Override @Transactional public void start(long user,long conversation,long meal,long action) {
        parent(user,conversation,true); required(user,conversation,meal,true);
        var row=lockedAction(user,conversation,meal,action);
        if(!"PENDING".equals(row.status)||meals.start(action)!=1) throw conflict();
    }
    @Override @Transactional public void complete(long user,long conversation,long meal,long action,String view,String metrics) {
        parent(user,conversation,true); var session=required(user,conversation,meal,true);
        var row=lockedAction(user,conversation,meal,action);
        if(!"RUNNING".equals(row.status)||row.version!=session.currentVersion||meals.expired(action)) throw conflict();
        ObjectNode result=(ObjectNode)node(view);
        decorate(result,session,row.version);
        if(!"CURRENT".equals(factsStatus(user,result))) throw new ApiException(HttpStatus.CONFLICT,"MEAL_FACTS_CHANGED","菜谱事实已变化，请重新安排");
        qualification(user,result);
        int ordinal=0;
        for(var option:result.withArray("options")) {
            ObjectNode object=(ObjectNode)option;
            object.put("ordinal",++ordinal); object.putNull("id");
            bounded(object.toString(),262144);
            long optionId=meals.option(user,conversation,meal,row.version,ordinal,object.toString());
            object.put("id",Long.toString(optionId));
        }
        bounded(result.toString(),524288);
        save(session,row.version,result,null);
        meals.finish(action,"SUCCEEDED",null,result.toString(),metrics);
        conversations.touch(conversation,user);
    }
    @Override @Transactional public void fail(long user,long conversation,long meal,long action,String code,String metrics) {
        parent(user,conversation,true); var session=required(user,conversation,meal,true);
        var row=lockedAction(user,conversation,meal,action);
        if(!Set.of("PENDING","RUNNING").contains(row.status)) return;
        // 错误码只来自编排固定枚举，不记录供应商正文、异常链或Prompt。
        Set<String> allowed=Set.of("MODEL_INVALID_RESPONSE","MODEL_TIMEOUT","MODEL_AUTHENTICATION_FAILED","MODEL_RATE_LIMITED",
                "MODEL_UNAVAILABLE","CREDENTIAL_NOT_CONFIGURED","CREDENTIAL_SERVICE_UNAVAILABLE","RESOURCE_LIMIT",
                "RETRIEVAL_FAILED","EXECUTION_FAILED","MEAL_FACTS_CHANGED","RUNTIME_INTERRUPTED","UNSAFE_CONTENT");
        if(!allowed.contains(code)) code="EXECUTION_FAILED";
        if(meals.expired(action)) code="RUNTIME_INTERRUPTED";
        var result=(ObjectNode)node(session.viewJson);
        if("GUIDE".equals(row.type)) {
            // 指导失败不能使用创建失败逻辑清掉套餐；餐与选择保持原状。
            if(meals.finish(action,"FAILED",code,result.toString(),metrics)!=1)throw conflict();
            conversations.touch(conversation,user);
            return;
        }
        result.put("status","FAILED"); result.put("answer","本次餐单未完成，请确认条件后再试。");
        result.putArray("options"); result.putNull("selectedOptionId");
        meals.finish(action,"FAILED",code,result.toString(),metrics);
        if(row.version==session.currentVersion) save(session,row.version,result,null);
        conversations.touch(conversation,user);
    }
    @Override public Map<String,Object> get(long user,long conversation,long meal) {
        parent(user,conversation,false);
        return map(protect(user,node(required(user,conversation,meal,false).viewJson)));
    }
    /** 采购只读来源；持锁模式必须加入调用方写事务，不恢复动作、不更新餐单。 */
    @Override @Transactional(readOnly=true)
    public ShoppingSource readShoppingSource(long user,long conversation,long meal,int version,long option,boolean lock) {
        if(lock&&org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("持锁采购读取必须加入创建清单的写事务");
        parent(user,conversation,lock);var session=required(user,conversation,meal,lock);
        if(session.currentVersion!=version)throw shoppingConflict("MEAL_VERSION_CONFLICT");
        if(shoppingSources.busy(user,conversation,meal))throw shoppingConflict("MEAL_BUSY");
        if(!"SELECTED".equals(session.status)||session.selectedOptionId==null||session.selectedOptionId!=option)
            throw shoppingConflict("MEAL_NOT_SELECTED");
        var view=node(session.viewJson);var selected=selectedView(view,option);
        String sourceState=factsStatus(user,selected);if("UNAVAILABLE".equals(sourceState))throw missing();
        if(!"CURRENT".equals(sourceState))throw shoppingConflict("MEAL_FACTS_CHANGED");
        qualification(user,selected);safety.safeTree(selected);
        if(session.diningCount==null||session.diningCount<=0)throw shoppingConflict("SHOPPING_SOURCE_CHANGED");
        try {
            // 沿用R4的有界十进制投影，历史指数不可绕过数值资源保护。
            var shoppingView=MealDecimalProjection.display(selected);var candidate=shoppingView.path("options").get(0);
            var materials=json.treeToValue(candidate.path("materials"),com.cooker.recipe.dto.MealMaterials.class);
            var refs=new ArrayList<ShoppingSource.RecipeReference>();
            for(String role:List.of("dishes","soups"))for(var fact:candidate.path(role))
                refs.add(new ShoppingSource.RecipeReference(Long.parseLong(fact.path("recipeId").asText()),fact.path("factFingerprint").asText()));
            if(materials==null||materials.targetDiningCount()!=session.diningCount||materials.recipes()==null||refs.isEmpty())throw shoppingConflict("SHOPPING_SOURCE_CHANGED");
            var expected=new HashMap<Long,String>();refs.forEach(ref->expected.put(ref.recipeId(),ref.factFingerprint()));
            var seen=new HashSet<Long>();for(var material:materials.recipes())
                if(!seen.add(material.facts().recipeId())||!Objects.equals(expected.get(material.facts().recipeId()),material.facts().factFingerprint()))throw shoppingConflict("SHOPPING_SOURCE_CHANGED");
            if(!seen.equals(expected.keySet()))throw shoppingConflict("SHOPPING_SOURCE_CHANGED");
            var budget=shoppingView.path("requirements").path("budgetYuan");
            return new ShoppingSource(conversation,meal,version,option,session.diningCount,
                    budget.isNull()||budget.isMissingNode()?null:new java.math.BigDecimal(budget.asText()),List.copyOf(refs),materials);
        }catch(ApiException e){throw e;}catch(Exception e){throw shoppingConflict("SHOPPING_SOURCE_CHANGED");}
    }
    private ApiException shoppingConflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"采购来源的版本、选择或事实已变化");}
    @Override public Map<String,Object> action(long user,long conversation,long meal,long action) {
        parent(user,conversation,false); var session=required(user,conversation,meal,false);
        var row=actionRow(user,conversation,meal,action);
        var value=json.createObjectNode(); var a=value.putObject("action");
        a.put("id",Long.toString(row.id)); a.put("mealId",Long.toString(row.mealId)); a.put("requestId",row.requestId);
        a.put("type",row.type); a.put("version",row.version); a.put("status",row.status);
        time(a,"createdAt",row.createdAt); time(a,"startedAt",row.startedAt); time(a,"finishedAt",row.finishedAt);
        if(row.errorCode==null)a.putNull("error"); else {var e=a.putObject("error");e.put("code",row.errorCode);e.put("message","本次餐单操作未完成，请确认条件后再试。");}
        a.set("metrics",node(row.metrics));
        // 终态读取动作自己的历史版本，不把当前餐新版本冒充旧请求结果。
        value.set("meal",protect(user,node(row.result==null?session.viewJson:row.result)));
        return map(value);
    }
    @Override public PageView<Map<String,Object>> list(long user,long conversation,int page,int size) {
        parent(user,conversation,false);
        if(page<1||size<1||size>50) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","分页参数无效");
        var items=meals.list(user,conversation,size,(long)(page-1)*size).stream().map(id->get(user,conversation,id)).toList();
        return new PageView<>(items,page,size,meals.count(user,conversation));
    }
    @Override @Transactional public Selection select(long user,long conversation,long meal,String request,int version,long option,String parameters) {
        parent(user,conversation,true); var session=required(user,conversation,meal,true);
        var prior=meals.request(user,conversation,request);
        if(prior!=null) {
            if(!node(prior.parameters).equals(node(parameters))||!prior.inputHash.equals(hash(parameters)))throw conflict();
            return new Selection(false,action(user,conversation,meal,prior.id));
        }
        recover(session);
        if(meals.active(meal)!=null||session.currentVersion!=version)throw conflict();
        if(session.selectedOptionId!=null) {
            if(session.selectedOptionId!=option)throw conflict();
            var existing=meals.selection(meal,version);
            return new Selection(false,action(user,conversation,meal,existing.id));
        }
        if(!"OPTIONS_READY".equals(session.status))throw conflict();
        var value=(ObjectNode)node(session.viewJson);
        if(!"CURRENT".equals(factsStatus(user,value)))throw new ApiException(HttpStatus.CONFLICT,"MEAL_FACTS_CHANGED","菜谱事实或可见性已变化，请重新安排");
        qualification(user,value);
        long action=meals.register(user,conversation,meal,version,request,"SELECT","",parameters,hash(parameters),null);
        if(meals.start(action)!=1)throw conflict();
        // 新动作行先登记并持锁，再锁方案；非法方案使整个选择事务回滚。
        if(meals.lockOption(user,conversation,meal,version,option)==null)throw conflict();
        value.put("status","SELECTED");value.put("selectedOptionId",Long.toString(option));
        value.put("answer","已选定，详细指导尚未开放。"); value.put("updatedAt",java.time.Instant.now().toString());
        bounded(value.toString(),524288);
        save(session,version,value,option);
        meals.finish(action,"SUCCEEDED",null,value.toString(),"{\"modelGatewayAttempts\":0,\"candidateServiceCalls\":0,\"semanticSearchCalls\":0,\"queryEmbeddingRequests\":0,\"modelUsages\":[],\"durationMs\":0}");
        conversations.touch(conversation,user);
        return new Selection(true,action(user,conversation,meal,action));
    }
    private void recover(Session session) {
        var active=meals.active(session.id);
        if(active!=null && meals.expired(active.id))fail(session.userId,session.conversationId,session.id,active.id,"RUNTIME_INTERRUPTED",active.metrics);
    }
    @Override @Transactional
    public MealRegistration registerGuide(long user,long conversation,long meal,String request,int version,long option,String parameters,boolean enabled) {
        parent(user,conversation,true);var session=required(user,conversation,meal,true);
        var prior=meals.request(user,conversation,request);
        if(prior!=null) {
            if(!node(prior.parameters).equals(node(parameters))||!prior.inputHash.equals(hash(parameters)))throw conflict();
            return registration(prior,session,false,prior.result==null?session.viewJson:prior.result);
        }
        recover(session);
        if(session.currentVersion!=version||!"SELECTED".equals(session.status)||session.selectedOptionId==null||session.selectedOptionId!=option)throw conflict();
        if(meals.active(meal)!=null)throw conflict();
        Long existing=guides.existing(meal,version,option);
        if(existing!=null)return registration(actionRow(user,conversation,meal,existing),session,false,session.viewJson);
        if(!enabled)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AGENT_EXECUTION_DISABLED","指导执行功能当前未启用");
        var view=(ObjectNode)node(session.viewJson);
        var selected=selectedView(view,option);
        if(!"CURRENT".equals(factsStatus(user,selected)))throw new ApiException(HttpStatus.CONFLICT,"MEAL_FACTS_CHANGED","菜谱事实已变化，请重新安排");
        qualification(user,selected);
        long action=meals.register(user,conversation,meal,version,request,"GUIDE","",parameters,hash(parameters),null);
        if(meals.lockOption(user,conversation,meal,version,option)==null)throw conflict();
        conversations.touch(conversation,user);
        return new MealRegistration(action,meal,version,0,session.profileDiningCount,session.viewJson,true,"PENDING");
    }
    @Override @Transactional
    public void completeGuide(long user,long conversation,long meal,long action,long option,String view,String metrics) {
        parent(user,conversation,true);var session=required(user,conversation,meal,true);
        var row=lockedAction(user,conversation,meal,action);
        if(!"GUIDE".equals(row.type)||!"RUNNING".equals(row.status)||row.version!=session.currentVersion||meals.expired(action)
                ||!"SELECTED".equals(session.status)||session.selectedOptionId==null||session.selectedOptionId!=option)throw conflict();
        if(meals.lockOption(user,conversation,meal,row.version,option)==null)throw conflict();
        var mealView=(ObjectNode)node(session.viewJson);
        var result=(ObjectNode)node(view);
        var selected=selectedView(mealView,option);
        if(!"CURRENT".equals(guideFactsStatus(user,result))||!"CURRENT".equals(factsStatus(user,selected)))throw new ApiException(HttpStatus.CONFLICT,"MEAL_FACTS_CHANGED","指导依据已变化，请重新安排");
        qualification(user,selected);safety.safeTree(result);
        result.put("schemaVersion",1);result.putNull("id");result.put("conversationId",Long.toString(conversation));result.put("mealId",Long.toString(meal));result.put("optionId",Long.toString(option));result.put("actionId",Long.toString(action));result.put("version",row.version);result.put("createdAt",java.time.Instant.now().toString());result.put("factsStatus","CURRENT");
        bounded(result.toString(),524288);
        long guide=guides.insert(user,conversation,meal,row.version,option,action,result.toString());result.put("id",Long.toString(guide));bounded(result.toString(),524288);
        if(guides.save(guide,result.toString())!=1)throw conflict();
        var reference=mealView.putObject("guide");reference.put("id",Long.toString(guide));reference.put("schemaVersion",1);reference.put("optionId",Long.toString(option));reference.put("version",row.version);
        mealView.put("guideAvailable",true);mealView.put("answer","指导已生成，可查看原菜谱依据与可选建议。");mealView.put("updatedAt",java.time.Instant.now().toString());bounded(mealView.toString(),524288);
        if(meals.finishGuide(action,mealView.toString(),metrics)!=1)throw conflict();
        save(session,row.version,mealView,option);conversations.touch(conversation,user);
    }
    @Override public Map<String,Object> guide(long user,long conversation,long meal,long guide) {
        parent(user,conversation,false);required(user,conversation,meal,false);
        String stored=guides.get(user,conversation,meal,guide);if(stored==null)throw missing();
        var view=(ObjectNode)node(stored);String state=guideFactsStatus(user,view);view.put("factsStatus",state);
        if(!"CURRENT".equals(state))view.withArray("noticeCodes").add("CHANGED".equals(state)?"FACTS_CHANGED":"FACTS_UNAVAILABLE");
        if("UNAVAILABLE".equals(state)){view.putNull("content");view.putArray("recipes");view.putNull("materials");}
        safety.safeTree(view);return map(MealDecimalProjection.display(view));
    }
    private String guideFactsStatus(long user,JsonNode guide) {
        var option=json.createObjectNode();option.set("dishes",guide.path("recipes"));option.putArray("soups");
        var meal=json.createObjectNode();meal.putArray("options").add(option);return factsStatus(user,meal);
    }
    private ObjectNode selectedView(JsonNode view,long option) {
        var selected=json.createObjectNode();selected.set("requirements",view.path("requirements"));var options=selected.putArray("options");
        for(var candidate:view.path("options"))if(Long.toString(option).equals(candidate.path("id").asText()))options.add(candidate);
        if(options.isEmpty())throw conflict();return selected;
    }
    private void parent(long user,long conversation,boolean lock) {
        if((lock?conversations.lockOwned(conversation,user):conversations.findOwned(conversation,user))==null)throw missing();
    }
    private Session required(long user,long conversation,long meal,boolean lock) {
        var value=lock?meals.lock(user,conversation,meal):meals.get(user,conversation,meal);if(value==null)throw missing();return value;
    }
    private Action actionRow(long user,long conversation,long meal,long action) {var row=meals.action(user,conversation,meal,action);if(row==null)throw missing();return row;}
    private Action lockedAction(long user,long conversation,long meal,long action) {var row=meals.lockAction(user,conversation,meal,action);if(row==null)throw missing();return row;}
    private void qualification(long user,JsonNode view) {
        var ids=new LinkedHashSet<Long>();
        for(var option:view.path("options"))for(String role:List.of("dishes","soups"))for(var fact:option.path(role))ids.add(Long.parseLong(fact.path("recipeId").asText()));
        if(ids.isEmpty())return;
        var state=view.path("requirements");
        Integer minutes=state.path("maxCookingMinutes").isIntegralNumber()?state.path("maxCookingMinutes").intValue():null;
        var filter=new RecipeEligibilityFilter("RANDOM".equals(state.path("mode").asText())?"":state.path("query").asText(),null,minutes,minutes==null,names(state.path("availableToolNames")),names(state.path("excludedIngredientNames")),user);
        var facts=recipes.readFacts(user,List.copyOf(ids));
        if(recipes.recheck(filter,facts).size()!=ids.size())throw new ApiException(HttpStatus.CONFLICT,"MEAL_FACTS_CHANGED","菜谱资格已变化，请重新安排");
    }
    private List<String> names(JsonNode values){if(values.isNull()||values.isMissingNode())return null;var result=new ArrayList<String>();values.forEach(value->result.add(value.textValue()));return result;}
    private MealRegistration registration(Action a,Session s,boolean created,String previous){return new MealRegistration(a.id,a.mealId,a.version,a.seed==null?0:a.seed,s.profileDiningCount,previous,created,a.status);}
    private ObjectNode base(Session session,int version) {
        var node=json.createObjectNode();decorate(node,session,version);node.putNull("diningCount");node.putNull("diningCountSource");
        var requirements=node.putObject("requirements");for(String key:List.of("dishCount","soupCount","mode","query","maxCookingMinutes","availableToolNames","budgetYuan"))requirements.putNull(key);
        requirements.putArray("excludedIngredientNames");requirements.putArray("availableIngredientNames");
        node.put("answer","餐单正在处理中。");node.putArray("options");node.putNull("selectedOptionId");node.putArray("noticeCodes");return node;
    }
    private void decorate(ObjectNode view,Session s,int version){view.put("schemaVersion",1);view.put("id",Long.toString(s.id));view.put("conversationId",Long.toString(s.conversationId));view.put("version",version);view.put("factsStatus","CURRENT");view.putNull("guide");view.put("guideAvailable",false);time(view,"createdAt",s.createdAt);view.put("updatedAt",java.time.Instant.now().toString());}
    private void save(Session s,int version,ObjectNode view,Long selected){meals.save(s.id,version,view.path("status").asText(),selected,view.path("diningCount").isIntegralNumber()?view.path("diningCount").intValue():null,view.path("diningCountSource").isTextual()?view.path("diningCountSource").textValue():null,view.toString());}
    private String factsStatus(long user,JsonNode view) {
        var fingerprints=new LinkedHashMap<Long,String>();
        for(var option:view.path("options"))for(String role:List.of("dishes","soups"))for(var fact:option.path(role))fingerprints.put(Long.parseLong(fact.path("recipeId").asText()),fact.path("factFingerprint").asText());
        if(fingerprints.isEmpty())return "CURRENT";
        try {var current=recipes.readFacts(user,List.copyOf(fingerprints.keySet()));return current.stream().allMatch(f->f.factFingerprint().equals(fingerprints.get(f.recipeId())))?"CURRENT":"CHANGED";}
        catch(ApiException failure){if("RECIPE_NOT_FOUND".equals(failure.code()))return "UNAVAILABLE";throw failure;}
    }
    private JsonNode protect(long user,JsonNode original){var view=(ObjectNode)original.deepCopy();String state=factsStatus(user,view);view.put("factsStatus",state);if(!"CURRENT".equals(state)){view.withArray("noticeCodes").add("CHANGED".equals(state)?"FACTS_CHANGED":"FACTS_UNAVAILABLE");if("UNAVAILABLE".equals(state)){view.putArray("options");view.putNull("selectedOptionId");view.putNull("guide");view.put("guideAvailable",false);view.put("answer","引用菜谱已不可访问，餐单事实已隐藏。");}}safety.safeTree(view);return MealDecimalProjection.display(view);}
    private Map<String,Object> map(JsonNode n){return json.convertValue(n,new TypeReference<Map<String,Object>>(){});}
    private JsonNode node(String value){try{return value==null?json.createObjectNode():json.readTree(value);}catch(Exception e){throw new IllegalStateException("餐单快照读取失败");}}
    private void time(ObjectNode n,String field,java.time.OffsetDateTime time){if(time==null)n.putNull(field);else n.put(field,time.toInstant().toString());}
    private void bounded(String value,int maximum){if(value.getBytes(StandardCharsets.UTF_8).length>maximum)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"RESOURCE_LIMIT","餐单快照超出资源上限");}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("运行环境不支持内容校验");}}
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"MEAL_NOT_FOUND","餐单或会话不存在");}
    private ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"MEAL_STATE_CONFLICT","餐单版本、请求或状态冲突");}
}
