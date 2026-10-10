import { apiClient } from '@/shared/api/client'
import type { RequestOptions } from '@/shared/api/types'
import type { DiningProfileApi } from './types'

interface ProfileRequestClient {
  request<T>(path: string, method?: string, body?: unknown, requestOptions?: RequestOptions): Promise<T>
}

/** 个人档案使用共享请求层，沿用 Cookie、CSRF 和有限续期。 */
export function createDiningProfileApi(client: ProfileRequestClient = apiClient): DiningProfileApi {
  return {
    getProfile(signal) {
      return client.request('/users/me/profile', 'GET', undefined, { auth: 'required', signal })
    },
    saveProfile(defaultDiningCount, signal) {
      return client.request('/users/me/profile', 'PUT', { defaultDiningCount }, { auth: 'required', signal })
    },
  }
}

export const diningProfileApi = createDiningProfileApi()
