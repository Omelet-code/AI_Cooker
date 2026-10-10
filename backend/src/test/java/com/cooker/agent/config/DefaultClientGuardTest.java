package com.cooker.agent.config;

import static org.assertj.core.api.Assertions.*;
import com.cooker.agent.dto.ModelOutputMode;
import com.cooker.evaluation.transport.*;
import com.cooker.evaluation.transport.SyncModelTransport.Lane;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.service.impl.QueryEmbeddingServiceImpl;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 实际两个默认生产客户端，经生产守卫向本机替身发送，不创建无关SDK客户端冒充接线。 */
class DefaultClientGuardTest {
    @TempDir Path dir;
    final Prompt prompt=new Prompt(new SystemMessage("请输出JSON"));
    EvaluationBatch batch(String id,int qm,int qh,int lm,int lh)throws Exception{return new EvaluationBatch(id,"fake-approval",dir.resolve(id+".jsonl"),qm,qh,lm,lh);}
    QueryEmbeddingServiceImpl query(Server s,SyncModelTransport transport){
        var p=new RetrievalIndexProperties();p.setApiKey("fake-b1-key");p.setEndpoint(s.url()+"/query");return new QueryEmbeddingServiceImpl(p,transport);
    }
    DefaultDeepSeekChatClientFactory llm(Server s,SyncModelTransport transport){return new DefaultDeepSeekChatClientFactory(s.url(),Duration.ofSeconds(1),Duration.ofMillis(150),transport);}
    @Test void 普通模式旧构造器和两条实际协议兼容()throws Exception{
        try(var s=new Server();var transport=DefaultSyncModelTransport.ordinary()){
            assertThat(query(s,transport).embed("番茄")).hasSize(1024);assertThat(s.json.get().path("parameters").path("text_type").asText()).isEqualTo("query");
            llm(s,transport).create("fake-b1-key",ModelOutputMode.JSON).call(prompt);
            assertThat(s.json.get().path("max_tokens").asInt()).isEqualTo(2048);
            llm(s,transport).createGuide("fake-b1-key").call(prompt);assertThat(s.json.get().path("max_tokens").asInt()).isEqualTo(8192);
            assertThat(s.authorization.get()).isEqualTo("Bearer fake-b1-key");assertThat(s.calls).hasValue(3);
            var noKey=new QueryEmbeddingServiceImpl(new RetrievalIndexProperties());assertThat(noKey.embed("番茄")).isNull();
            var factory=new DefaultDeepSeekChatClientFactory();assertThat(factory.defaultReadTimeout()).isEqualTo(Duration.ofSeconds(30));assertThat(factory.guideReadTimeout()).isEqualTo(Duration.ofSeconds(60));
        }
    }
    @Test void 两默认客户端共用批次且协议和真实接收计数一致()throws Exception{
        try(var s=new Server();var b=batch("success",4,4,6,6);var transport=DefaultSyncModelTransport.controlled(b)){
            assertThat(query(s,transport).embed("番茄")).hasSize(1024);
            var factory=llm(s,transport);factory.create("fake-b1-key",ModelOutputMode.JSON).call(prompt);
            assertThat(s.json.get().path("model").asText()).isEqualTo("deepseek-flash");
            assertThat(s.json.get().path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(s.json.get().path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(s.json.get().path("max_tokens").asInt()).isEqualTo(2048);
            factory.createGuide("fake-b1-key").call(prompt);assertThat(s.json.get().path("max_tokens").asInt()).isEqualTo(8192);
            assertThat(s.calls).hasValue(3);assertThat(b.methods(Lane.QUERY)).isEqualTo(1);assertThat(b.http(Lane.QUERY)).isEqualTo(1);
            assertThat(b.methods(Lane.LLM)).isEqualTo(2);assertThat(b.http(Lane.LLM)).isEqualTo(2);
            assertThat(Files.readString(dir.resolve("success.jsonl"))).contains("METHOD_RESERVED","HTTP_RESERVED","SUCCEEDED","\"usage\":null").doesNotContain("fake-b1-key","请输出JSON");
        }
    }
    @Test void 受控无上下文和无方法票据均零发送()throws Exception{
        try(var s=new Server();var t=DefaultSyncModelTransport.controlled(null)){
            assertThatThrownBy(()->query(s,t).embed("番茄")).isInstanceOf(EvaluationBatch.Stopped.class);
            assertThatThrownBy(()->llm(s,t).create("fake-b1-key",ModelOutputMode.JSON)).isInstanceOf(EvaluationBatch.Stopped.class);
            assertThat(s.calls).hasValue(0);
        }
        try(var s=new Server();var b=batch("no-ticket",1,1,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            var client=t.builder(Lane.QUERY,URI.create(s.url()+"/query"),Duration.ofSeconds(1),Duration.ofSeconds(1)).build();
            assertThatThrownBy(()->client.post().uri(s.url()+"/query").body(Map.of()).retrieve().body(String.class)).isInstanceOf(RuntimeException.class);
            assertThat(s.calls).hasValue(0);assertThat(b.stopped()).isTrue();
        }
    }
    @Test void 两通道错误和重定向均只接收一次并跨通道停()throws Exception{
        for(int code:List.of(401,403,429,500,503,307))for(Lane lane:Lane.values()){
            try(var s=new Server();var b=batch("error-"+code+"-"+lane,4,4,6,6);var t=DefaultSyncModelTransport.controlled(b)){
                s.status=code;
                assertThatThrownBy(()->{if(lane==Lane.QUERY)query(s,t).embed("番茄");else llm(s,t).create("fake-b1-key",ModelOutputMode.JSON).call(prompt);}).isInstanceOf(RuntimeException.class);
                assertThat(s.calls).hasValue(1);assertThat(b.http(lane)).isEqualTo(1);assertThat(b.methods(lane)).isEqualTo(1);assertThat(b.stopped()).isTrue();
                assertThatThrownBy(()->query(s,t).embed("追加")).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(()->llm(s,t).createGuide("fake-b1-key")).isInstanceOf(RuntimeException.class);
                assertThat(s.calls).hasValue(1);
            }
        }
    }
    @Test void 两通道断开与超时不隐式补发未知占尝试()throws Exception{
        for(Lane lane:Lane.values())for(boolean drop:List.of(true,false)){
            try(var s=new Server();var b=batch("unknown-"+lane+"-"+drop,4,4,6,6);var t=DefaultSyncModelTransport.controlled(b)){
                s.drop=drop;s.delay=drop?0:250;
                SyncModelTransport shortReads=new SyncModelTransport(){
                    public org.springframework.web.client.RestClient.Builder builder(Lane l,URI u,Duration c,Duration r){return t.builder(l,u,c,Duration.ofMillis(60));}
                    public <T>T call(Lane l,java.util.function.Supplier<T> op){return t.call(l,op);}
                    public org.springframework.ai.chat.model.ChatModel model(org.springframework.ai.chat.model.ChatModel d){return t.model(d);}
                };
                assertThatThrownBy(()->{if(lane==Lane.QUERY)query(s,shortReads).embed("番茄");else llm(s,shortReads).createGuide("fake-b1-key").call(prompt);}).isInstanceOf(RuntimeException.class);
                assertThat(s.calls).hasValue(1);assertThat(b.http(lane)).isEqualTo(1);assertThat(b.stopped()).isTrue();
                assertThat(Files.readString(dir.resolve("unknown-"+lane+"-"+drop+".jsonl"))).contains("UNKNOWN");
            }
        }
    }
    @Test void 方法和HTTP分别限额且并发追加网页请求不能越界()throws Exception{
        try(var s=new Server();var b=batch("concurrent",1,1,6,6);var t=DefaultSyncModelTransport.controlled(b);var pool=Executors.newFixedThreadPool(2)){
            var q=query(s,t);var success=new AtomicInteger();
            var task=(Callable<Void>)()->{try{q.embed("番茄");success.incrementAndGet();}catch(RuntimeException ignored){}return null;};
            for(var f:pool.invokeAll(List.of(task,task)))f.get();
            assertThat(success).hasValue(1);assertThat(s.calls).hasValue(1);assertThat(b.stopped()).isTrue();
            assertThatThrownBy(()->llm(s,t).createGuide("fake-b1-key")).isInstanceOf(RuntimeException.class);
        }
        try(var s=new Server();var b=batch("http-zero",1,0,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            assertThatThrownBy(()->query(s,t).embed("番茄")).isInstanceOf(RuntimeException.class);
            assertThat(b.methods(Lane.QUERY)).isEqualTo(1);assertThat(b.http(Lane.QUERY)).isZero();assertThat(s.calls).hasValue(0);
        }
    }
    @Test void stream与document被发送前拒绝且旧账本不可重开()throws Exception{
        try(var s=new Server();var b=batch("protocol",1,1,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            assertThatThrownBy(()->t.call(Lane.QUERY,()->t.builder(Lane.QUERY,URI.create(s.url()+"/query"),Duration.ofSeconds(1),Duration.ofSeconds(1)).build()
                .post().uri(s.url()+"/query").body(Map.of("parameters",Map.of("text_type","document"))).retrieve().body(String.class))).isInstanceOf(RuntimeException.class);
            assertThat(s.calls).hasValue(0);
            assertThatThrownBy(()->batch("protocol",1,1,1,1)).isInstanceOf(FileAlreadyExistsException.class);
        }
        try(var s=new Server();var b=batch("stream",1,1,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            assertThatThrownBy(()->llm(s,t).createGuide("fake-b1-key").stream(prompt)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(s.calls).hasValue(0);assertThat(b.stopped()).isTrue();
        }
    }
    @Test void 无Key或响应解码失败停止且不把未知usage补零()throws Exception{
        try(var b=batch("no-key",1,1,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            assertThatThrownBy(()->new QueryEmbeddingServiceImpl(new RetrievalIndexProperties(),t).embed("番茄")).isInstanceOf(EvaluationBatch.Stopped.class);
            assertThat(b.http(Lane.QUERY)).isZero();assertThat(b.stopped()).isTrue();
        }
        try(var s=new Server();var b=batch("bad-json",1,1,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            s.invalid=true;assertThatThrownBy(()->query(s,t).embed("番茄")).isInstanceOf(RuntimeException.class);
            assertThat(s.calls).hasValue(1);assertThat(b.stopped()).isTrue();
        }
    }
    @Test void 配置只绑定独立批准文件且缺上下文不创建账本()throws Exception{
        var config=new EvaluationTransportConfiguration();var p=new EvaluationHttpProperties();
        try(var normal=config.syncModelTransport(p)){assertThat(normal).isNotNull();}
        p.setEnabled(true);try(var missing=config.syncModelTransport(p)){assertThatThrownBy(()->missing.call(Lane.QUERY,()->new float[]{1})).isInstanceOf(EvaluationBatch.Stopped.class);}
        var json=new ObjectMapper();var approval=dir.resolve("fake-approval.json");var ledger=dir.resolve("configured.jsonl");
        json.writeValue(approval.toFile(),Map.of("schemaVersion",1,"batchId","configured","authorizationId","fake-b1-approval",
            "ledgerPath",ledger.toString(),"queryMethods",4,"queryHttp",4,"llmMethods",6,"llmHttp",6));
        p.setBatchFile(approval.toString());try(var configured=config.syncModelTransport(p)){assertThat(configured).isNotNull();assertThat(Files.exists(ledger)).isTrue();}
        assertThatThrownBy(()->config.syncModelTransport(p)).isInstanceOf(FileAlreadyExistsException.class);
    }
    @Test void LLM截断和无效内容停止而非成功且批次之间不共享额度()throws Exception{
        for(String content:List.of("not-json","{} {}")){
            try(var s=new Server();var b=batch(content.equals("not-json")?"invalid-llm":"trailing-llm",4,4,6,6);var t=DefaultSyncModelTransport.controlled(b)){
                s.content=content;assertThatThrownBy(()->llm(s,t).createGuide("fake-b1-key").call(prompt)).isInstanceOf(RuntimeException.class);
                assertThat(b.stopped()).isTrue();assertThat(s.calls).hasValue(1);
            }
        }
        try(var s=new Server();var b=batch("truncated",4,4,6,6);var t=DefaultSyncModelTransport.controlled(b)){
            s.finish="length";assertThatThrownBy(()->llm(s,t).createGuide("fake-b1-key").call(prompt)).isInstanceOf(RuntimeException.class);
            assertThat(b.stopped()).isTrue();assertThat(s.calls).hasValue(1);
        }
        try(var a=batch("retrieval",18,18,0,0);var b=batch("meals",4,4,6,6)){
            var ticket=a.reserve(Lane.QUERY);assertThatThrownBy(()->b.beforeHttp(ticket)).isInstanceOf(EvaluationBatch.Stopped.class);
            assertThat(a.methods(Lane.QUERY)).isEqualTo(1);assertThat(b.methods(Lane.QUERY)).isZero();
        }
    }
    @Test void 写失败和启动属性漂移均停止且零出站()throws Exception{
        try(var s=new Server();var b=batch("write-failed",1,1,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            try{Thread.currentThread().interrupt();assertThatThrownBy(()->query(s,t).embed("番茄")).isInstanceOf(RuntimeException.class);}
            finally{Thread.interrupted();}
            assertThat(b.stopped()).isTrue();assertThat(s.calls).hasValue(0);
        }
        try(var s=new Server();var b=batch("drift",1,1,1,1);var t=DefaultSyncModelTransport.controlled(b)){
            String old=System.getProperty("jdk.httpclient.enableAllMethodRetry");
            try{System.setProperty("jdk.httpclient.enableAllMethodRetry","true");assertThatThrownBy(()->query(s,t).embed("番茄")).isInstanceOf(IllegalStateException.class);}
            finally{System.setProperty("jdk.httpclient.enableAllMethodRetry",old);}
            assertThat(b.stopped()).isTrue();assertThat(s.calls).hasValue(0);
        }
    }
    static final class Server implements AutoCloseable{
        final HttpServer server;final ExecutorService pool=Executors.newCachedThreadPool();final AtomicInteger calls=new AtomicInteger();
        final AtomicReference<JsonNode> json=new AtomicReference<>();final AtomicReference<String> authorization=new AtomicReference<>();
        volatile int status=200,delay;volatile boolean drop,invalid;volatile String finish="stop",content="{}";
        Server()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
            server.createContext("/",e->{calls.incrementAndGet();json.set(new ObjectMapper().readTree(e.getRequestBody().readAllBytes()));authorization.set(e.getRequestHeaders().getFirst("Authorization"));
                if(drop){e.close();return;}if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
                String vector="[1,"+"0,".repeat(1022)+"0]";
                String body=invalid?"{bad":"/query".equals(e.getRequestURI().getPath())?"{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":"+vector+"}]}}":
                    "{\"id\":\"local\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"+new ObjectMapper().writeValueAsString(content)+"},\"finish_reason\":\""+finish+"\"}]}";
                if(status>=300&&status<400)e.getResponseHeaders().set("Location",url()+"/should-not-follow");
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");
                try{e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}catch(java.io.IOException ignored){/* 替身超时客户端已断开。 */}finally{e.close();}
            });server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){server.stop(0);pool.shutdownNow();}
    }
}
