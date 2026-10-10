package com.cooker.shopping.service.impl;

import com.cooker.common.ApiException;
import com.cooker.conversation.ConversationIdParser;
import com.cooker.conversation.dto.ShoppingSource;
import com.cooker.conversation.service.MealPersistenceService;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.cooker.shopping.dto.ShoppingRequest;
import com.cooker.shopping.entity.ShoppingList;
import com.cooker.shopping.mapper.ShoppingMapper;
import com.cooker.shopping.service.*;
import com.cooker.shopping.vo.ShoppingView;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.cooker.shopping.service.ShoppingJson.*;

/** 跨模块仅依赖公共Service；模型、报价及持久库存不参与采购需求事务。 */
@Service
public class ShoppingServiceImpl implements ShoppingService {
    private final ShoppingMapper mapper;
    private final MealPersistenceService meals;
    private final CurrentUserService current;
    private final RecipeMealFactsService facts;
    public ShoppingServiceImpl(ShoppingMapper mapper,MealPersistenceService meals,CurrentUserService current,RecipeMealFactsService facts){this.mapper=mapper;this.meals=meals;this.current=current;this.facts=facts;}
    @Override @Transactional(readOnly=true)
    public ShoppingView.Preview preview(String conversation,String meal,int version,String option){
        if(version<=0)throw invalid();var source=meals.readShoppingSource(current.requireUserId(),ConversationIdParser.parse(conversation),ConversationIdParser.parse(meal),version,ConversationIdParser.parse(option),false);
        return new ShoppingView.Preview(1,ShoppingDemandCalculator.source(source),ShoppingDemandCalculator.lines(source));
    }
    @Override @Transactional
    public Creation create(ShoppingRequest request){
        long user=current.requireUserId();mapper.lockRequest(user,request.requestId());String inputHash=hash(request);var prior=mapper.request(user,request.requestId());
        if(prior!=null)return replay(user,prior,inputHash);
        var source=meals.readShoppingSource(user,request.conversationId(),request.mealId(),request.expectedVersion(),request.optionId(),true);
        var header=ShoppingDemandCalculator.source(source);if(!header.sourceFingerprint().equals(request.sourceFingerprint()))throw conflict("SHOPPING_SOURCE_CHANGED");
        var demand=ShoppingDemandCalculator.calculate(source,request.confirmations());
        var view=new ShoppingView(1,null,null,"CURRENT",header,demand.lines(),demand.items(),demand.status(),"NOT_MATCHED",demand.costs());
        var row=new ShoppingList();row.userId=user;row.conversationId=source.conversationId();row.mealId=source.mealId();row.version=source.version();row.optionId=source.optionId();row.requestId=request.requestId();row.inputHash=inputHash;row.sourceFingerprint=request.sourceFingerprint();row.status=demand.status();row.budgetStatus=demand.costs().budgetStatus();row.unknownItemCount=demand.costs().unknownItemCount();row.budgetYuan=source.budgetYuan()==null?null:source.budgetYuan().setScale(2,RoundingMode.HALF_UP);
        if(row.unknownItemCount==0){row.estimatedDemandCost=new BigDecimal("0.00");row.consumptionCost=new BigDecimal("0.00");row.purchaseOutlay=new BigDecimal("0.00");}
        row.evidenceJson=encode(source);row.viewJson=encode(view);
        Long id=mapper.insert(row);
        if(id==null)return replay(user,mapper.request(user,request.requestId()),inputHash);
        int ordinal=0;for(var item:demand.items())if(mapper.item(id,user,++ordinal,item)!=1)throw new IllegalStateException("采购明细保存失败");
        // 父行/餐单锁持续到提交；再次核验可见性/指纹，失败使整份清单回滚。
        var finalSource=meals.readShoppingSource(user,source.conversationId(),source.mealId(),source.version(),source.optionId(),true);
        if(!hash(finalSource).equals(request.sourceFingerprint()))throw conflict("SHOPPING_SOURCE_CHANGED");
        var saved=mapper.get(user,id);var finalView=new ShoppingView(1,id.toString(),saved.createdAt.toInstant().toString(),"CURRENT",header,demand.lines(),demand.items(),demand.status(),"NOT_MATCHED",demand.costs());
        if(mapper.view(user,id,encode(finalView))!=1)throw new IllegalStateException("采购快照保存失败");
        return new Creation(true,finalView);
    }
    private Creation replay(long user,ShoppingList row,String hash){if(row==null||!row.inputHash.equals(hash))throw conflict("SHOPPING_REQUEST_CONFLICT");return new Creation(false,read(user,row));}
    @Override @Transactional(readOnly=true)
    public ShoppingView get(String id){long user=current.requireUserId();var row=mapper.get(user,ConversationIdParser.parse(id));if(row==null)throw missing();return read(user,row);}
    private ShoppingView read(long user,ShoppingList row){
        try {
            var source=JSON.readValue(row.evidenceJson,ShoppingSource.class);var now=facts.readFacts(user,source.recipes().stream().map(ShoppingSource.RecipeReference::recipeId).toList());
            String state=now.stream().allMatch(f->source.recipes().stream().anyMatch(r->r.recipeId()==f.recipeId()&&r.factFingerprint().equals(f.factFingerprint())))?"CURRENT":"CHANGED";
            var view=JSON.readValue(row.viewJson,ShoppingView.class);
            return new ShoppingView(view.schemaVersion(),view.id(),view.createdAt(),state,view.source(),view.lines(),view.items(),view.status(),view.matchingStatus(),view.costs());
        }catch(ApiException e){if("RECIPE_NOT_FOUND".equals(e.code()))throw missing();throw e;}catch(Exception e){throw new IllegalStateException("采购历史快照读取失败");}
    }
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"SHOPPING_NOT_FOUND","采购清单或引用菜谱不存在或不可访问");}
}
