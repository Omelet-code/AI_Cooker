package com.cooker.policy;

import org.slf4j.Logger;

/** 只输出服务端生成的元信息；禁止传异常对象、消息或业务正文给日志框架。 */
public final class MealSafeDiagnostics {
    private MealSafeDiagnostics(){}
    public static void record(Logger log,String event,long conversation,long meal,long action,String type,int version,String stage,String code,Exception exception){
        var unsafe=exception instanceof MealSafeProjection.Unsafe u?u:null;
        var guide=exception instanceof MealGuideRejected g?g:null;
        String path=unsafe==null?("UNSAFE_CONTENT".equals(code)?"model.output":"NONE"):unsafe.fieldPath;
        String rule=unsafe==null?("UNSAFE_CONTENT".equals(code)?"OUTPUT_BOUNDARY":"NONE"):unsafe.rule;
        if(guide!=null){path=guide.fieldPath();rule=guide.rule().name();}
        log.warn("餐单诊断 event={} conversationId={} mealId={} actionId={} type={} version={} stage={} code={} exceptionClass={} recipeId={} fieldPath={} rule={}",
                event,conversation,meal,action,type,version,stage,code,exception.getClass().getSimpleName(),unsafe==null?null:unsafe.recipeId,path,rule);
    }
}
