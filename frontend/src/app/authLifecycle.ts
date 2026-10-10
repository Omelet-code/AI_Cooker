import type {Router} from 'vue-router'
import {apiClient,type AuthenticationFailureReason} from '@/shared/api/client'
import {clearSessionScopedState} from '@/shared/auth/sessionCleanup'
import type {useIdentityStore} from '@/stores/identity'
type IdentityStore=ReturnType<typeof useIdentityStore>
export function installAuthenticationLifecycle(
 identity:Pick<IdentityStore,'status'|'clearUser'>,
 router:Router,
 client:Pick<typeof apiClient,'onAuthenticationFailure'>=apiClient,
):()=>void{
 let redirectPending=false
 const unsubscribe=client.onAuthenticationFailure((_reason:AuthenticationFailureReason)=>{
  if(identity.status!=='authenticated')return
  identity.clearUser()
  clearSessionScopedState()
  if(redirectPending||router.currentRoute.value.name==='login')return
  redirectPending=true
  void router.replace({name:'login',query:{reason:'session-expired'}})
   .catch(()=>undefined)
   .finally(()=>{redirectPending=false})
 })
 return unsubscribe
}
