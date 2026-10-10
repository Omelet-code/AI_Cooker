import {apiClient} from '@/shared/api/client'
import type {RequestOptions} from '@/shared/api/types'
import type {ModelCredentialSettingsApi,ModelCredentialStatus} from './types'

interface SettingsRequestClient {
 request<T>(path:string,method?:string,body?:unknown,requestOptions?:RequestOptions):Promise<T>
}

/** 将T8用户凭证接口封装在identity归属的页面API适配器内。 */
export function createModelCredentialSettingsApi(client:SettingsRequestClient=apiClient):ModelCredentialSettingsApi{
 return {
  getStatus(){return client.request<ModelCredentialStatus>('/users/me/llm-credential/status','GET',undefined,{auth:'required'})},
  save(apiKey:string){return client.request<ModelCredentialStatus>('/users/me/llm-credential','PUT',{apiKey},{auth:'required'})},
  remove(){return client.request<ModelCredentialStatus>('/users/me/llm-credential','DELETE',undefined,{auth:'required'})},
 }
}

export const modelCredentialSettingsApi=createModelCredentialSettingsApi()
