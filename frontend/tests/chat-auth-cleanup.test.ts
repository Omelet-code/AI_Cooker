import {computed,effectScope} from 'vue'
import {createPinia,setActivePinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import {describe,expect,it} from 'vitest'
import {installAuthenticationLifecycle} from '@/app/authLifecycle'
import {useIdentityStore} from '@/stores/identity'
import {createApiClient} from '@/shared/api/client'
import {createConversationApi} from '@/modules/conversation/api'
import {useChatState} from '@/modules/conversation/useChatState'
import {envelope,failure} from './helpers'
import {conversationFixture,pageFixture,requestId} from './chat-fixtures'

describe('最终401与聊天私有状态清理',()=>{
 it('真实共享续期失败通知清空用户、草稿、消息和待确认原文',async()=>{
  setActivePinia(createPinia());const identity=useIdentityStore();identity.user={id:'1',username:'fake'};identity.status='authenticated'
  let writes=0,refreshes=0
  const client=createApiClient({mode:'real',fetchImpl:(async(url)=>{
   const path=String(url)
   if(path==='/api/auth/csrf')return envelope({token:'fake-csrf',headerName:'X-XSRF-TOKEN'})
   if(path==='/api/auth/refresh'){refreshes++;return failure(401,'REFRESH_REJECTED')}
   if(path.endsWith('/turns')){writes++;return failure(401,'ACCESS_TOKEN_EXPIRED')}
   if(path.includes('/messages'))return envelope(pageFixture())
   return envelope(conversationFixture())
  }) as typeof fetch})
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{}},{path:'/login',name:'login',component:{}}]})
  const uninstall=installAuthenticationLifecycle(identity,router,client),scope=effectScope()
  const state=scope.run(()=>useChatState(createConversationApi(client),computed(()=>identity.user?.id??null),{uuid:()=>requestId}))!
  await state.activate('1');state.draft.value='不得残留的虚构正文';await state.send()
  expect(writes).toBe(1);expect(refreshes).toBe(1);expect(identity.status).toBe('anonymous')
  expect(state.pending.value).toBeNull();expect(state.draft.value).toBe('');expect(state.messages.value.items).toEqual([]);expect(state.selected.value).toBeNull()
  scope.stop();uninstall()
 })
})
