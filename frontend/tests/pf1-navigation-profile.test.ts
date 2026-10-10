import { createSSRApp, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterView } from 'vue-router'
import { useIdentityStore } from '@/stores/identity'

const pending = vi.hoisted(() => ({
  chat: { pending: null as unknown },
  cooking: { pendingCooking: null as unknown },
}))

vi.mock('@/modules/conversation/store', () => ({ useConversationStore: () => pending.chat }))
vi.mock('@/stores/cooking', () => ({ useCookingStore: () => pending.cooking }))
vi.mock('vue-router', async original => {
  const actual = await original<typeof import('vue-router')>()
  return { ...actual, createWebHistory: actual.createMemoryHistory }
})

import router from '@/router'

beforeEach(async () => {
  setActivePinia(createPinia())
  const identity = useIdentityStore()
  identity.user = { id: 'user-a', username: 'cook' }
  identity.status = 'authenticated'
  pending.chat.pending = null
  pending.cooking.pendingCooking = null
  await router.push('/recipes')
})

describe('PF1 导航与个人档案层级', () => {
  it('个人档案子页及旧收藏/历史链接保留名称、query、hash与登录守卫', async () => {
    expect(router.resolve('/settings/profile').name).toBe('profile-settings')
    expect(router.resolve('/settings/profile/favorites?from=archive#saved').name).toBe('favorites')
    expect(router.resolve('/settings/profile/cooked-recipes?from=archive#history').name).toBe('cooked-recipes')
    expect(router.resolve('/favorites?from=legacy#saved').fullPath).toBe('/favorites?from=legacy#saved')
    expect(router.resolve('/cooked-recipes?from=legacy#history').fullPath).toBe('/cooked-recipes?from=legacy#history')
    expect(router.resolve('/chat/90071992547409930?turn=2#history').fullPath).toBe('/chat/90071992547409930?turn=2#history')
    for (const path of ['/settings/profile', '/settings/profile/favorites', '/favorites', '/cooked-recipes']) {
      expect(router.resolve(path).meta.requiresAuth).toBe(true)
    }

    const identity = useIdentityStore()
    identity.user = null
    identity.status = 'anonymous'
    await router.push('/favorites?from=legacy#saved')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/favorites?from=legacy#saved')
  })

  it('档案布局提供三个子页，顶栏只有一个默认进入厨房的助手入口', async () => {
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
    expect(html).toContain('个人档案导航')
    expect(html).toContain('用餐设置')
    expect(html).toContain('我的收藏')
    expect(html).toContain('做菜历史')
    expect(html).toMatch(/<a[^>]*href="\/kitchen"[^>]*>烹饪助手<\/a>/)
    expect(html).not.toMatch(/<a[^>]*href="\/chat"[^>]*>烹饪助手<\/a>/)
    expect(html.match(/>烹饪助手<\/a>/g)).toHaveLength(1)
    expect(html).toMatch(/<a[^>]*href="\/settings\/model"[^>]*>模型设置<\/a>/)
  })

  it('旧聊天待核对横幅仍返回原会话', async () => {
    pending.chat.pending = { conversationId: '90071992547409930' }
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
    expect(html).toMatch(/核对本次发送<\/a>/)
    expect(html).toContain('/chat/90071992547409930')
  })
})
