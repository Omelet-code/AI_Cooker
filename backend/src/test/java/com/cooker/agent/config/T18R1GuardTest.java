package com.cooker.agent.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cooker.agent.dto.*;
import com.cooker.agent.service.*;
import com.cooker.agent.service.impl.ModelGatewayServiceImpl;
import com.cooker.evaluation.transport.*;
import com.cooker.identity.service.*;
import com.cooker.policy.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/** 仅虚构凭证、本机替身及独立临时账本，不重放缺失的真人响应。 */
class T18R1GuardTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    final ModelChatRequest request=new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.SYSTEM,"请输出JSON")),ModelOutputMode.JSON);
    ModelGatewayServiceImpl gateway(DefaultClientGuardTest.Server s,SyncModelTransport t){
        var current=mock(CurrentUserService.class);when(current.requireUserId()).thenReturn(1L);
        var credentials=mock(LlmCredentialService.class);when(credentials.decryptCurrentUserCredential()).thenReturn("fake-r1-key");
        return new ModelGatewayServiceImpl(current,credentials,new DefaultDeepSeekChatClientFactory(s.url(),Duration.ofSeconds(1),Duration.ofSeconds(1),t));
    }
    EvaluationBatch batch(String id,int lm)throws Exception{return new EvaluationBatch(id,"fake-r1-approval",dir.resolve(id+".jsonl"),2,2,lm,lm);}
    @Test void 已停构建和零额度call必须是资源拒绝而非凭证或坏响应()throws Exception{
        try(var s=new DefaultClientGuardTest.Server();var b=batch("stopped",2);var t=DefaultSyncModelTransport.controlled(b)){
            b.stop();assertThatThrownBy(()->gateway(s,t).completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason().name()).isEqualTo("EVALUATION_STOPPED"));assertThat(s.calls).hasValue(0);
        }
        try(var s=new DefaultClientGuardTest.Server();var b=batch("exhausted",0);var t=DefaultSyncModelTransport.controlled(b)){
            assertThatThrownBy(()->gateway(s,t).completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason().name()).isEqualTo("EVALUATION_STOPPED"));assertThat(s.calls).hasValue(0);
        }
    }
    @Test void 真实Policy拒绝日志必须提供受限规则字段而不记录模型正文()throws Exception{
        var logger=(Logger)LoggerFactory.getLogger("t18-r1-safe-test");var appender=new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            try{new MealGuidePolicy(new MealPolicy()).validate("{\"evil-secret-field\":\"fake-secret-body\"}",json.createArrayNode(),json.createObjectNode());fail("应拒绝未知结构");}
            catch(com.cooker.common.ApiException e){MealSafeDiagnostics.record(logger,"ACTION_FAILED",1,2,3,"GUIDE",1,"VALIDATE_GUIDE",e.code(),e);}
            String log=appender.list.getFirst().getFormattedMessage();assertThat(log).contains("rule=OBJECT_FIELDS","fieldPath=guide").doesNotContain("rule=NONE","evil-secret-field","fake-secret-body");
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void 缺上下文及包装后的停止仍单独分类零发送()throws Exception{
        try(var s=new DefaultClientGuardTest.Server();var t=DefaultSyncModelTransport.controlled(null)){
            assertThatThrownBy(()->gateway(s,t).complete(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason().name()).isEqualTo("EVALUATION_STOPPED"));assertThat(s.calls).hasValue(0);
        }
        var current=mock(CurrentUserService.class);when(current.requireUserId()).thenReturn(1L);
        var credentials=mock(LlmCredentialService.class);when(credentials.decryptCurrentUserCredential()).thenReturn("fake-key");
        var factory=mock(DeepSeekChatClientFactory.class);when(factory.createGuide(anyString())).thenThrow(new IllegalStateException("fake-secret",new EvaluationBatch.Stopped()));
        var gateway=new ModelGatewayServiceImpl(current,credentials,factory);
        assertThatThrownBy(()->gateway.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->{assertThat(e.reason().name()).isEqualTo("EVALUATION_STOPPED");assertThat(e.getMessage()).doesNotContain("fake-secret");assertThat(e.getCause()).isNull();});
        var model=mock(org.springframework.ai.chat.model.ChatModel.class);doReturn(model).when(factory).createGuide(anyString());when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(new IllegalStateException("fake-secret",new EvaluationBatch.Stopped()));
        assertThatThrownBy(()->gateway.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason().name()).isEqualTo("EVALUATION_STOPPED"));
    }
    @Test void 供应商状态保留分类且停批后不再发送()throws Exception{
        for(int status:List.of(401,403,429,503)){
            try(var s=new DefaultClientGuardTest.Server();var b=batch("http-"+status,3);var t=DefaultSyncModelTransport.controlled(b)){
                s.status=status;String reason=status==401||status==403?"MODEL_AUTHENTICATION_FAILED":status==429?"RATE_LIMITED":"PROVIDER_UNAVAILABLE";
                var g=gateway(s,t);assertThatThrownBy(()->g.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->{assertThat(e.reason().name()).isEqualTo(reason);assertThat(e.getCause()).isNull();});
                assertThat(b.stopped()).isTrue();assertThat(b.http(SyncModelTransport.Lane.LLM)).isEqualTo(1);
                assertThatThrownBy(()->g.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason().name()).isEqualTo("EVALUATION_STOPPED"));assertThat(s.calls).hasValue(1);
            }
        }
    }
    @Test void 超时及截断无效JSON仍是原分类()throws Exception{
        try(var s=new DefaultClientGuardTest.Server();var b=batch("timeout",2);var t=DefaultSyncModelTransport.controlled(b)){
            s.delay=500;var current=mock(CurrentUserService.class);when(current.requireUserId()).thenReturn(1L);var credentials=mock(LlmCredentialService.class);when(credentials.decryptCurrentUserCredential()).thenReturn("fake-key");
            var g=new ModelGatewayServiceImpl(current,credentials,new DefaultDeepSeekChatClientFactory(s.url(),Duration.ofSeconds(1),Duration.ofMillis(150),t));
            assertThatThrownBy(()->g.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason().name()).isEqualTo("TIMEOUT"));assertThat(s.calls).hasValue(1);assertThat(b.stopped()).isTrue();
        }
        for(String kind:List.of("length","not-json","{} {}","invalid-envelope")){
            try(var s=new DefaultClientGuardTest.Server();var b=batch("bad-"+kind.replaceAll("[^a-z]","x"),2);var t=DefaultSyncModelTransport.controlled(b)){
                if(kind.equals("length"))s.finish="length";else if(kind.equals("invalid-envelope"))s.invalid=true;else s.content=kind;
                assertThatThrownBy(()->gateway(s,t).completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason().name()).isEqualTo("INVALID_RESPONSE"));assertThat(s.calls).hasValue(1);assertThat(b.stopped()).isTrue();
            }
        }
    }
    @Test void 真正凭证缺失解密与空Key继续分类且零发送()throws Exception{
        try(var s=new DefaultClientGuardTest.Server();var t=DefaultSyncModelTransport.ordinary()){
            var current=mock(CurrentUserService.class);when(current.requireUserId()).thenReturn(1L);var credentials=mock(LlmCredentialService.class);
            var g=new ModelGatewayServiceImpl(current,credentials,new DefaultDeepSeekChatClientFactory(s.url(),Duration.ofSeconds(1),Duration.ofSeconds(1),t));
            when(credentials.decryptCurrentUserCredential()).thenThrow(new com.cooker.common.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LLM_CREDENTIAL_NOT_CONFIGURED","fake-secret"));
            assertThatThrownBy(()->g.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.CREDENTIAL_NOT_CONFIGURED));
            doThrow(new IllegalStateException("fake-secret")).when(credentials).decryptCurrentUserCredential();
            assertThatThrownBy(()->g.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE));
            doReturn("").when(credentials).decryptCurrentUserCredential();assertThatThrownBy(()->g.completeGuide(request)).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE));assertThat(s.calls).hasValue(0);
        }
    }
}
