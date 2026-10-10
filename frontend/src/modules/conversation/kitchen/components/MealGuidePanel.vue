<script setup lang="ts">
import { computed } from 'vue'
import type { MealGuideView } from '../types'
import { projectGuide } from './guidePresentation'
import { formatIngredientSource, hasStructuredIngredientAmount } from '@/modules/recipe/format'

const props = defineProps<{ guide: MealGuideView | null; state: 'loading' | 'ready' | 'error' | 'idle'; error: string }>()
const projection = computed(() => props.guide ? projectGuide(props.guide) : null)

function amount(value: string | null, unit: string | null) {
  return value === null ? '未知' : value + (unit ? ' ' + unit : '')
}
function names(ids: string[]) {
  const facts = props.guide?.recipes ?? []
  return ids.map(id => facts.find(fact => fact.recipeId === id)?.name ?? '未关联菜谱').join('、') || '未指定菜谱'
}
</script>

<template>
  <section class="guide-panel" aria-label="烹饪指导">
    <p v-if="state === 'loading'" role="status">正在读取已保存的指导…</p>
    <p v-else-if="state === 'error'" class="guide-error" role="alert">{{ error }} 刷新只会重新读取，不会重新生成。</p>
    <template v-else-if="guide?.factsStatus === 'UNAVAILABLE'">
      <h3>历史指导依据当前不可访问</h3>
      <p>菜名、材料和指导内容已由服务端隐藏。</p>
    </template>
    <template v-else-if="guide?.content && projection">
      <header><h3>本餐烹饪指导</h3><p>人数快照：{{ guide.diningCount }} 人；人设 {{ guide.personaVersion }}。</p></header>
      <p v-if="guide.factsStatus === 'CHANGED'" class="guide-warning">引用菜谱事实已变化。以下为保存时的历史快照，仅供回看，不能用来重新选择或生成。</p>
      <p class="guide-overview">{{ guide.content.overview }}</p>

      <section v-if="projection.preparation.length" class="guide-section preparation-section">
        <h4>整餐备菜与准备</h4>
        <ol><li v-for="(block, index) in projection.preparation" :key="'prep-' + index">{{ block.text }}</li></ol>
      </section>

      <section class="recipe-guidance-list" aria-label="逐菜指导">
        <article v-for="entry in projection.recipes" :key="entry.fact.recipeId" class="recipe-guidance-card">
          <header><h4>{{ entry.fact.name }}</h4><span>{{ entry.fact.categoryCode }}</span></header>
          <section class="recipe-facts">
            <h5>原菜谱事实</h5>
            <h6>材料</h6>
            <ul v-if="entry.fact.ingredients.length">
              <li v-for="ingredient in entry.fact.ingredients" :key="ingredient.recipeIngredientId">
                <span>{{ hasStructuredIngredientAmount(ingredient) ? ingredient.rawName : formatIngredientSource(ingredient) }}{{ ingredient.optional ? '（可选）' : '' }}</span>
                <span v-if="hasStructuredIngredientAmount(ingredient)">{{ amount(ingredient.amountMin, ingredient.unitLabel) }}<template v-if="ingredient.amountMax !== null && ingredient.amountMax !== ingredient.amountMin">～{{ amount(ingredient.amountMax, ingredient.unitLabel) }}</template></span>
              </li>
            </ul>
            <p v-else class="muted">原菜谱没有结构化材料。</p>
            <h6>做法步骤</h6>
            <ol><li v-for="step in entry.fact.steps" :key="step.stepNo">{{ step.content }}</li></ol>
            <h6>厨具</h6><p>{{ entry.fact.tools.map(tool => tool.name + (tool.optional ? '（可选）' : '')).join('、') || '未提供' }}</p>
            <p v-if="entry.fact.additionalContent"><strong>原始补充说明：</strong>{{ entry.fact.additionalContent }}</p>
            <section v-if="entry.materials.length" class="recipe-material-reference">
              <h6>本餐参考用量</h6>
              <ul><li v-for="item in entry.materials" :key="item.original.recipeIngredientId">
                <template v-if="hasStructuredIngredientAmount(item.original)">{{ item.original.rawName }}：{{ amount(item.referenceAmount, item.original.unitLabel) }}（{{ item.status === 'REFERENCE_SCALED' ? '参考折算' : item.status === 'UNKNOWN' ? '数量未知' : '仅列原量' }}）</template>
                <template v-else>按原文准备；采购总量待确认</template>
                <span v-if="item.reason">；{{ item.reason }}</span>
              </li></ul>
            </section>
          </section>

          <section v-if="entry.explanations.length" class="recipe-explanations">
            <h5>小厨讲解</h5>
            <ol><li v-for="(block, index) in entry.explanations" :key="'explain-' + index">{{ block.text }}</li></ol>
          </section>
          <section v-if="entry.suggestions.length" class="recipe-suggestions">
            <h5>模型补充：本菜可选建议</h5>
            <ul><li v-for="(item, index) in entry.suggestions" :key="'single-suggestion-' + index"><strong>{{ item.title }}</strong>：{{ item.text }}</li></ul>
            <p>补充内容不是原菜谱事实，不会自动加入材料或采购清单。</p>
          </section>
        </article>
      </section>

      <details v-if="projection.cookingOrder.length || projection.cautions.length" class="whole-meal-notes">
        <summary>整餐安排与提醒</summary>
        <section v-if="projection.cookingOrder.length"><h5>整餐烹饪顺序</h5><ol><li v-for="(block, index) in projection.cookingOrder" :key="'order-' + index">{{ block.text }}</li></ol></section>
        <section v-if="projection.cautions.length"><h5>整餐注意事项</h5><ul><li v-for="(block, index) in projection.cautions" :key="'caution-' + index">{{ block.text }}</li></ul></section>
      </details>

      <section v-if="projection.sharedSuggestions.length" class="shared-suggestions">
        <h4>模型补充：跨菜或未指定菜谱的可选建议</h4>
        <ul><li v-for="(item, index) in projection.sharedSuggestions" :key="'shared-suggestion-' + index">
          <strong>{{ item.title }}</strong>：{{ item.text }}<span>适用范围：{{ names(item.recipeIds) }}</span>
        </li></ul>
        <p>补充内容不是原菜谱事实，不会自动加入材料或采购清单。</p>
      </section>

      <section class="guide-references" aria-label="参考菜谱">
        <h4>参考菜谱</h4>
        <ul v-if="projection.references.length"><li v-for="recipe in projection.references" :key="recipe.recipeId">
          <RouterLink :to="{ name: 'recipe-detail', params: { id: recipe.recipeId } }">{{ recipe.name }}</RouterLink>
        </li></ul>
        <p v-else class="muted">本指导没有可链接的菜谱依据。</p>
        <p v-if="guide.factsStatus === 'CHANGED'" class="muted">链接打开的是当前菜谱；上方内容仍是指导生成时保存的历史快照。</p>
      </section>
      <p class="source-note">材料事实与参考折算分开展示；模型补充不会改写原材料。</p>
    </template>
    <p v-else class="guide-muted">尚无已保存指导。重新进入、刷新或读取历史不会触发模型请求。</p>
  </section>
</template>

<style scoped>
.guide-panel{margin-top:20px;padding:20px;border:1px solid #dfe6d7;border-radius:12px;background:#fff;color:#495648;font-size:13px;line-height:1.75;min-width:0}
.guide-panel h3{margin:0;color:#354a36;font-size:19px}.guide-panel h4{margin:0 0 10px;color:#4e6449;font-size:14px}.guide-panel h5{margin:0 0 8px;font-size:13px}.guide-panel h6{margin:12px 0 4px;color:#74816f;font-size:11px}
.guide-panel a{color:#4e7047}.guide-panel header p,.guide-muted,.source-note,.muted{color:#798476;font-size:11px}.guide-overview{padding:12px;border-radius:8px;background:#f4f7ef}
.guide-section,.recipe-guidance-list,.whole-meal-notes,.shared-suggestions,.guide-references{margin-top:17px;padding-top:15px;border-top:1px solid #edf0e7}.guide-panel ol,.guide-panel ul{display:grid;gap:8px;padding-left:20px}.guide-panel li{overflow-wrap:anywhere}.guide-panel p{margin:5px 0}.recipe-guidance-card{margin-top:12px;padding:16px;border:1px solid #e6ebe0;border-radius:10px;background:#fafbf8;min-width:0}.recipe-guidance-card>header{display:flex;align-items:center;justify-content:space-between;gap:10px}.recipe-guidance-card>header h4{margin:0;color:#384f39}.recipe-guidance-card>header span{color:#83907f;font-size:10px}.recipe-facts,.recipe-explanations,.recipe-suggestions{margin-top:13px;padding-top:12px;border-top:1px solid #e9ede4}.recipe-facts li{display:flex;justify-content:space-between;gap:12px}.recipe-facts li span:last-child{text-align:right;white-space:nowrap}.recipe-explanations{color:#4d5f4a}.recipe-suggestions,.shared-suggestions{padding:13px;border-radius:9px;background:#fff9eb}.recipe-suggestions h5,.shared-suggestions h4{color:#81682f}.recipe-suggestions>p,.shared-suggestions>p{color:#7d704c;font-size:11px}.whole-meal-notes{padding:14px;border-radius:9px;background:#f5f7f1}.whole-meal-notes summary{color:#4e6449;font-weight:650;cursor:pointer}.whole-meal-notes section{margin-top:12px}.guide-warning{padding:10px;border-radius:8px;background:#fff6df;color:#806327}.guide-error{color:#9f4f42}
</style>
