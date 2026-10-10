import {apiClient} from '@/shared/api/client'
import type {LoginRequest,RegisterRequest,UserProfile} from './types'
export const identityApi={
 register(input:RegisterRequest){return apiClient.request<UserProfile>('/auth/register','POST',input,{auth:'public'})},
 login(input:LoginRequest){return apiClient.request<UserProfile>('/auth/login','POST',input,{auth:'public'})},
 currentUser(){return apiClient.request<UserProfile>('/users/me')},
 logout(){return apiClient.request<null>('/auth/logout','POST',{})},
 initializeCsrf(){return apiClient.getCsrf()},refreshCsrf(){return apiClient.refreshCsrf()},
}
