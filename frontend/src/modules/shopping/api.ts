import { apiClient } from '@/shared/api/client'
import { ApiError } from '@/shared/api/types'
import type { ProductMatchRequest, ProductMatchView, ShoppingCreateRequest, ShoppingListView, ShoppingPreview } from './types'
import { parseLatestMatch, parseProductMatch, parseShoppingList, parseShoppingPreview, validatePreviewAgainstRoute } from './validation'

function id(value: string): string {
  if (!/^[1-9]\d*$/.test(value)) throw new ApiError(400, 'VALIDATION_ERROR', '采购编号格式不正确。')
  return value
}
export function createShoppingApi(client: Pick<typeof apiClient, 'request'> = apiClient) {
  return {
    async preview(input: { conversationId: string; mealId: string; expectedVersion: number; optionId: string }, signal?: AbortSignal): Promise<ShoppingPreview> {
      const query = new URLSearchParams({ expectedVersion: String(input.expectedVersion), optionId: input.optionId })
      const result = parseShoppingPreview(await client.request(`/conversations/${id(input.conversationId)}/meals/${id(input.mealId)}/shopping-preview?${query}`, 'GET', undefined, { auth: 'required', signal }))
      validatePreviewAgainstRoute(result, input)
      return result
    },
    async create(request: ShoppingCreateRequest, signal?: AbortSignal): Promise<ShoppingListView> {
      const result = parseShoppingList(await client.request('/shopping-lists', 'POST', request, { auth: 'required', signal }))
      if (result.source.conversationId !== request.conversationId || result.source.mealId !== request.mealId || result.source.version !== request.expectedVersion || result.source.optionId !== request.optionId || result.source.sourceFingerprint !== request.sourceFingerprint) throw new ApiError(200, 'INVALID_RESPONSE', '服务器保存的采购清单与已确认来源不一致。')
      return result
    },
    async getList(listId: string, signal?: AbortSignal): Promise<ShoppingListView> {
      const key = id(listId), result = parseShoppingList(await client.request(`/shopping-lists/${key}`, 'GET', undefined, { auth: 'required', signal }))
      if (result.id !== key) throw new ApiError(200, 'INVALID_RESPONSE', '采购清单编号与请求不一致。')
      return result
    },
    async createMatch(listId: string, request: ProductMatchRequest, signal?: AbortSignal): Promise<ProductMatchView> {
      const key = id(listId), value = parseProductMatch(await client.request(`/shopping-lists/${key}/product-matches`, 'POST', request, { auth: 'required', signal }), key)
      if (value.cityCode !== request.cityCode) throw new ApiError(200, 'INVALID_RESPONSE', '报价城市与本次请求不一致。')
      return value
    },
    async latestMatch(listId: string, signal?: AbortSignal): Promise<ProductMatchView | null> {
      const key = id(listId)
      return parseLatestMatch(await client.request(`/shopping-lists/${key}/product-matches/latest`, 'GET', undefined, { auth: 'required', signal }), key)
    },
    async getMatch(listId: string, matchId: string, signal?: AbortSignal): Promise<ProductMatchView> {
      const key = id(listId), match = id(matchId)
      const result = parseProductMatch(await client.request(`/shopping-lists/${key}/product-matches/${match}`, 'GET', undefined, { auth: 'required', signal }), key)
      if (result.id !== match) throw new ApiError(200, 'INVALID_RESPONSE', '报价编号与请求不一致。')
      return result
    },
  }
}
export const shoppingApi = createShoppingApi()
