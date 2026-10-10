import { apiClient } from '@/shared/api/client'
import { ApiError } from '@/shared/api/types'
import { isResourceId } from '../validation'
import type { KitchenApi, GuideInput, MealInput, MealRevisionInput, MealSelectionInput } from './types'
import { parseMealAction, parseMealGuide, parseMealPage, parseMealView } from './validation'

interface KitchenRequestClient {
  request<T>(path: string, method?: string, body?: unknown, options?: {
    auth?: 'required' | 'public'
    signal?: AbortSignal
    timeoutMs?: number
  }): Promise<T>
}

function id(value: string, label: string): string {
  if (!isResourceId(value)) throw new ApiError(400, 'VALIDATION_ERROR', label + '编号不合法。')
  return encodeURIComponent(value)
}
function base(conversationId: string): string {
  return '/conversations/' + id(conversationId, '对话') + '/meals'
}

export function createKitchenApi(client: KitchenRequestClient = apiClient): KitchenApi {
  return {
    async listMeals(conversationId, page, signal) {
      if (!Number.isSafeInteger(page) || page < 1) throw new ApiError(400, 'VALIDATION_ERROR', '页码不合法。')
      const result = await client.request<unknown>(base(conversationId) + '?page=' + page + '&size=20', 'GET', undefined, { auth: 'required', signal })
      return parseMealPage(result, conversationId)
    },
    async getMeal(conversationId, mealId, signal) {
      const result = await client.request<unknown>(base(conversationId) + '/' + id(mealId, '餐单'), 'GET', undefined, { auth: 'required', signal })
      return parseMealView(result, conversationId, mealId)
    },
    async createMeal(conversationId, input: MealInput, signal) {
      const result = await client.request<unknown>(base(conversationId), 'POST', input, { auth: 'required', signal, timeoutMs: 125000 })
      return parseMealAction(result, conversationId)
    },
    async reviseMeal(conversationId, mealId, input: MealRevisionInput, signal) {
      const result = await client.request<unknown>(base(conversationId) + '/' + id(mealId, '餐单') + '/revisions', 'POST', input, { auth: 'required', signal, timeoutMs: 125000 })
      return parseMealAction(result, conversationId, mealId)
    },
    async selectOption(conversationId, mealId, input: MealSelectionInput, signal) {
      const result = await client.request<unknown>(base(conversationId) + '/' + id(mealId, '餐单') + '/selection', 'POST', input, { auth: 'required', signal })
      return parseMealAction(result, conversationId, mealId)
    },
    async getAction(conversationId, mealId, actionId, signal) {
      const result = await client.request<unknown>(base(conversationId) + '/' + id(mealId, '餐单') + '/actions/' + id(actionId, '动作'), 'GET', undefined, { auth: 'required', signal })
      return parseMealAction(result, conversationId, mealId)
    },
    async generateGuide(conversationId, mealId, input: GuideInput, signal) {
      const result = await client.request<unknown>(base(conversationId) + '/' + id(mealId, '餐单') + '/guides', 'POST', input, { auth: 'required', signal, timeoutMs: 125000 })
      return parseMealAction(result, conversationId, mealId)
    },
    async getGuide(conversationId, mealId, guideId, signal) {
      const result = await client.request<unknown>(base(conversationId) + '/' + id(mealId, '餐单') + '/guides/' + id(guideId, '指导'), 'GET', undefined, { auth: 'required', signal })
      return parseMealGuide(result, conversationId, mealId, guideId)
    },
  }
}

export const kitchenApi = createKitchenApi()
