import { computed } from 'vue'
import { defineStore } from 'pinia'
import { useIdentityStore } from '@/stores/identity'
import { createKitchenState } from './state'

export const useKitchenStore = defineStore('kitchen', () => {
  const identity = useIdentityStore()
  return createKitchenState({ ownerId: computed(() => identity.user?.id ?? null) })
})
