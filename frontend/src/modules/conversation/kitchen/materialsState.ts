import { ref } from 'vue'
import { isResourceId } from '../validation'
import { kitchenApi } from './api'
import type { KitchenApi, MealOption, MealView } from './types'

export interface MaterialsRouteContext {
  conversationId: unknown
  mealId: unknown
  version: unknown
  optionId: unknown
}

export interface MaterialsSnapshot {
  meal: MealView
  option: MealOption
}

export type MaterialsReadStatus = 'idle' | 'loading' | 'ready' | 'changed' | 'unavailable' | 'error'

/** 材料详情只用既有 GET；每次路由/账号变化都使旧读取代次失效。 */
export function createKitchenMaterialsState(
  getOwnerId: () => string | null,
  api: Pick<KitchenApi, 'getMeal'> = kitchenApi,
) {
  const status = ref<MaterialsReadStatus>('idle')
  const error = ref('')
  const snapshot = ref<MaterialsSnapshot | null>(null)
  let generation = 0
  let controller: AbortController | null = null

  function invalidate() {
    generation++
    controller?.abort()
    controller = null
    snapshot.value = null
  }

  function invalidLink(message = '材料详情链接参数不合法，请回到厨房历史重新打开。') {
    invalidate()
    error.value = message
    status.value = 'error'
  }

  async function load(context: MaterialsRouteContext): Promise<boolean> {
    invalidate()
    error.value = ''
    status.value = 'idle'

    const versionText = typeof context.version === 'string' ? context.version : ''
    const version = /^\d{1,16}$/.test(versionText) ? Number(versionText) : NaN
    if (!isResourceId(context.conversationId) || !isResourceId(context.mealId)
      || !isResourceId(context.optionId) || !Number.isSafeInteger(version) || version < 1) {
      invalidLink()
      return false
    }
    const ownerId = getOwnerId()
    if (!ownerId) {
      invalidLink('当前账号状态不可用，请重新登录后从厨房历史打开材料详情。')
      return false
    }

    const readGeneration = generation
    const requestController = new AbortController()
    controller = requestController
    status.value = 'loading'
    const isCurrent = () => generation === readGeneration && controller === requestController && getOwnerId() === ownerId

    try {
      const meal = await api.getMeal(context.conversationId, context.mealId, requestController.signal)
      if (!isCurrent()) return false
      if (meal.id !== context.mealId || meal.conversationId !== context.conversationId) {
        invalidLink('服务器返回的餐单不属于此链接中的会话，材料未显示。')
        return false
      }
      if (meal.version !== version) {
        invalidLink('餐单版本已变化，详情页不会替换成当前版本。请回到厨房历史核对。')
        return false
      }
      if (meal.factsStatus === 'UNAVAILABLE') {
        status.value = 'unavailable'
        return false
      }
      const option = meal.options.find(item => item.id === context.optionId)
      if (!option) {
        invalidLink('此方案不属于指定餐单版本，材料未显示。请回到厨房历史核对。')
        return false
      }
      snapshot.value = { meal, option }
      status.value = meal.factsStatus === 'CHANGED' ? 'changed' : 'ready'
      return true
    } catch (cause) {
      if (!isCurrent()) return false
      error.value = cause instanceof Error ? cause.message : '读取材料详情失败，请回到厨房历史重试。'
      status.value = 'error'
      return false
    } finally {
      if (isCurrent()) controller = null
    }
  }

  function clear() {
    invalidate()
    status.value = 'idle'
    error.value = ''
  }

  return { status, error, snapshot, load, clear }
}
