import { computed, effectScope, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createKitchenState } from '@/modules/conversation/kitchen/state'
import { parseGuideForMeal, parseMealAction, parseMealView } from '@/modules/conversation/kitchen/validation'
import { ApiError } from '@/shared/api/types'
import type { KitchenApi, MealActionView, MealGuideView, MealView } from '@/modules/conversation/kitchen/types'
import { actionView, fourDishMeal, guide, kitchenRequestId, meal } from './kitchen-fixtures'

// 将架构复现探针改成正确行为断言，进入常规测试，不把缺陷存在当作验收通过。
const scopes: ReturnType<typeof effectScope>[] = []
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
function harness(overrides: Partial<KitchenApi> = {}) {
  const scope = effectScope(); scopes.push(scope)
  const owner = ref<string | null>('1')
  let sequence = 0
  const selected = meal({ status: 'SELECTED', selectedOptionId: '100' })
  const api: KitchenApi = {
    listMeals: vi.fn(async () => ({ items: [], page: 1, size: 20, total: 0 })),
    getMeal: vi.fn(async (_cid, id) => id === '30' ? selected : meal({ id })),
    createMeal: vi.fn(async (_cid, input) => actionView('CREATE', input.requestId, meal())),
    reviseMeal: vi.fn(async (_cid, id, input) => actionView('REVISE', input.requestId, meal({ id, version: input.expectedVersion + 1 }))),
    selectOption: vi.fn(async (_cid, _id, input) => actionView('SELECT', input.requestId, selected)),
    getAction: vi.fn(), generateGuide: vi.fn(async () => { throw new ApiError(0, 'REQUEST_TIMEOUT', '') }),
    getGuide: vi.fn(async () => guide()), ...overrides,
  }
  const state = scope.run(() => createKitchenState({
    ownerId: computed(() => owner.value), meals: api,
    conversations: {
      list: async () => ({ items: [], page: 1, size: 20, total: 0 }),
      detail: async id => ({ id, title: '隔离回归', createdAt: '2026-10-04', updatedAt: '2026-10-04' }), create: vi.fn(),
    },
    profile: { getProfile: async () => ({ defaultDiningCount: 3 }), saveProfile: vi.fn() },
    uuid: () => '00000000-0000-4000-8000-' + String(++sequence).padStart(12, '0'),
  }))!
  return { state, api, owner }
}
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.unstubAllGlobals() })

describe('UF2 R1～R3 正确行为持续回归', () => {
  it('R1：合法修订7→8展示新版本并关闭pending', async () => {
    const { state } = harness()
    await state.activate('1'); state.activeMeal.value = meal({ version: 7 })
    state.draft.value = '少油一些'; await state.reviseMeal()
    expect(state.activeMeal.value?.version).toBe(8)
    expect(state.pending.value).toEqual([])
  })

  it('R1：旧修订重放不得覆盖当前已知更高版本', async () => {
    const delayed = deferred<MealActionView>()
    const { state } = harness({ reviseMeal: vi.fn(() => delayed.promise) })
    await state.activate('1'); state.activeMeal.value = meal({ version: 7 }); state.draft.value = '少油'
    const operation = state.reviseMeal()
    state.activeMeal.value = meal({ version: 9 })
    delayed.resolve(actionView('REVISE', kitchenRequestId, meal({ version: 8 })))
    await operation
    expect(state.activeMeal.value?.version).toBe(9)
    expect(state.pending.value).toEqual([])
  })

  it('R2：同会话换餐后迟到SELECT不覆盖新餐，也不追加GUIDE', async () => {
    const delayed = deferred<MealActionView>()
    const { state, api } = harness({ selectOption: vi.fn(() => delayed.promise) })
    await state.activate('1'); state.activeMeal.value = meal()
    const operation = state.selectAndGuide('100')
    await state.showMeal('31')
    delayed.resolve(actionView('SELECT', kitchenRequestId, meal({ status: 'SELECTED', selectedOptionId: '100' })))
    await operation
    expect(state.activeMeal.value?.id).toBe('31')
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(state.pending.value[0].status).toBe('unknown')
    expect(vi.mocked(api.selectOption).mock.calls[0][3]?.aborted).toBe(true)
  })

  it('R2：开始新餐需确认，确认后迟到SELECT保持新餐输入状态', async () => {
    const delayed = deferred<MealActionView>()
    const confirm = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('window', { confirm })
    const { state, api } = harness({ selectOption: vi.fn(() => delayed.promise) })
    await state.activate('1'); state.activeMeal.value = meal()
    const operation = state.selectAndGuide('100')
    state.startNewMeal()
    expect(state.activeMeal.value?.id).toBe('30')
    state.startNewMeal()
    delayed.resolve(actionView('SELECT', kitchenRequestId, meal({ status: 'SELECTED', selectedOptionId: '100' })))
    await operation
    expect(state.activeMeal.value).toBeNull()
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(confirm).toHaveBeenCalledTimes(2)
  })

  it('R2：迟到餐单GET和指导GET不能覆盖后来选中的餐', async () => {
    const delayedMeal = deferred<MealView>()
    const delayedGuide = deferred<MealGuideView>()
    const saved = meal({ status: 'SELECTED', selectedOptionId: '100', guideAvailable: true,
      guide: { id: '700', schemaVersion: 1, optionId: '100', version: 1 } })
    let mode: 'meal' | 'guide' = 'meal'
    const { state } = harness({
      getMeal: vi.fn(async (_cid, id) => id === '30' ? mode === 'meal' ? delayedMeal.promise : saved : meal({ id })),
      getGuide: vi.fn(() => delayedGuide.promise),
    })
    await state.activate('1')
    const read = state.showMeal('30'); await state.showMeal('31')
    delayedMeal.resolve(saved); await read
    expect(state.activeMeal.value?.id).toBe('31')
    mode = 'guide'
    const guideRead = state.showMeal('30')
    await vi.waitFor(() => expect(state.guideState.value).toBe('loading'))
    await state.showMeal('32'); delayedGuide.resolve(guide(saved)); await guideRead
    expect(state.activeMeal.value?.id).toBe('32')
    expect(state.guide.value).toBeNull()
  })

  it('R2：迟到GUIDE不覆盖另一餐', async () => {
    const delayed = deferred<MealActionView>()
    const { state } = harness({ generateGuide: vi.fn(() => delayed.promise) })
    await state.activate('1'); state.activeMeal.value = meal()
    const operation = state.selectAndGuide('100')
    await vi.waitFor(() => expect(state.pending.value[0]?.kind).toBe('GUIDE'))
    await state.showMeal('31')
    delayed.resolve(actionView('GUIDE', state.pending.value[0].requestId,
      meal({ status: 'SELECTED', selectedOptionId: '100' })))
    await operation
    expect(state.activeMeal.value?.id).toBe('31')
    expect(state.guide.value).toBeNull()
  })

  it('R2：新版本相同选项不能接续原版本点击链生成GUIDE', async () => {
    const { state, api } = harness({ getMeal: vi.fn(async () => meal({ version: 2, status: 'SELECTED', selectedOptionId: '100' })) })
    await state.activate('1'); state.activeMeal.value = meal()
    await state.selectAndGuide('100')
    expect(api.generateGuide).not.toHaveBeenCalled()
    expect(state.activeMeal.value?.version).toBe(2)
  })

  it('R2：另一会话/另一餐不能核对POST；返回原上下文后原键重放', async () => {
    vi.stubGlobal('window', { confirm: () => true })
    const { state, api } = harness({ createMeal: vi.fn(async () => { throw new ApiError(0, 'REQUEST_TIMEOUT', '') }) })
    await state.activate('1'); state.draft.value = '安排本餐'; await state.createMeal()
    const original = state.pending.value[0], payload = original.payload
    await state.activate('2'); expect(await state.retryPending(original.key)).toBe(false)
    await state.activate('1'); await state.showMeal('31')
    expect(await state.retryPending(original.key)).toBe(false)
    expect(api.createMeal).toHaveBeenCalledTimes(1)
    state.startNewMeal(); await state.retryPending(original.key)
    expect(api.createMeal).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.createMeal).mock.calls[1][1]).toEqual(payload)
  })

  it('R2：未知SELECT或GUIDE未核对前不能无提示另起新键', async () => {
    const first = harness({ selectOption: vi.fn(async () => { throw new ApiError(0, 'REQUEST_TIMEOUT', '') }) })
    await first.state.activate('1'); first.state.activeMeal.value = meal()
    await first.state.selectAndGuide('100'); await first.state.selectAndGuide('101')
    expect(first.api.selectOption).toHaveBeenCalledTimes(1)
    const second = harness()
    await second.state.activate('1'); second.state.activeMeal.value = meal()
    await second.state.selectAndGuide('100'); await second.state.regenerateGuide()
    expect(second.api.generateGuide).toHaveBeenCalledTimes(1)
  })

  it('R3：合法null指标的FAILED正常结束，不伪造0；负数/字符串拒绝', async () => {
    const failed = actionView('CREATE', kitchenRequestId, meal({ status: 'FAILED', options: [] }), 'FAILED')
    Object.assign(failed.action.metrics, { semanticSearchCalls: null, queryEmbeddingRequests: null })
    expect(parseMealAction(failed, '1').action.metrics.semanticSearchCalls).toBeNull()
    for (const value of [-1, '0']) {
      const invalid = structuredClone(failed)
      Object.assign(invalid.action.metrics, { semanticSearchCalls: value })
      expect(() => parseMealAction(invalid, '1')).toThrowError(ApiError)
    }
    const { state } = harness({ createMeal: vi.fn(async () => parseMealAction(failed, '1')) })
    await state.activate('1'); state.draft.value = '安排本餐'; await state.createMeal()
    expect(state.activeMeal.value?.status).toBe('FAILED')
    expect(state.pending.value).toEqual([])
  })

  it('R2：指导前的GET迟到不接续失效链；原键核对SELECT不启动GUIDE', async () => {
    const read = deferred<MealView>()
    const selected = meal({ status: 'SELECTED', selectedOptionId: '100' })
    const first = harness({ getMeal: vi.fn(async (_cid, id) => id === '30' ? read.promise : meal({ id })) })
    await first.state.activate('1'); first.state.activeMeal.value = meal()
    const operation = first.state.selectAndGuide('100')
    await vi.waitFor(() => expect(first.api.getMeal).toHaveBeenCalled())
    await first.state.showMeal('31'); read.resolve(selected); await operation
    expect(first.state.activeMeal.value?.id).toBe('31')
    expect(first.api.generateGuide).not.toHaveBeenCalled()

    const select = vi.fn().mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', ''))
      .mockImplementationOnce(async (_cid, _id, input) => actionView('SELECT', input.requestId, selected))
    const second = harness({ selectOption: select })
    await second.state.activate('1'); second.state.activeMeal.value = meal()
    await second.state.selectAndGuide('100')
    const original = second.state.pending.value[0]
    await second.state.retryPending(original.key)
    expect(select.mock.calls[1][2]).toEqual(original.payload)
    expect(second.api.generateGuide).not.toHaveBeenCalled()
    expect(second.state.pending.value).toEqual([])
  })

  it('持续解析与回看四菜一汤的两套完整事实，参考小数保持字符串', () => {
    const value = parseMealView(fourDishMeal(), '1', '30')
    expect(value.options).toHaveLength(2)
    for (const option of value.options) {
      expect(option.dishes).toHaveLength(4)
      expect(option.soups).toHaveLength(1)
      expect(option.materials.recipes).toHaveLength(5)
      expect(option.materials.recipes[0].materials[0].referenceAmount).toBe('3.000')
    }
    value.status = 'SELECTED'; value.selectedOptionId = '100'
    value.guide = { id: '700', schemaVersion: 1, optionId: '100', version: 1 }; value.guideAvailable = true
    const saved = parseGuideForMeal(guide(value), value)
    expect(saved.recipes).toHaveLength(5)
    expect(saved.content?.recipes).toHaveLength(5)
  })
})
