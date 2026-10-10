package com.cooker.agent.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cooker.agent.dto.*;
import com.cooker.agent.service.*;
import com.cooker.agent.service.impl.ModelGatewayServiceImpl;
import com.cooker.identity.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** 真本机HTTP协议；Key都是虚构测试值，不接收费地址。 */
class MealGuideGatewayProtocolTest {
    @Test void 普通两万指导六万四包含系统与非BMP且超限零网络()throws Exception {
        try(var server=new Server()){
            var gateway=gateway(server,Duration.ofSeconds(1));
            gateway.complete(sized(20000));assertThat(server.calls).hasValue(1);
            assertThatThrownBy(()->gateway.complete(sized(20001))).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.INVALID_INPUT));assertThat(server.calls).hasValue(1);
            gateway.completeGuide(sized(64000));assertThat(server.calls).hasValue(2);assertThat(server.json().path("max_tokens").asInt()).isEqualTo(8192);
            long actual=0;for(var message:server.json().path("messages")){String content=message.path("content").asText();actual+=content.codePointCount(0,content.length());}assertThat(actual).isEqualTo(64000);
            assertThatThrownBy(()->gateway.completeGuide(sized(64001))).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.INVALID_INPUT));assertThat(server.calls).hasValue(2);
        }
    }
    @Test void 两档十六消息允许十七消息拒绝且不读取凭证()throws Exception {
        try(var server=new Server()){
            var gateway=gateway(server,Duration.ofSeconds(1));var messages=new ArrayList<ModelMessage>();
            for(int i=0;i<16;i++)messages.add(new ModelMessage(ModelMessage.Role.SYSTEM,"请输出完整JSON"));
            var request=new ModelChatRequest(messages,ModelOutputMode.JSON);gateway.complete(request);gateway.completeGuide(request);assertThat(server.calls).hasValue(2);
            messages.add(new ModelMessage(ModelMessage.Role.USER,"第十七条"));
            assertThatThrownBy(()->gateway.complete(request)).isInstanceOf(ModelGatewayException.class);assertThatThrownBy(()->gateway.completeGuide(request)).isInstanceOf(ModelGatewayException.class);assertThat(server.calls).hasValue(2);
            var current=mock(CurrentUserService.class);var credentials=mock(LlmCredentialService.class);var rejected=new ModelGatewayServiceImpl(current,credentials,new DefaultDeepSeekChatClientFactory());
            assertThatThrownBy(()->rejected.completeGuide(sized(64001))).isInstanceOf(ModelGatewayException.class);verifyNoInteractions(current,credentials);
        }
    }
    private ModelChatRequest sized(int points){String system="请输出完整JSON";int remaining=points-system.codePointCount(0,system.length());return new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.SYSTEM,system),new ModelMessage(ModelMessage.Role.USER,"😀".repeat(remaining))),ModelOutputMode.JSON);}
    @Test void 旧档2048与指导8192隔离且JSON与thinking协议不变()throws Exception {
        try(var server=new Server()){
            var gateway=gateway(server,Duration.ofSeconds(1));
            var request=request(ModelOutputMode.JSON);gateway.complete(request);assertThat(server.json().path("max_tokens").asInt()).isEqualTo(2048);
            gateway.completeGuide(request);assertThat(server.json().path("max_tokens").asInt()).isEqualTo(8192);assertThat(server.json().path("response_format").path("type").asText()).isEqualTo("json_object");assertThat(server.json().path("thinking").path("type").asText()).isEqualTo("disabled");assertThat(server.calls).hasValue(2);assertThat(server.authorization.get()).isEqualTo("Bearer fake-u4-protocol-key");
            var factory=new DefaultDeepSeekChatClientFactory();assertThat(factory.defaultReadTimeout()).isEqualTo(Duration.ofSeconds(30));assertThat(factory.guideReadTimeout()).isEqualTo(Duration.ofSeconds(60));
        }
    }
    @Test void 指导截断和429零重试且文本档拒绝()throws Exception {
        try(var server=new Server()){
            var gateway=gateway(server,Duration.ofSeconds(1));
            assertThatThrownBy(()->gateway.completeGuide(request(ModelOutputMode.TEXT))).isInstanceOf(ModelGatewayException.class);assertThat(server.calls).hasValue(0);
            server.finish.set("LENGTH");assertThatThrownBy(()->gateway.completeGuide(request(ModelOutputMode.JSON))).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.INVALID_RESPONSE));assertThat(server.calls).hasValue(1);
            server.status.set(429);assertThatThrownBy(()->gateway.completeGuide(request(ModelOutputMode.JSON))).isInstanceOfSatisfying(ModelGatewayException.class,e->{assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.RATE_LIMITED);assertThat(e.getCause()).isNull();});assertThat(server.calls).hasValue(2);
        }
    }
    @Test void 专用读取超时只发送一次()throws Exception {
        try(var server=new Server()){server.delay=300;var gateway=gateway(server,Duration.ofMillis(60));assertThatThrownBy(()->gateway.completeGuide(request(ModelOutputMode.JSON))).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.TIMEOUT));assertThat(server.calls).hasValue(1);}
    }
    private ModelGatewayServiceImpl gateway(Server server,Duration read){var current=mock(CurrentUserService.class);when(current.requireUserId()).thenReturn(1L);var credentials=mock(LlmCredentialService.class);when(credentials.decryptCurrentUserCredential()).thenReturn("fake-u4-protocol-key");return new ModelGatewayServiceImpl(current,credentials,new DefaultDeepSeekChatClientFactory(server.url(),Duration.ofSeconds(1),read));}
    private ModelChatRequest request(ModelOutputMode mode){return new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.SYSTEM,"请输出完整JSON")),mode);}
    private static final class Server implements AutoCloseable {
        final HttpServer http;final AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger(200);final AtomicReference<String> body=new AtomicReference<>(),authorization=new AtomicReference<>(),finish=new AtomicReference<>("stop");int delay;
        Server()throws Exception{http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/chat/completions",exchange->{calls.incrementAndGet();body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();}String response="{\"id\":\"mock\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\""+finish.get()+"\"}]}";byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");try{exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);}catch(java.io.IOException ignored){/* 超时用例的客户端已断开。 */}finally{exchange.close();}});http.start();}
        String url(){return "http://127.0.0.1:"+http.getAddress().getPort();}
        com.fasterxml.jackson.databind.JsonNode json()throws Exception{return new ObjectMapper().readTree(body.get());}
        public void close(){http.stop(0);}
    }
}
