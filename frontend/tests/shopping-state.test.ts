import { computed, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/types'
import { createShoppingState } from '@/modules/shopping/state'
import { list, match, preview, source } from './shopping-fixtures'

const owner = ref<string | null>('account-a')
function harness(overrides: Record<string, unknown> = {}) {
  const api = { preview: vi.fn(async () => preview), create: vi.fn(async () => list), getList: vi.fn(async () => list), latestMatch: vi.fn(async () => null), getMatch: vi.fn(), createMatch: vi.fn(), ...overrides }
  const state = createShoppingState({ ownerId: computed(() => owner.value), api: api as never })
  return { state, api }
}
function activate(state: ReturnType<typeof createShoppingState>) { state.activate({ kind: 'new', ownerId: owner.value, source }) }
const tick = () => new Promise(resolve => setTimeout(resolve, 0))

beforeEach(() => { owner.value = 'account-a'; vi.stubGlobal('window', { confirm: () => true }) })
describe('F3-D 采购操作生命周期', () => {
  it('第二段报价读取只在原清单仍为当前上下文时发布', async () => {
    let resolveOld!: (value: typeof match) => void
    const oldQuote = new Promise<typeof match>(resolve => { resolveOld = resolve })
    const { state } = harness({
      getList: vi.fn(async (id: string) => ({ ...list, id })),
      latestMatch: vi.fn((id: string) => id === '55' ? oldQuote : Promise.resolve({ ...match, id: '77', listId: '56' })),
    })
    state.activate({ kind: 'list', ownerId: owner.value, listId: '55', matchId: null })
    const olderRead = state.loadList('55')
    await tick()
    state.activate({ kind: 'list', ownerId: owner.value, listId: '56', matchId: null })
    await state.loadList('56')
    resolveOld({ ...match, listId: '55' })
    await olderRead
    expect(state.list.value?.id).toBe('56')
    expect(state.match.value?.listId).toBe('56')
  })

  it('账号改变后第二段迟到报价不能复活旧账号数据', async () => {
    let resolveOld!: (value: typeof match) => void
    const oldQuote = new Promise<typeof match>(resolve => { resolveOld = resolve })
    const { state } = harness({ latestMatch: vi.fn(() => oldQuote) })
    state.activate({ kind: 'list', ownerId: owner.value, listId: '55', matchId: null })
    const olderRead = state.loadList('55')
    await tick()
    owner.value = 'account-b'
    state.activate({ kind: 'home', ownerId: owner.value })
    resolveOld(match)
    await olderRead
    expect(state.list.value).toBeNull()
    expect(state.match.value).toBeNull()
    expect(state.scope.value?.ownerId).toBe('account-b')
  })

  it('同一上下文新读取开始后，旧取消的catch和finally不清除新busy', async () => {
    let resolveSecond!: (value: typeof preview) => void
    const second = new Promise<typeof preview>(resolve => { resolveSecond = resolve })
    const apiPreview = vi.fn()
      .mockImplementationOnce((_source: unknown, signal: AbortSignal) => new Promise<typeof preview>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new ApiError(0, 'REQUEST_ABORTED', '旧读取取消')), { once: true })
      }))
      .mockImplementationOnce(() => second)
    const { state } = harness({ preview: apiPreview })
    activate(state)
    const olderRead = state.loadPreview(source)
    const newerRead = state.loadPreview(source)
    await olderRead
    expect(state.busy.value).toBe(true)
    resolveSecond(preview)
    await newerRead
    expect(state.busy.value).toBe(false)
  })

  it('同账号切换页面后旧写失败保留原键但不污染新页面错误', async () => {
    let rejectWrite!: (reason: unknown) => void
    const delayed = new Promise<typeof list>((_resolve, reject) => { rejectWrite = reject })
    const { state } = harness({ create: vi.fn(() => delayed) })
    activate(state); await state.loadPreview(source)
    const writing = state.createList()
    const requestId = state.pendingOperation.value!.payload.requestId
    state.activate({ kind: 'home', ownerId: owner.value })
    rejectWrite(new ApiError(0, 'REQUEST_TIMEOUT', '旧页面等待结束'))
    await writing
    expect(state.pendingOperation.value?.payload.requestId).toBe(requestId)
    expect(state.error.value).toBe('')
    expect(state.busy.value).toBe(false)
  })

  it('离开来源上下文后迟到的建单成功不导航也不覆盖当前页', async () => {
    let resolveCreate!: (value: typeof list) => void
    const delayed = new Promise<typeof list>(resolve => { resolveCreate = resolve })
    const { state } = harness({ create: vi.fn(() => delayed) })
    activate(state); await state.loadPreview(source)
    const writing = state.createList()
    const operation = state.pendingOperation.value
    state.activate({ kind: 'home', ownerId: owner.value })
    resolveCreate(list); await writing
    expect(state.redirectToListId.value).toBeNull()
    expect(state.list.value).toBeNull()
    expect(state.pendingOperation.value).toBe(operation)
    expect(state.error.value).toBe('')
  })

  it('用户停止本地等待时保留未知原键，迟到成功也不再发布', async () => {
    let resolveMatch!: (value: typeof match) => void
    let requestSignal!: AbortSignal
    const delayed = new Promise<typeof match>(resolve => { resolveMatch = resolve })
    const { state, api } = harness({ getMatch: vi.fn(async () => match), createMatch: vi.fn((_id: string, _payload: unknown, signal: AbortSignal) => { requestSignal = signal; return delayed }) })
    state.activate({ kind: 'list', ownerId: owner.value, listId: '55', matchId: '66' }); await state.loadList('55', '66')
    const savedMatch = state.match.value
    const writing = state.createMatch()
    const operation = state.pendingOperation.value!
    expect(state.writeWaiting.value).toBe(true)
    state.stopWaiting()
    expect(requestSignal.aborted).toBe(true)
    expect(state.writeWaiting.value).toBe(false)
    expect(state.busy.value).toBe(false)
    expect(state.pendingOperation.value).toBe(operation)
    expect(state.notice.value).toContain('服务器可能仍在处理')
    resolveMatch(match); await writing
    expect(state.pendingOperation.value).toBe(operation)
    expect(state.match.value).toBe(savedMatch)
    expect(api.createMatch).toHaveBeenCalledOnce()
  })

  it('拒绝显式核对确认时不新增采购POST', async () => {
    const createMatch = vi.fn().mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '未知'))
    const { state } = harness({ createMatch })
    state.activate({ kind: 'list', ownerId: owner.value, listId: '55', matchId: null }); await state.loadList('55')
    await state.createMatch()
    vi.stubGlobal('window', { confirm: () => false })
    await state.retryPending()
    expect(createMatch).toHaveBeenCalledOnce()
  })

  it('逐项显式确认后成功清单先结束busy再产生路由目标', async () => {
    const { state, api } = harness(); activate(state); await state.loadPreview(source)
    expect(state.canCreate()).toBe(true)
    const id = await state.createList()
    expect(id).toBe('55'); expect(state.busy.value).toBe(false); expect(state.pendingOperation.value).toBeNull()
    expect(state.redirectToListId.value).toBe('55'); expect(api.create).toHaveBeenCalledOnce()
  })
  it('超时保留不可变原键和payload，只有显式确认才同键重放', async () => {
    const create = vi.fn().mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '未知')).mockResolvedValueOnce(list)
    const { state } = harness({ create }); activate(state); await state.loadPreview(source); await state.createList()
    const original = state.pendingOperation.value!
    expect(original.kind).toBe('CREATE_LIST'); expect(state.busy.value).toBe(false)
    expect(Object.keys(original.originalRoute.query).sort()).toEqual(['conversationId', 'expectedVersion', 'mealId', 'optionId'])
    await state.retryPending()
    expect(create).toHaveBeenCalledTimes(2); expect(create.mock.calls[0]?.[0]).toBe(create.mock.calls[1]?.[0])
    expect(state.pendingOperation.value).toBeNull()
  })
  it('取消重放不产生新请求，A→B→A不接收旧账号响应', async () => {
    let complete!: (value: typeof preview) => void
    const delayed = new Promise<typeof preview>(resolve => { complete = resolve })
    const { state, api } = harness({ preview: vi.fn(() => delayed) }); activate(state)
    const loading = state.loadPreview(source); owner.value = 'account-b'; state.activate({ kind: 'new', ownerId: owner.value, source }); owner.value = 'account-a'; state.activate({ kind: 'new', ownerId: owner.value, source })
    complete(preview); await loading; await tick()
    expect(state.preview.value).toBeNull(); expect(api.create).not.toHaveBeenCalled()
    state.activate({ kind: 'new', ownerId: owner.value, source }); await state.loadPreview(source)
    state.confirmationChoices.value[preview.lines[0]!.lineKey] = null
    expect(state.confirmationPayload()).toBeNull()
  })
  it('跨来源/清单迟到读取不得覆盖当前页面', async () => {
    let complete!: (value: typeof preview) => void
    const delayed = new Promise<typeof preview>(resolve => { complete = resolve })
    const { state } = harness({ preview: vi.fn(() => delayed) }); activate(state)
    const loading = state.loadPreview(source); state.activate({ kind: 'list', ownerId: owner.value, listId: '55', matchId: null }); complete(preview); await loading
    expect(state.preview.value).toBeNull(); expect(state.scope.value?.kind).toBe('list')
  })
  it('A→B→A期间迟到写响应不清除新状态、不导航或复活旧未知键', async () => {
    let fail!: (reason: unknown) => void
    const delayed = new Promise<typeof list>((_resolve, reject) => { fail = reject })
    const { state } = harness({ create: vi.fn(() => delayed) }); activate(state); await state.loadPreview(source)
    const original = state.createList()
    owner.value = 'account-b'; state.activate({ kind: 'new', ownerId: owner.value, source })
    owner.value = 'account-a'; state.activate({ kind: 'new', ownerId: owner.value, source }); await state.loadPreview(source)
    fail(new ApiError(0, 'REQUEST_TIMEOUT', '旧账号请求迟到'))
    expect(await original).toBeNull(); expect(state.pendingOperation.value).toBeNull(); expect(state.redirectToListId.value).toBeNull()
  })
  it('清单匹配超时后城市变更不改旧payload，核对重放只用原城市与原UUID', async () => {
    const createMatch = vi.fn().mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '未知')).mockResolvedValueOnce({ ...list, id: '55' })
    const { state } = harness({ createMatch }); state.activate({ kind: 'list', ownerId: owner.value, listId: '55' }); await state.loadList('55')
    state.cityCode.value = '310000'; await state.createMatch()
    const original = state.pendingOperation.value!
    state.cityCode.value = '440100'; await state.retryPending()
    expect(createMatch).toHaveBeenCalledTimes(2); expect(createMatch.mock.calls[0]?.[1]).toBe(createMatch.mock.calls[1]?.[1])
    expect((original.payload as { cityCode: string }).cityCode).toBe('310000'); expect(state.pendingOperation.value).toBeNull()
  })
  it('来源事实变化不能发新报价，已有未知成本不会自动换算', async () => {
    const { state, api } = harness(); state.activate({ kind: 'list', ownerId: owner.value, listId: '55' }); await state.loadList('55')
    state.list.value!.factsStatus = 'CHANGED'
    expect(await state.createMatch()).toBeNull(); expect(api.createMatch).not.toHaveBeenCalled()
  })
})
