import { computed } from 'vue'
import { defineStore } from 'pinia'
import { useIdentityStore } from '@/stores/identity'
import { registerSessionCleanup } from '@/shared/auth/sessionCleanup'
import { createShoppingState } from './state'

export const useShoppingStore = defineStore('shopping', () => {
  const identity = useIdentityStore()
  const state = createShoppingState({ ownerId: computed(() => identity.user?.id ?? null) })
  registerSessionCleanup(state.clearAccount)
  return state
})
