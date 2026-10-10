import { computed, onScopeDispose, ref, watch, type ComputedRef } from 'vue'
import { ApiError } from '@/shared/api/types'
import { registerSessionCleanup } from '@/shared/auth/sessionCleanup'
import { conversationApi } from '../api'
import type { ConversationApi, ConversationView, Page } from '../types'
import { diningProfileApi } from '@/modules/identity/diningProfile/api'
import { parseDiningProfile } from '@/modules/identity/diningProfile/validation'
import type { DiningProfileApi } from '@/modules/identity/diningProfile/types'
import { kitchenApi } from './api'
import type {
  GuideInput, KitchenApi, MealActionView, MealGuideView, MealInput, MealRevisionInput,
  MealSelectionInput, MealView, PendingKitchenAction,
} from './types'
import { parseGuideForMeal, parseMealView } from './validation'

export const kitchenLeaveWarning = '餐单操作尚未确认。离开会停止本地等待，但服务器可能仍在处理；之后可用原编号核对。确定离开吗？'
export const kitchenReplayWarning = '核对可能继续服务端操作，模型指导可能产生费用。将使用原请求编号和原请求内容；请确认后继续。'
const PAGE_SIZE = 20

export interface KitchenStateDependencies {
  ownerId: ComputedRef<string | null>
  meals?: KitchenApi
  conversations?: Pick<ConversationApi, 'list' | 'create' | 'detail'>
  profile?: DiningProfileApi
  uuid?: () => string
  wait?: (milliseconds: number, signal: AbortSignal) => Promise<void>
  now?: () => number
}

function safeError(error: unknown): string {
  if (!(error instanceof ApiError)) return '请求未完成，请检查网络后重新读取核对。'
  if (error.status === 401) return '登录状态已失效，请重新登录。'
  if (error.status === 400) return '请求内容未通过服务端校验，请检查后修改。'
  if (error.status === 404) return '该会话、餐单或指导当前不可访问。'
  if (error.status === 409) return '餐单版本或当前状态已变化，请重新读取后再操作。'
  if (error.status === 503 && error.code === 'AGENT_EXECUTION_DISABLED') return '服务器当前未启用餐单或指导生成。'
  if (error.status === 403 && error.code === 'CSRF_INVALID') return '安全校验未通过，请重新读取页面后再试。'
  return '请求未完成；服务器状态可能已变化，请先读取历史核对。'
}
function isUnknown(error: unknown): boolean {
  return error instanceof ApiError && (
    error.status === 0 || error.status >= 500 || error.code === 'INVALID_RESPONSE'
  )
}
function emptyPage<T>(): Page<T> { return { items: [], page: 1, size: PAGE_SIZE, total: 0 } }
function positiveCount(text: string): number | null {
  if (!/^[1-9]\d*$/.test(text)) return null
  const value = Number(text)
  return Number.isSafeInteger(value) && value <= 2147483647 ? value : null
}

export function createKitchenState(deps: KitchenStateDependencies) {
  const mealsApi = deps.meals ?? kitchenApi
  const conversationsApi = deps.conversations ?? conversationApi
  const profilesApi = deps.profile ?? diningProfileApi
  const makeUuid = deps.uuid ?? (() => crypto.randomUUID())
  const wait = deps.wait ?? ((milliseconds, signal) => new Promise<void>((resolve, reject) => {
    if (signal.aborted) { reject(new ApiError(0, 'REQUEST_ABORTED', '')); return }
    const timer = setTimeout(resolve, milliseconds)
    signal.addEventListener('abort', () => { clearTimeout(timer); reject(new ApiError(0, 'REQUEST_ABORTED', '')) }, { once: true })
  }))
  const now = deps.now ?? Date.now

  const conversations = ref<Page<ConversationView>>(emptyPage())
  const conversationPage = ref(1)
  const conversationState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const conversationError = ref('')
  const conversation = ref<ConversationView | null>(null)
  const conversationTitle = ref('')
  const createdConversationId = ref<string | null>(null)
  const createBusy = ref(false)
  const createUnknown = ref(false)
  const createError = ref('')
  const meals = ref<Page<MealView>>(emptyPage())
  const mealPage = ref(1)
  const mealState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const mealError = ref('')
  const activeConversationId = ref<string | null>(null)
  const activeMeal = ref<MealView | null>(null)
  const guide = ref<MealGuideView | null>(null)
  const guideState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const guideError = ref('')
  const profileState = ref<'loading' | 'ready' | 'error'>('loading')
  const profileCount = ref<number | null>(null)
  const profileError = ref('')
  const draft = ref('')
  const diningCountInput = ref('')
  const inputError = ref('')
  const operationError = ref('')
  const operationMessage = ref('')
  const busy = ref(false)
  const pending = ref<PendingKitchenAction[]>([])
  const targetMealId = ref<string | null>(null)
  const activeOwnerId = ref<string | null>(deps.ownerId.value)
  const anyPending = computed(() => pending.value.length > 0 || createBusy.value || createUnknown.value)
  const activePending = computed(() => pending.value.find(item =>
    item.ownerId === activeOwnerId.value && item.conversationId === activeConversationId.value,
  ) ?? null)
  const hasPendingWait = computed(() => createBusy.value || pending.value.some(item => item.status === 'sending' || item.status === 'running'))
  const hasUnconfirmedTarget = computed(() => pending.value.some(item =>
    item.ownerId === deps.ownerId.value && item.conversationId === activeConversationId.value
    && (item.mealId === (activeMeal.value?.id ?? targetMealId.value)
      || item.originMealId === (activeMeal.value?.id ?? targetMealId.value)),
  ))

  let generation = 0
  let creationGeneration = 0
  // 会话代次之外另设餐单代次，切换同会话中的餐单也会使旧点击链失效。
  let mealGeneration = 0
  let mealReadController: AbortController | null = null
  let listController: AbortController | null = null
  let profileController: AbortController | null = null
  let conversationController: AbortController | null = null
  let operationController: AbortController | null = null
  let creationController: AbortController | null = null
  const actionControllers = new Map<string, AbortController>()

  function current(version: number, owner: string | null, conversationId: string | null): boolean {
    return generation === version && owner !== null && deps.ownerId.value === owner
      && activeOwnerId.value === owner && activeConversationId.value === conversationId
  }
  function pendingByKey(key: string) { return pending.value.find(item => item.key === key) }
  function updatePending(key: string, changes: Partial<PendingKitchenAction>) {
    const item = pendingByKey(key)
    if (item) Object.assign(item, changes)
  }
  function removePending(key: string) {
    pending.value = pending.value.filter(item => item.key !== key)
    actionControllers.delete(key)
  }
  function clearData(clearCreation = true) {
    conversations.value = emptyPage(); conversationPage.value = 1; conversationState.value = 'idle'; conversationError.value = ''
    conversation.value = null; meals.value = emptyPage(); mealPage.value = 1; mealState.value = 'idle'; mealError.value = ''
    activeConversationId.value = null; activeMeal.value = null; guide.value = null; guideState.value = 'idle'; guideError.value = ''
    targetMealId.value = null
    profileState.value = 'loading'; profileCount.value = null; profileError.value = ''
    draft.value = ''; diningCountInput.value = ''; inputError.value = ''; operationError.value = ''; operationMessage.value = ''
    if (clearCreation) {
      createBusy.value = false; createUnknown.value = false; createError.value = ''
      conversationTitle.value = ''; createdConversationId.value = null
    }
    busy.value = false
  }
  function stopWaits(keepPending: boolean) {
    generation++
    stopMealWaits(keepPending)
    listController?.abort(); profileController?.abort(); conversationController?.abort(); creationController?.abort()
    listController = null; profileController = null; conversationController = null; creationController = null
    if (keepPending && createBusy.value) {
      createUnknown.value = true
      createError.value = '会话创建等待已停止，结果未知；请刷新历史，确认后再决定是否重新创建。'
    }
  }
  function stopMealWaits(keepPending = true) {
    mealGeneration++
    mealReadController?.abort(); mealReadController = null
    operationController?.abort(); operationController = null
    for (const controller of actionControllers.values()) controller.abort()
    actionControllers.clear()
    busy.value = false
    if (keepPending) {
      for (const item of pending.value) {
        if (item.status === 'sending' || item.status === 'running') {
          item.status = 'unknown'
          item.message = '本地等待已停止；请回到原会话和原餐单，用原请求编号核对。'
        }
      }
    }
  }
  function confirmMealChange(): boolean {
    return !hasUnconfirmedTarget.value || typeof window === 'undefined' || window.confirm(kitchenLeaveWarning)
  }
  function canRetryPending(key: string): boolean {
    const context = pendingByKey(key)
    if (!context || context.ownerId !== deps.ownerId.value || context.conversationId !== activeConversationId.value) return false
    return context.mealId === null
      ? activeMeal.value === null && targetMealId.value === null
      : activeMeal.value?.id === context.mealId
  }
  function mayStartAction(): boolean {
    if (!hasUnconfirmedTarget.value) return true
    operationError.value = '本餐还有未确认操作，请先用原请求编号核对，不能另起新请求。'
    return false
  }
  function actionCurrent(context: PendingKitchenAction, version: number, mealVersion: number): boolean {
    return current(version, context.ownerId, context.conversationId) && mealGeneration === mealVersion
      && (context.kind === 'CREATE'
        ? activeMeal.value === null || activeMeal.value.id === context.mealId
        : activeMeal.value?.id === context.mealId)
  }
  function clearAccount() {
    creationGeneration++
    stopWaits(false)
    pending.value = []
    activeOwnerId.value = null
    clearData()
  }

  async function loadConversations(page = conversationPage.value, signal?: AbortSignal): Promise<boolean> {
    const owner = deps.ownerId.value
    if (!owner || !Number.isSafeInteger(page) || page < 1) return false
    const version = generation, controller = new AbortController()
    listController?.abort(); listController = controller
    conversationState.value = 'loading'; conversationError.value = ''
    try {
      const result = await conversationsApi.list(page, signal ?? controller.signal)
      if (!current(version, owner, activeConversationId.value)) return false
      conversations.value = result; conversationPage.value = result.page; conversationState.value = 'ready'
      return true
    } catch (error) {
      if (!current(version, owner, activeConversationId.value)) return false
      conversationState.value = 'error'; conversationError.value = safeError(error); return false
    } finally { if (listController === controller) listController = null }
  }

  async function loadProfile(owner: string, version: number) {
    const controller = new AbortController(); profileController?.abort(); profileController = controller
    profileState.value = 'loading'; profileCount.value = null; profileError.value = ''
    try {
      const result = parseDiningProfile(await profilesApi.getProfile(controller.signal))
      if (generation !== version || deps.ownerId.value !== owner) return
      profileCount.value = result.defaultDiningCount; profileState.value = 'ready'
    } catch (error) {
      if (generation !== version || deps.ownerId.value !== owner) return
      profileState.value = 'error'; profileError.value = safeError(error)
    } finally { if (profileController === controller) profileController = null }
  }

  async function loadMeals(page = mealPage.value, expectedGeneration = generation): Promise<boolean> {
    const owner = deps.ownerId.value, cid = activeConversationId.value
    if (!owner || !cid || !Number.isSafeInteger(page) || page < 1) return false
    const controller = new AbortController(); conversationController?.abort(); conversationController = controller
    mealState.value = 'loading'; mealError.value = ''
    try {
      const result = await mealsApi.listMeals(cid, page, controller.signal)
      if (!current(expectedGeneration, owner, cid)) return false
      meals.value = result; mealPage.value = result.page; mealState.value = 'ready'
      return true
    } catch (error) {
      if (!current(expectedGeneration, owner, cid)) return false
      mealState.value = 'error'; mealError.value = safeError(error); return false
    } finally { if (conversationController === controller) conversationController = null }
  }

  async function showMeal(mealId: string, expectedGeneration = generation): Promise<boolean> {
    const owner = deps.ownerId.value, cid = activeConversationId.value
    if (!owner || !cid) return false
    if (!confirmMealChange()) return false
    stopMealWaits()
    targetMealId.value = mealId
    const mealVersion = mealGeneration
    const controller = new AbortController(); mealReadController = controller
    activeMeal.value = null; mealState.value = 'loading'
    guide.value = null; guideError.value = ''; guideState.value = 'idle'
    try {
      const value = parseMealView(await mealsApi.getMeal(cid, mealId, controller.signal), cid, mealId)
      if (!current(expectedGeneration, owner, cid) || mealGeneration !== mealVersion) return false
      activeMeal.value = value
      mealState.value = 'ready'
      if (value.guide && value.factsStatus !== 'UNAVAILABLE') {
        guideState.value = 'loading'
        try {
          const saved = parseGuideForMeal(await mealsApi.getGuide(cid, value.id, value.guide.id, controller.signal), value)
          if (!current(expectedGeneration, owner, cid) || mealGeneration !== mealVersion || activeMeal.value?.id !== mealId) return false
          guide.value = saved; guideState.value = 'ready'
        } catch (error) {
          if (!current(expectedGeneration, owner, cid) || mealGeneration !== mealVersion) return false
          guideState.value = 'error'; guideError.value = safeError(error)
        }
      }
      return true
    } catch (error) {
      if (!current(expectedGeneration, owner, cid) || mealGeneration !== mealVersion) return false
      mealState.value = 'error'; mealError.value = safeError(error); return false
    } finally { if (mealReadController === controller) mealReadController = null }
  }

  async function activate(conversationId: string | null) {
    stopWaits(true)
    const owner = deps.ownerId.value, version = generation
    activeOwnerId.value = owner
    clearData(false)
    activeConversationId.value = conversationId
    if (!owner) { clearAccount(); return }
    void loadConversations(1)
    void loadProfile(owner, version)
    if (!conversationId) return
    const controller = new AbortController(); conversationController = controller
    mealState.value = 'loading'
    try {
      const value = await conversationsApi.detail(conversationId, controller.signal)
      if (!current(version, owner, conversationId) || value.id !== conversationId) return
      conversation.value = value
      await loadMeals(1, version)
      if (!current(version, owner, conversationId)) return
      const first = meals.value.items[0]
      if (first) await showMeal(first.id, version)
      else mealState.value = 'ready'
    } catch (error) {
      if (!current(version, owner, conversationId)) return
      mealState.value = 'error'; mealError.value = safeError(error)
    } finally { if (conversationController === controller) conversationController = null }
  }
  function leavePage() {
    stopWaits(true)
    activeOwnerId.value = deps.ownerId.value
    clearData(false)
  }

  async function createConversation(confirmedRetry = false): Promise<boolean> {
    if (createBusy.value || !deps.ownerId.value) return false
    if (createUnknown.value && !confirmedRetry) return false
    const title = conversationTitle.value.trim() || undefined
    const owner = deps.ownerId.value, version = generation, controller = new AbortController()
    const creationVersion = ++creationGeneration
    creationController?.abort(); creationController = controller
    createBusy.value = true; createError.value = ''; createUnknown.value = false
    try {
      const result = await conversationsApi.create(title, controller.signal)
      if (deps.ownerId.value !== owner || creationGeneration !== creationVersion) return false
      // 成功响应已确定创建终态；先结束等待，再发布会触发页面导航的ID。
      if (creationController === controller) creationController = null
      createBusy.value = false
      createUnknown.value = false; conversationTitle.value = ''
      if (generation !== version) {
        createError.value = '会话已创建；请在厨房对话历史中查找并打开。'
        void loadConversations(1)
        return true
      }
      createdConversationId.value = result.id
      await loadConversations(1)
      return true
    } catch (error) {
      if (deps.ownerId.value !== owner || creationGeneration !== creationVersion) return false
      if (isUnknown(error)) createUnknown.value = true
      createError.value = isUnknown(error)
        ? '新会话创建结果未知；请先刷新列表。再次创建可能多出一段会话。'
        : safeError(error)
      return false
    } finally {
      if (creationController === controller) creationController = null
      if (deps.ownerId.value === owner && creationGeneration === creationVersion) createBusy.value = false
    }
  }

  function startNewMeal() {
    if (!confirmMealChange()) return false
    stopMealWaits()
    targetMealId.value = null
    activeMeal.value = null; guide.value = null; guideState.value = 'idle'; guideError.value = ''
    mealState.value = 'ready'
    draft.value = ''; diningCountInput.value = ''; inputError.value = ''; operationError.value = ''; operationMessage.value = ''
    return true
  }

  function register(kind: PendingKitchenAction['kind'], cid: string, mealId: string | null, payload: PendingKitchenAction['payload']) {
    const context: PendingKitchenAction = {
      key: makeUuid(), ownerId: deps.ownerId.value!, conversationId: cid, mealId, kind,
      originMealId: activeMeal.value?.id ?? targetMealId.value,
      requestId: payload.requestId, payload: Object.freeze({ ...payload }), actionId: null, guideId: null,
      status: 'sending', message: '正在提交，请等待服务器确认。',
    }
    pending.value = [...pending.value, context]
    return context
  }
  function finishContext(context: PendingKitchenAction) { removePending(context.key) }
  function setUnknown(context: PendingKitchenAction, message = '操作结果未知；保留了原编号和原请求内容，请核对后再决定。') {
    updatePending(context.key, { status: 'unknown', message })
  }
  function setContextAction(context: PendingKitchenAction, result: MealActionView) {
    updatePending(context.key, {
      actionId: result.action.id,
      mealId: result.meal.id,
      status: result.action.status === 'PENDING' || result.action.status === 'RUNNING' ? 'running' : 'sending',
      message: result.action.status === 'FAILED' ? '操作失败，当前餐单事实仍可读取。' : '服务器仍在处理，正在只读核对状态。',
    })
  }
  async function boundedAction(context: PendingKitchenAction, initial: MealActionView): Promise<MealActionView | null> {
    let result = initial
    if (result.action.status === 'PENDING' || result.action.status === 'RUNNING') {
      setContextAction(context, result)
      const controller = new AbortController(); actionControllers.set(context.key, controller)
      const started = now()
      try {
        for (let attempt = 0; attempt < 30 && now() - started < 120000; attempt++) {
          if (attempt > 0) await wait(Math.min(1000, Math.max(0, 120000 - (now() - started))), controller.signal)
          if (now() - started >= 120000) break
          result = await mealsApi.getAction(context.conversationId, context.mealId!, context.actionId!, controller.signal)
          if (controller.signal.aborted) return null
          if (result.action.id !== context.actionId || result.action.type !== context.kind) throw new ApiError(200, 'INVALID_RESPONSE', '')
          setContextAction(context, result)
          if (result.action.status !== 'PENDING' && result.action.status !== 'RUNNING') return result
        }
      } catch { setUnknown(context, '状态核对被停止或未能完成；仍保留原编号，可稍后再次只读核对。') }
      finally { if (actionControllers.get(context.key) === controller) actionControllers.delete(context.key) }
      setUnknown(context, '已达到30次或120秒核对上限；服务器状态尚未确认。')
      return null
    }
    return result
  }

  async function applyAction(context: PendingKitchenAction, action: MealActionView, version: number, allowGuide: boolean, mealVersion: number) {
    if (!actionCurrent(context, version, mealVersion)) {
      setUnknown(context, '原点击链已失效；请回到原会话和原餐单读取核对。')
      return
    }
    if (action.action.status === 'PENDING' || action.action.status === 'RUNNING') {
      const settled = await boundedAction(context, action)
      if (!settled) return
      action = settled
    }
    if (action.action.type !== context.kind
      || (action.action.requestId !== context.requestId && (context.kind === 'CREATE' || context.kind === 'REVISE'))) {
      setUnknown(context, '服务器返回的动作编号或类型与原请求不符，请用原编号核对。')
      return
    }
    // 修订登记时递增版本；选择和指导仍绑定请求中的原版本。
    const expectedActionVersion = 'expectedVersion' in context.payload
      ? context.payload.expectedVersion + (context.kind === 'REVISE' ? 1 : 0) : 1
    if (action.action.version !== expectedActionVersion) {
      setUnknown(context, '服务器返回的动作版本与原请求不符，请用原编号核对。')
      return
    }
    if (!actionCurrent(context, version, mealVersion)) {
      setUnknown(context, '操作已返回，但当前页面已切换；请回到原会话读取核对。')
      return
    }
    if (activeMeal.value && activeMeal.value.version > action.meal.version) {
      finishContext(context)
      operationMessage.value = '原操作已结束，保留当前较新餐单；历史结果不会覆盖新版本。'
      return
    }
    if (action.action.status === 'FAILED') {
      if (!activeMeal.value?.guide || action.meal.guide) activeMeal.value = action.meal
      targetMealId.value = action.meal.id
      operationError.value = action.action.error?.code === 'AGENT_EXECUTION_DISABLED'
        ? '服务器当前未启用生成。餐单与已选方案仍保留。'
        : '本次操作未完成；已选方案保留，可查看错误状态或显式重新生成指导。'
      finishContext(context)
      await loadMeals(mealPage.value, version)
      return
    }
    if (action.meal.factsStatus === 'UNAVAILABLE') {
      activeMeal.value = action.meal; guide.value = null; finishContext(context)
      operationError.value = '引用菜谱当前不可访问，服务端已隐藏餐单内容。'
      await loadMeals(mealPage.value, version); return
    }
    if (context.kind === 'GUIDE') {
      const signal = operationController?.signal
      const refreshed = await mealsApi.getMeal(context.conversationId, action.meal.id, signal)
      if (!actionCurrent(context, version, mealVersion)) { setUnknown(context); return }
      activeMeal.value = parseMealView(refreshed, context.conversationId, action.meal.id)
      if (activeMeal.value.version !== expectedActionVersion || activeMeal.value.factsStatus === 'UNAVAILABLE') {
        guide.value = null; finishContext(context)
        operationMessage.value = '当前餐单已变化，原指导操作只供历史核对。'; return
      }
      const reference = activeMeal.value.guide
      if (reference) {
        const saved = parseGuideForMeal(await mealsApi.getGuide(context.conversationId, action.meal.id, reference.id, signal), activeMeal.value)
        if (!actionCurrent(context, version, mealVersion)) { setUnknown(context); return }
        guide.value = saved
        guideState.value = 'ready'
      } else { setUnknown(context, '动作已结束，但当前餐单没有可读取的指导；请刷新后核对。'); return }
      finishContext(context); operationMessage.value = '指导已保存，可查看完整菜谱依据。'
      await loadMeals(mealPage.value, version); return
    }
    if (context.kind === 'CREATE' || context.kind === 'REVISE') {
      activeMeal.value = action.meal; guide.value = null
      targetMealId.value = action.meal.id
      finishContext(context)
      await loadMeals(mealPage.value, version)
      return
    }
    // SELECT 可能重放较早快照，统一先GET当前餐单，避免抹掉已经保存的指导。
    const selected = await mealsApi.getMeal(context.conversationId, action.meal.id, operationController?.signal)
    if (!actionCurrent(context, version, mealVersion)) { setUnknown(context); return }
    activeMeal.value = parseMealView(selected, context.conversationId, action.meal.id)
    if (activeMeal.value.version !== expectedActionVersion || activeMeal.value.factsStatus !== 'CURRENT' || activeMeal.value.status !== 'SELECTED'
      || activeMeal.value.selectedOptionId !== (context.payload as MealSelectionInput).optionId) {
      finishContext(context); operationError.value = '餐单版本或事实已变化，未继续请求生成指导。'; return
    }
    if (!allowGuide) {
      finishContext(context)
      operationMessage.value = '原选择已核对；未自动生成指导。'
      await loadMeals(mealPage.value, version); return
    }
    if (activeMeal.value.guide) {
      const saved = parseGuideForMeal(
        await mealsApi.getGuide(context.conversationId, activeMeal.value.id, activeMeal.value.guide.id, operationController?.signal),
        activeMeal.value,
      )
      if (!actionCurrent(context, version, mealVersion)) { setUnknown(context); return }
      guide.value = saved
      guideState.value = 'ready'; finishContext(context); return
    }
    const guideRequestId = makeUuid()
    if (guideRequestId === context.requestId) {
      finishContext(context); operationError.value = '请求编号生成冲突，未发送指导请求。'; return
    }
    const input: GuideInput = {
      requestId: guideRequestId,
      expectedVersion: activeMeal.value.version,
      optionId: activeMeal.value.selectedOptionId,
    }
    const guideContext = register('GUIDE', context.conversationId, activeMeal.value.id, input)
    finishContext(context)
    await postContext(guideContext, version, false, mealVersion)
  }

  async function postContext(context: PendingKitchenAction, version: number, allowGuide = false, mealVersion = mealGeneration) {
    const controller = new AbortController(); operationController?.abort(); operationController = controller
    busy.value = true; operationError.value = ''; operationMessage.value = ''
    updatePending(context.key, { status: 'sending' })
    try {
      let result: MealActionView
      switch (context.kind) {
        case 'CREATE': result = await mealsApi.createMeal(context.conversationId, context.payload as MealInput, controller.signal); break
        case 'REVISE': result = await mealsApi.reviseMeal(context.conversationId, context.mealId!, context.payload as MealRevisionInput, controller.signal); break
        case 'SELECT': result = await mealsApi.selectOption(context.conversationId, context.mealId!, context.payload as MealSelectionInput, controller.signal); break
        case 'GUIDE': result = await mealsApi.generateGuide(context.conversationId, context.mealId!, context.payload as GuideInput, controller.signal); break
      }
      setContextAction(context, result)
      await applyAction(context, result, version, allowGuide, mealVersion)
    } catch (error) {
      if (!actionCurrent(context, version, mealVersion) || isUnknown(error)) setUnknown(context)
      else {
        operationError.value = safeError(error)
        finishContext(context)
      }
    } finally {
      if (operationController === controller) operationController = null
      if (generation === version && mealGeneration === mealVersion) busy.value = false
    }
  }

  function inputPayload(): MealInput | null {
    if (!draft.value.trim()) { inputError.value = '请输入本餐需求。'; return null }
    if (Array.from(draft.value).length > 4000) { inputError.value = '本餐需求最多4000个Unicode字符。'; return null }
    let diningCount: number | null | undefined
    if (diningCountInput.value !== '') {
      const parsed = positiveCount(diningCountInput.value)
      if (parsed === null) { inputError.value = '用餐人数须为1到2147483647之间的正整数。'; return null }
      diningCount = parsed
    }
    inputError.value = ''
    const payload: MealInput = { requestId: makeUuid(), content: draft.value }
    if (diningCount !== undefined) Object.assign(payload, { diningCount })
    return Object.freeze(payload)
  }
  async function createMeal() {
    const cid = activeConversationId.value
    if (!cid || busy.value || activeMeal.value || !mayStartAction()) return false
    const payload = inputPayload()
    if (!payload) return false
    const context = register('CREATE', cid, null, payload)
    await postContext(context, generation)
    return !pendingByKey(context.key)
  }
  async function reviseMeal() {
    const meal = activeMeal.value, cid = activeConversationId.value
    if (!cid || !meal || busy.value || meal.factsStatus === 'UNAVAILABLE' || !mayStartAction()) return false
    const input = inputPayload()
    if (!input) return false
    const payload: MealRevisionInput = Object.freeze({ ...input, expectedVersion: meal.version })
    const context = register('REVISE', cid, meal.id, payload)
    await postContext(context, generation)
    return !pendingByKey(context.key)
  }
  async function selectAndGuide(optionId: string) {
    const meal = activeMeal.value, cid = activeConversationId.value
    if (!cid || !meal || busy.value || meal.factsStatus !== 'CURRENT' || meal.status !== 'OPTIONS_READY'
      || !meal.options.some(option => option.id === optionId) || !mayStartAction()) return false
    operationError.value = ''; operationMessage.value = ''
    const payload: MealSelectionInput = Object.freeze({ requestId: makeUuid(), expectedVersion: meal.version, optionId })
    const context = register('SELECT', cid, meal.id, payload)
    await postContext(context, generation, true)
    return !pendingByKey(context.key)
  }
  async function regenerateGuide() {
    const meal = activeMeal.value, cid = activeConversationId.value
    if (!cid || !meal || busy.value || meal.factsStatus !== 'CURRENT' || meal.status !== 'SELECTED'
      || !meal.selectedOptionId || !mayStartAction()) return false
    if (typeof window !== 'undefined' && !window.confirm('重新生成会发送一次新的模型请求，可能产生费用。确定重新生成吗？')) return false
    const payload: GuideInput = Object.freeze({
      requestId: makeUuid(), expectedVersion: meal.version, optionId: meal.selectedOptionId,
    })
    const context = register('GUIDE', cid, meal.id, payload)
    await postContext(context, generation)
    return !pendingByKey(context.key)
  }
  async function retryPending(key: string): Promise<boolean> {
    const context = pendingByKey(key)
    if (!context || busy.value || !canRetryPending(key) || context.status === 'sending' || context.status === 'running') {
      operationError.value = '请先回到原会话，并打开原餐单或原新餐输入区，再核对原请求。'
      return false
    }
    if (typeof window !== 'undefined' && !window.confirm(kitchenReplayWarning)) return false
    const version = generation, mealVersion = mealGeneration
    if (context.actionId && context.mealId) {
      const controller = new AbortController(); actionControllers.set(context.key, controller)
      busy.value = true; updatePending(context.key, { status: 'running' })
      try {
        const result = await mealsApi.getAction(context.conversationId, context.mealId, context.actionId, controller.signal)
        if (!actionCurrent(context, version, mealVersion)) { setUnknown(context); return false }
        setContextAction(context, result)
        if (result.action.status === 'PENDING' || result.action.status === 'RUNNING') {
          const settled = await boundedAction(context, result)
          if (!settled) return false
          await applyAction(context, settled, version, false, mealVersion)
        } else await applyAction(context, result, version, false, mealVersion)
        return !pendingByKey(key)
      } catch { setUnknown(context); return false }
      finally {
        actionControllers.delete(context.key)
        if (generation === version && mealGeneration === mealVersion) busy.value = false
      }
    }
    await postContext(context, version, false, mealVersion)
    return !pendingByKey(key)
  }
  async function refreshActiveMeal() {
    if (activeMeal.value) await showMeal(activeMeal.value.id)
    else await loadMeals(mealPage.value)
  }

  watch(deps.ownerId, owner => {
    if (owner === activeOwnerId.value) return
    creationGeneration++
    stopWaits(false); pending.value = []; activeOwnerId.value = owner; clearData()
  }, { flush: 'sync' })
  const unregisterCleanup = registerSessionCleanup(clearAccount)
  onScopeDispose(() => {
    stopWaits(true)
    unregisterCleanup()
  })

  return {
    conversations, conversationPage, conversationState, conversationError, conversation, conversationTitle, createdConversationId,
    createBusy, createUnknown, createError, meals, mealPage, mealState, mealError, activeConversationId,
    activeMeal, guide, guideState, guideError, profileState, profileCount, profileError, draft, diningCountInput,
    inputError, operationError, operationMessage, busy, pending, anyPending, activePending, hasPendingWait, hasUnconfirmedTarget, canRetryPending,
    activate, leavePage, loadConversations, loadMeals, showMeal, createConversation, startNewMeal, createMeal, reviseMeal,
    selectAndGuide, regenerateGuide, retryPending, refreshActiveMeal, clearAccount,
  }
}
