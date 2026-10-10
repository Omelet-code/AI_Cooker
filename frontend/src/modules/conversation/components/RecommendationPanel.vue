<script setup lang="ts">
import type {RecommendationTurnView} from '../types'
import {noticeLabels,outcomeLabels,reasonLabels,turnErrorMessage} from '../presentation'
import {isResourceId} from '../validation'
defineProps<{data:RecommendationTurnView}>()
</script>
<template>
 <section class="chat-result" aria-label="选中轮次结果">
  <template v-if="data.turn.status==='FAILED'">
   <h3>该轮执行失败</h3><p role="alert">{{turnErrorMessage(data.turn.error?.code)}}</p>
   <RouterLink v-if="data.turn.error?.code==='CREDENTIAL_NOT_CONFIGURED'" :to="{name:'model-settings'}">前往模型设置</RouterLink>
   <p class="chat-muted">已有失败轮次的重放不会重新执行；新轮必须由你明确发送。</p>
  </template>
  <template v-else-if="data.turn.status==='PENDING'||data.turn.status==='RUNNING'">
   <h3>该轮尚未确认完成</h3><p>服务器状态：{{data.turn.status==='PENDING'?'等待执行':'执行中'}}。这里只读取已提交记录；GET 不会恢复过期轮次。</p>
  </template>
  <template v-else-if="data.result">
   <h3>{{outcomeLabels[data.result.outcome]}}</h3>
   <p class="chat-answer">{{data.result.answer}}</p>
   <div v-if="data.result.outcome==='RECOMMENDED'" class="chat-recommendations">
    <article v-for="recipe in data.result.recipes.slice(0,3)" :key="recipe.recipeId" class="chat-recommendation">
     <h4>{{recipe.name}}</h4><p v-if="recipe.summary" class="chat-answer">{{recipe.summary}}</p>
     <p>预计时间：{{recipe.estimatedMinutes===null?'未知':recipe.estimatedMinutes+' 分钟'}}</p>
     <ul><li v-for="reason in recipe.reasonCodes" :key="reason">{{reasonLabels[reason]??'理由尚未确认'}}</li></ul>
     <RouterLink v-if="isResourceId(recipe.recipeId)" :to="{name:'recipe-detail',params:{id:recipe.recipeId}}">查看菜谱详情 →</RouterLink><span v-else>菜谱编号不可用</span>
    </article>
   </div>
   <details class="chat-conditions"><summary>本轮条件</summary><dl>
    <dt>语义需求</dt><dd>{{data.result.constraints.query}}</dd>
    <dt>时间上限</dt><dd>{{data.result.constraints.maxCookingMinutes===null?'未知 / 未限定':data.result.constraints.maxCookingMinutes+' 分钟'}}</dd>
    <dt>厨具</dt><dd>{{data.result.constraints.availableToolNames===null?'未知 / 未限定':data.result.constraints.availableToolNames.length===0?'明确没有厨具':data.result.constraints.availableToolNames.join('、')}}</dd>
    <dt>排除食材</dt><dd>{{data.result.constraints.excludedIngredientNames.join('、')||'未声明'}}</dd>
    <dt>声明已有食材</dt><dd>{{data.result.constraints.availableIngredientNames.join('、')||'未声明'}}</dd>
    <dt>声明预算</dt><dd>{{data.result.constraints.budgetYuan===null?'未知':data.result.constraints.budgetYuan+' 元（未核实）'}}</dd>
   </dl></details>
   <ul class="chat-notices"><li v-for="code in data.result.noticeCodes" :key="code">{{noticeLabels[code]??'条件仍需核对，请勿将推荐视为保证。'}}</li></ul>
   <details class="chat-metrics"><summary>调用记录（非费用统计）</summary>
    <p>网关尝试 {{data.result.metrics.modelGatewayAttempts}} 次，检索调用 {{data.result.metrics.retrievalCalls}} 次；实际 query Embedding 请求数：{{data.result.metrics.queryEmbeddingRequests===null?'未知':data.result.metrics.queryEmbeddingRequests}}。</p>
    <p v-for="usage in data.result.metrics.modelUsages" :key="usage.attempt">第 {{usage.attempt}} 次尝试：输入 token {{usage.promptTokens===null?'未知':usage.promptTokens}}，输出 token {{usage.completionTokens===null?'未知':usage.completionTokens}}，总 token {{usage.totalTokens===null?'未知':usage.totalTokens}}。</p>
    <p>网关尝试数不等于供应商计费次数，此处不推算费用或复用历史性能指标。</p>
   </details>
  </template>
  <template v-else><h3>该轮已结束</h3><p>没有结构化推荐快照，可从消息历史查看保存的原文。</p></template>
 </section>
</template>
<style scoped>
.chat-result{padding:20px;border:1px solid #dfe6d7;border-radius:12px;background:#f8faf4;font-size:13px;line-height:1.7}.chat-result h3{margin:0 0 9px;color:#344932;font-size:18px}.chat-result p{margin:7px 0}.chat-answer{white-space:pre-wrap;overflow-wrap:anywhere}.chat-muted,.chat-notices,.chat-metrics{color:#6d7868;font-size:12px}.chat-recommendations{display:grid;gap:12px;margin:16px 0}.chat-recommendation{padding:16px;border:1px solid #e3e8dc;border-radius:9px;background:#fff}.chat-recommendation h4{margin:0;color:#374638;font-size:16px}.chat-recommendation ul{padding-left:19px;color:#7a8375;font-size:12px}.chat-result a{color:#486843;font-weight:600}.chat-conditions,.chat-metrics{margin-top:16px}.chat-conditions summary,.chat-metrics summary{cursor:pointer}.chat-conditions dl{display:grid;grid-template-columns:110px 1fr;gap:7px;margin:13px 0}.chat-conditions dt{color:#778371}.chat-conditions dd{margin:0;overflow-wrap:anywhere}.chat-notices{padding-left:20px}.chat-notices li{margin:5px 0}
</style>
