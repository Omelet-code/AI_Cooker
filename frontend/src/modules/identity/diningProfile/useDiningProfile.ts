import { computed, onScopeDispose, ref, watch, type ComputedRef } from 'vue'
import { registerSessionCleanup } from '@/shared/auth/sessionCleanup'
import { ApiError } from '@/shared/api/types'
import type { DiningProfileApi } from './types'
import { parseDiningCountInput, parseDiningProfile } from './validation'

export type DiningProfileState = 'loading' | 'ready' | 'error'
export type DiningProfileWrite = 'save' | 'clear'

function isUnknownWrite(error: unknown): boolean {
  return error instanceof ApiError && (
    error.status === 0 || error.status >= 500 || error.code === 'INVALID_RESPONSE'
  )
}

function safeError(error: unknown, reading: boolean): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '登录状态已失效，请重新登录。'
    if (error.status === 400) return '人数格式或范围未通过服务端校验，请检查后重试。'
    if (error.status === 403 && error.code === 'CSRF_INVALID') return '安全校验未通过，请重新读取后重试。'
    if (error.status === 403) return '当前账号无权修改个人档案。'
    if (error.status === 404) return '个人档案接口暂不可用。'
    if (error.status >= 500) return reading ? '读取个人档案失败，请稍后重试。' : '保存结果尚未确认，请先重新读取档案。'
    if (error.status === 0) return reading ? '网络请求未完成，无法读取个人档案。' : '网络结果尚未确认，请先重新读取档案。'
    if (error.code === 'INVALID_RESPONSE') return '服务器响应格式异常，请重新读取档案确认结果。'
  }
  return reading ? '读取个人档案失败，请稍后重试。' : '保存个人档案失败，请重新读取确认。'
}

/** 页面内草稿与服务端已保存值分开；身份、会话或页面生命周期结束时丢弃私有状态。 */
export function useDiningProfile(api: DiningProfileApi, userId: ComputedRef<string | null>) {
  const savedCount = ref<number | null>(null)
  const countInput = ref('')
  const state = ref<DiningProfileState>('loading')
  const statusError = ref('')
  const actionError = ref('')
  const actionMessage = ref('')
  const validationError = ref('')
  const writeUncertain = ref<DiningProfileWrite | null>(null)
  const busyAction = ref<DiningProfileWrite | 'read' | null>(null)
  const isBusy = computed(() => busyAction.value !== null)

  let generation = 0
  let disposed = false
  let controller: AbortController | null = null

  function isCurrent(requestGeneration: number, ownerId: string | null): boolean {
    return !disposed && generation === requestGeneration && ownerId !== null && userId.value === ownerId
  }

  function clearPrivateState(message = '登录状态已失效，请重新登录。') {
    generation++
    controller?.abort()
    controller = null
    savedCount.value = null
    countInput.value = ''
    state.value = 'error'
    statusError.value = message
    actionError.value = ''
    actionMessage.value = ''
    validationError.value = ''
    writeUncertain.value = null
    busyAction.value = null
  }

  async function load(): Promise<boolean> {
    const ownerId = userId.value
    if (!ownerId) {
      clearPrivateState()
      return false
    }
    if (busyAction.value) return false

    generation++
    const requestGeneration = generation
    controller?.abort()
    const requestController = new AbortController()
    controller = requestController
    busyAction.value = 'read'
    savedCount.value = null
    state.value = 'loading'
    statusError.value = ''
    actionError.value = ''

    try {
      const profile = parseDiningProfile(await api.getProfile(requestController.signal))
      if (!isCurrent(requestGeneration, ownerId)) return false
      savedCount.value = profile.defaultDiningCount
      countInput.value = profile.defaultDiningCount === null ? '' : String(profile.defaultDiningCount)
      state.value = 'ready'
      statusError.value = ''
      actionError.value = ''
      actionMessage.value = ''
      writeUncertain.value = null
      validationError.value = ''
      return true
    } catch (error) {
      if (!isCurrent(requestGeneration, ownerId)) return false
      state.value = 'error'
      savedCount.value = null
      statusError.value = safeError(error, true)
      return false
    } finally {
      if (isCurrent(requestGeneration, ownerId) && controller === requestController) {
        controller = null
        busyAction.value = null
      }
    }
  }

  function validateInput(): number | null {
    const value = parseDiningCountInput(countInput.value)
    if (value === null) {
      validationError.value = '请输入 1 到 2147483647 之间的正整数。'
      return null
    }
    validationError.value = ''
    return value
  }

  async function write(action: DiningProfileWrite, value: number | null): Promise<boolean> {
    const ownerId = userId.value
    if (!ownerId || state.value !== 'ready' || busyAction.value) return false

    const requestGeneration = ++generation
    const requestController = new AbortController()
    controller = requestController
    busyAction.value = action
    actionError.value = ''
    actionMessage.value = ''
    writeUncertain.value = null
    validationError.value = ''

    try {
      const profile = parseDiningProfile(await api.saveProfile(value, requestController.signal))
      if (!isCurrent(requestGeneration, ownerId)) return false
      savedCount.value = profile.defaultDiningCount
      countInput.value = profile.defaultDiningCount === null ? '' : String(profile.defaultDiningCount)
      writeUncertain.value = null
      actionMessage.value = action === 'clear' ? '已清空默认用餐人数。' : '默认用餐人数已保存。'
      return true
    } catch (error) {
      if (!isCurrent(requestGeneration, ownerId)) return false
      if (isUnknownWrite(error)) writeUncertain.value = action
      actionError.value = safeError(error, false)
      return false
    } finally {
      if (isCurrent(requestGeneration, ownerId) && controller === requestController) {
        controller = null
        busyAction.value = null
      }
    }
  }

  async function save(): Promise<boolean> {
    if (busyAction.value) return false
    const value = validateInput()
    if (value === null) return false
    return write('save', value)
  }

  function clear(): Promise<boolean> {
    return write('clear', null)
  }

  function clearValidation() {
    validationError.value = ''
  }

  watch(userId, (next, previous) => {
    if (next === previous) return
    clearPrivateState()
    if (next) void load()
  }, { flush: 'sync' })

  const unregisterCleanup = registerSessionCleanup(() => clearPrivateState())
  onScopeDispose(() => {
    disposed = true
    generation++
    controller?.abort()
    controller = null
    unregisterCleanup()
    savedCount.value = null
    countInput.value = ''
    busyAction.value = null
  })

  return {
    savedCount, countInput, state, statusError, actionError, actionMessage,
    validationError, writeUncertain, busyAction, isBusy,
    load, save, clear, clearValidation,
  }
}
