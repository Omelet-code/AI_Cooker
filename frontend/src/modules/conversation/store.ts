import {computed} from 'vue'
import {defineStore} from 'pinia'
import {useIdentityStore} from '@/stores/identity'
import {conversationApi} from './api'
import {useChatState} from './useChatState'

export const useConversationStore=defineStore('conversation',()=>{
 const identity=useIdentityStore()
 return useChatState(conversationApi,computed(()=>identity.user?.id??null))
})
