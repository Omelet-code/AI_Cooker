package com.cooker.agent.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cooker.agent.dto.*;
import com.cooker.agent.service.*;
import com.cooker.agent.service.impl.*;
import com.cooker.conversation.dto.MealRegistration;
import com.cooker.conversation.service.MealPersistenceService;
import com.cooker.evaluation.transport.*;
import com.cooker.identity.service.*;
import com.cooker.policy.*;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.service.RecipeMealFactsService;
import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.service.impl.QueryEmbeddingServiceImpl;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 实际指导Service、网关、两个默认客户端及Policy；持久化/事实为明确合成替身。 */
class T18R1GuideStopTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    final MealPersistenceService store=mock(MealPersistenceService.class);
    final CurrentUserService current=mock(CurrentUserService.class);
    final MealHttpRequest request=new MealHttpRequest(UUID.randomUUID().toString(),"",null,1,"5");
    final String snapshot="{\"diningCount\":3,\"requirements\":{},\"noticeCodes\":[],\"options\":[{\"id\":\"5\",\"dishes\":[{\"recipeId\":\"10\",\"factFingerprint\":\"fixture\"}],\"soups\":[]}]}";
    MealGuidanceServiceImpl service(DefaultClientGuardTest.Server s,SyncModelTransport transport,MealGuidePolicy policy){
        when(current.requireUserId()).thenReturn(1L);
        when(store.registerGuide(anyLong(),anyLong(),anyLong(),anyString(),anyInt(),anyLong(),anyString(),anyBoolean())).thenReturn(new MealRegistration(3,2,1,0,3,snapshot,true,"PENDING"));
        when(store.action(1,1,2,3)).thenReturn(Map.of("selectedOptionId","5"));
        var facts=mock(RecipeMealFactsService.class);
        var fact=new MealRecipeFacts(10,"合成菜谱","vegetable_dish",new MealRecipeFacts.RoleEvidence(MealRole.DISH,"fixture","fixture"),10,"a".repeat(64),3,null,List.of(),List.of(new MealRecipeFacts.Step(1,1,"按原步骤准备")),List.of(),"","fixture");
        when(facts.readFacts(eq(1L),anyList())).thenReturn(List.of(fact));when(facts.recheck(any(),anyList())).thenReturn(List.of(fact));
        when(facts.materials(eq(1L),anyList(),eq(3))).thenReturn(new MealMaterials(3,List.of(new MealMaterials.RecipeMaterials(fact,List.of())),List.of(),List.of(),"fixture"));
        var credentials=mock(LlmCredentialService.class);when(credentials.decryptCurrentUserCredential()).thenReturn("fake-r1-key");
        var factory=new DefaultDeepSeekChatClientFactory(s.url(),Duration.ofSeconds(1),Duration.ofSeconds(1),transport);
        return new MealGuidanceServiceImpl(store,current,facts,new ModelGatewayServiceImpl(current,credentials,factory),policy,new MealPolicy(),new RecommendationClock(),true,transport);
    }
    QueryEmbeddingServiceImpl query(DefaultClientGuardTest.Server s,SyncModelTransport transport){var p=new RetrievalIndexProperties();p.setApiKey("fake-r1-key");p.setEndpoint(s.url()+"/query");return new QueryEmbeddingServiceImpl(p,transport);}
    @Test void 合法JSON传输成功后真实业务拒绝立即停批且两客户端零追加()throws Exception{
        Path ledger=dir.resolve("business.jsonl");
        try(var s=new DefaultClientGuardTest.Server();var b=new EvaluationBatch("business","fake-approval",ledger,2,2,3,3);var t=DefaultSyncModelTransport.controlled(b)){
            s.content="{}";var service=service(s,t,new MealGuidePolicy(new MealPolicy()));var result=service.generate("1","2",request);
            assertThat(result.body()).containsEntry("selectedOptionId","5");assertThat(b.stopped()).isTrue();assertThat(s.calls).hasValue(1);
            verify(store).fail(eq(1L),eq(1L),eq(2L),eq(3L),eq("MODEL_INVALID_RESPONSE"),anyString());verify(store,never()).completeGuide(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
            assertThatThrownBy(()->query(s,t).embed("番茄")).isInstanceOf(EvaluationBatch.Stopped.class);
            var credentials=mock(LlmCredentialService.class);when(credentials.decryptCurrentUserCredential()).thenReturn("fake-r1-key");
            var g=new ModelGatewayServiceImpl(current,credentials,new DefaultDeepSeekChatClientFactory(s.url(),Duration.ofSeconds(1),Duration.ofSeconds(1),t));
            assertThatThrownBy(()->g.completeGuide(new ModelChatRequest(List.of(new ModelMessage(ModelMessage.Role.SYSTEM,"输出JSON")),ModelOutputMode.JSON))).isInstanceOfSatisfying(ModelGatewayException.class,e->assertThat(e.reason()).isEqualTo(ModelGatewayException.Reason.EVALUATION_STOPPED));
            service.generate("1","2",request);
            verify(store).fail(eq(1L),eq(1L),eq(2L),eq(3L),eq("RESOURCE_LIMIT"),anyString());
            assertThat(b.methods(SyncModelTransport.Lane.LLM)).isEqualTo(1);assertThat(b.http(SyncModelTransport.Lane.LLM)).isEqualTo(1);assertThat(b.methods(SyncModelTransport.Lane.QUERY)).isZero();assertThat(s.calls).hasValue(1);
            String events=Files.readString(ledger);assertThat(events).containsSubsequence("SUCCEEDED","GUIDE_REJECTED","GLOBAL_STOP").doesNotContain("\"event\":\"FAILED\"","fake-r1-key","合成菜谱");
        }
    }
    @Test void 校验窗口中的并发query等待拒绝后零发送()throws Exception{
        try(var s=new DefaultClientGuardTest.Server();var b=new EvaluationBatch("race","fake-approval",dir.resolve("race.jsonl"),2,2,3,3);var t=DefaultSyncModelTransport.controlled(b);var pool=Executors.newFixedThreadPool(2)){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
            var policy=new MealGuidePolicy(new MealPolicy()){
                @Override public com.fasterxml.jackson.databind.node.ObjectNode validate(String raw,JsonNode facts,JsonNode materials){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("校验探针等待超时");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("校验探针被中断");}return super.validate(raw,facts,materials);}
            };
            var service=service(s,t,policy);var guidance=pool.submit(()->service.generate("1","2",request));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var query=pool.submit(()->{attempted.countDown();return query(s,t).embed("番茄");});assertThat(attempted.await(5,TimeUnit.SECONDS)).isTrue();
            try{assertThatThrownBy(()->query.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            guidance.get(5,TimeUnit.SECONDS);assertThatThrownBy(()->query.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(EvaluationBatch.Stopped.class);assertThat(s.calls).hasValue(1);assertThat(b.stopped()).isTrue();
        }
    }
    @Test void 普通模式业务拒绝不永久禁用模型且旧指导GET保持原接口()throws Exception{
        try(var s=new DefaultClientGuardTest.Server();var t=DefaultSyncModelTransport.ordinary()){
            var service=service(s,t,new MealGuidePolicy(new MealPolicy()));service.generate("1","2",request);service.generate("1","2",request);
            assertThat(query(s,t).embed("番茄")).hasSize(1024);assertThat(s.calls).hasValue(3);
            when(store.guide(1,1,2,8)).thenReturn(Map.of("historical","unchanged"));assertThat(service.get("1","2","8")).containsEntry("historical","unchanged");assertThat(s.calls).hasValue(3);
        }
    }
}
