<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useIdentityStore } from '@/stores/identity'
import { isResourceId } from '@/modules/conversation/validation'
import { createKitchenMaterialsState } from '@/modules/conversation/kitchen/materialsState'
import type { MealOption } from '@/modules/conversation/kitchen/types'
import { formatIngredientSource, hasStructuredIngredientAmount } from '@/modules/recipe/format'

const route = useRoute()
const identity = useIdentityStore()
const ownerId = computed(() => identity.user?.id ?? null)
const conversationId = computed(() => typeof route.params.conversationId === 'string' ? route.params.conversationId : '')
const mealId = computed(() => typeof route.params.mealId === 'string' ? route.params.mealId : '')
const version = computed(() => route.query.version)
const optionId = computed(() => route.query.optionId)
const materials = createKitchenMaterialsState(() => ownerId.value)

watch([ownerId, conversationId, mealId, version, optionId], () => {
  void materials.load({
    conversationId: conversationId.value,
    mealId: mealId.value,
    version: version.value,
    optionId: optionId.value,
  })
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => materials.clear())

const snapshot = computed(() => materials.snapshot.value)
const returnRoute = computed(() => isResourceId(conversationId.value) && isResourceId(mealId.value)
  && typeof version.value === 'string' && typeof optionId.value === 'string' ? {
    name: 'kitchen-conversation',
    params: { conversationId: conversationId.value },
    query: { mealId: mealId.value, mealVersion: version.value, optionId: optionId.value },
  } : { name: 'kitchen' })
const summaryItems = computed(() => {
  const current = snapshot.value
  if (!current) return []
  return current.option.materials.summary.filter(item => item.sources.some(source => {
    const recipe = current.option.materials.recipes.find(row => row.facts.recipeId === source.recipeId)?.facts
    const ingredient = recipe?.ingredients.find(row => row.recipeIngredientId === source.recipeIngredientId)
    return !ingredient || hasStructuredIngredientAmount(ingredient) || source.referenceAmount !== null
  }))
})
const pendingItems = computed(() => snapshot.value?.option.materials.pending.filter(item =>
  hasStructuredIngredientAmount(item.material.original)) ?? [])

function summaryName(item: MealOption['materials']['summary'][number]) {
  for (const source of item.sources) {
    const recipe = snapshot.value?.option.materials.recipes.find(row => row.facts.recipeId === source.recipeId)?.facts
    const ingredient = recipe?.ingredients.find(row => row.recipeIngredientId === source.recipeIngredientId)
    if (ingredient) return formatIngredientSource(ingredient)
  }
  return '未标准化材料'
}
function amount(value: string | null, unit: string | null) {
  return value === null ? '未知' : value + (unit ? ' ' + unit : '')
}
</script>

<template>
  <section class="materials-page" aria-labelledby="materials-title">
    <header class="materials-heading">
      <div><p class="eyebrow">只读餐单快照</p><h1 id="materials-title">材料清单</h1></div>
      <RouterLink :to="returnRoute">返回原餐单</RouterLink>
    </header>
    <p class="materials-scope">只读取链接指定的会话、餐单版本和方案；查看不会选择方案、生成指导或创建采购清单。</p>
    <p v-if="materials.status.value === 'loading'" class="materials-state" role="status">正在读取指定版本的材料…</p>
    <p v-else-if="materials.status.value === 'error'" class="materials-state materials-error" role="alert">{{ materials.error.value }}</p>
    <section v-else-if="materials.status.value === 'unavailable'" class="materials-state materials-error" role="alert">
      <h2>此历史餐单事实当前不可读取</h2>
      <p>未展示或替换为其他版本的菜名、材料和折算。请回到厨房历史核对。</p>
    </section>
    <template v-else-if="snapshot">
      <p v-if="materials.status.value === 'changed'" class="materials-warning" role="status">引用菜谱事实已变化。以下是此版本仍可读取的保存快照，仅供回看；详情链接可能展示当前菜谱。</p>
      <header class="option-title">
        <div><p class="eyebrow">餐单第 {{ snapshot.meal.version }} 版 · 方案 {{ snapshot.option.ordinal }}</p><h2>{{ snapshot.option.dishes.map(item => item.name).concat(snapshot.option.soups.map(item => item.name)).join('、') }}</h2></div>
        <span>{{ snapshot.option.explanation }}</span>
      </header>

      <section class="materials-section">
        <h3>合并材料参考</h3>
        <p v-if="snapshot.option.materials.summaryStatus !== 'COMPLETE'" class="muted">部分材料不能合并或仍需核对；未知数量保留为未知。</p>
        <ul v-if="summaryItems.length">
          <li v-for="(item, index) in summaryItems" :key="item.ingredientId + '-' + index">
            {{ summaryName(item) }}：{{ amount(item.referenceAmount, item.unitLabel) }}
            （{{ item.status === 'REFERENCE_SCALED' ? '参考折算' : item.status === 'UNKNOWN' ? '数量未知' : '仅列原量' }}）
            <span v-if="item.optional">（可选）</span><span v-if="item.reason">；{{ item.reason }}</span>
          </li>
        </ul>
        <p v-else class="muted">没有可直接作为整餐数量依据的合并材料；请查看逐菜原文用料。</p>
        <p>参考折算按服务器提供的人数快照计算，不表示已核价或核验家庭库存。</p>
      </section>

      <section v-if="pendingItems.length" class="materials-section pending-section">
        <h3>需要确认的材料</h3>
        <ul><li v-for="(item, index) in pendingItems" :key="item.recipeId + '-' + index">
          {{ snapshot.option.materials.recipes.find(row => row.facts.recipeId === item.recipeId)?.facts.name ?? '菜谱' }}：{{ item.material.original.rawLine || item.material.original.rawName }}；{{ item.material.needsConfirmation ? '需要确认' : '数量或单位待核对' }}。{{ item.reason }}
        </li></ul>
      </section>

      <section class="materials-section recipe-materials">
        <h3>逐菜原始材料与参考量</h3>
        <article v-for="row in snapshot.option.materials.recipes" :key="row.facts.recipeId" class="recipe-material-card">
          <h4><RouterLink :to="{ name: 'recipe-detail', params: { id: row.facts.recipeId } }">{{ row.facts.name }}</RouterLink></h4>
          <p v-if="!row.facts.ingredients.length" class="muted">原菜谱没有结构化材料。</p>
          <ul v-else><li v-for="ingredient in row.facts.ingredients" :key="ingredient.recipeIngredientId">
            <span>{{ hasStructuredIngredientAmount(ingredient) ? ingredient.rawName : formatIngredientSource(ingredient) }}{{ ingredient.optional ? '（可选）' : '' }}</span>
            <template v-if="hasStructuredIngredientAmount(ingredient)">
              <span>原量 {{ amount(ingredient.amountMin, ingredient.unitLabel) }}<template v-if="ingredient.amountMax !== null && ingredient.amountMax !== ingredient.amountMin">～{{ amount(ingredient.amountMax, ingredient.unitLabel) }}</template></span>
              <span>本餐参考 {{ amount(row.materials.find(item => item.original.recipeIngredientId === ingredient.recipeIngredientId)?.referenceAmount ?? null, ingredient.unitLabel) }}<template v-if="row.materials.find(item => item.original.recipeIngredientId === ingredient.recipeIngredientId)?.reason">；{{ row.materials.find(item => item.original.recipeIngredientId === ingredient.recipeIngredientId)?.reason }}</template></span>
            </template>
            <span v-else class="muted">按原文准备；采购总量待确认<template v-if="row.materials.find(item => item.original.recipeIngredientId === ingredient.recipeIngredientId)?.reason">；{{ row.materials.find(item => item.original.recipeIngredientId === ingredient.recipeIngredientId)?.reason }}</template></span>
          </li></ul>
        </article>
      </section>
    </template>
  </section>
</template>

<style scoped>
.materials-page{max-width:980px;min-width:0;margin:0 auto;padding:38px 0 64px;color:#4b5848}.materials-heading,.option-title{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.materials-heading h1{margin:7px 0;color:#354a36;font-size:30px}.materials-heading>a{color:#4f7047;font-size:12px}.materials-scope,.materials-state,.materials-warning{padding:12px 14px;border-radius:8px;background:#f4f7ef;color:#65715f;font-size:12px;line-height:1.7}.materials-error{background:#fbefec;color:#984d42}.materials-warning{background:#fff6df;color:#806327}.option-title{margin-top:20px;padding:17px;border:1px solid #e3e9dc;border-radius:10px;background:#fafbf8}.option-title h2{margin:4px 0;font-size:18px}.option-title>span{max-width:45%;color:#737f6e;font-size:12px;line-height:1.6}.materials-section{margin-top:17px;padding:18px;border:1px solid #e5e9e0;border-radius:10px;background:#fff}.materials-section h3{margin:0 0 10px;color:#4b6247;font-size:15px}.materials-section ul{display:grid;gap:8px;padding-left:20px;font-size:12px;line-height:1.65}.materials-section li{overflow-wrap:anywhere}.muted{color:#818a7c;font-size:11px;line-height:1.6}.pending-section{border-color:#eadcb7;background:#fffaf0}.recipe-material-card{padding-top:14px;border-top:1px solid #edf0e7}.recipe-material-card+article{margin-top:12px}.recipe-material-card h4{margin:0 0 8px;font-size:13px}.recipe-material-card a{color:#486843}.recipe-material-card li{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(110px,.8fr) minmax(130px,.9fr);gap:10px}
@media(max-width:620px){.materials-page{padding:28px 0 45px}.materials-heading h1{font-size:26px}.option-title{flex-direction:column}.option-title>span{max-width:none}.recipe-material-card li{grid-template-columns:1fr;gap:2px}}
</style>
