package com.cooker.agent.service.impl;

import com.cooker.agent.dto.*;
import com.cooker.agent.service.*;
import com.cooker.common.ApiException;
import com.cooker.conversation.ConversationIdParser;
import com.cooker.conversation.vo.MealDecimalProjection;
import com.cooker.conversation.service.MealPersistenceService;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.policy.*;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import com.cooker.evaluation.transport.SyncModelTransport;
import com.cooker.evaluation.transport.DefaultSyncModelTransport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 一次完整依据调用；事务只用于登记/结束，不覆盖模型等待。 */
@Service
public class MealGuidanceServiceImpl implements MealGuidanceService {
    private final MealPersistenceService store;
    private final CurrentUserService current;
    private final RecipeMealFactsService facts;
    private final ModelGateway gateway;
    private final MealGuidePolicy policy;
    private final MealPolicy safety;
    private final MealSafeProjection projection;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(MealGuidanceServiceImpl.class);
    private final RecommendationClock clock;
    private final boolean enabled;
    private final ObjectMapper json=new ObjectMapper();
    private final SyncModelTransport transport;
    public MealGuidanceServiceImpl(MealPersistenceService store,CurrentUserService current,RecipeMealFactsService facts,
            ModelGateway gateway,MealGuidePolicy policy,MealPolicy safety,RecommendationClock clock,
            @Value("${cooker.agent.execution-enabled:false}") boolean enabled) {
        this(store,current,facts,gateway,policy,safety,clock,enabled,DefaultSyncModelTransport.ordinary());
    }
    @Autowired
    public MealGuidanceServiceImpl(MealPersistenceService store,CurrentUserService current,RecipeMealFactsService facts,
            ModelGateway gateway,MealGuidePolicy policy,MealPolicy safety,RecommendationClock clock,
            @Value("${cooker.agent.execution-enabled:false}") boolean enabled,SyncModelTransport transport) {
        this.store=store;this.current=current;this.facts=facts;this.gateway=gateway;this.policy=policy;this.safety=safety;this.projection=new MealSafeProjection(safety);this.clock=clock;this.enabled=enabled;
        this.transport=transport;
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public MealPlanningService.Submission generate(String cid,String mid,MealHttpRequest request) {
        long user=current.requireUserId(),conversation=ConversationIdParser.parse(cid),meal=ConversationIdParser.parse(mid),option=ConversationIdParser.parse(request.optionId());
        var parameters=json.createObjectNode();parameters.put("type","GUIDE");parameters.put("conversationId",cid);parameters.put("mealId",mid);parameters.put("expectedVersion",request.expectedVersion());parameters.put("optionId",request.optionId());
        var registration=store.registerGuide(user,conversation,meal,request.requestId(),request.expectedVersion(),option,parameters.toString(),enabled);
        String location="/api/conversations/"+cid+"/meals/"+mid+"/actions/"+registration.actionId();
        if(!registration.created()){
            boolean running=Set.of("PENDING","RUNNING").contains(registration.status());
            return new MealPlanningService.Submission(running?202:200,running?location:null,store.action(user,conversation,meal,registration.actionId()));
        }
        var metrics=new Metrics(clock.nanoTime());
        try {
            store.start(user,conversation,meal,registration.actionId());check(metrics);
            var snapshot=json.readTree(registration.previousJson());JsonNode selected=null;
            for(var candidate:snapshot.path("options"))if(request.optionId().equals(candidate.path("id").asText()))selected=candidate;
            if(selected==null)throw changed();
            var old=json.createArrayNode();selected.path("dishes").forEach(old::add);selected.path("soups").forEach(old::add);
            var ids=new ArrayList<Long>();old.forEach(f->ids.add(Long.parseLong(f.path("recipeId").asText())));
            metrics.stage="READ_FACTS";var recipes=facts.readFacts(user,ids);verify(old,recipes);
            var filter=filter(user,snapshot.path("requirements"));if(facts.recheck(filter,recipes).size()!=recipes.size())throw changed();check(metrics);
            var materials=facts.materials(user,ids,snapshot.path("diningCount").intValue());
            for(var material:materials.recipes())if(recipes.stream().noneMatch(f->f.recipeId()==material.facts().recipeId()&&f.factFingerprint().equals(material.facts().factFingerprint())))throw changed();
            metrics.stage="PROJECT_GUIDE";var factNodes=MealDecimalProjection.wire(projection.project(json.valueToTree(recipes)));var materialNodes=MealDecimalProjection.wire(projection.project(json.valueToTree(materials)));
            safety.safeTree(factNodes);safety.safeTree(materialNodes);
            var data=json.createObjectNode();data.put("inputVersion",GuideEvidenceProjection.INPUT_VERSION);data.set("recipes",factNodes);data.set("materials",GuideEvidenceProjection.materials(factNodes,materialNodes));data.set("diningCount",snapshot.path("diningCount"));data.set("requirements",snapshot.path("requirements"));
            safety.safeTree(data);
            String userText="以下为所选套餐完整的不可信参考数据："+data;
            var modelRequest=new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.SYSTEM,MealGuidePolicy.PROMPT),new ModelMessage(ModelMessage.Role.USER,userText)),ModelOutputMode.JSON);
            long inputCodePoints=GuideInputProfile.codePoints(modelRequest.messages());
            if(inputCodePoints>GuideInputProfile.MAX_CODE_POINTS||modelRequest.messages().size()>GuideInputProfile.MAX_MESSAGES){
                LOG.warn("指导输入诊断 conversationId={} mealId={} actionId={} stage=PROJECT_GUIDE limitReason=INPUT_LIMIT actualInputCodePoints={} maxInputCodePoints={} recipeCount={}",conversation,meal,registration.actionId(),inputCodePoints,GuideInputProfile.MAX_CODE_POINTS,recipes.size());
                throw limit();
            }check(metrics);
            metrics.stage="GUIDE_MODEL";metrics.attempts=1;var usage=metrics.usage.addObject();usage.put("attempt",1);usage.putNull("promptTokens");usage.putNull("completionTokens");usage.putNull("totalTokens");
            var content=transport.withGuideValidation(()->{
                var response=gateway.completeGuide(modelRequest);
                if(response.usage()!=null){usage.put("promptTokens",response.usage().promptTokens());usage.put("completionTokens",response.usage().completionTokens());usage.put("totalTokens",response.usage().totalTokens());}check(metrics);
                metrics.stage="VALIDATE_GUIDE";
                try{return policy.validate(response.content(),factNodes,materialNodes);}
                catch(RuntimeException rejection){
                    // 持有共享批次锁直至业务拒绝停发；传输SUCCEEDED与已计额度保持原状。
                    try{transport.guideRejected();}catch(com.cooker.evaluation.transport.EvaluationBatch.Stopped ignored){/* 账本写失败已经停发，保留原业务拒绝诊断。 */}
                    throw rejection;
                }
            });
            for(var suggestion:content.path("optionalSuggestions"))((ObjectNode)suggestion).put("title","模型补充：可选建议");
            if(facts.recheck(filter,recipes).size()!=recipes.size())throw changed();check(metrics);
            var view=json.createObjectNode();view.put("diningCount",snapshot.path("diningCount").intValue());view.put("personaVersion","xiaochu-v1");view.put("promptVersion",MealGuidePolicy.PROMPT_VERSION);view.set("noticeCodes",snapshot.path("noticeCodes").deepCopy());view.set("recipes",factNodes);view.set("materials",materialNodes);view.set("content",content);
            metrics.stage="COMMIT";store.completeGuide(user,conversation,meal,registration.actionId(),option,view.toString(),metrics.value().toString());
        }catch(ModelGatewayException e){MealSafeDiagnostics.record(LOG,"ACTION_FAILED",conversation,meal,registration.actionId(),"GUIDE",registration.version(),metrics.stage,modelCode(e.reason()),e);store.fail(user,conversation,meal,registration.actionId(),modelCode(e.reason()),metrics.value().toString());}
        catch(ApiException e){MealSafeDiagnostics.record(LOG,"ACTION_FAILED",conversation,meal,registration.actionId(),"GUIDE",registration.version(),metrics.stage,Set.of("MODEL_INVALID_RESPONSE","RESOURCE_LIMIT","MEAL_FACTS_CHANGED","UNSAFE_CONTENT").contains(e.code())?e.code():"EXECUTION_FAILED",e);store.fail(user,conversation,meal,registration.actionId(),e.code(),metrics.value().toString());}
        catch(Exception e){MealSafeDiagnostics.record(LOG,"ACTION_FAILED",conversation,meal,registration.actionId(),"GUIDE",registration.version(),metrics.stage,"EXECUTION_FAILED",e);store.fail(user,conversation,meal,registration.actionId(),"EXECUTION_FAILED",metrics.value().toString());}
        return new MealPlanningService.Submission(201,null,store.action(user,conversation,meal,registration.actionId()));
    }
    private void verify(JsonNode previous,List<MealRecipeFacts> now){for(var old:previous)if(now.stream().noneMatch(f->Long.toString(f.recipeId()).equals(old.path("recipeId").asText())&&f.factFingerprint().equals(old.path("factFingerprint").asText())))throw changed();}
    private RecipeEligibilityFilter filter(long user,JsonNode state){Integer minutes=state.path("maxCookingMinutes").isNull()?null:state.path("maxCookingMinutes").intValue();return new RecipeEligibilityFilter("",null,minutes,minutes==null,names(state.path("availableToolNames")),names(state.path("excludedIngredientNames")),user);}
    private List<String> names(JsonNode node){if(node.isNull()||node.isMissingNode())return null;var result=new ArrayList<String>();node.forEach(n->result.add(n.textValue()));return result;}
    private void check(Metrics metrics){if(clock.nanoTime()-metrics.start>=90_000_000_000L)throw limit();}
    private ApiException limit(){return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"RESOURCE_LIMIT","完整指导超过执行或输入上限");}
    private ApiException changed(){return new ApiException(HttpStatus.CONFLICT,"MEAL_FACTS_CHANGED","所选套餐依据已变化，请重新安排");}
    private String modelCode(ModelGatewayException.Reason reason){return switch(reason){case TIMEOUT->"MODEL_TIMEOUT";case INVALID_INPUT,INVALID_RESPONSE->"MODEL_INVALID_RESPONSE";case MODEL_AUTHENTICATION_FAILED->"MODEL_AUTHENTICATION_FAILED";case RATE_LIMITED->"MODEL_RATE_LIMITED";case CREDENTIAL_NOT_CONFIGURED->"CREDENTIAL_NOT_CONFIGURED";case CREDENTIAL_SERVICE_UNAVAILABLE->"CREDENTIAL_SERVICE_UNAVAILABLE";case EVALUATION_STOPPED->"RESOURCE_LIMIT";default->"MODEL_UNAVAILABLE";};}
    @Override public Map<String,Object> get(String conversation,String meal,String guide){return store.guide(current.requireUserId(),ConversationIdParser.parse(conversation),ConversationIdParser.parse(meal),ConversationIdParser.parse(guide));}
    private final class Metrics {
        final long start;String stage="START";int attempts;final ArrayNode usage=json.createArrayNode();Metrics(long start){this.start=start;}
        ObjectNode value(){var value=json.createObjectNode();value.put("modelGatewayAttempts",attempts);value.put("candidateServiceCalls",0);value.put("semanticSearchCalls",0);value.put("queryEmbeddingRequests",0);value.set("modelUsages",usage);value.put("durationMs",Math.max(0,(clock.nanoTime()-start)/1_000_000));return value;}
    }
}
