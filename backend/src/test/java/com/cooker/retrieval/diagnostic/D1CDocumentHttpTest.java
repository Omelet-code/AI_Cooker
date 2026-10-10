package com.cooker.retrieval.diagnostic;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.service.impl.DashScopeEmbeddingModel;
import com.cooker.retrieval.service.impl.EmbeddingProviderException;
import com.cooker.retrieval.service.impl.IndexRunStoppedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 平台协议使用替身与本机临时HTTP，不读取真实Key，不出站到百炼。 */
class D1CDocumentHttpTest {
    @TempDir Path directory;
    static String response(int size) {
        return "{\"output\":{\"embeddings\":[" + java.util.stream.IntStream.range(0,size).mapToObj(i ->
                "{\"text_index\":"+i+",\"embedding\":["+"0.1,".repeat(1023)+"0.1]}").collect(java.util.stream.Collectors.joining(",")) + "]},\"usage\":{\"total_tokens\":17}}";
    }
    @ParameterizedTest @CsvSource({"401,AUTH","403,AUTH","400,PARAMETER","429,RATE_LIMIT","503,SERVER","302,REDIRECT"})
    void HTTP分类保持有限脱敏(int status, EmbeddingProviderException.Kind kind) {
        assertThat(DashScopeEmbeddingModel.classify(status,status==429 ? "{\"code\":\"Throttling.Requests\"}" : "第三方私密正文")).isEqualTo(kind);
    }
    @Test void 配额类429不能当成普通限流() {
        assertThat(DashScopeEmbeddingModel.classify(429,"{\"code\":\"Throttling.AllocationQuota\",\"message\":\"secret\"}")).isEqualTo(EmbeddingProviderException.Kind.QUOTA);
        assertThat(DashScopeEmbeddingModel.classify(429,"未知响应")).isEqualTo(EmbeddingProviderException.Kind.QUOTA);
    }
    @Test void 文档参数及usage可观测但查询不占本次费用账本() throws Exception {
        var recipe=BoundedIndexLedgerTest.recipe(1,1); var props=BoundedIndexLedgerTest.properties(); props.setApiKey("test-only");
        var client=RestClient.builder(); var server=MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo(props.getEndpoint())).andExpect(content().string(containsString("\"text_type\":\"query\""))).andRespond(withSuccess(response(1),MediaType.APPLICATION_JSON));
        server.expect(requestTo(props.getEndpoint())).andExpect(content().string(containsString("\"text_type\":\"document\"")))
                .andExpect(content().string(containsString("\"output_type\":\"dense\""))).andExpect(content().string(containsString("\"dimension\":1024")))
                .andRespond(withSuccess(response(1),MediaType.APPLICATION_JSON));
        try(var ledger=new BoundedIndexLedger(directory,BoundedIndexLedgerTest.plan(List.of(recipe)),0)) {
            var model=new DashScopeEmbeddingModel(client.build(),new ObjectMapper(),props,ledger);
            assertThat(model.embedQuery("测试查询")).hasSize(1024); assertThat(ledger.summary()).containsEntry("httpAttempts",0);
            ledger.begin("execute"); var chunk=BoundedIndexLedgerTest.chunks(recipe).subList(0,1); ledger.prepareBatch(chunk);
            assertThat(model.embed(List.of(chunk.getFirst().content()))).hasSize(1); ledger.finish();
            assertThat(ledger.summary()).containsEntry("httpAttempts",1).containsEntry("observedUsageTokens",17L);
        } server.verify();
    }
    @Test void 重定向不携带鉴权发送第二次请求() throws Exception {
        AtomicInteger sent=new AtomicInteger(), followed=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/embedding",exchange -> {sent.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/other"); exchange.sendResponseHeaders(302,-1); exchange.close();});
        server.createContext("/other",exchange -> {followed.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {
            var recipe=BoundedIndexLedgerTest.recipe(1,1); var props=BoundedIndexLedgerTest.properties(); props.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort()+"/embedding");props.setApiKey("test-only");
            try(var ledger=new BoundedIndexLedger(directory,IndexPlan.build(DocumentIndexTool.DATABASE,props,List.of(recipe)),0)) {
                ledger.begin("execute"); var chunks=BoundedIndexLedgerTest.chunks(recipe).subList(0,1); ledger.prepareBatch(chunks);
                var model=new DashScopeEmbeddingModel(DocumentIndexTool.httpClient(1000,1000),new ObjectMapper(),props,ledger);
                assertThatThrownBy(() -> model.embed(List.of(chunks.getFirst().content()))).isInstanceOf(IndexRunStoppedException.class).hasMessageContaining("REDIRECT");
                assertThat(ledger.summary()).containsEntry("httpAttempts",1).containsEntry("stopped",true);
            }
            assertThat(sent.get()).isEqualTo(1);assertThat(followed.get()).isZero();
        } finally {server.stop(0);}
    }
    @Test void 真实本地HTTP超时仍占尝试且无隐式POST重试() throws Exception {
        AtomicInteger sent=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/slow",exchange -> {sent.incrementAndGet();exchange.getRequestBody().readAllBytes();
            try {Thread.sleep(200);} catch(InterruptedException exception) {Thread.currentThread().interrupt();} finally {exchange.close();}});server.start();
        try {
            var recipe=BoundedIndexLedgerTest.recipe(1,1); var props=BoundedIndexLedgerTest.properties(); props.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort()+"/slow");props.setApiKey("test-only");
            try(var ledger=new BoundedIndexLedger(directory,IndexPlan.build(DocumentIndexTool.DATABASE,props,List.of(recipe)),0)) {
                ledger.begin("execute");var chunks=BoundedIndexLedgerTest.chunks(recipe).subList(0,1);ledger.prepareBatch(chunks);
                var model=new DashScopeEmbeddingModel(DocumentIndexTool.httpClient(1000,50),new ObjectMapper(),props,ledger);
                assertThatThrownBy(() -> model.embed(List.of(chunks.getFirst().content()))).isInstanceOf(EmbeddingProviderException.class).hasMessageNotContaining("test-only");
                assertThat(ledger.summary()).containsEntry("httpAttempts",1).containsEntry("failedResponses",1L);ledger.finish();
            } assertThat(sent.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @Test void 无响应断连也不隐式重试POST() throws Exception {
        AtomicInteger sent=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/closed",exchange -> {sent.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.close();});server.start();
        try {
            var props=BoundedIndexLedgerTest.properties();props.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort()+"/closed");props.setApiKey("test-only");
            var model=new DashScopeEmbeddingModel(DocumentIndexTool.httpClient(1000,1000),new ObjectMapper(),props);
            assertThatThrownBy(() -> model.embed("测试")).isInstanceOf(EmbeddingProviderException.class);assertThat(sent.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @Test void 无效向量数值被拒绝而不入库() {
        var props=BoundedIndexLedgerTest.properties();props.setApiKey("test-only");var client=RestClient.builder();var server=MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo(props.getEndpoint())).andRespond(withSuccess(response(1).replaceFirst("0.1,","\"不可用\","),MediaType.APPLICATION_JSON));
        var model=new DashScopeEmbeddingModel(client.build(),new ObjectMapper(),props);
        assertThatThrownBy(() -> model.embed("测试")).isInstanceOf(EmbeddingProviderException.class).hasMessageContaining("非有限");server.verify();
    }
}
