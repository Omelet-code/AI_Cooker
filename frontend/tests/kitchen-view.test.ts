import { createSSRApp, h, reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterView } from 'vue-router'
import { useIdentityStore } from '@/stores/identity'
import { fourDishMeal, guide, meal } from './kitchen-fixtures'

const holder = vi.hoisted(() => ({
  kitchen: null as any,
  chat: { pending: null },
  cooking: { pendingCooking: null },
}))
vi.mock('@/modules/conversation/kitchen/store', () => ({ useKitchenStore: () => holder.kitchen }))
vi.mock('@/modules/conversation/store', () => ({ useConversationStore: () => holder.chat }))
vi.mock('@/stores/cooking', () => ({ useCookingStore: () => holder.cooking }))
vi.mock('vue-router', async original => {
  const actual = await original<typeof import('vue-router')>()
  return { ...actual, createWebHistory: actual.createMemoryHistory }
})
import router from '@/router'

let pinia: ReturnType<typeof createPinia>
function state(overrides: Record<string, unknown> = {}) {
  const current = meal()
  return reactive({
    conversations: { items: [], page: 1, size: 20, total: 0 }, conversationPage: 1,
    conversationState: 'ready', conversationError: '', conversation: { id: '1', title: '虚构对话' },
    conversationTitle: '', createdConversationId: null, createBusy: false, createUnknown: false, createError: '',
    meals: { items: [], page: 1, size: 20, total: 0 }, mealPage: 1, mealState: 'ready', mealError: '',
    activeConversationId: '1', activeMeal: current, guide: null, guideState: 'idle', guideError: '',
    profileState: 'ready', profileCount: 3, profileError: '', draft: '', diningCountInput: '',
    inputError: '', operationError: '', operationMessage: '', busy: false, pending: [],
    anyPending: false, activePending: null, hasPendingWait: false, hasUnconfirmedTarget: false, canRetryPending: vi.fn(() => false),
    activate: vi.fn(), leavePage: vi.fn(), loadConversations: vi.fn(), loadMeals: vi.fn(),
    showMeal: vi.fn(), createConversation: vi.fn(), startNewMeal: vi.fn(), createMeal: vi.fn(),
    reviseMeal: vi.fn(), selectAndGuide: vi.fn(), regenerateGuide: vi.fn(), retryPending: vi.fn(),
    refreshActiveMeal: vi.fn(),
    ...overrides,
  })
}
beforeEach(async () => {
  pinia = createPinia(); setActivePinia(pinia)
  const identity = useIdentityStore()
  identity.user = { id: '1', username: 'virtual-user' } as never
  identity.status = 'authenticated'
  holder.chat = { pending: null }
  holder.cooking = { pendingCooking: null }
  holder.kitchen = state()
  await router.push('/kitchen/1')
})
async function render() {
  const app = createSSRApp({ render: () => h(RouterView) })
  app.use(pinia); app.use(router)
  return renderToString(app)
}

describe('UF2厨房助手视图与路由', () => {
  it('路由受登录守卫保护且旧/chat路由仍保留', async () => {
    expect(router.resolve('/kitchen').meta.requiresAuth).toBe(true)
    expect(router.resolve('/kitchen/90071992547409930').name).toBe('kitchen-conversation')
    expect(router.resolve('/chat/1').name).toBe('chat-conversation')
    expect(router.resolve('/kitchen/1/meals/30/materials?version=1&optionId=100').name).toBe('kitchen-materials')
    expect(router.resolve('/kitchen/1/meals/30/materials?version=1&optionId=100').meta.requiresAuth).toBe(true)
    const identity = useIdentityStore()
    identity.user = null; identity.status = 'anonymous'
    await router.push('/recipes')
    await router.push('/kitchen/1')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/kitchen/1')
  })

  it('从统一厨房工作台保留旧版推荐记录与操作核对入口', async () => {
    const html = await render()
    expect(html).toMatch(/<a[^>]*href="\/chat"[^>]*>旧版推荐记录与操作核对<\/a>/)
  })

  it('材料详情返回链接通过mealId只读恢复原餐单，不触发选择或指导写入', async () => {
    await router.push('/kitchen/1?mealId=30&mealVersion=1&optionId=100')
    const html = await render()
    await vi.waitFor(() => expect(holder.kitchen.showMeal).toHaveBeenCalledWith('30'))
    expect(html).toContain('和小厨商量这一餐')
    expect(holder.kitchen.createMeal).not.toHaveBeenCalled()
    expect(holder.kitchen.reviseMeal).not.toHaveBeenCalled()
    expect(holder.kitchen.selectAndGuide).not.toHaveBeenCalled()
    expect(holder.kitchen.regenerateGuide).not.toHaveBeenCalled()
  })

  it('未选时展示两套方案并用材料详情入口代替长材料清单', async () => {
    holder.kitchen.activeMeal.noticeCodes = ['PREFERENCE_NOT_GUARANTEED', 'BUDGET_NOT_VERIFIED', 'AVAILABLE_INGREDIENTS_NOT_INVENTORY']
    const html = await render()
    expect(html).toContain('个人默认人数：3 人')
    expect(html).toContain('个人档案快照')
    expect(html).toContain('餐单方案')
    expect(html).toContain('方案 1')
    expect(html).toContain('方案 2')
    expect(html).not.toContain('番茄适量')
    expect(html).toContain('查看材料清单')
    expect(html).toContain('/kitchen/1/meals/30/materials?version=1&amp;optionId=100')
    expect(html).toContain('需要查看并确认')
    expect(html).toContain('预算尚未核价')
    expect(html).toContain('未读取家庭库存')
    expect(html).toContain('href="/recipes/10"')
  })

  it('选定后正文按菜组织，原事实与讲解分开且引用集中到末尾', async () => {
    const selected = meal({ status: 'SELECTED', selectedOptionId: '100' })
    holder.kitchen = state({ activeMeal: selected, guide: guide(selected), guideState: 'ready' })
    const html = await render()
    expect(html).toContain('本餐烹饪指导')
    expect(html).toContain('aria-label="已选方案摘要"')
    expect(html).toContain('整餐备菜与准备')
    expect(html).toContain('逐菜指导')
    expect(html).toContain('原菜谱事实')
    expect(html).toContain('小厨讲解')
    expect(html).toContain('模型补充：本菜可选建议')
    expect(html).toContain('整餐安排与提醒')
    expect(html).toContain('原始注意事项')
    expect(html).toContain('参考菜谱')
    expect(html).toContain('href=\"/recipes/10\"')
    expect(html).toContain('href=\"/recipes/20\"')
    expect(html).not.toContain('href=\"#kitchen-source-')
    expect(html).toContain('查看材料清单')
  })

  it('指导读取失败仍保留已选摘要和显式重新生成入口', async () => {
    const selected = meal({ status: 'SELECTED', selectedOptionId: '100' })
    holder.kitchen = state({ activeMeal: selected, guide: null, guideState: 'error', guideError: '读取指导失败' })
    const html = await render()
    expect(html).toContain('aria-label="已选方案摘要"')
    expect(html).toContain('方案 1')
    expect(html).toContain('读取指导失败')
    expect(html).toContain('为已选方案生成指导')
  })

  it('CHANGED历史指导保留已选快照并禁用采购入口', async () => {
    const selected = meal({ status: 'SELECTED', selectedOptionId: '100', factsStatus: 'CHANGED' })
    const savedGuide = guide(selected)
    savedGuide.factsStatus = 'CHANGED'
    holder.kitchen = state({ activeMeal: selected, guide: savedGuide, guideState: 'ready' })
    const html = await render()
    expect(html).toContain('历史快照，仅供回看')
    expect(html).toContain('aria-label="已选方案摘要"')
    expect(html).not.toContain('确认食材并生成采购清单')
  })

  it('UNAVAILABLE隐藏菜名与材料；档案读取失败不显示为未设置', async () => {
    const hidden = meal({ factsStatus: 'UNAVAILABLE', options: [], selectedOptionId: null })
    holder.kitchen = state({ activeMeal: hidden, profileState: 'error', profileCount: null, profileError: '仿真读取错误' })
    const html = await render()
    expect(html).toContain('不能据此判断是否已设置')
    expect(html).not.toContain('个人默认人数：尚未设置')
    expect(html).toContain('引用菜谱不可访问')
    expect(html).not.toContain('虚构菜谱10')
    expect(html).not.toContain('番茄适量')
  })

  it('澄清状态展示服务端问题和修订入口；单套或无匹配不补造第二套', async () => {
    const clarification = meal({
      status: 'CLARIFICATION', options: [], answer: '请说明是否需要清真菜。',
    })
    holder.kitchen = state({ activeMeal: clarification })
    const clarificationHtml = await render()
    expect(clarificationHtml).toContain('请说明是否需要清真菜。')
    expect(clarificationHtml).toContain('补充条件并修订本餐')
    expect(clarificationHtml).not.toContain('方案 1')

    const oneOption = meal({ options: [meal().options[0]], answer: '当前只有一套符合条件。' })
    holder.kitchen = state({ activeMeal: oneOption })
    const oneOptionHtml = await render()
    expect(oneOptionHtml).toContain('方案 1')
    expect(oneOptionHtml).not.toContain('方案 2')

    const noMatch = meal({ status: 'FAILED', options: [], answer: '当前条件下没有可用方案。' })
    holder.kitchen = state({ activeMeal: noMatch })
    const noMatchHtml = await render()
    expect(noMatchHtml).toContain('当前条件下没有可用方案。')
    expect(noMatchHtml).not.toContain('方案 1')
  })

  it('四菜一汤两套材料都可展示；另一餐上下文只提供返回入口', async () => {
    const full = fourDishMeal()
    holder.kitchen = state({ activeMeal: full })
    const html = await render()
    expect(html).toContain('方案 1'); expect(html).toContain('方案 2')
    for (const option of full.options) {
      for (const recipe of [...option.dishes, ...option.soups]) expect(html).toContain(recipe.name)
    }
    expect(html.match(/查看材料清单/g)).toHaveLength(2)
    expect(html).not.toContain('3.000')
    holder.kitchen = state({ activeMeal: meal({ id: '31' }), pending: [{
      key: 'original', kind: 'SELECT', conversationId: '1', mealId: '30', originMealId: '30',
      requestId: '原编号', status: 'unknown', message: '需核对原餐单',
    }] })
    const pendingHtml = await render()
    expect(pendingHtml).toContain('打开原餐单')
    expect(pendingHtml).not.toContain('确认后核对原操作')
  })
})
