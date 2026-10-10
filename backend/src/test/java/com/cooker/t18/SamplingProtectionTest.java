package com.cooker.t18;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.*;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.core.retry.*;
import org.springframework.web.reactive.function.client.WebClient;

class SamplingProtectionTest {
    @TempDir Path dir;
    @Test void HTTP前记账且与实际接收数一致并拒绝重复发送() throws Exception {
        try (var supplier = new LocalSupplier(); var ledger = ledger("retrieval",18,18,0,0)) {
            var t = ledger.reserve("Q01", SamplingLedger.Lane.QUERY);
            var client = GuardedLocalTransport.client(supplier.uri("/query"), ledger, t);
            send(client, supplier);
            assertThat(ledger.http(SamplingLedger.Lane.QUERY)).isEqualTo(supplier.received.get()).isEqualTo(1);
            ledger.finish(t, SamplingLedger.State.SUCCEEDED);
            assertThatThrownBy(() -> send(client, supplier)).isInstanceOf(RuntimeException.class);
            assertThat(supplier.received).hasValue(1);
            assertThat(Files.readString(dir.resolve("retrieval.jsonl"))).contains("METHOD_RESERVED", "HTTP_RESERVED", "SUCCEEDED", "\"usage\":null");
        }
    }
    @Test void 两个独立批次不能共享票据额度且旧账本不能覆盖() throws Exception {
        try (var retrieval=ledger("retrieval",18,18,0,0); var meals=ledger("meals",4,4,12,12)) {
            var t=retrieval.reserve("Q01",SamplingLedger.Lane.QUERY);
            assertThatThrownBy(()->meals.beforeHttp(t)).isInstanceOf(SamplingLedger.Stopped.class);
            assertThat(retrieval.methods(SamplingLedger.Lane.QUERY)).isEqualTo(1);
            assertThat(meals.methods(SamplingLedger.Lane.QUERY)).isZero();
            assertThatThrownBy(()->ledger("retrieval",18,18,0,0)).isInstanceOf(FileAlreadyExistsException.class);
        }
    }
    @Test void 超时占一次且跨query和LLM全局停止() throws Exception {
        try(var supplier=new LocalSupplier();var ledger=ledger("timeout",2,2,2,2)) {
            supplier.delay=800;
            var t=ledger.reserve("Q01",SamplingLedger.Lane.QUERY);
            var client=GuardedLocalTransport.client(supplier.uri("/query"),ledger,t);
            assertThatThrownBy(()->send(client,supplier)).isInstanceOf(RuntimeException.class);
            assertThat(t.state()).isEqualTo(SamplingLedger.State.UNKNOWN);
            assertThat(ledger.methods(SamplingLedger.Lane.QUERY)).isEqualTo(1);
            assertThat(ledger.http(SamplingLedger.Lane.QUERY)).isEqualTo(1);
            assertThat(supplier.received).hasValue(1);
            assertThatThrownBy(()->ledger.reserve("guide",SamplingLedger.Lane.LLM)).isInstanceOf(SamplingLedger.Stopped.class);
        }
    }
    @Test void 断开响应不隐式补发且未知不能恢复额度() throws Exception {
        try(var supplier=new LocalSupplier();var ledger=ledger("disconnect",2,2,0,0)) {
            supplier.drop=true;
            var t=ledger.reserve("Q01",SamplingLedger.Lane.QUERY);
            assertThatThrownBy(()->send(GuardedLocalTransport.client(supplier.uri("/query"),ledger,t),supplier)).isInstanceOf(RuntimeException.class);
            assertThat(supplier.received).hasValue(1);
            assertThat(t.state()).isEqualTo(SamplingLedger.State.UNKNOWN);
            assertThatThrownBy(()->ledger.reserve("Q02",SamplingLedger.Lane.QUERY)).isInstanceOf(SamplingLedger.Stopped.class);
        }
    }
    @Test void HTTP错误或重定向一次即停且没有跳往新地址() throws Exception {
        for(int status: List.of(401,403,429,500,307)) {
            try(var supplier=new LocalSupplier();var ledger=ledger("status"+status,1,1,1,1)) {
                supplier.status=status;
                var t=ledger.reserve("Q01",SamplingLedger.Lane.QUERY);
                assertThatThrownBy(()->send(GuardedLocalTransport.client(supplier.uri("/query"),ledger,t),supplier)).isInstanceOf(RuntimeException.class);
                assertThat(t.state()).isEqualTo(SamplingLedger.State.FAILED);
                assertThat(supplier.received).hasValue(1);
                assertThat(ledger.stopped()).isTrue();
            }
        }
    }
    @Test void document与外部地址拒绝且方法和HTTP分别耗尽() throws Exception {
        try(var supplier=new LocalSupplier();var ledger=ledger("forbidden",2,0,0,0)) {
            var t=ledger.reserve("Q01",SamplingLedger.Lane.QUERY);
            assertThatThrownBy(()->send(GuardedLocalTransport.client(supplier.uri("/query"),ledger,t),supplier)).isInstanceOf(RuntimeException.class);
            assertThat(ledger.methods(SamplingLedger.Lane.QUERY)).isEqualTo(1);
            assertThat(supplier.received).hasValue(0);
        }
        try(var supplier=new LocalSupplier();var ledger=ledger("document",1,1,0,0)) {
            var t=ledger.reserve("Q01",SamplingLedger.Lane.QUERY);
            var client=GuardedLocalTransport.client(supplier.uri("/query"),ledger,t);
            assertThatThrownBy(()->client.post().uri(supplier.uri("/query")).body(Map.of("parameters",Map.of("text_type","document"))).retrieve().body(String.class)).isInstanceOf(RuntimeException.class);
            assertThat(supplier.received).hasValue(0);
            assertThatThrownBy(()->GuardedLocalTransport.client(java.net.URI.create("https://example.com/query"),ledger,t)).isInstanceOf(IllegalArgumentException.class);
        }
        try(var ledger=ledger("methodzero",0,1,0,0)) {
            assertThatThrownBy(()->ledger.reserve("Q01",SamplingLedger.Lane.QUERY)).isInstanceOf(SamplingLedger.Stopped.class);
        }
    }
    @Test void 并发额度原子且账本写失败零发送() throws Exception {
        try(var ledger=ledger("concurrent",1,1,0,0);var pool=Executors.newFixedThreadPool(2)) {
            var successes=new java.util.concurrent.atomic.AtomicInteger();
            var tasks=List.of("A","B").stream().<Callable<Void>>map(a->()->{try{ledger.reserve(a,SamplingLedger.Lane.QUERY);successes.incrementAndGet();}catch(SamplingLedger.Stopped ignored){}return null;}).toList();
            for(var future:pool.invokeAll(tasks)) future.get();
            assertThat(successes).hasValue(1);
        }
        var ledger=ledger("closed",1,1,0,0); ledger.close();
        assertThatThrownBy(()->ledger.reserve("A",SamplingLedger.Lane.QUERY)).isInstanceOf(SamplingLedger.Stopped.class);
    }
    @Test void 实际DeepSeek协议替身也经过HTTP账本且零SDK重试() throws Exception {
        try(var supplier=new LocalSupplier();var ledger=ledger("llm",0,0,2,2)) {
            var t=ledger.reserve("meal",SamplingLedger.Lane.LLM);
            var client=GuardedLocalTransport.client(supplier.uri("/chat/completions"),ledger,t);
            var api=new DeepSeekApi("/chat/completions","/beta",client.mutate().baseUrl(supplier.uri("").toString()).build(),WebClient.builder().build());
            var model=DeepSeekChatModel.builder().deepSeekApi(api).options(DeepSeekChatOptions.builder().model("deepseek-flash").disableThinking().build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
            var current=org.mockito.Mockito.mock(com.cooker.identity.service.CurrentUserService.class);
            org.mockito.Mockito.when(current.requireUserId()).thenReturn(1L);
            var credentials=org.mockito.Mockito.mock(com.cooker.identity.service.LlmCredentialService.class);
            org.mockito.Mockito.when(credentials.decryptCurrentUserCredential()).thenReturn("fake-t18-llm-only");
            var factory=new com.cooker.agent.config.DeepSeekChatClientFactory() {
                public org.springframework.ai.chat.model.ChatModel create(String key,com.cooker.agent.dto.ModelOutputMode mode){return model;}
                public org.springframework.ai.chat.model.ChatModel createGuide(String key){return model;}
            };
            var gateway=new com.cooker.agent.service.impl.ModelGatewayServiceImpl(current,credentials,factory);
            var request=new com.cooker.agent.dto.ModelChatRequest(List.of(new com.cooker.agent.dto.ModelMessage(
                com.cooker.agent.dto.ModelMessage.Role.SYSTEM,"请输出JSON")),com.cooker.agent.dto.ModelOutputMode.JSON);
            assertThat(gateway.complete(request).content()).isEqualTo("{}");
            ledger.finish(t,SamplingLedger.State.SUCCEEDED);
            assertThat(supplier.received).hasValue(1);
            assertThat(ledger.http(SamplingLedger.Lane.LLM)).isEqualTo(1);
            ledger.stop();
            assertThatThrownBy(()->ledger.reserve("guide",SamplingLedger.Lane.LLM)).isInstanceOf(SamplingLedger.Stopped.class);
        }
    }
    @Test void 写账本失败关闭守卫且尚未发送HTTP() throws Exception {
        try(var supplier=new LocalSupplier();var ledger=ledger("writefail",1,1,0,0)) {
            try {
                Thread.currentThread().interrupt();
                assertThatThrownBy(()->ledger.reserve("Q01",SamplingLedger.Lane.QUERY)).isInstanceOf(SamplingLedger.Stopped.class);
            } finally { Thread.interrupted(); }
            assertThat(ledger.stopped()).isTrue();assertThat(ledger.methods(SamplingLedger.Lane.QUERY)).isZero();
            assertThat(supplier.received).hasValue(0);
        }
    }
    private SamplingLedger ledger(String name,int qm,int qh,int lm,int lh)throws Exception {return new SamplingLedger(name,dir.resolve(name+".jsonl"),qm,qh,lm,lh);}
    private void send(org.springframework.web.client.RestClient client,LocalSupplier supplier) {
        client.post().uri(supplier.uri("/query")).body(Map.of("parameters",Map.of("text_type","query"),"input",Map.of("texts",List.of("测试")))).retrieve().body(String.class);
    }
}
