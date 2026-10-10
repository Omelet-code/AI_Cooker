import { watch, type ComputedRef } from 'vue'
import type { Router } from 'vue-router'
import type { PendingShoppingOperation, PendingShoppingRoute, ShoppingListView, ShoppingSourceRoute } from './types'

/** 两处“返回原操作”入口共用冻结时保存的原路由。 */
export function pendingShoppingLocation(operation: PendingShoppingOperation): PendingShoppingRoute {
  return operation.originalRoute
}

interface NavigationState {
  redirectToListId: string | null
  list: ShoppingListView | null
  scope: { kind: string; source?: ShoppingSourceRoute } | null
  busy: boolean
  pendingOperation: PendingShoppingOperation | null
}
/** 只在当前来源已收到匹配清单成功响应后切换路由。 */
export function watchShoppingListCreation(router: Pick<Router, 'replace'>, state: NavigationState, source: ComputedRef<ShoppingSourceRoute | null>) {
  return watch(() => state.redirectToListId, id => {
    const current = source.value, saved = state.list
    if (!id || !current || !saved || state.busy || state.pendingOperation || saved.id !== id
      || state.scope?.kind !== 'new' || saved.source.conversationId !== current.conversationId
      || saved.source.mealId !== current.mealId || saved.source.version !== current.expectedVersion
      || saved.source.optionId !== current.optionId) return
    state.redirectToListId = null
    void router.replace({ name: 'shopping-list', params: { listId: id } })
  }, { flush: 'sync' })
}
