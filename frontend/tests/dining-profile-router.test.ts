import { createSSRApp, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterView } from 'vue-router'
import { useIdentityStore } from '@/stores/identity'

const pending = vi.hoisted(() => ({ chat: { pending: null as unknown }, cooking: { pendingCooking: null as unknown } }))
vi.mock('@/modules/conversation/store', () => ({ useConversationStore: () => pending.chat }))
vi.mock('@/stores/cooking', () => ({ useCookingStore: () => pending.cooking }))
vi.mock('vue-router', async original => {
  const actual = await original<typeof import('vue-router')>()
  return { ...actual, createWebHistory: actual.createMemoryHistory }
})

import router from '@/router'

beforeEach(async () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const identity = useIdentityStore()
  identity.user = { id: 'user-a', username: 'cook' }
  identity.status = 'authenticated'
  pending.chat.pending = null
  pending.cooking.pendingCooking = null
  await router.push('/recipes')
})

describe('个人档案受保护路由与导航', () => {
  it('新增档案路由继承登录保护，原模型设置和菜谱路由仍可解析', async () => {
    expect(router.resolve('/settings/profile').name).toBe('profile-settings')
    expect(router.resolve('/settings/profile').meta.requiresAuth).toBe(true)
    expect(router.resolve('/settings/profile/favorites?from=archive#saved').name).toBe('favorites')
    expect(router.resolve('/settings/profile/cooked-recipes?from=archive#history').name).toBe('cooked-recipes')
    expect(router.resolve('/favorites?from=legacy#saved').fullPath).toBe('/favorites?from=legacy#saved')
    expect(router.resolve('/cooked-recipes?from=legacy#history').fullPath).toBe('/cooked-recipes?from=legacy#history')
    expect(router.resolve('/favorites').meta.requiresAuth).toBe(true)
    expect(router.resolve('/cooked-recipes').meta.requiresAuth).toBe(true)
    expect(router.resolve('/chat/90071992547409930?turn=2#history').fullPath).toBe('/chat/90071992547409930?turn=2#history')
    expect(router.resolve('/settings/model').name).toBe('model-settings')
    expect(router.resolve('/recipes').name).toBe('recipes')

    const identity = useIdentityStore()
    identity.user = null
    identity.status = 'anonymous'
    await router.push('/settings/profile')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/settings/profile')
  })

  it('实际布局同时展示个人档案和旧模型设置导航入口', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const identity = useIdentityStore()
    identity.user = { id: 'user-a', username: 'cook' }
    identity.status = 'authenticated'
    await router.push('/settings/profile')
    const app = createSSRApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    const html = await renderToString(app)
    expect(html).toMatch(/href=\"\/settings\/profile\"[^>]*>个人档案<\/a>/)
    expect(html).toMatch(/href=\"\/settings\/model\"[^>]*>模型设置<\/a>/)
  })
})
