package com.cooker.conversation.service;

import com.cooker.conversation.dto.MealRegistration;
import com.cooker.conversation.vo.PageView;
import java.util.Map;

/** 可信当前用户由调用方认证；本模块再次核验会话、餐单、动作归属。 */
public interface MealPersistenceService {
    com.cooker.conversation.dto.ShoppingSource readShoppingSource(long user,long conversation,long meal,int version,long option,boolean lock);
    MealRegistration register(long user, long conversation, Long meal, String type, String requestId,
            String content, String parameters, Integer profileDiningCount, Integer expectedVersion, boolean enabled);
    void start(long user, long conversation, long meal, long action);
    void complete(long user, long conversation, long meal, long action, String view, String metrics);
    void fail(long user, long conversation, long meal, long action, String code, String metrics);
    Map<String,Object> get(long user, long conversation, long meal);
    Map<String,Object> action(long user, long conversation, long meal, long action);
    PageView<Map<String,Object>> list(long user, long conversation, int page, int size);
    record Selection(boolean created, Map<String,Object> body) {}
    Selection select(long user, long conversation, long meal, String requestId, int version,
            long option, String parameters);
    MealRegistration registerGuide(long user,long conversation,long meal,String requestId,int version,long option,String parameters,boolean enabled);
    void completeGuide(long user,long conversation,long meal,long action,long option,String view,String metrics);
    Map<String,Object> guide(long user,long conversation,long meal,long guide);
}
