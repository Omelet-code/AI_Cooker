import { createSSRApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { renderToString } from '@vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterView } from 'vue-router'
import { useIdentityStore } from '@/stores/identity'
import { meal } from './kitchen-fixtures'

const api = vi.hoisted(() => ({ getMeal: vi.fn() }))
vi.mock('@/modules/conversation/kitchen/api', () => ({ kitchenApi: { getMeal: api.getMeal } }))
vi.mock('vue-router', async original => {
  const actual = await original<typeof import('vue-router')>()
  return { ...actual, createWebHistory: actual.createMemoryHistory }
})
import router from '@/router'

let pinia: ReturnType<typeof createPinia>
beforeEach(async () => {
  pinia = createPinia()
  setActivePinia(pinia)
  const identity = useIdentityStore()
  identity.user = { id: 'account-a', username: 'cook' }
  identity.status = 'authenticated'
  api.getMeal.mockReset().mockResolvedValue(meal())
  await router.push('/recipes')
})

describe('材料详情路由与只读页面', () => {
  it('刷新式深链只调用既有GET并保留原餐单返回上下文', async () => {
    await router.push('/kitchen/1/meals/30/materials?version=1&optionId=100')
    expect(router.currentRoute.value.name).toBe('kitchen-materials')
    expect(router.currentRoute.value.meta.requiresAuth).toBe(true)
    const app = createSSRApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    await nextTick()
    const html = await renderToString(app)
    expect(api.getMeal).toHaveBeenCalledTimes(1)
    expect(api.getMeal).toHaveBeenCalledWith('1', '30', expect.any(AbortSignal))
    expect(html).toContain('正在读取指定版本的材料')
    expect(html).toContain('返回原餐单')
    expect(html).toContain('mealVersion=1')
    expect(html).toContain('optionId=100')
  })

  it('无效版本深链提示回历史，且不请求餐单或产生写操作', async () => {
    await router.push('/kitchen/1/meals/30/materials?version=1.5&optionId=100')
    const app = createSSRApp({ render: () => h(RouterView) })
    app.use(pinia)
    app.use(router)
    const html = await renderToString(app)
    expect(api.getMeal).not.toHaveBeenCalled()
    expect(html).toContain('链接参数不合法')
    expect(Object.keys(api)).toEqual(['getMeal'])
  })

})
