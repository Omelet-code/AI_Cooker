package com.cooker.evaluation.transport;

import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.http.client.*;
import org.springframework.web.client.RestClient;

/** 普通模式兼容旧工厂；受控模式只使用启动时已禁重试的JDK同步HTTP。 */
public class DefaultSyncModelTransport implements SyncModelTransport,AutoCloseable {
    private final boolean controlled;
    private final EvaluationBatch batch;
    private final ThreadLocal<EvaluationBatch.Ticket> active=new ThreadLocal<>();
    private DefaultSyncModelTransport(boolean controlled,EvaluationBatch batch){this.controlled=controlled;this.batch=batch;}
    public static DefaultSyncModelTransport ordinary(){return new DefaultSyncModelTransport(false,null);}
    public static DefaultSyncModelTransport controlled(EvaluationBatch batch){verifyRuntime();return new DefaultSyncModelTransport(true,batch);}
    public static void verifyRuntime(){
        var args=ManagementFactory.getRuntimeMXBean().getInputArguments();
        if(Runtime.version().feature()!=21||!args.contains("-Djdk.httpclient.enableAllMethodRetry=false")
                ||!args.contains("-Djdk.httpclient.disableRetryConnect=true")
                ||!"false".equals(System.getProperty("jdk.httpclient.enableAllMethodRetry"))
                ||!"true".equals(System.getProperty("jdk.httpclient.disableRetryConnect"))
                ||Boolean.parseBoolean(System.getProperty("jdk.internal.httpclient.disableHostnameVerification","false")))
            throw new IllegalStateException("受控模式须使用JDK21并在JVM启动时关闭POST及连接重试，保留TLS主机校验");
    }
    @Override public RestClient.Builder builder(Lane lane,URI endpoint,Duration connect,Duration read){
        if(!controlled){var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(connect);f.setReadTimeout(read);return RestClient.builder().requestFactory(f);}
        requireContext();verifyBoundRuntime();
        var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connect).build();
        var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(read);
        return RestClient.builder().requestFactory(new BudgetRequestFactory(factory,endpoint,lane,batch,active));
    }
    @Override public <T>T call(Lane lane,Supplier<T> operation){
        if(!controlled)return operation.get();
        requireContext();
        // 整个方法持有批次锁，其他网页线程不能在失败解码与全局停止之间插入新发送。
        synchronized(batch){
            verifyBoundRuntime();batch.check();if(active.get()!=null){batch.stop();throw new EvaluationBatch.Stopped();}
            var ticket=batch.reserve(lane);active.set(ticket);
            try{T result=operation.get();if(result==null){batch.failed(ticket,false);throw new EvaluationBatch.Stopped();}batch.complete(ticket);return result;}
            catch(RuntimeException e){batch.failed(ticket,ticket.sent);throw e;}
            finally{active.remove();}
        }
    }
    private void requireContext(){if(batch==null)throw new EvaluationBatch.Stopped();batch.check();}
    @Override public <T>T withGuideValidation(Supplier<T> operation){
        if(!controlled)return operation.get();
        // 缺上下文或已停批仍由实际网关构建/call分类，不在外层改成通用执行错误。
        if(batch==null)return operation.get();
        synchronized(batch){return operation.get();}
    }
    @Override public void guideRejected(){if(controlled&&batch!=null)batch.guideRejected();}
    private void verifyBoundRuntime(){try{verifyRuntime();}catch(RuntimeException e){batch.stop();throw e;}}
    @Override public org.springframework.ai.chat.model.ChatModel model(org.springframework.ai.chat.model.ChatModel delegate){
        if(!controlled)return delegate;
        return new org.springframework.ai.chat.model.ChatModel(){
            @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt){return DefaultSyncModelTransport.this.call(Lane.LLM,()->delegate.call(prompt));}
            @Override public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt){
                requireContext();batch.stop();throw new UnsupportedOperationException("受控评测禁止流式模型调用");
            }
            @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions(){return delegate.getDefaultOptions();}
        };
    }
    @Override public void close()throws Exception{if(batch!=null)batch.close();}
}
