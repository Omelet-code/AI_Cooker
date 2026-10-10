import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { KitchenApi, MealView } from '@/modules/conversation/kitchen/types'
import { createKitchenMaterialsState } from '@/modules/conversation/kitchen/materialsState'
import { meal } from './kitchen-fixtures'

let owner: string | null
let getMeal: ReturnType<typeof vi.fn>
function api() {
  return { getMeal } as unknown as Pick<KitchenApi, 'getMeal'>
}
const context = (overrides: Partial<{ conversationId: unknown; mealId: unknown; version: unknown; optionId: unknown }> = {}) => ({
  conversationId: '1', mealId: '30', version: '1', optionId: '100', ...overrides,
})

beforeEach(() => {
  owner = 'account-a'
  getMeal = vi.fn(async () => meal())
})

describe('材料详情只读状态', () => {
  it('按原会话和餐单只读取一次，并核对版本与方案后才发布材料', async () => {
    const state = createKitchenMaterialsState(() => owner, api())
    expect(await state.load(context())).toBe(true)
    expect(getMeal).toHaveBeenCalledTimes(1)
    expect(getMeal).toHaveBeenCalledWith('1', '30', expect.any(AbortSignal))
    expect(state.status.value).toBe('ready')
    expect(state.snapshot.value?.option.id).toBe('100')
  })

  it('旧版本或不属于餐单的方案不给出替代材料，UNAVAILABLE不发布快照', async () => {
    const state = createKitchenMaterialsState(() => owner, api())
    getMeal.mockResolvedValueOnce(meal({ version: 2 }))
    expect(await state.load(context())).toBe(false)
    expect(state.snapshot.value).toBeNull()
    expect(state.error.value).toContain('版本已变化')

    getMeal.mockResolvedValueOnce(meal({ options: [] }))
    expect(await state.load(context())).toBe(false)
    expect(state.snapshot.value).toBeNull()
    expect(state.error.value).toContain('方案不属于')

    getMeal.mockResolvedValueOnce(meal({ factsStatus: 'UNAVAILABLE', options: [], selectedOptionId: null, guide: null, guideAvailable: false }))
    expect(await state.load(context())).toBe(false)
    expect(state.status.value).toBe('unavailable')
    expect(state.snapshot.value).toBeNull()
  })

  it('CHANGED但仍可读时仅保留同版本历史快照并标明状态', async () => {
    const state = createKitchenMaterialsState(() => owner, api())
    getMeal.mockResolvedValueOnce(meal({ factsStatus: 'CHANGED' }))
    expect(await state.load(context())).toBe(true)
    expect(state.status.value).toBe('changed')
    expect(state.snapshot.value?.meal.factsStatus).toBe('CHANGED')
  })

  it('账号改变后忽略迟到GET，即使旧请求忽略AbortSignal', async () => {
    const state = createKitchenMaterialsState(() => owner, api())
    let rejectOld!: (reason: Error) => void
    let resolveCurrent!: (value: MealView) => void
    getMeal.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectOld = reject }))
    getMeal.mockImplementationOnce(() => new Promise(resolve => { resolveCurrent = resolve }))
    const oldRead = state.load(context())
    owner = 'account-b'
    const currentRead = state.load(context())
    rejectOld(new Error('旧账号迟到错误'))
    await oldRead
    expect(state.snapshot.value).toBeNull()
    expect(state.status.value).toBe('loading')
    expect(state.error.value).toBe('')
    resolveCurrent(meal())
    expect(await currentRead).toBe(true)
    expect(state.snapshot.value?.meal.id).toBe('30')
  })

  it('同账号切换餐单后迟到的旧GET不能覆盖新餐', async () => {
    const state = createKitchenMaterialsState(() => owner, api())
    let resolveOld!: (value: MealView) => void
    let resolveCurrent!: (value: MealView) => void
    getMeal.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    getMeal.mockImplementationOnce(() => new Promise(resolve => { resolveCurrent = resolve }))
    const oldRead = state.load(context())
    const currentRead = state.load(context({ mealId: '31', optionId: '101' }))
    resolveOld(meal())
    await oldRead
    expect(state.snapshot.value).toBeNull()
    resolveCurrent(meal({ id: '31', options: [meal().options[1]] }))
    expect(await currentRead).toBe(true)
    expect(state.snapshot.value?.meal.id).toBe('31')
  })

  it('拒绝格式不合法的深链参数，不调用API', async () => {
    const state = createKitchenMaterialsState(() => owner, api())
    expect(await state.load(context({ version: '1.5' }))).toBe(false)
    expect(getMeal).not.toHaveBeenCalled()
    expect(state.error.value).toContain('链接参数')
  })
})
