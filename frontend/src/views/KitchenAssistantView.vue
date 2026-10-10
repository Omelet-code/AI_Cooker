<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useIdentityStore } from '@/stores/identity'
import { useKitchenStore } from '@/modules/conversation/kitchen/store'
import { kitchenLeaveWarning } from '@/modules/conversation/kitchen/state'
import { isResourceId } from '@/modules/conversation/validation'
import MealOptionCard from '@/modules/conversation/kitchen/components/MealOptionCard.vue'
import MealGuidePanel from '@/modules/conversation/kitchen/components/MealGuidePanel.vue'

const kitchen = useKitchenStore()
const identity = useIdentityStore()
const route = useRoute()
const router = useRouter()
const routeMealNotice = ref('')
const routeId = computed(() => typeof route.params.conversationId === 'string' ? route.params.conversationId : null)
const ownerId = computed(() => identity.user?.id ?? null)
const selectedOption = computed(() => kitchen.activeMeal?.options.find(option => option.id === kitchen.activeMeal?.selectedOptionId) ?? null)
const canOpenShopping = computed(() => !!routeId.value && kitchen.activeMeal?.status === 'SELECTED'
  && kitchen.activeMeal.factsStatus === 'CURRENT' && kitchen.activeMeal.diningCount !== null && kitchen.activeMeal.diningCount > 0
  && (kitchen.activeMeal.diningCountSource === 'EXPLICIT' || kitchen.activeMeal.diningCountSource === 'PROFILE')
  && !!selectedOption.value && !kitchen.busy && !kitchen.anyPending && !kitchen.hasUnconfirmedTarget)
const shoppingRoute = computed(() => canOpenShopping.value && kitchen.activeMeal && selectedOption.value ? {
  name: 'shopping-new',
  query: { conversationId: routeId.value!, mealId: kitchen.activeMeal.id, expectedVersion: String(kitchen.activeMeal.version), optionId: selectedOption.value.id },
} : { name: 'shopping-home' })
const canRevise = computed(() => kitchen.activeMeal
  && kitchen.activeMeal.status !== 'PENDING'
  && kitchen.activeMeal.factsStatus !== 'UNAVAILABLE'
  && (kitchen.activeMeal.status === 'CLARIFICATION' || kitchen.activeMeal.status === 'FAILED' || kitchen.activeMeal.factsStatus === 'CHANGED'))
const hasSelectedSummary = computed(() => kitchen.activeMeal?.status === 'SELECTED' && !!selectedOption.value)
const activeNoticeLabels: Record<string, string> = {
  ONLY_ONE_OPTION: '当前条件只能组成一套餐单。',
  PREFERENCE_NOT_GUARANTEED: '偏好匹配不作保证。',
  BUDGET_NOT_VERIFIED: '预算尚未核价。',
  AVAILABLE_INGREDIENTS_NOT_INVENTORY: '已有食材来自本次声明，系统未读取家庭库存。',
  MATERIALS_REQUIRE_CONFIRMATION: '部分材料用量或单位需要你确认。',
  GENERAL_DIET_ONLY: '健康相关内容仅为一般饮食建议。',
  FACTS_CHANGED: '引用菜谱事实已变化，当前只供历史回看。',
  FACTS_UNAVAILABLE: '引用菜谱当前不可访问，内容已由服务端隐藏。',
}
const statusLabels: Record<string, string> = {
  CLARIFICATION: '需要补充条件',
  OPTIONS_READY: '餐单已准备好',
  SELECTED: '已选定',
  FAILED: '本次餐单生成失败',
  PENDING: '正在处理',
}
let routeGeneration = 0
async function restoreMaterialMeal(generation: number, id: string, owner: string | null) {
  const mealId = route.query.mealId
  const versionText = route.query.mealVersion
  const optionId = route.query.optionId
  if (mealId === undefined && versionText === undefined && optionId === undefined) return
  if (!isResourceId(mealId) || !isResourceId(optionId) || typeof versionText !== 'string' || !/^\d{1,16}$/.test(versionText) || Number(versionText) < 1) {
    routeMealNotice.value = '返回餐单定位信息无效；请从本餐历史重新选择。'
    return
  }
  const expectedVersion = Number(versionText)
  const opened = await kitchen.showMeal(mealId)
  if (generation !== routeGeneration || routeId.value !== id || ownerId.value !== owner) return
  if (!opened) {
    routeMealNotice.value = '原餐单尚未打开。请先处理当前未确认操作，再从本餐历史核对。'
    return
  }
  if (kitchen.activeMeal?.id !== mealId) {
    routeMealNotice.value = '无法恢复原餐单，请从本餐历史重新选择。'
  } else if (kitchen.activeMeal.version !== expectedVersion) {
    routeMealNotice.value = `原材料详情属于第 ${expectedVersion} 版；当前读取到第 ${kitchen.activeMeal.version} 版，已明确标出版本变化。`
  } else if (!kitchen.activeMeal.options.some(option => option.id === optionId) && kitchen.activeMeal.factsStatus !== 'UNAVAILABLE') {
    routeMealNotice.value = '返回的方案与当前餐单版本不匹配；请从本餐历史核对。'
  }
}
watch([routeId, ownerId], async ([id, owner]) => {
  const generation = ++routeGeneration
  routeMealNotice.value = ''
  await kitchen.activate(id)
  if (generation !== routeGeneration || routeId.value !== id || ownerId.value !== owner || !id) return
  await restoreMaterialMeal(generation, id, owner)
}, { immediate: true, flush: 'sync' })
watch(() => kitchen.createdConversationId, async id => {
  if (typeof id !== 'string') return
  await router.push({ name: 'kitchen-conversation', params: { conversationId: id } })
})
onBeforeUnmount(() => kitchen.leavePage())
function confirmPendingLeave() {
  return !kitchen.activePending || typeof window === 'undefined' || window.confirm(kitchenLeaveWarning)
}
onBeforeRouteLeave(confirmPendingLeave)
onBeforeRouteUpdate(to => to.params.conversationId === route.params.conversationId || confirmPendingLeave())

async function createConversation() {
  if (kitchen.createUnknown && !window.confirm('创建会话结果未知。请先刷新列表；再次创建可能重复。仍要重新创建吗？')) return
  await kitchen.createConversation(kitchen.createUnknown)
}
function newMeal() {
  if (!kitchen.startNewMeal()) return
  if (route.query.mealId !== undefined || route.query.mealVersion !== undefined || route.query.optionId !== undefined) {
    void router.replace({ name: 'kitchen-conversation', params: { conversationId: routeId.value }, query: {} })
  }
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
function openHistoryMeal(id: string) {
  void (async () => {
    const opened = await kitchen.showMeal(id)
    if (opened && (route.query.mealId !== undefined || route.query.mealVersion !== undefined || route.query.optionId !== undefined)) {
      void router.replace({ name: 'kitchen-conversation', params: { conversationId: routeId.value }, query: {} })
    }
  })()
}
</script>

<template>
  <section class="kitchen-page" aria-labelledby="kitchen-title">
    <header class="kitchen-heading">
      <div><p class="eyebrow">XIAOCHU · KITCHEN ASSISTANT</p><h1 id="kitchen-title">和小厨商量这一餐</h1></div>
      <div class="kitchen-heading-links"><RouterLink :to="{ name: 'profile-settings' }">个人档案</RouterLink><RouterLink :to="{ name: 'chat' }">旧版推荐记录与操作核对</RouterLink></div>
    </header>
    <p class="kitchen-intro">描述本餐需求，小厨会列出服务器保存的餐单和材料。只有你点击“选这套并生成指导”后，才会先记录选择，再单独请求指导。预算不核价，已声明食材不代表家庭库存。</p>
    <p v-if="routeMealNotice" class="kitchen-warning" role="status">{{ routeMealNotice }}</p>

    <section v-if="kitchen.pending.length" class="kitchen-pending" role="status" aria-label="待核对的厨房操作">
      <h2>有尚未确认的操作</h2>
      <article v-for="item in kitchen.pending" :key="item.key" class="pending-item">
        <div><strong>{{ item.kind === 'GUIDE' ? '指导生成' : item.kind === 'SELECT' ? '餐单选择' : item.kind === 'REVISE' ? '餐单修订' : '新餐单' }}</strong>
          <p>{{ item.message }}</p><p>原请求编号：{{ item.requestId }}。核对时继续使用原请求内容；不会自动换编号。</p>
        </div>
        <RouterLink :to="{ name: 'kitchen-conversation', params: { conversationId: item.conversationId } }">回到原会话</RouterLink>
        <template v-if="item.status === 'unknown'">
          <button v-if="kitchen.canRetryPending(item.key)" class="secondary-button" type="button" :disabled="kitchen.busy" @click="kitchen.retryPending(item.key)">确认后核对原操作</button>
          <template v-else>
            <p>请回到原会话，{{ item.mealId ? '打开原餐单后核对。' : '进入新餐输入区后核对。' }}</p>
            <button v-if="routeId === item.conversationId" class="secondary-button" type="button"
              @click="item.mealId ? kitchen.showMeal(item.mealId) : newMeal()">{{ item.mealId ? '打开原餐单' : '回到新餐输入区' }}</button>
          </template>
        </template>
      </article>
      <p>等待停止不代表服务器操作停止。未知结果不自动重发；指导核对可能涉及模型费用。</p>
    </section>

    <div class="kitchen-grid" :class="{ 'with-selected-summary': hasSelectedSummary }">
      <aside class="kitchen-sidebar" aria-label="对话和餐单历史">
        <form class="kitchen-new-conversation" @submit.prevent="createConversation">
          <label for="kitchen-title-input">开始一段厨房对话</label>
          <input id="kitchen-title-input" v-model="kitchen.conversationTitle" :disabled="kitchen.createBusy" maxlength="100" placeholder="标题（可留空）">
          <button class="secondary-button" type="submit" :disabled="kitchen.createBusy">
            {{ kitchen.createBusy ? '正在创建…' : kitchen.createUnknown ? '确认后再次创建' : '新建对话' }}
          </button>
          <p v-if="kitchen.createError" class="kitchen-error" role="alert">{{ kitchen.createError }}</p>
        </form>
        <div class="history-heading"><h2>对话历史</h2><button class="text-button" type="button" :disabled="kitchen.conversationState === 'loading'" @click="kitchen.loadConversations(1)">刷新</button></div>
        <p v-if="kitchen.conversationState === 'loading'" role="status">正在读取对话…</p>
        <p v-else-if="kitchen.conversationState === 'error'" class="kitchen-error" role="alert">{{ kitchen.conversationError }}</p>
        <p v-else-if="!kitchen.conversations.items.length" class="muted">还没有厨房对话。</p>
        <nav v-else class="conversation-list">
          <RouterLink v-for="item in kitchen.conversations.items" :key="item.id"
            :to="{ name: 'kitchen-conversation', params: { conversationId: item.id } }">{{ item.title }}</RouterLink>
        </nav>
        <div class="history-pagination">
          <button class="secondary-button" type="button" :disabled="kitchen.conversationPage <= 1" @click="kitchen.loadConversations(kitchen.conversationPage - 1)">上一页</button>
          <span>第 {{ kitchen.conversationPage }} 页</span>
          <button class="secondary-button" type="button" :disabled="kitchen.conversationPage * kitchen.conversations.size >= kitchen.conversations.total" @click="kitchen.loadConversations(kitchen.conversationPage + 1)">下一页</button>
        </div>

        <template v-if="routeId">
          <div class="history-heading meal-history-heading"><h2>本餐历史</h2><button class="text-button" type="button" @click="newMeal">开始新的一餐</button></div>
          <p v-if="kitchen.mealState === 'loading'" role="status">正在读取本餐历史…</p>
          <p v-else-if="kitchen.mealState === 'error'" class="kitchen-error" role="alert">{{ kitchen.mealError }}</p>
          <p v-else-if="!kitchen.meals.items.length" class="muted">这段对话还没有餐单。</p>
          <nav v-else class="meal-list">
            <button v-for="item in kitchen.meals.items" :key="item.id" type="button"
              :class="{ active: kitchen.activeMeal?.id === item.id }" @click="openHistoryMeal(item.id)">
              <span>第 {{ item.version }} 版 · {{ statusLabels[item.status] ?? item.status }}</span>
              <small>{{ item.answer }}</small>
            </button>
          </nav>
          <div v-if="kitchen.meals.total > kitchen.meals.size" class="history-pagination">
            <button class="secondary-button" type="button" :disabled="kitchen.mealPage <= 1" @click="kitchen.loadMeals(kitchen.mealPage - 1)">上一页</button>
            <span>第 {{ kitchen.mealPage }} 页</span>
            <button class="secondary-button" type="button" :disabled="kitchen.mealPage * kitchen.meals.size >= kitchen.meals.total" @click="kitchen.loadMeals(kitchen.mealPage + 1)">下一页</button>
          </div>
        </template>
      </aside>

      <main class="kitchen-main">
        <template v-if="!routeId">
          <div class="kitchen-empty"><h2>先新建或选择一段对话</h2><p>每次新的一餐都会重新询问条件，不自动沿用上一餐。</p></div>
        </template>
        <template v-else-if="kitchen.mealState === 'loading' && !kitchen.activeMeal">
          <p class="page-state" role="status">正在读取会话和餐单…</p>
        </template>
        <template v-else>
          <header class="thread-heading">
            <div><p class="eyebrow">本次厨房对话</p><h2>{{ kitchen.conversation?.title ?? '厨房对话' }}</h2></div>
            <button class="text-button" type="button" @click="kitchen.refreshActiveMeal">只读刷新</button>
          </header>
          <p v-if="kitchen.profileState === 'loading'" class="muted" role="status">正在读取个人默认人数…</p>
          <p v-else-if="kitchen.profileState === 'error'" class="kitchen-warning" role="alert">
            个人默认人数读取失败，不能据此判断是否已设置；你仍可在本餐明确填写人数。{{ kitchen.profileError }}
          </p>
          <p v-else class="profile-note">个人默认人数：{{ kitchen.profileCount === null ? '尚未设置' : kitchen.profileCount + ' 人' }}。本餐明确填写优先；留空由服务器按个人档案处理。</p>
          <p v-if="kitchen.hasUnconfirmedTarget" class="kitchen-warning" role="status">本餐还有未确认操作，请先用原请求编号核对，再发起新的请求。</p>

          <section v-if="!kitchen.activeMeal || canRevise" class="meal-composer">
            <h3>{{ kitchen.activeMeal ? '补充条件并修订本餐' : '描述这一餐' }}</h3>
            <p v-if="kitchen.activeMeal" class="muted">修订会创建新版本。旧方案保留为历史，不能从旧版本继续选择。</p>
            <form @submit.prevent="kitchen.activeMeal ? kitchen.reviseMeal() : kitchen.createMeal()">
              <label for="meal-content">本餐需求</label>
              <textarea id="meal-content" v-model="kitchen.draft" rows="4" maxlength="8000"
                placeholder="例如：想吃清淡些，三个人，做三道菜和一份汤。系统不会自动沿用上一餐条件。" />
              <label for="meal-dining-count">本餐人数（可留空使用个人档案）</label>
              <input id="meal-dining-count" v-model="kitchen.diningCountInput" inputmode="numeric" autocomplete="off" placeholder="留空时不发送人数覆盖">
              <p v-if="kitchen.inputError" class="kitchen-error" role="alert">{{ kitchen.inputError }}</p>
              <p v-if="kitchen.operationError" class="kitchen-error" role="alert">{{ kitchen.operationError }}</p>
              <p v-if="kitchen.operationMessage" class="success-message" role="status">{{ kitchen.operationMessage }}</p>
              <button class="primary-button" type="submit" :disabled="kitchen.busy || kitchen.hasUnconfirmedTarget">
                {{ kitchen.busy ? '等待服务器结果…' : kitchen.activeMeal ? '提交修订' : '安排本餐' }}
              </button>
            </form>
          </section>

          <template v-if="kitchen.activeMeal">
            <article class="meal-state-card">
              <header><div><p class="eyebrow">餐单版本 {{ kitchen.activeMeal.version }}</p><h3>{{ statusLabels[kitchen.activeMeal.status] ?? kitchen.activeMeal.status }}</h3></div>
                <span :class="'facts-badge facts-' + kitchen.activeMeal.factsStatus">{{ kitchen.activeMeal.factsStatus === 'CURRENT' ? '事实当前' : kitchen.activeMeal.factsStatus === 'CHANGED' ? '历史事实已变化' : '事实不可用' }}</span>
              </header>
              <p class="meal-answer">{{ kitchen.activeMeal.answer }}</p>
              <p>人数：{{ kitchen.activeMeal.diningCount === null ? '未知' : kitchen.activeMeal.diningCount + ' 人' }}
                <span v-if="kitchen.activeMeal.diningCountSource">（{{ kitchen.activeMeal.diningCountSource === 'EXPLICIT' ? '本餐指定' : '个人档案快照' }}）</span>
              </p>
              <p v-if="kitchen.activeMeal.requirements.budgetYuan !== null">预算：{{ kitchen.activeMeal.requirements.budgetYuan }} 元（未核价）</p>
              <ul class="notice-list"><li v-for="code in kitchen.activeMeal.noticeCodes" :key="code">{{ activeNoticeLabels[code] ?? '本餐条件需结合服务端事实核对。' }}</li></ul>
              <p v-if="kitchen.activeMeal.factsStatus === 'CHANGED'" class="kitchen-warning">菜谱事实已变化；这里只能查看已保存快照。请修订本餐后再选择或生成指导。</p>
              <p v-if="kitchen.activeMeal.factsStatus === 'UNAVAILABLE'" class="kitchen-error">引用菜谱不可访问，内容已隐藏。</p>
              <div v-if="kitchen.activeMeal.status === 'OPTIONS_READY' || kitchen.activeMeal.status === 'SELECTED'" class="requirements">
                <h4>本餐条件快照</h4>
                <dl>
                  <dt>菜 / 汤</dt><dd>{{ kitchen.activeMeal.requirements.dishCount ?? '未知' }} / {{ kitchen.activeMeal.requirements.soupCount ?? '未知' }}</dd>
                  <dt>单菜时间上限</dt><dd>{{ kitchen.activeMeal.requirements.maxCookingMinutes === null ? '未知 / 未限制' : kitchen.activeMeal.requirements.maxCookingMinutes + ' 分钟' }}</dd>
                  <dt>厨具</dt><dd>{{ kitchen.activeMeal.requirements.availableToolNames === null ? '未知 / 未限制' : kitchen.activeMeal.requirements.availableToolNames.length ? kitchen.activeMeal.requirements.availableToolNames.join('、') : '明确没有厨具' }}</dd>
                  <dt>排除食材</dt><dd>{{ kitchen.activeMeal.requirements.excludedIngredientNames.join('、') || '未声明' }}</dd>
                  <dt>本次声明食材</dt><dd>{{ kitchen.activeMeal.requirements.availableIngredientNames.join('、') || '未声明；不读取家庭库存' }}</dd>
                </dl>
              </div>
            </article>

            <section v-if="kitchen.activeMeal.factsStatus !== 'UNAVAILABLE' && kitchen.activeMeal.status === 'OPTIONS_READY' && kitchen.activeMeal.options.length" class="meal-options">
              <h3>餐单方案</h3>
              <div class="meal-options-grid">
                <MealOptionCard v-for="option in kitchen.activeMeal.options" :key="option.id" :option="option"
                  :selected="option.id === kitchen.activeMeal.selectedOptionId"
                  :conversation-id="kitchen.activeMeal.conversationId" :meal-id="kitchen.activeMeal.id" :version="kitchen.activeMeal.version"
                  :disabled="kitchen.busy || kitchen.hasUnconfirmedTarget || kitchen.activeMeal.factsStatus !== 'CURRENT' || kitchen.activeMeal.status !== 'OPTIONS_READY'"
                  @select="kitchen.selectAndGuide" />
              </div>
            </section>

            <section v-if="kitchen.activeMeal.status === 'SELECTED' && kitchen.activeMeal.factsStatus !== 'UNAVAILABLE'" class="guide-area">
              <p v-if="selectedOption" class="profile-note">指导正文按菜谱组织；原菜谱事实与小厨讲解分开展示，模型补充单独标注。</p>
              <RouterLink v-if="canOpenShopping" class="shopping-entry" :to="shoppingRoute">确认食材并生成采购清单</RouterLink>
              <p v-if="kitchen.operationError" class="kitchen-error" role="alert">{{ kitchen.operationError }}</p>
              <button v-if="!kitchen.activeMeal.guideAvailable && kitchen.activeMeal.factsStatus === 'CURRENT'"
                class="primary-button" type="button" :disabled="kitchen.busy || kitchen.hasUnconfirmedTarget" @click="kitchen.regenerateGuide">
                {{ kitchen.busy ? '等待指导结果…' : kitchen.operationError ? '确认后重新请求指导（可能产生费用）' : '为已选方案生成指导' }}
              </button>
              <MealGuidePanel :guide="kitchen.guide" :state="kitchen.guideState" :error="kitchen.guideError" />
            </section>
          </template>
        </template>
      </main>
      <aside v-if="hasSelectedSummary && selectedOption && kitchen.activeMeal" class="selected-summary" aria-label="已选方案摘要">
        <p class="eyebrow">服务端确认已选</p><h2>方案 {{ selectedOption.ordinal }}</h2>
        <p class="summary-evaluation">{{ selectedOption.explanation }}</p>
        <h3>本方案菜谱</h3>
        <ul><li v-for="recipe in selectedOption.dishes.concat(selectedOption.soups)" :key="recipe.recipeId">
          <RouterLink :to="{ name: 'recipe-detail', params: { id: recipe.recipeId } }">{{ recipe.name }}</RouterLink>
        </li></ul>
        <RouterLink class="materials-link" :to="{ name: 'kitchen-materials', params: { conversationId: kitchen.activeMeal.conversationId, mealId: kitchen.activeMeal.id }, query: { version: String(kitchen.activeMeal.version), optionId: selectedOption.id } }">查看材料清单</RouterLink>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.kitchen-page{padding:40px 0 64px}.kitchen-heading,.thread-heading,.history-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}
.kitchen-heading h1{margin:8px 0;color:#2f4133;font-size:30px}.kitchen-heading-links{display:flex;align-items:center;gap:14px;flex-wrap:wrap}.kitchen-heading-links>a{color:#55724a;font-size:12px}.kitchen-intro{max-width:920px;color:#758072;font-size:13px;line-height:1.75}
.kitchen-grid{display:grid;grid-template-columns:275px minmax(0,1fr);gap:20px;margin-top:23px}.kitchen-grid.with-selected-summary{grid-template-columns:minmax(210px,.85fr) minmax(420px,2fr) minmax(210px,.9fr)}.kitchen-sidebar,.kitchen-main,.selected-summary{min-width:0;padding:18px;border:1px solid #e3e7dc;border-radius:12px;background:#fff}.kitchen-main{padding:22px}.selected-summary{align-self:start;position:sticky;top:18px}.selected-summary h2{margin:5px 0;color:#40553e;font-size:17px}.selected-summary h3{margin:17px 0 8px;color:#56664f;font-size:12px}.selected-summary ul{display:grid;gap:8px;padding-left:18px;font-size:12px}.selected-summary li{overflow-wrap:anywhere}.selected-summary a{color:#4e7047}.summary-evaluation{color:#687762;font-size:12px;line-height:1.65}.materials-link{display:inline-flex;margin:8px 0;padding:8px 11px;border:1px solid #d9e2d1;border-radius:7px;color:#486843;font-size:12px;text-decoration:none}
.kitchen-new-conversation,.meal-composer form{display:grid;gap:9px}.kitchen-new-conversation label,.meal-composer label{color:#4d5d47;font-size:12px;font-weight:600}.kitchen-new-conversation input,.meal-composer input,.meal-composer textarea{width:100%;padding:10px 12px;border:1px solid #dce3d4;border-radius:8px;background:#fff;color:#34442f;font:inherit;font-size:13px}.meal-composer textarea{resize:vertical;line-height:1.65}.kitchen-new-conversation button{justify-self:start}
.history-heading{margin:22px 0 8px}.history-heading h2{margin:0;color:#465744;font-size:14px}.history-heading .text-button{font-size:11px}.meal-history-heading{margin-top:25px}
.conversation-list,.meal-list{display:grid;gap:5px}.conversation-list a,.meal-list button{padding:9px 10px;border:0;border-radius:7px;background:transparent;color:#667360;font-size:12px;text-align:left;text-decoration:none;overflow-wrap:anywhere}.conversation-list a.router-link-active,.meal-list button.active{background:#edf2e5;color:#465f3d;font-weight:700}.meal-list button{display:grid;gap:4px;cursor:pointer}.meal-list small{display:-webkit-box;overflow:hidden;color:#7c8678;font-size:10px;line-height:1.4;-webkit-box-orient:vertical;-webkit-line-clamp:2}
.history-pagination{display:flex;align-items:center;justify-content:space-between;gap:5px;margin:12px 0;color:#879082;font-size:10px}.history-pagination .secondary-button{min-height:32px;padding:0 8px;font-size:10px}.muted{color:#80897d;font-size:11px;line-height:1.6}
.thread-heading{padding-bottom:14px;border-bottom:1px solid #edf0e8}.thread-heading h2{margin:3px 0;color:#354b37;font-size:18px}.profile-note{padding:10px 12px;border-radius:8px;background:#f3f6ed;color:#687662;font-size:11px;line-height:1.6}.kitchen-warning{padding:11px;border:1px solid #ead7a7;border-radius:8px;background:#fff9ea;color:#7c672f;font-size:12px;line-height:1.6}.kitchen-error{color:#a34c42;font-size:12px;line-height:1.6}.success-message{color:#4f7044;font-size:12px}
.kitchen-empty{padding:65px 12px;text-align:center;color:#73836a}.kitchen-empty h2{font-size:19px}.kitchen-empty p{font-size:12px}.meal-composer{margin-top:19px;padding:17px;border:1px solid #e5e9df;border-radius:11px;background:#fafbf8}.meal-composer h3,.meal-state-card h3,.meal-options>h3{margin:0;color:#3d513c;font-size:16px}.meal-composer form{margin-top:13px}.meal-composer .primary-button{justify-self:end;min-height:39px;padding:0 15px}
.meal-state-card{margin-top:17px;padding:16px;border:1px solid #e2e8db;border-radius:11px;background:#f9fbf6;color:#556251;font-size:12px;line-height:1.65}.meal-state-card header{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}.meal-answer{color:#465944;font-size:14px}.facts-badge{padding:4px 8px;border-radius:99px;background:#e8f0df;color:#4c693f;font-size:10px;white-space:nowrap}.facts-CHANGED{background:#fff0d2;color:#886622}.facts-UNAVAILABLE{background:#f7e4df;color:#8e4f44}.notice-list{padding-left:18px;color:#7a7151;font-size:11px}
.requirements{margin-top:13px;padding-top:12px;border-top:1px solid #e9ede4}.requirements h4{margin:0 0 9px;color:#5a6a52;font-size:12px}.requirements dl{display:grid;grid-template-columns:125px 1fr;gap:5px;margin:0}.requirements dt{color:#828b7e}.requirements dd{margin:0;overflow-wrap:anywhere}
.meal-options{margin-top:20px}.meal-options>h3{margin-bottom:12px}.meal-options-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.guide-area{margin-top:18px}.guide-area>.primary-button{min-height:39px;padding:0 15px}
.shopping-entry{display:inline-flex;margin:6px 0 12px;padding:10px 13px;border-radius:8px;background:#526d46;color:#fff;font-size:12px;text-decoration:none}
.kitchen-pending{margin:16px 0;padding:14px;border:1px solid #e6c77f;border-radius:10px;background:#fff8e7;color:#725b26;font-size:12px}.kitchen-pending h2{margin:0 0 9px;font-size:14px}.pending-item{display:flex;align-items:center;gap:12px;justify-content:space-between;padding:10px 0;border-top:1px solid #f0e4c6}.pending-item p{margin:4px 0;color:#786a48;font-size:11px;line-height:1.5}.pending-item a{color:#536d43;white-space:nowrap}.kitchen-pending>p{font-size:11px}
@media(max-width:1100px){.kitchen-grid.with-selected-summary{grid-template-columns:220px minmax(0,1fr)}.selected-summary{grid-column:2;position:static}.meal-options-grid{grid-template-columns:1fr}}
@media(max-width:850px){.kitchen-grid{grid-template-columns:230px minmax(0,1fr)}}
@media(max-width:650px){.kitchen-page{padding:30px 0 48px}.kitchen-grid,.kitchen-grid.with-selected-summary{grid-template-columns:1fr}.kitchen-sidebar,.kitchen-main,.selected-summary{padding:15px}.selected-summary{grid-column:1}.pending-item{align-items:flex-start;flex-direction:column}.requirements dl{grid-template-columns:100px 1fr}}
</style>
