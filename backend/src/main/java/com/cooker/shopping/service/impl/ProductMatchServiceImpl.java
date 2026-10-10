package com.cooker.shopping.service.impl;

import com.cooker.common.ApiException;
import com.cooker.conversation.ConversationIdParser;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.shopping.dto.ProductMatchRequest;
import com.cooker.shopping.entity.ProductMatchRow;
import com.cooker.shopping.mapper.*;
import com.cooker.shopping.provider.ProductProvider;
import com.cooker.shopping.service.*;
import com.cooker.shopping.vo.ProductMatchView;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.cooker.shopping.service.ShoppingJson.*;

/** 当前Provider是本地Mock；未来真实网络报价必须另设计事务边界。 */
@Service
public class ProductMatchServiceImpl implements ProductMatchService {
    private final ProductMatchMapper mapper;
    private final ShoppingMapper locks;
    private final ShoppingService demands;
    private final CurrentUserService users;
    private final ProductProvider provider;
    public ProductMatchServiceImpl(ProductMatchMapper mapper,ShoppingMapper locks,ShoppingService demands,CurrentUserService users,ProductProvider provider){this.mapper=mapper;this.locks=locks;this.demands=demands;this.users=users;this.provider=provider;}
    @Override @Transactional
    public Creation create(String listText,ProductMatchRequest request){
        long user=users.requireUserId(),list=ConversationIdParser.parse(listText);String inputHash=hash(Map.of("listId",list,"cityCode",request.cityCode()));
        locks.lockRequest(user,request.requestId());var old=mapper.request(user,request.requestId());
        if(old!=null){if(!old.inputHash.equals(inputHash))throw conflict("SHOPPING_MATCH_REQUEST_CONFLICT");return new Creation(false,read(listText,old));}
        if(mapper.lockList(user,list)==null)throw missing();var source=demands.get(listText);
        if(!"CURRENT".equals(source.factsStatus()))throw conflict("SHOPPING_SOURCE_CHANGED");
        var needs=ProductMatchCalculator.needs(source);ProductProvider.Offers offers=new ProductProvider.Offers("NOT_QUERIED",List.of());
        if(!needs.isEmpty())try{offers=provider.lookup(request.cityCode(),needs);}catch(RuntimeException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"SHOPPING_PROVIDER_UNAVAILABLE","Mock商品目录当前不可用，请检查本地配置");}
        if(offers==null||offers.catalogVersion()==null||offers.selections()==null)throw new ApiException(HttpStatus.BAD_GATEWAY,"SHOPPING_PROVIDER_UNAVAILABLE","Mock商品目录当前不可用");
        var calculated=ProductMatchCalculator.calculate(source,request.cityCode(),offers);var row=new ProductMatchRow();row.userId=user;row.listId=list;row.requestId=request.requestId();row.inputHash=inputHash;row.sourceFingerprint=source.source().sourceFingerprint();row.cityCode=request.cityCode();row.catalogVersion=offers.catalogVersion();row.matchingStatus=calculated.matchingStatus();row.viewJson=encode(calculated);
        Long id=mapper.insert(row,calculated.costs());if(id==null){var winner=mapper.request(user,request.requestId());if(winner==null||!winner.inputHash.equals(inputHash))throw conflict("SHOPPING_MATCH_REQUEST_CONFLICT");return new Creation(false,read(listText,winner));}
        int order=0;for(var line:calculated.lines())if(mapper.item(id,user,list,++order,line)!=1)throw new IllegalStateException("商品明细保存失败");
        for(var group:calculated.purchaseGroups())if(mapper.group(id,user,list,group)!=1)throw new IllegalStateException("采购组保存失败");
        var finalSource=demands.get(listText);if(!"CURRENT".equals(finalSource.factsStatus())||!source.source().sourceFingerprint().equals(finalSource.source().sourceFingerprint()))throw conflict("SHOPPING_SOURCE_CHANGED");
        var saved=mapper.get(user,list,id);var view=decorate(calculated,id.toString(),saved.capturedAt.toInstant().toString(),"CURRENT");
        if(mapper.view(id,user,encode(view))!=1)throw new IllegalStateException("商品快照保存失败");return new Creation(true,view);
    }
    @Override @Transactional(readOnly=true) public ProductMatchView get(String listText,String idText){long user=users.requireUserId(),list=ConversationIdParser.parse(listText);var row=mapper.get(user,list,ConversationIdParser.parse(idText));if(row==null)throw missing();return read(listText,row);}
    @Override @Transactional(readOnly=true) public ProductMatchView latest(String listText){long user=users.requireUserId(),list=ConversationIdParser.parse(listText);demands.get(listText);var row=mapper.latest(user,list);return row==null?null:read(listText,row);}
    private ProductMatchView read(String list,ProductMatchRow row){var current=demands.get(list);try{return decorate(JSON.readValue(row.viewJson,ProductMatchView.class),row.id.toString(),row.capturedAt.toInstant().toString(),current.factsStatus());}catch(Exception e){throw new IllegalStateException("商品历史快照读取失败");}}
    private ProductMatchView decorate(ProductMatchView v,String id,String time,String state){return new ProductMatchView(v.schemaVersion(),id,v.listId(),v.sourceFingerprint(),state,v.provider(),v.realData(),v.catalogVersion(),v.cityCode(),time,v.matchingStatus(),v.lines(),v.purchaseGroups(),v.costs(),v.message());}
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"SHOPPING_MATCH_NOT_FOUND","采购清单或匹配快照不存在或不可访问");}
}
