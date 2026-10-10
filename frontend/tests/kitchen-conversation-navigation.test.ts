import { computed, effectScope, nextTick, ref, watch } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createKitchenState } from '@/modules/conversation/kitchen/state'
import type { KitchenApi } from '@/modules/conversation/kitchen/types'
import type { ConversationApi, ConversationView, Page } from '@/modules/conversation/types'
import { ApiError } from '@/shared/api/types'

const scopes: ReturnType<typeof effectScope>[] = []
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
function conversation(id: string): ConversationView {
  return { id, title: '导航回归', createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z' }
}
async function harness(create: ConversationApi['create']) {
  const owner = ref<string | null>('account-a')
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/kitchen/:conversationId?', name: 'kitchen-conversation', component: { template: '<div />' } },
  ] })
  await router.push('/kitchen')
  const meals: KitchenApi = {
    listMeals: vi.fn(async () => ({ items: [], page: 1, size: 20, total: 0 })),
    getMeal: vi.fn(), createMeal: vi.fn(), reviseMeal: vi.fn(), selectOption: vi.fn(),
    generateGuide: vi.fn(), getGuide: vi.fn(), getAction: vi.fn(),
  }
  const conversations = {
    create, list: vi.fn(async () => ({ items: [conversation('11')], page: 1, size: 20, total: 1 })),
    detail: vi.fn(async (id: string) => conversation(id)),
  }
  const scope = effectScope(); scopes.push(scope)
  const state = scope.run(() => {
    const state = createKitchenState({ ownerId: computed(() => owner.value), conversations, meals,
      profile: { getProfile: async () => ({ defaultDiningCount: 3 }), saveProfile: vi.fn() } })
    const routeId = computed(() => typeof router.currentRoute.value.params.conversationId === 'string'
      ? router.currentRoute.value.params.conversationId : null)
    // 对应页面的两个watch：成功ID→真实内存路由导航→实际activate，保留相同flush语义。
    watch([routeId, owner], ([id]) => { void state.activate(id) }, { immediate: true, flush: 'sync' })
    watch(state.createdConversationId, async id => {
      if (typeof id === 'string') await router.push({ name: 'kitchen-conversation', params: { conversationId: id } })
    })
    return state
  })!
  await nextTick()
  return { state, router, owner, conversations }
}
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()) })

describe('U5 R3：成功创建与页面导航生命周期', () => {
  it('成功POST通过页面watch导航并实际激活，不误报未知且仅创建一次', async () => {
    const create = vi.fn(async () => conversation('11'))
    const { state, router, conversations } = await harness(create)
    // 成功POST之后的列表GET仍在等待，让真实路由导航发生于finally之前。
    const refresh = deferred<Page<ConversationView>>()
    conversations.list.mockImplementationOnce(() => refresh.promise)
    const operation = state.createConversation()
    try {
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/kitchen/11'))
      await vi.waitFor(() => expect(state.conversation.value?.id).toBe('11'))
      expect(state.activeConversationId.value).toBe('11')
      expect(conversations.detail).toHaveBeenCalledWith('11', expect.any(AbortSignal))
      expect(state.createBusy.value).toBe(false)
      expect(state.createUnknown.value).toBe(false)
      expect(state.createError.value).toBe('')
      expect(create).toHaveBeenCalledTimes(1)
    } finally {
      refresh.resolve({ items: [conversation('11')], page: 1, size: 20, total: 1 })
      await operation
    }
  })

  it('传输超时仍未知，不自动导航或重复POST', async () => {
    const create = vi.fn(async () => { throw new ApiError(0, 'REQUEST_TIMEOUT', '') })
    const { state, router } = await harness(create)
    expect(await state.createConversation()).toBe(false)
    expect(state.createUnknown.value).toBe(true)
    expect(state.createBusy.value).toBe(false)
    expect(state.createdConversationId.value).toBeNull()
    expect(router.currentRoute.value.path).toBe('/kitchen')
    expect(await state.createConversation()).toBe(false)
    expect(create).toHaveBeenCalledTimes(1)
  })

  it('成功前手工导航取消实际AbortSignal，取消后保留未知', async () => {
    const create = vi.fn((_title?: string, signal?: AbortSignal) => new Promise<ConversationView>((_done, fail) => {
      signal!.addEventListener('abort', () => fail(new ApiError(0, 'REQUEST_ABORTED', '')), { once: true })
    }))
    const { state, router } = await harness(create)
    const operation = state.createConversation()
    await router.push('/kitchen/9')
    expect(await operation).toBe(false)
    expect(create.mock.calls[0][1]?.aborted).toBe(true)
    expect(state.createUnknown.value).toBe(true)
    expect(state.createBusy.value).toBe(false)
    expect(state.createdConversationId.value).toBeNull()
    expect(router.currentRoute.value.path).toBe('/kitchen/9')
  })

  it('成功前手工离开，后来明确收到成功只提示历史查找，不抢导航', async () => {
    const response = deferred<ConversationView>()
    const create = vi.fn(() => response.promise)
    const { state, router } = await harness(create)
    const operation = state.createConversation()
    await router.push('/kitchen/9')
    expect(state.createUnknown.value).toBe(true)
    response.resolve(conversation('11'))
    expect(await operation).toBe(true)
    expect(router.currentRoute.value.path).toBe('/kitchen/9')
    expect(state.activeConversationId.value).toBe('9')
    expect(state.createdConversationId.value).toBeNull()
    expect(state.createUnknown.value).toBe(false)
    expect(state.createError.value).toContain('会话已创建')
    expect(create).toHaveBeenCalledTimes(1)
  })

  it.each([false, true])('账号切换后的迟到成功隔离（切回原账号=%s）', async backToOriginal => {
    const response = deferred<ConversationView>()
    const { state, router, owner } = await harness(vi.fn(() => response.promise))
    const operation = state.createConversation()
    owner.value = 'account-b'
    if (backToOriginal) owner.value = 'account-a'
    response.resolve(conversation('11'))
    expect(await operation).toBe(false)
    expect(router.currentRoute.value.path).toBe('/kitchen')
    expect(state.createdConversationId.value).toBeNull()
    expect(state.createUnknown.value).toBe(false)
    expect(state.createError.value).toBe('')
  })

  it('双击期间只创建一次，成功导航后仍没有未知提示', async () => {
    const response = deferred<ConversationView>()
    const create = vi.fn(() => response.promise)
    const { state, router } = await harness(create)
    const first = state.createConversation()
    expect(await state.createConversation()).toBe(false)
    expect(create).toHaveBeenCalledTimes(1)
    response.resolve(conversation('11')); await first
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/kitchen/11'))
    expect(state.createUnknown.value).toBe(false)
    expect(state.createBusy.value).toBe(false)
  })

  it('旧成功请求的列表刷新收尾不能清除后一次创建的等待状态', async () => {
    const secondResponse = deferred<ConversationView>()
    const create = vi.fn().mockResolvedValueOnce(conversation('11')).mockImplementationOnce(() => secondResponse.promise)
    const { state, router, conversations } = await harness(create)
    const refresh = deferred<Page<ConversationView>>()
    conversations.list.mockImplementationOnce(() => refresh.promise)
    const first = state.createConversation()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/kitchen/11'))
    const second = state.createConversation()
    expect(state.createBusy.value).toBe(true)
    refresh.resolve({ items: [conversation('11')], page: 1, size: 20, total: 1 })
    await first
    expect(state.createBusy.value).toBe(true)
    secondResponse.resolve(conversation('12')); await second
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/kitchen/12'))
    expect(state.createBusy.value).toBe(false)
    expect(state.createUnknown.value).toBe(false)
    expect(create).toHaveBeenCalledTimes(2)
  })
})
