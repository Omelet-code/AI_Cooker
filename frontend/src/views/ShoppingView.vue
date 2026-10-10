<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useIdentityStore } from '@/stores/identity'
import { useShoppingStore } from '@/modules/shopping/store'
import ShoppingLines from '@/modules/shopping/components/ShoppingLines.vue'
import { pendingShoppingLocation, watchShoppingListCreation } from '@/modules/shopping/navigation'
import { isSourceRoute } from '@/modules/shopping/validation'
import type { ShoppingSourceRoute } from '@/modules/shopping/types'

const route = useRoute(), router = useRouter(), identity = useIdentityStore(), shopping = useShoppingStore()
const listIdInput = ref(''), cityOptions = [{ code: '110000', name: '北京' }, { code: '310000', name: '上海' }, { code: '440100', name: '广州' }]
const source = computed<ShoppingSourceRoute | null>(() => {
  const raw = { conversationId: route.query.conversationId, mealId: route.query.mealId, expectedVersion: typeof route.query.expectedVersion === 'string' && /^\d+$/.test(route.query.expectedVersion) ? Number(route.query.expectedVersion) : NaN, optionId: route.query.optionId }
  return isSourceRoute(raw) ? raw : null
})
const matchId = computed(() => typeof route.query.matchId === 'string' ? route.query.matchId : null)
const isDetail = computed(() => route.name === 'shopping-list')
const estimatedKnown = computed(() => shopping.match?.costs.estimatedKnownSubtotal ?? shopping.list?.costs.estimatedKnownSubtotal ?? null)
const estimatedUnknown = computed(() => shopping.match?.costs.estimatedUnknownItemCount ?? shopping.list?.costs.estimatedUnknownItemCount ?? null)
const sourcePath = computed(() => shopping.pendingOperation ? pendingShoppingLocation(shopping.pendingOperation) : { name: 'shopping-home' })
function routeKey(): string {
  return route.name === 'shopping-new' && source.value ? `new:${source.value.conversationId}:${source.value.mealId}:${source.value.expectedVersion}:${source.value.optionId}`
    : isDetail.value && typeof route.params.listId === 'string' ? `list:${route.params.listId}:${matchId.value ?? ''}` : 'home'
}
watch(() => [routeKey(), identity.user?.id ?? null] as const, ([, owner]) => {
  if (route.name === 'shopping-new' && source.value) { shopping.activate({ kind: 'new', ownerId: owner, source: source.value }); void shopping.loadPreview(source.value); return }
  if (isDetail.value && typeof route.params.listId === 'string' && /^[1-9]\d*$/.test(route.params.listId)) { shopping.activate({ kind: 'list', ownerId: owner, listId: route.params.listId, matchId: matchId.value }); void shopping.loadList(route.params.listId, matchId.value); return }
  shopping.activate({ kind: 'home', ownerId: owner })
}, { immediate: true, flush: 'sync' })
watchShoppingListCreation(router, shopping, source)
const confirmations = computed(() => shopping.confirmationPayload())
const canCreate = computed(() => shopping.canCreate())
async function saveList() {
  await shopping.createList()
}
async function openKnownList() {
  const id = listIdInput.value.trim()
  if (!/^[1-9]\d*$/.test(id)) { shopping.error = '请输入有效的清单编号。'; return }
  await router.push({ name: 'shopping-list', params: { listId: id } })
}
async function matchProducts() { await shopping.createMatch() }
function formatCost(value: string | null | undefined): string { return value === null || value === undefined ? '未知' : `${value} 元` }
function statusText(status: string): string {
  const labels: Record<string, string> = { NOT_MATCHED: '尚未匹配', NOT_NEEDED: '本次无需购买', NO_MATCH: '没有可匹配商品', PARTIAL: '部分匹配', MATCHED: '已匹配', WITHIN_BUDGET: '在预算内', OVER_BUDGET: '超过预算', UNKNOWN: '费用未知', NOT_SET: '未设置预算', OUT_OF_STOCK: '所选城市缺货', NO_PRODUCT: '没有可用商品', QUOTE_UNKNOWN: '报价未知', UNIT_UNSUPPORTED: '单位不兼容', QUANTITY_PENDING: '用量待确认', HAVE_ENOUGH: '已有足量', NEED_BUY: '需要购买', DRAFT: '草稿（仍有待确认用量）', READY_FOR_MATCHING: '可查看商品匹配' }
  return labels[status] ?? status
}
onBeforeRouteLeave(() => {
  if (!shopping.pendingOperation) return true
  const approved = typeof window === 'undefined' || window.confirm('采购操作仍在等待或结果未知。离开不会停止服务器处理；可稍后回到原上下文核对。仍要离开吗？')
  if (approved && shopping.writeWaiting) shopping.stopWaiting()
  return approved
})
onBeforeRouteUpdate(() => {
  if (!shopping.pendingOperation) return true
  const approved = typeof window === 'undefined' || window.confirm('采购操作仍在等待或结果未知。切换页面不会停止服务器处理；可稍后回到原上下文核对。仍要切换吗？')
  if (approved && shopping.writeWaiting) shopping.stopWaiting()
  return approved
})
onBeforeUnmount(() => shopping.stopWaiting())
</script>

<template>
  <section class="shopping-page">
    <header class="page-head"><div><p class="eyebrow">COOKER · SHOPPING</p><h1>采购确认与费用</h1><p>根据已选餐单逐项确认食材，再由服务器读取保存的需求和商品快照。</p></div><RouterLink v-if="isDetail" to="/shopping">采购入口</RouterLink></header>
    <aside v-if="shopping.pendingOperation" class="pending" role="status"><strong>采购操作结果尚未确认</strong><p>{{ shopping.pendingOperation.summary }}可能仍在服务器处理。本地取消等待不会停止服务器执行；只在原清单或来源上下文显式核对时，才会使用原编号与原请求内容。</p><RouterLink :to="sourcePath">返回原操作上下文</RouterLink><button v-if="shopping.writeWaiting" type="button" @click="shopping.stopWaiting">停止本地等待</button><button type="button" :disabled="shopping.busy || shopping.pendingOperation.contextKey !== (shopping.scope ? `${shopping.scope.kind}|${shopping.scope.ownerId ?? ''}|${shopping.scope.source ? `${shopping.scope.source.conversationId}/${shopping.scope.source.mealId}/${shopping.scope.source.expectedVersion}/${shopping.scope.source.optionId}` : ''}|${shopping.scope.listId ?? ''}|${shopping.scope.matchId ?? ''}` : '')" @click="shopping.retryPending">核对原操作（可能继续执行）</button></aside>
    <p v-if="shopping.notice" class="notice" role="status">{{ shopping.notice }}</p><p v-if="shopping.error" class="error" role="alert">{{ shopping.error }}</p>
    <template v-if="route.name === 'shopping-home'">
      <article class="panel"><h2>打开已知清单</h2><p>服务器当前没有全部历史清单列表。可输入此前保存的清单编号，或从原链接回看。</p><form @submit.prevent="openKnownList"><label>清单编号<input v-model="listIdInput" inputmode="numeric" autocomplete="off"></label><button class="primary" type="submit">读取清单</button></form></article>
      <article class="panel"><h2>开始采购确认</h2><p>请先在小厨餐单中选定套餐，再使用“确认食材并生成采购清单”入口。这里不会伪造历史记录或自动发送采购请求。</p><RouterLink class="primary link-button" to="/kitchen">打开小厨餐单</RouterLink></article>
    </template>
    <template v-else-if="route.name === 'shopping-new'">
      <p v-if="!source" class="error">来源参数无效或不完整；请返回小厨餐单重新进入。</p>
      <template v-else>
        <p v-if="shopping.busy" role="status">正在读取服务器原料预览…</p>
        <template v-if="shopping.preview">
          <article class="panel"><h2>本餐原料确认</h2><p>餐单 {{ shopping.preview.source.mealId }} · {{ shopping.preview.source.diningCount }} 人 · 版本 {{ shopping.preview.source.version }}</p><p v-if="shopping.preview.source.budgetYuan !== null">预算 {{ shopping.preview.source.budgetYuan }} 元；该金额尚未证明可满足采购。</p>
            <ShoppingLines :lines="shopping.preview.lines" :choices="shopping.confirmationChoices" :amounts="shopping.amountDrafts" :units="shopping.unitDrafts" :disabled="shopping.busy || !!shopping.pendingOperation" @choice="(key,value)=>shopping.confirmationChoices[key]=value" @amount="(key,value)=>shopping.amountDrafts[key]=value" @unit="(key,value)=>shopping.unitDrafts[key]=value" />
            <p v-if="confirmations?.some(row=>row.availability==='NEED_BUY' && !row.confirmedAmount)">部分用量尚未确认，将保留为未知，不会按0计算。</p>
            <p v-if="shopping.preview.lines.some(line=>line.optional)">可选食材也需要逐行确认。</p>
            <button class="primary" type="button" :disabled="!canCreate" @click="saveList">{{ shopping.busy ? '正在保存…' : '保存采购清单' }}</button>
          </article>
        </template>
      </template>
    </template>
    <template v-else-if="isDetail">
      <p v-if="shopping.busy" role="status">正在读取服务器清单与报价快照…</p>
      <template v-if="shopping.list">
        <article class="panel"><h2>清单 {{ shopping.list.id }}</h2><p class="permalink"><RouterLink :to="{name:'shopping-list',params:{listId:shopping.list.id}}">固定清单链接（可收藏或复制地址）：/shopping/{{ shopping.list.id }}</RouterLink></p><p>来源餐单 {{ shopping.list.source.mealId }} · {{ shopping.list.source.diningCount }} 人 · {{ shopping.list.createdAt }}</p><p>清单状态：{{ statusText(shopping.list.status) }} · 菜谱事实：{{ shopping.list.factsStatus }}</p><p v-if="shopping.list.factsStatus==='CHANGED'" class="warning">来源事实已变化；本清单只读，不可创建新报价。</p><p v-if="shopping.list.factsStatus==='UNAVAILABLE'" class="warning">来源当前不可访问；仅显示服务器允许回看的采购快照。</p>
          <h3>逐项需求</h3><ul class="facts-list"><li v-for="line in shopping.list.lines" :key="line.lineKey"><strong>{{ line.name }}</strong> · {{ line.availability ? statusText(line.availability) : '待确认' }} · 原始：{{ line.rawLine }} · 本餐参考：{{ line.referenceAmount ?? '未知' }} {{ line.unitLabel ?? line.unitCode ?? '' }}<span v-if="line.confirmedAmount"> · 用户确认：{{ line.confirmedAmount }} {{ line.confirmedUnitCode }}</span><span v-if="line.reason"> · {{ line.reason }}</span></li></ul>
          <h3>服务端金额快照</h3><dl class="costs"><dt>独立需求估价</dt><dd>{{ formatCost(shopping.list.costs.estimatedDemandCost) }}</dd><dt>模拟商品消耗成本</dt><dd>{{ formatCost(shopping.list.costs.consumptionCost) }}</dd><dt>整包采购支出</dt><dd>{{ formatCost(shopping.list.costs.purchaseOutlay) }}</dd><dt>已知小计 / 未知项</dt><dd>{{ formatCost(shopping.list.costs.knownSubtotal) }} / {{ shopping.list.costs.unknownItemCount }}</dd><dt>预算状态</dt><dd>{{ statusText(shopping.list.costs.budgetStatus) }}</dd></dl>
        </article>
        <article class="panel"><h2>商品报价快照</h2>
          <template v-if="shopping.match">
            <p class="mock-banner" role="status">{{ shopping.match.provider }} · {{ shopping.match.realData ? '真实数据标识' : 'Mock模拟数据' }} · 目录 {{ shopping.match.catalogVersion }} · 城市 {{ shopping.match.cityCode }} · {{ shopping.match.capturedAt }}</p><p class="permalink"><RouterLink :to="{name:'shopping-list',params:{listId:shopping.list.id},query:{matchId:shopping.match.id}}">固定报价链接（指定已保存版本，可收藏或复制地址）：/shopping/{{ shopping.list.id }}?matchId={{ shopping.match.id }}</RouterLink></p>
            <p>{{ statusText(shopping.match.matchingStatus) }}。{{ shopping.match.message ?? '' }}</p>
            <p>独立需求估价：{{ formatCost(shopping.match.costs.estimatedDemandCost) }}；模拟消耗成本：{{ formatCost(shopping.match.costs.consumptionCost) }}；整包采购支出：{{ formatCost(shopping.match.costs.purchaseOutlay) }}</p>
            <p>匹配报价已知小计：{{ formatCost(shopping.match.costs.knownSubtotal) }}；未知 {{ shopping.match.costs.unknownItemCount }} 项。独立估价小计：{{ formatCost(estimatedKnown) }}；未知 {{ estimatedUnknown ?? '未知' }} 项。预算：{{ statusText(shopping.match.costs.budgetStatus) }}</p>
            <ul class="facts-list"><li v-for="row in shopping.match.lines" :key="row.itemKey"><strong>{{ row.name }}</strong> · {{ statusText(row.status) }}<span v-if="row.product"> · {{ row.product.name }} · {{ row.product.packageAmount ?? '规格未知' }} {{ row.product.packageUnit ?? '' }} · 商品事实与价格来自服务器。</span><span v-if="row.reason"> · {{ row.reason }}</span><a v-if="row.product?.purchaseUrl && /^https?:\/\//.test(row.product.purchaseUrl)" :href="row.product.purchaseUrl" target="_blank" rel="noopener noreferrer">打开商品页面</a><span v-else-if="row.product" class="muted">暂未提供购买入口</span></li></ul>
            <h3>采购组（按整包合并后的支出）</h3><ul class="facts-list"><li v-for="group in shopping.match.purchaseGroups" :key="group.groupKey">{{ group.itemKeys.join('、') }} · {{ group.quantity ?? '未知' }} {{ group.unitCode ?? '' }} · {{ group.product?.name ?? '无匹配商品' }} · {{ group.packageCount ?? '包数未知' }} 包 · 支出 {{ formatCost(group.purchaseOutlay) }}</li></ul>
          </template>
          <p v-else-if="shopping.list.factsStatus !== 'CURRENT'">事实已变化，不能为旧清单新建匹配；既有保存快照若可读会显示于此。</p>
          <p v-else>尚无已保存的商品报价。打开页面和刷新只读取，不会触发匹配。</p>
          <form v-if="shopping.list.factsStatus==='CURRENT'" class="city-form" @submit.prevent="matchProducts"><label>Mock城市<select v-model="shopping.cityCode"><option v-for="city in cityOptions" :key="city.code" :value="city.code">{{ city.name }}（{{ city.code }}）</option></select></label><button class="primary" type="submit" :disabled="shopping.busy || !!shopping.pendingOperation">{{ shopping.busy ? '正在匹配…' : '明确请求匹配模拟商品' }}</button></form>
          <p class="muted">匹配将提交城市和请求编号；后端商品价格、包装和商品链接由服务器目录决定。本地不会生成商品或报价。</p>
        </article>
      </template>
    </template>
  </section>
</template>

<style scoped>
.shopping-page{max-width:1040px;margin:0 auto;padding:38px 0 62px;color:#4c5948}.page-head{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:22px}.page-head h1{margin:5px 0;color:#344a36;font-size:28px}.page-head p{max-width:760px;color:#7c8579;font-size:13px;line-height:1.6}.page-head>a{color:#55724a}.panel{margin:14px 0;padding:20px;border:1px solid #e1e7dc;border-radius:12px;background:white}.panel h2{margin:0 0 12px;color:#3d513c;font-size:18px}.panel h3{margin:18px 0 10px;color:#52664d;font-size:14px}.panel p,.panel li{font-size:12px;line-height:1.7}.panel form{display:flex;align-items:flex-end;gap:12px}.panel label{display:grid;gap:6px;color:#56664e;font-size:12px}.panel input,.panel select{min-height:38px;padding:7px 10px;border:1px solid #dce3d4;border-radius:7px;font:inherit}.primary,.link-button{display:inline-flex;align-items:center;justify-content:center;min-height:39px;padding:0 15px;border:0;border-radius:7px;background:#526d46;color:#fff;text-decoration:none;font:inherit;font-size:12px;cursor:pointer}.primary:disabled{opacity:.55;cursor:not-allowed}.pending,.warning,.notice,.mock-banner{padding:13px;border:1px solid #e7ce91;border-radius:9px;background:#fff9ea;color:#79642d;font-size:12px;line-height:1.65}.pending button{margin-left:12px}.error{color:#a2493e;font-size:13px}.facts-list{display:grid;gap:8px;padding-left:20px}.facts-list a,.permalink a{margin-left:8px;color:#517044}.permalink{font-size:12px;overflow-wrap:anywhere}.costs{display:grid;grid-template-columns:190px 1fr;gap:7px;margin:10px 0}.costs dt{color:#788173}.costs dd{margin:0;color:#40543d}.mock-banner{border-color:#dbb96d;background:#fff5d7;font-weight:700}.muted{color:#7c8579;font-size:11px;line-height:1.6}.city-form{margin-top:14px}@media(max-width:650px){.shopping-page{padding:25px 0}.panel{padding:15px}.page-head,.panel form{align-items:stretch;flex-direction:column}.costs{grid-template-columns:140px 1fr}}
</style>
