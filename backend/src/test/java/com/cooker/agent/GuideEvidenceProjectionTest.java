package com.cooker.agent;

import static org.assertj.core.api.Assertions.*;
import com.cooker.agent.service.GuideEvidenceProjection;
import com.cooker.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;

/** 引用必须归属所选完整事实；原对象不被修改，未知字段值不会丢失。 */
class GuideEvidenceProjectionTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode facts()throws Exception{return json.readTree("[{\"recipeId\":\"10\",\"ingredients\":[{\"recipeIngredientId\":\"101\",\"rawName\":\"原材料\",\"rawLine\":\"完整原行😀\",\"amountMin\":null,\"unitCode\":null}]}]");}
    ObjectNode materials(JsonNode facts){var root=json.createObjectNode();root.put("targetDiningCount",3);root.put("summaryStatus","PARTIAL");var recipe=root.putArray("recipes").addObject();recipe.set("facts",facts.get(0).deepCopy());var item=recipe.putArray("materials").addObject();item.set("original",facts.get(0).path("ingredients").get(0).deepCopy());item.putNull("referenceAmount");item.put("status","UNKNOWN");item.put("reason","原份数与原用量未知");item.put("needsConfirmation",true);var pending=root.putArray("pending").addObject();pending.put("recipeId","10");pending.set("material",item.deepCopy());pending.put("reason","需确认原始单位");var summary=root.putArray("summary").addObject();summary.put("reason","保留来源");var source=summary.putArray("sources").addObject();source.put("recipeId","10");source.put("recipeIngredientId","101");source.putNull("referenceAmount");return root;}
    @Test void 原材料移为引用后保留全部待确认折算来源且不修改输入()throws Exception {
        var facts=facts();var original=materials(facts);var before=original.deepCopy();var compact=GuideEvidenceProjection.materials(facts,original);
        assertThat(original).isEqualTo(before);assertThat(compact.path("summary")).isEqualTo(original.path("summary"));assertThat(compact.path("summaryStatus")).isEqualTo(original.path("summaryStatus"));
        var item=compact.path("recipes").get(0).path("materials").get(0);assertThat(item.path("recipeIngredientId").asText()).isEqualTo("101");assertThat(item.has("original")).isFalse();assertThat(item.path("referenceAmount").isNull()).isTrue();assertThat(item.path("status").asText()).isEqualTo("UNKNOWN");assertThat(item.path("needsConfirmation").asBoolean()).isTrue();assertThat(compact.path("pending").get(0).path("reason").asText()).isEqualTo("需确认原始单位");
    }
    @Test void 原材料内容失配不能被引用去重掩盖()throws Exception {
        var facts=facts();var material=materials(facts);((ObjectNode)material.path("recipes").get(0).path("materials").get(0).path("original")).put("rawLine","改变的原行");assertThatThrownBy(()->GuideEvidenceProjection.materials(facts,material)).isInstanceOf(ApiException.class);
    }
    @Test void 待确认和汇总来源必须属于所选菜且引用有效()throws Exception {
        var facts=facts();var pending=materials(facts);((ObjectNode)pending.path("pending").get(0)).put("recipeId","999");assertThatThrownBy(()->GuideEvidenceProjection.materials(facts,pending)).isInstanceOf(ApiException.class);
        var summary=materials(facts);((ObjectNode)summary.path("summary").get(0).path("sources").get(0)).put("recipeIngredientId","999");assertThatThrownBy(()->GuideEvidenceProjection.materials(facts,summary)).isInstanceOf(ApiException.class);
    }
}
