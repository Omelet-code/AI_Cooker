import { computed, ref, type Ref } from 'vue'
import { ApiError } from '@/shared/api/types'
import { shoppingApi } from './api'
import type { Availability, Confirmation, PendingShoppingOperation, ProductMatchView, ShoppingCreateRequest, ShoppingListView, ShoppingPreview, ShoppingSourceRoute } from './types'
import { allowedUnits, isDecimalInput } from './validation'

type Api = typeof shoppingApi
type Scope = { kind: 'home' | 'new' | 'list'; ownerId: string | null; source?: ShoppingSourceRoute; listId?: string; matchId?: string | null }
function key(scope: Scope): string { return `${scope.kind}|${scope.ownerId ?? ''}|${scope.source ? `${scope.source.conversationId}/${scope.source.mealId}/${scope.source.expectedVersion}/${scope.source.optionId}` : ''}|${scope.listId ?? ''}|${scope.matchId ?? ''}` }
export function createShoppingState(deps: { ownerId: Ref<string | null>; api?: Api }) {
  const api = deps.api ?? shoppingApi
  const scopeKey = ref(''), scope = ref<Scope | null>(null), preview = ref<ShoppingPreview | null>(null), list = ref<ShoppingListView | null>(null), match = ref<ProductMatchView | null>(null)
  const busy = ref(false), writeWaiting = ref(false), error = ref(''), notice = ref(''), pendingOperation = ref<PendingShoppingOperation | null>(null)
  const confirmationChoices = ref<Record<string, Availability | null>>({}), amountDrafts = ref<Record<string, string>>({}), unitDrafts = ref<Record<string, string>>({})
  const cityCode = ref('110000'), lastCreatedListId = ref<string | null>(null), redirectToListId = ref<string | null>(null)
  let generation = 0, ownerGeneration = 0, readGeneration = 0, writeGeneration = 0, readController: AbortController | null = null, writeController: AbortController | null = null, lastOwner = deps.ownerId.value
  const anyPending = computed(() => pendingOperation.value !== null)
  const knownScope = computed(() => !!scope.value && scope.value.ownerId === deps.ownerId.value)

  function activate(next: Scope): void {
    if (next.ownerId !== lastOwner) {
      ownerGeneration++; generation++; readGeneration++; writeGeneration++; readController?.abort(); writeController?.abort(); pendingOperation.value = null; preview.value = null; list.value = null; match.value = null
      lastCreatedListId.value = null; redirectToListId.value = null; busy.value = false; error.value = ''; notice.value = ''; lastOwner = next.ownerId
      writeWaiting.value = false
    }
    const nextKey = key(next)
    if (nextKey === scopeKey.value) return
    generation++; readGeneration++; writeGeneration++; readController?.abort(); writeController?.abort(); readController = null; busy.value = false; writeWaiting.value = false; scopeKey.value = nextKey; scope.value = next
    preview.value = null; list.value = null; match.value = null; confirmationChoices.value = {}; amountDrafts.value = {}; unitDrafts.value = {}
    cityCode.value = '110000'; lastCreatedListId.value = null; redirectToListId.value = null; error.value = ''; notice.value = ''
    if (pendingOperation.value && (pendingOperation.value.ownerId !== next.ownerId || pendingOperation.value.contextKey !== nextKey)) notice.value = '有一项采购操作结果尚未确认。请返回原清单或原来源上下文核对，当前页面不会重放。'
  }
  function isCurrent(version: number, target: string, owner: string | null): boolean { return generation === version && scopeKey.value === target && deps.ownerId.value === owner }
  function isReadCurrent(readVersion: number, version: number, target: string, owner: string | null): boolean {
    return readGeneration === readVersion && isCurrent(version, target, owner)
  }
  function isWriteCurrent(writeVersion: number, version: number, target: string, owner: string | null): boolean {
    return writeGeneration === writeVersion && isCurrent(version, target, owner)
  }
  function setFailure(reason: unknown, operation?: PendingShoppingOperation): void {
    const status = reason instanceof ApiError ? reason.status : 0
    if (operation && (status === 0 || status >= 500 || !(reason instanceof ApiError) || reason.code === 'INVALID_RESPONSE' || reason.code === 'REQUEST_ABORTED' || reason.code === 'REQUEST_TIMEOUT')) {
      pendingOperation.value = operation
      error.value = '服务器结果尚未确认，未自动重试。请在原页面显式核对同一操作。'
    } else {
      if (operation && pendingOperation.value?.payload.requestId === operation.payload.requestId) pendingOperation.value = null
      error.value = reason instanceof ApiError && status === 409 ? '来源版本或清单状态已变化，请重新读取后再操作。' : '采购请求未完成，请检查输入或稍后重试。'
    }
  }
  async function loadPreview(source: ShoppingSourceRoute): Promise<void> {
    const owner = deps.ownerId.value, target = scopeKey.value
    if (scope.value?.kind !== 'new' || !owner || !knownScope.value) return
    readGeneration++; const readVersion = readGeneration; readController?.abort(); const controller = new AbortController(); readController = controller; const version = generation
    busy.value = true; error.value = ''; preview.value = null
    try {
      const result = await api.preview(source, controller.signal)
      if (isReadCurrent(readVersion, version, target, owner)) { preview.value = result; confirmationChoices.value = Object.fromEntries(result.lines.map(line => [line.lineKey, line.availability])) }
    } catch (reason) { if (isReadCurrent(readVersion, version, target, owner)) error.value = reason instanceof ApiError && reason.status === 404 ? '餐单或方案已不可访问。' : '无法读取服务器原料预览，请重试。' }
    finally { if (isReadCurrent(readVersion, version, target, owner)) busy.value = false }
  }
  async function loadList(listId: string, matchId?: string | null): Promise<void> {
    const owner = deps.ownerId.value, target = scopeKey.value
    if (scope.value?.kind !== 'list' || !owner || !knownScope.value) return
    readGeneration++; const readVersion = readGeneration; readController?.abort(); const controller = new AbortController(); readController = controller; const version = generation
    busy.value = true; error.value = ''; list.value = null; match.value = null
    try {
      const saved = await api.getList(listId, controller.signal)
      if (!isReadCurrent(readVersion, version, target, owner)) return
      const savedMatch = matchId ? await api.getMatch(listId, matchId, controller.signal) : await api.latestMatch(listId, controller.signal)
      if (!isReadCurrent(readVersion, version, target, owner)) return
      list.value = saved
      match.value = savedMatch
      if (match.value && match.value.sourceFingerprint !== saved.source.sourceFingerprint) { match.value = null; error.value = '报价快照与清单来源不一致，已隐藏该报价。' }
    } catch (reason) { if (isReadCurrent(readVersion, version, target, owner)) { list.value = null; match.value = null; error.value = reason instanceof ApiError && reason.status === 404 ? '清单或报价不存在，或当前账号无权查看。' : '无法读取服务器清单或报价快照。' } }
    finally { if (isReadCurrent(readVersion, version, target, owner)) busy.value = false }
  }
  function confirmationPayload(): Confirmation[] | null {
    if (!preview.value) return null
    const rows: Confirmation[] = []
    for (const line of preview.value.lines) {
      const availability = confirmationChoices.value[line.lineKey]
      if (availability !== 'HAVE_ENOUGH' && availability !== 'NEED_BUY') return null
      const needsAmount = availability === 'NEED_BUY' && (line.needsConfirmation || line.referenceAmount === null)
      const entered = amountDrafts.value[line.lineKey]?.trim() ?? ''
      const unit = unitDrafts.value[line.lineKey] ?? ''
      if (needsAmount && entered && (!isDecimalInput(entered) || !allowedUnits(line).includes(unit))) return null
      if (needsAmount && !entered && unit) return null
      rows.push({ lineKey: line.lineKey, availability, confirmedAmount: needsAmount && entered ? entered : null, confirmedUnitCode: needsAmount && entered ? unit : null })
    }
    return rows
  }
  function canCreate(): boolean { return !!preview.value && preview.value.source.version === scope.value?.source?.expectedVersion && preview.value.source.optionId === scope.value.source?.optionId && confirmationPayload() !== null && !busy.value && !pendingOperation.value && preview.value.source !== undefined }
  async function submitOperation(operation: PendingShoppingOperation, version: number, target: string): Promise<string | null> {
    const ownerEpoch = ownerGeneration, writeVersion = ++writeGeneration
    busy.value = true; writeWaiting.value = true; error.value = ''; notice.value = ''; pendingOperation.value = operation
    const controller = new AbortController(); writeController = controller
    try {
      if (operation.kind === 'CREATE_LIST') {
        const saved = await api.create(operation.payload as ShoppingCreateRequest, controller.signal)
        const current = ownerGeneration === ownerEpoch && isWriteCurrent(writeVersion, version, target, operation.ownerId)
        if (current) {
          if (pendingOperation.value?.payload.requestId === operation.payload.requestId) pendingOperation.value = null
          // 先发布已知成功并释放忙碌态，避免路由 watch 把成功导航误判成取消。
          busy.value = false; writeWaiting.value = false; list.value = saved; lastCreatedListId.value = saved.id; redirectToListId.value = saved.id
        }
        return current ? saved.id : null
      }
      const saved = await api.createMatch(operation.listId!, operation.payload as never, controller.signal)
      const current = ownerGeneration === ownerEpoch && isWriteCurrent(writeVersion, version, target, operation.ownerId)
      if (current && pendingOperation.value?.payload.requestId === operation.payload.requestId) pendingOperation.value = null
      if (current) { busy.value = false; writeWaiting.value = false; match.value = saved; notice.value = saved.provider === 'MOCK' || !saved.realData ? '这是 Mock 模拟商品与价格，不代表真实库存、购买或支付。' : '' }
      return current ? saved.id : null
    } catch (reason) {
      if (ownerGeneration === ownerEpoch && isWriteCurrent(writeVersion, version, target, operation.ownerId)) { writeWaiting.value = false; setFailure(reason, operation) }
      return null
    } finally { if (ownerGeneration === ownerEpoch && isWriteCurrent(writeVersion, version, target, operation.ownerId)) busy.value = false; if (writeController === controller) writeController = null }
  }
  async function createList(): Promise<string | null> {
    if (!canCreate() || !scope.value?.source || !preview.value || !deps.ownerId.value) return null
    const source = scope.value.source
    const payload: ShoppingCreateRequest = Object.freeze({ requestId: crypto.randomUUID(), conversationId: source.conversationId, mealId: source.mealId, expectedVersion: source.expectedVersion, optionId: source.optionId, sourceFingerprint: preview.value.source.sourceFingerprint, confirmations: Object.freeze(confirmationPayload()!.map(row => Object.freeze(row))) })
    const operation: PendingShoppingOperation = {
      kind: 'CREATE_LIST', ownerId: deps.ownerId.value, contextKey: scopeKey.value, path: '/shopping-lists', payload, listId: null,
      originalRoute: { name: 'shopping-new', query: { conversationId: source.conversationId, mealId: source.mealId, expectedVersion: source.expectedVersion, optionId: source.optionId } },
      summary: '创建采购清单',
    }
    const version = generation, target = scopeKey.value; return submitOperation(operation, version, target)
  }
  async function createMatch(): Promise<string | null> {
    if (!list.value || list.value.factsStatus !== 'CURRENT' || busy.value || pendingOperation.value || !/^\d{6}$/.test(cityCode.value) || !deps.ownerId.value || scope.value?.kind !== 'list') return null
    const payload = Object.freeze({ requestId: crypto.randomUUID(), cityCode: cityCode.value })
    const originalRoute = scope.value.matchId
      ? { name: 'shopping-list' as const, params: { listId: list.value.id }, query: { matchId: scope.value.matchId } }
      : { name: 'shopping-list' as const, params: { listId: list.value.id } }
    const operation: PendingShoppingOperation = {
      kind: 'MATCH_PRODUCTS', ownerId: deps.ownerId.value, contextKey: scopeKey.value,
      path: `/shopping-lists/${list.value.id}/product-matches`, payload, listId: list.value.id, originalRoute,
      summary: '匹配模拟商品',
    }
    return submitOperation(operation, generation, scopeKey.value)
  }
  async function retryPending(): Promise<string | null> {
    const operation = pendingOperation.value
    if (!operation || operation.ownerId !== deps.ownerId.value || operation.contextKey !== scopeKey.value || busy.value) return null
    if (!window.confirm('将使用原编号、原请求内容再次核对；服务器可能继续处理本次操作。是否继续？')) return null
    return submitOperation(operation, generation, scopeKey.value)
  }
  function stopWaiting(): void {
    if (!writeWaiting.value || !pendingOperation.value) return
    writeGeneration++; writeController?.abort(); writeController = null
    writeWaiting.value = false; busy.value = false
    notice.value = '已停止本地等待。服务器可能仍在处理；原操作编号与内容已保留，可返回原上下文显式核对。'
  }
  function clearAccount(): void {
    ownerGeneration++; generation++; readGeneration++; writeGeneration++; readController?.abort(); writeController?.abort(); scope.value = null; scopeKey.value = ''; preview.value = null; list.value = null; match.value = null
    pendingOperation.value = null; busy.value = false; writeWaiting.value = false; error.value = ''; notice.value = ''; confirmationChoices.value = {}; amountDrafts.value = {}; unitDrafts.value = {}; lastCreatedListId.value = null; redirectToListId.value = null
  }
  return { scope, preview, list, match, busy, writeWaiting, error, notice, pendingOperation, confirmationChoices, amountDrafts, unitDrafts, cityCode, lastCreatedListId, redirectToListId, anyPending, knownScope, activate, loadPreview, loadList, confirmationPayload, canCreate, createList, createMatch, retryPending, stopWaiting, clearAccount }
}
