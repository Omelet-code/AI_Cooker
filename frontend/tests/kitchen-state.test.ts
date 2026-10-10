import { computed, effectScope, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/types'
import { createKitchenState } from '@/modules/conversation/kitchen/state'
import type { KitchenApi, MealActionView, MealGuideView, MealView } from '@/modules/conversation/kitchen/types'
import type { ConversationApi, ConversationView } from '@/modules/conversation/types'
import { actionView, guide, meal } from './kitchen-fixtures'

const scopes: ReturnType<typeof effectScope>[] = []
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
function conversation(id = '1'): ConversationView {
  return { id, title: '虚构厨房会话', createdAt: '2026-10-04T00:00:00Z', updatedAt: '2026-10-04T00:00:00Z' }
}
function page<T>(items: T[] = [], pageNo = 1) { return { items, page: pageNo, size: 20, total: items.length } }

function harness(
  overrides: Partial<KitchenApi> = {},
  conversationOverrides: Partial<Pick<ConversationApi, 'list' | 'create' | 'detail'>> = {},
  profileOverride?: { getProfile: (signal?: AbortSignal) => Promise<unknown>; saveProfile: () => void },
) {
  const owner = ref<string | null>('user-a')
  const scope = effectScope(); scopes.push(scope)
  let sequence = 0
  const uuid = vi.fn(() => '00000000-0000-4000-8000-' + String(++sequence).padStart(12, '0'))
  const api: KitchenApi = {
    listMeals: vi.fn(async () => page<MealView>()),
    getMeal: vi.fn(async (_cid, id) => meal({ id })),
    createMeal: vi.fn(async (_cid, input) => actionView('CREATE', input.requestId, meal())),
    reviseMeal: vi.fn(async (_cid, id, input) => actionView('REVISE', input.requestId, meal({ id, version: input.expectedVersion + 1 }))),
    selectOption: vi.fn(async (_cid, id, input) => actionView('SELECT', input.requestId, meal({
      id, status: 'SELECTED', selectedOptionId: input.optionId,
    }))),
    getAction: vi.fn(async (_cid, id, actionId) => {
      const result = actionView('SELECT', '00000000-0000-4000-8000-000000000001', meal({ id }), 'RUNNING')
      result.action.id = actionId
      return result
    }),
    generateGuide: vi.fn(async (_cid, id, input) => actionView('GUIDE', input.requestId, meal({
      id, status: 'SELECTED', selectedOptionId: input.optionId,
      guide: { id: '700', schemaVersion: 1, optionId: input.optionId, version: input.expectedVersion },
      guideAvailable: true,
    }))),
    getGuide: vi.fn(async (_cid, id) => ({ ...guide(), mealId: id })),
    ...overrides,
  }
  const conversations: Pick<ConversationApi, 'list' | 'create' | 'detail'> = {
    list: vi.fn(async () => page([conversation()])),
    create: vi.fn(async () => conversation('2')),
    detail: vi.fn(async id => conversation(id)),
    ...conversationOverrides,
  }
  const profile = profileOverride ?? { getProfile: vi.fn(async () => ({ defaultDiningCount: 3 })), saveProfile: vi.fn() }
  const state = scope.run(() => createKitchenState({
    ownerId: computed(() => owner.value), meals: api, conversations, profile, uuid,
    wait: async () => undefined,
  }))!
  return { state, api, owner, scope, uuid, profile, conversations }
}
afterEach(() => {
  for (const scope of scopes.splice(0)) scope.stop()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('UF2 餐单与指导状态', () => {
  it('打开和刷新历史只GET；人数留空时不伪造档案值为EXPLICIT', async () => {
    const { state, api, profile } = harness()
    await state.activate('1')
    expect(api.getMeal).not.toHaveBeenCalled()
    expect(api.createMeal).not.toHaveBeenCalled()
    expect(api.selectOption).not.toHaveBeenCalled()
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(profile.getProfile).toHaveBeenCalledTimes(1)
    state.draft.value = '一餐需求'
    state.diningCountInput.value = ''
    await state.createMeal()
    expect(api.createMeal).toHaveBeenCalledWith('1', expect.not.objectContaining({ diningCount: expect.anything() }), expect.any(AbortSignal))
  })

  it('选项按钮路径先SELECT再GUIDE，两个POST使用不同请求编号', async () => {
    let saved = meal()
    const select = vi.fn(async (_cid: string, _id: string, input: { requestId: string; optionId: string }) => {
      saved = meal({ status: 'SELECTED', selectedOptionId: input.optionId })
      return actionView('SELECT', input.requestId, saved)
    })
    const generate = vi.fn(async (_cid: string, _id: string, input: { requestId: string; optionId: string; expectedVersion: number }) => {
      saved = meal({
        status: 'SELECTED', selectedOptionId: input.optionId,
        guide: { id: '700', schemaVersion: 1, optionId: input.optionId, version: 1 }, guideAvailable: true,
      })
      return actionView('GUIDE', input.requestId, saved)
    })
    const { state, api } = harness({
      selectOption: select,
      getMeal: vi.fn(async () => saved),
      generateGuide: generate,
      getGuide: vi.fn(async () => guide()),
    })
    await state.activate('1')
    state.activeMeal.value = meal()
    await state.selectAndGuide('100')
    expect(select).toHaveBeenCalledTimes(1)
    expect(generate).toHaveBeenCalledTimes(1)
    expect(select.mock.invocationCallOrder[0]).toBeLessThan(generate.mock.invocationCallOrder[0])
    expect(select.mock.calls[0][2].requestId).not.toBe(generate.mock.calls[0][2].requestId)
    expect(state.activeMeal.value?.status).toBe('SELECTED')
    expect(state.guide.value?.id).toBe('700')
    expect(api.getGuide).toHaveBeenCalledTimes(1)
  })

  it('双击只启动一条选择链', async () => {
    const pending = deferred<MealActionView>()
    const selected = meal({ status: 'SELECTED', selectedOptionId: '100' })
    const { state, api } = harness({
      selectOption: vi.fn(() => pending.promise),
      getMeal: vi.fn(async () => selected),
    })
    await state.activate('1')
    state.activeMeal.value = meal()
    const first = state.selectAndGuide('100')
    expect(await state.selectAndGuide('100')).toBe(false)
    expect(api.selectOption).toHaveBeenCalledTimes(1)
    pending.resolve(actionView('SELECT', '00000000-0000-4000-8000-000000000001',
      meal({ status: 'SELECTED', selectedOptionId: '100' })))
    await first
    expect(api.generateGuide).toHaveBeenCalledTimes(1)
  })

  it('选择请求未知时保留原键，不自动发送指导；手工核对只GET已知动作', async () => {
    const { state, api } = harness({
      selectOption: vi.fn().mockRejectedValue(new ApiError(0, 'REQUEST_TIMEOUT', '')),
    })
    await state.activate('1')
    state.activeMeal.value = meal()
    await state.selectAndGuide('100')
    expect(state.pending.value).toHaveLength(1)
    expect(state.pending.value[0].status).toBe('unknown')
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(api.selectOption).toHaveBeenCalledTimes(1)
    expect(state.pending.value[0].payload).toMatchObject({ optionId: '100', expectedVersion: 1 })
  })

  it('已有指导在刷新/选择复用时只GET，不增加指导POST', async () => {
    const saved = meal({
      status: 'SELECTED', selectedOptionId: '100',
      guide: { id: '700', schemaVersion: 1, optionId: '100', version: 1 }, guideAvailable: true,
    })
    const { state, api } = harness({
      selectOption: vi.fn(async (_cid, _id, input) => actionView('SELECT', input.requestId, saved)),
      getMeal: vi.fn(async () => saved),
    })
    await state.activate('1')
    state.activeMeal.value = meal()
    await state.selectAndGuide('100')
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(api.getGuide).toHaveBeenCalledWith('1', '30', '700', expect.any(AbortSignal))
    expect(state.guide.value?.id).toBe('700')
  })

  it('201响应中的FAILED保留选择；重新生成需显式创建新键', async () => {
    let calls = 0
    const selected = meal({ status: 'SELECTED', selectedOptionId: '100' })
    const generate = vi.fn(async (_cid, id, input) => {
      calls++
      return actionView('GUIDE', input.requestId, selected, calls === 1 ? 'FAILED' : 'SUCCEEDED')
    })
    const { state } = harness({ generateGuide: generate, getMeal: vi.fn(async () => selected) })
    const confirm = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('window', { confirm })
    await state.activate('1')
    state.activeMeal.value = meal()
    await state.selectAndGuide('100')
    expect(state.activeMeal.value?.status).toBe('SELECTED')
    expect(state.activeMeal.value?.selectedOptionId).toBe('100')
    expect(await state.regenerateGuide()).toBe(false)
    expect(generate).toHaveBeenCalledTimes(1)
    await state.regenerateGuide()
    expect(generate).toHaveBeenCalledTimes(2)
    expect(generate.mock.calls[0][2].requestId).not.toBe(generate.mock.calls[1][2].requestId)
    expect(confirm).toHaveBeenCalledTimes(2)
  })

  it('GUIDE结果未知时保留已选方案、原编号和原请求内容，不自动重发', async () => {
    const generate = vi.fn(async (_cid: string, _id: string, _input: { requestId: string; expectedVersion: number; optionId: string }) => {
      throw new ApiError(0, 'REQUEST_TIMEOUT', '')
    })
    const selected = meal({ status: 'SELECTED', selectedOptionId: '100' })
    const { state } = harness({ generateGuide: generate, getMeal: vi.fn(async () => selected) })
    await state.activate('1')
    state.activeMeal.value = meal()
    await state.selectAndGuide('100')
    expect(state.activeMeal.value?.status).toBe('SELECTED')
    expect(state.activeMeal.value?.selectedOptionId).toBe('100')
    expect(state.pending.value).toHaveLength(1)
    const pending = state.pending.value[0]
    expect(pending.kind).toBe('GUIDE')
    expect(pending.status).toBe('unknown')
    expect(pending.payload).toMatchObject({ requestId: pending.requestId, optionId: '100', expectedVersion: 1 })
    expect(generate).toHaveBeenCalledTimes(1)
  })

  it('201失败、202在途和历史轮询都有界', async () => {
    const running = actionView('SELECT', '00000000-0000-4000-8000-000000000001',
      meal({ status: 'SELECTED', selectedOptionId: '100' }), 'RUNNING')
    const { state, api } = harness({
      selectOption: vi.fn(async () => running),
      getAction: vi.fn(async (_cid, id, actionId) => {
        const result = actionView('SELECT', running.action.requestId, meal({ id, status: 'SELECTED', selectedOptionId: '100' }), 'RUNNING')
        result.action.id = actionId
        return result
      }),
    })
    await state.activate('1')
    state.activeMeal.value = meal()
    await state.selectAndGuide('100')
    expect(api.getAction).toHaveBeenCalledTimes(30)
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(state.pending.value[0].status).toBe('unknown')
  })

  it('切换会话或账号后迟到结果不串入当前视图', async () => {
    const pending = deferred<MealActionView>()
    const { state, api, owner } = harness({ selectOption: vi.fn(() => pending.promise) })
    await state.activate('1')
    state.activeMeal.value = meal()
    const first = state.selectAndGuide('100')
    await state.activate('2')
    pending.resolve(actionView('SELECT', '00000000-0000-4000-8000-000000000001',
      meal({ status: 'SELECTED', selectedOptionId: '100' })))
    await first
    expect(state.activeConversationId.value).toBe('2')
    expect(state.activeMeal.value).toBeNull()
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(state.pending.value[0].status).toBe('unknown')
    owner.value = 'user-b'
    expect(state.pending.value).toHaveLength(0)
    expect(state.activeMeal.value).toBeNull()
  })

  it('UNAVAILABLE响应不向UI保留餐名、材料或指导', async () => {
    const hidden = meal({ factsStatus: 'UNAVAILABLE', options: [], selectedOptionId: null })
    const { state, api } = harness({ getMeal: vi.fn(async () => hidden) })
    await state.activate('1')
    await state.showMeal('30')
    expect(state.activeMeal.value?.options).toEqual([])
    expect(state.guide.value).toBeNull()
    expect(api.generateGuide).not.toHaveBeenCalled()
  })

  it('档案读取失败不猜人数；显式人数随本餐提交，修订携带当前版本', async () => {
    const { state, api } = harness({}, {}, {
      getProfile: vi.fn(async () => { throw new ApiError(0, 'REQUEST_TIMEOUT', '') }), saveProfile: vi.fn(),
    })
    await state.activate('1')
    expect(state.profileCount.value).toBeNull()
    expect(state.profileState.value).toBe('error')
    state.draft.value = '四人晚餐'
    state.diningCountInput.value = '4'
    await state.createMeal()
    expect(api.createMeal).toHaveBeenCalledWith('1', expect.objectContaining({ diningCount: 4 }), expect.any(AbortSignal))

    state.activeMeal.value = meal({ version: 7 })
    state.draft.value = '改成少油'
    state.diningCountInput.value = ''
    await state.reviseMeal()
    expect(api.reviseMeal).toHaveBeenCalledWith('1', '30', expect.objectContaining({ expectedVersion: 7 }), expect.any(AbortSignal))
    expect(state.activeMeal.value?.version).toBe(8)
    expect(state.pending.value).toEqual([])
  })

  it('SELECT失败或事实已变化都不请求GUIDE', async () => {
    const failed = actionView('SELECT', '00000000-0000-4000-8000-000000000001', meal(), 'FAILED')
    const first = harness({ selectOption: vi.fn(async () => failed) })
    await first.state.activate('1')
    first.state.activeMeal.value = meal()
    await first.state.selectAndGuide('100')
    expect(first.api.generateGuide).not.toHaveBeenCalled()

    const changed = meal({ factsStatus: 'CHANGED' })
    const second = harness({ getMeal: vi.fn(async () => changed) })
    await second.state.activate('1')
    second.state.activeMeal.value = meal()
    await second.state.selectAndGuide('100')
    expect(second.api.generateGuide).not.toHaveBeenCalled()
    expect(second.state.operationError.value).toContain('未继续请求生成指导')
  })

  it('新会话创建未知时不自动重建；确认后仍复用原标题请求', async () => {
    const create = vi.fn()
      .mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', ''))
      .mockResolvedValueOnce(conversation('2'))
    const { state, conversations } = harness({}, { create })
    state.conversationTitle.value = '周末晚餐'
    await state.createConversation()
    expect(state.createUnknown.value).toBe(true)
    expect(create).toHaveBeenCalledTimes(1)
    expect(await state.createConversation()).toBe(false)
    expect(create).toHaveBeenCalledTimes(1)
    expect(await state.createConversation(true)).toBe(true)
    expect(create.mock.calls.map(call => call[0])).toEqual(['周末晚餐', '周末晚餐'])
    expect(conversations.list).toHaveBeenCalled()
  })
})
