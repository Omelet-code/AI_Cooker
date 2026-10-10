import { createSSRApp, h, reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { renderToString } from '@vue/server-renderer'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useIdentityStore } from '@/stores/identity'
import KitchenAssistantView from '@/views/KitchenAssistantView.vue'
import { meal } from './kitchen-fixtures'

const holder = vi.hoisted(() => ({ kitchen: null as any }))
vi.mock('@/modules/conversation/kitchen/store', () => ({ useKitchenStore: () => holder.kitchen }))

async function render(overrides: Record<string, unknown> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/kitchen/:conversationId/meals/:mealId/materials', name: 'kitchen-materials', component: { template: '<main>只读材料</main>' } },
    { path: '/kitchen/:conversationId', name: 'kitchen-conversation', component: KitchenAssistantView },
    { path: '/shopping/new', name: 'shopping-new', component: { template: '<main>采购预览</main>' } },
    { path: '/recipes/:id', name: 'recipe-detail', component: { template: '<main>菜谱</main>' } },
    { path: '/settings/profile', name: 'profile-settings', component: { template: '<main>档案</main>' } },
    { path: '/chat', name: 'chat', component: { template: '<main>旧版聊天</main>' } },
  ] })
  await router.push('/kitchen/1')
  const currentMeal = meal({ status: 'SELECTED', selectedOptionId: '100', diningCount: 3, diningCountSource: 'PROFILE', ...overrides })
  holder.kitchen = reactive({
    conversations: { items: [] }, conversationPage: 1, conversationState: 'ready', conversationError: '', conversationTitle: '', createdConversationId: null, createBusy: false, createUnknown: false, createError: '',
    meals: { items: [currentMeal] }, mealPage: 1, mealState: 'ready', mealError: '', activeConversationId: '1', activeMeal: currentMeal, guide: null, guideState: 'idle', guideError: '',
    profileState: 'ready', profileCount: 3, profileError: '', draft: '', diningCountInput: '', inputError: '', operationError: '', operationMessage: '', busy: false, pending: [], anyPending: false, activePending: null, hasPendingWait: false, hasUnconfirmedTarget: false, canRetryPending: () => false,
    activate: vi.fn(), leavePage: vi.fn(), loadConversations: vi.fn(), loadMeals: vi.fn(), showMeal: vi.fn(), createConversation: vi.fn(), startNewMeal: () => true, createMeal: vi.fn(), reviseMeal: vi.fn(), selectAndGuide: vi.fn(), regenerateGuide: vi.fn(), retryPending: vi.fn(), refreshActiveMeal: vi.fn(),
    ...('kitchenOverrides' in overrides ? overrides.kitchenOverrides as Record<string, unknown> : {}),
  })
  const pinia = createPinia(); setActivePinia(pinia)
  const identity = useIdentityStore(); identity.user = { id: 'user-a', username: 'cook' }; identity.status = 'authenticated'
  const app = createSSRApp({ render: () => h(RouterView) }); app.use(pinia); app.use(router)
  return { html: await renderToString(app), router }
}

describe('F3-D 小厨到采购入口条件', () => {
  beforeEach(() => { setActivePinia(createPinia()) })
  it('只对当前已选套餐、明确人数且无在途操作展示来源参数', async () => {
    const { html, router } = await render()
    expect(html).toContain('确认食材并生成采购清单')
    expect(router.resolve({ name: 'shopping-new', query: { conversationId: '1', mealId: '30', expectedVersion: '1', optionId: '100' } }).fullPath)
      .toBe('/shopping/new?conversationId=1&mealId=30&expectedVersion=1&optionId=100')
  })
  it.each([
    ['未选择方案', { status: 'OPTIONS_READY', selectedOptionId: null }],
    ['人数未知', { diningCount: null, diningCountSource: null }],
    ['菜谱事实已变化', { factsStatus: 'CHANGED' }],
    ['用户操作在途', { kitchenOverrides: { busy: true } }],
    ['存在未知操作', { kitchenOverrides: { anyPending: true } }],
  ])('%s时隐藏采购入口', async (_label, overrides) => {
    const { html } = await render(overrides as Record<string, unknown>)
    expect(html).not.toContain('确认食材并生成采购清单')
  })
})
