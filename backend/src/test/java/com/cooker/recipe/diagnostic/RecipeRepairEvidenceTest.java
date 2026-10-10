package com.cooker.recipe.diagnostic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.cooker.recipe.dto.repair.RecipeRepairEvidence.*;
import com.cooker.recipe.dto.repair.*;
import com.cooker.recipe.dto.repair.RecipeRepairApproval.Decision;
import com.cooker.recipe.mapper.RecipeRepairMapper;
import com.cooker.recipe.service.impl.RecipeRepairServiceImpl;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

/** 不依赖Docker的固定证据和审批安全边界测试，不替代真实PG事务验收。 */
class RecipeRepairEvidenceTest {
    static RecipeRepairEvidence evidence;
    @TempDir Path folder;
    @BeforeAll static void load() throws Exception {
        evidence=RecipeRepairEvidence.load(Path.of(Objects.requireNonNull(RecipeRepairEvidenceTest.class.getResource("/fixtures/d1-b/database-input.jsonl")).toURI()),Path.of(Objects.requireNonNull(RecipeRepairEvidenceTest.class.getResource("/fixtures/d1-b/manifest.jsonl")).toURI()));
    }
    static RecipeRepairApproval valid(Decision... selections) {
        var t=evidence.approvalTemplate();var selected=new HashMap<String,Decision>();for(var d:selections)selected.put(d.candidateKey(),d);
        var a=new RecipeRepairApproval(t.schemaVersion(),t.ruleVersion(),t.inputHash(),t.manifestHash(),t.reviewHash(),UUID.randomUUID(),"test_only","jdbc:postgresql://127.0.0.1:1/test_only","纯校验测试",List.of("APPLY","ROLLBACK"),t.decisions().stream().map(d -> selected.getOrDefault(d.candidateKey(),d)).toList(),null);
        return new RecipeRepairApproval(a.schemaVersion(),a.ruleVersion(),a.inputHash(),a.manifestHash(),a.reviewHash(),a.requestKey(),a.targetDatabase(),a.targetIdentifier(),a.reviewer(),a.allowedActions(),a.decisions(),approvalHash(a));
    }
    static Decision people() {var c=evidence.candidates().get("PEOPLE:264");return new Decision(c.key(),c.hash(),"APPROVE","仅测试审批校验",null,null,null,null,false,false);}
    @Test void 审定包完整全部待审且复生成内容hash稳定() throws Exception {
        RecipeRepairReview.write(evidence,folder.resolve("first"));RecipeRepairReview.write(evidence,folder.resolve("second"));
        assertThat(Files.readString(folder.resolve("first/review.json"))).isEqualTo(Files.readString(folder.resolve("second/review.json")));
        assertThat(evidence.candidates()).hasSize(1416);assertThat(evidence.approvalTemplate().decisions()).allMatch(d -> d.decision().equals("DEFER"));
        assertThat(evidence.candidates().values().stream().filter(c -> c.category().equals("词典冲突"))).hasSize(52).allMatch(c -> !c.evidence().path("relatedLines").isEmpty());
        assertThat(evidence.candidates().values().stream().filter(c -> c.evidence().path("uncertainMaterial").asBoolean())).hasSize(530);
    }
    @Test void 原文与提案字节改动拒绝() throws Exception {
        Path changed=folder.resolve("changed.jsonl");Files.writeString(changed,"{}");
        assertThatThrownBy(() -> RecipeRepairEvidence.load(changed,changed)).hasMessageContaining("hash");
    }
    @Test void 未批准和零事实批准拒绝且不触发事务或Mapper() {
        var mapper=mock(RecipeRepairMapper.class);var transactions=mock(PlatformTransactionManager.class);var service=new RecipeRepairServiceImpl(mapper,transactions);
        assertThatThrownBy(() -> service.apply(evidence,evidence.approvalTemplate())).hasMessageContaining("审批缺少");
        assertThatThrownBy(() -> service.dryRun(evidence,valid())).hasMessageContaining("没有获准");verifyNoInteractions(mapper,transactions);
    }
    @Test void 审批内容篡改与未知事实字段均拒绝() throws Exception {
        ObjectNode n=JSON.valueToTree(valid(people()));n.put("reviewer","篡改");
        assertThatThrownBy(() -> evidence.validate(JSON.treeToValue(n,RecipeRepairApproval.class))).hasMessageContaining("内容hash");
        ((ObjectNode)n.path("decisions").get(0)).put("newFields","不得覆写");assertThatThrownBy(() -> JSON.treeToValue(n,RecipeRepairApproval.class)).isInstanceOf(Exception.class);
    }
    @Test void 审批标准ID和排序不能将小数静默截断() {
        ObjectNode n=JSON.valueToTree(valid(people()));((ObjectNode)n.path("decisions").get(0)).put("ingredientId",1.5);
        assertThatThrownBy(() -> JSON.treeToValue(n,RecipeRepairApproval.class)).isInstanceOf(Exception.class);
    }
    @Test void 返回数据不能改变已冻结事实和候选hash() {
        var original=evidence.reviewHash();evidence.candidates().get("PEOPLE:264").evidence().put("newServings",999);evidence.recipes().get(264L).deepCopy();
        assertThat(evidence.candidates().get("PEOPLE:264").evidence().path("newServings").asInt()).isEqualTo(2);assertThat(evidence.reviewHash()).isEqualTo(original);assertThat(evidence.validate(valid(people()))).hasSize(1);
    }
    @Test void CLI默认dryRun在连接前拒绝未批准模板() throws Exception {
        Path approval=folder.resolve("pending.json");Files.writeString(approval,JSON.writeValueAsString(evidence.approvalTemplate()));
        Path input=Path.of(Objects.requireNonNull(getClass().getResource("/fixtures/d1-b/database-input.jsonl")).toURI()),manifest=Path.of(Objects.requireNonNull(getClass().getResource("/fixtures/d1-b/manifest.jsonl")).toURI());
        assertThatThrownBy(() -> RecipeRepairTool.execute(new String[]{input.toString(),manifest.toString(),approval.toString(),"--target","jdbc:postgresql://127.0.0.1:1/test_only"})).hasMessageContaining("审批缺少");
    }
}
