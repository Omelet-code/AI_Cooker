import { computed, createSSRApp, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { renderToString } from '@vue/server-renderer'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useIdentityStore } from '@/stores/identity'
import { useShoppingStore } from '@/modules/shopping/store'
import { ApiError } from '@/shared/api/types'
import { watchShoppingListCreation } from '@/modules/shopping/navigation'
import ShoppingView from '@/views/ShoppingView.vue'
import { list, match, preview, source } from './shopping-fixtures'

const api = vi.hoisted(() => ({ preview: vi.fn(), create: vi.fn(), getList: vi.fn(), latestMatch: vi.fn(), getMatch: vi.fn(), createMatch: vi.fn() }))
vi.mock('@/modules/shopping/api', () => ({ shoppingApi: api }))
vi.mock('vue-router', async original => {
  const actual = await original<typeof import('vue-router')>()
  return { ...actual, createWebHistory: actual.createMemoryHistory }
})
import appRouter from '@/router'

async function renderRoute(path: string, pinia = createPinia()) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/shopping/new', name: 'shopping-new', component: ShoppingView }, { path: '/shopping/:listId', name: 'shopping-list', component: ShoppingView }, { path: '/shopping', name: 'shopping-home', component: ShoppingView },
  ] })
  await router.push(path); await router.isReady(); setActivePinia(pinia)
  const identity = useIdentityStore(); identity.user = { id: 'user-a', username: 'cook' }; identity.status = 'authenticated'
  const app = createSSRApp({ render: () => h(RouterView) }); app.use(pinia); app.use(router)
  const html = await renderToString(app)
  for (let index = 0; index < 12; index++) await Promise.resolve()
  return { html, router, pinia }
}

describe('F3-D 实际Vue Router与页面watch激活链', () => {
  beforeEach(() => {
    vi.clearAllMocks(); setActivePinia(createPinia())
    const identity = useIdentityStore(); identity.user = { id: 'user-a', username: 'cook' }; identity.status = 'authenticated'
    api.preview.mockResolvedValue(preview); api.create.mockResolvedValue(list); api.getList.mockResolvedValue(list); api.latestMatch.mockResolvedValue(null); api.getMatch.mockResolvedValue(match); api.createMatch.mockResolvedValue(match)
  })
  it('静态/new路由优先且受登录保护，实际页面watch读取服务器预览', async () => {
    expect(appRouter.resolve('/shopping/new').name).toBe('shopping-new')
    expect(appRouter.resolve('/shopping/new').meta.requiresAuth).toBe(true)
    const { html, pinia } = await renderRoute(`/shopping/new?conversationId=${source.conversationId}&mealId=${source.mealId}&expectedVersion=${source.expectedVersion}&optionId=${source.optionId}`)
    expect(api.preview).toHaveBeenCalledOnce(); expect(api.create).not.toHaveBeenCalled()
    setActivePinia(pinia); expect(useShoppingStore().preview?.source.mealId).toBe('20')
    expect(html).toContain('正在读取服务器原料预览')
  })
  it('刷新已知清单通过实际页面watch只读指定报价，不触发POST', async () => {
    const { router, pinia } = await renderRoute('/shopping/55?matchId=66')
    expect(router.currentRoute.value.name).toBe('shopping-list')
    expect(api.getList).toHaveBeenCalledWith('55', expect.any(AbortSignal)); expect(api.getMatch).toHaveBeenCalledWith('55', '66', expect.any(AbortSignal))
    expect(api.create).not.toHaveBeenCalled(); expect(api.createMatch).not.toHaveBeenCalled()
    setActivePinia(pinia); expect(useShoppingStore().list?.id).toBe('55'); expect(useShoppingStore().match?.provider).toBe('MOCK')
  })
  it('成功清单页展示清单固定链接和当前报价matchId固定链接', async () => {
    const { router, pinia } = await renderRoute('/shopping/55?matchId=66')
    setActivePinia(pinia)
    const state = useShoppingStore(); state.list = list; state.match = match; state.busy = false
    vi.spyOn(state, 'loadList').mockResolvedValue()
    const app = createSSRApp({ render: () => h(RouterView) }); app.use(pinia); app.use(router)
    const html = await renderToString(app)
    expect(html).toContain('href="/shopping/55"')
    expect(html).toContain('href="/shopping/55?matchId=66"')
  })
  it('等待中的实际采购页面提供停止入口，离开后旧成功不再发布', async () => {
    let resolveMatch!: (value: typeof match) => void
    let signal!: AbortSignal
    const delayed = new Promise<typeof match>(resolve => { resolveMatch = resolve })
    const { router, pinia } = await renderRoute('/shopping/55?matchId=66')
    setActivePinia(pinia)
    const state = useShoppingStore(); state.list = list; state.match = match; state.busy = false
    vi.spyOn(state, 'loadList').mockResolvedValue()
    api.createMatch.mockImplementation((_id: string, _payload: unknown, requestSignal: AbortSignal) => { signal = requestSignal; return delayed })
    const writing = state.createMatch()
    const app = createSSRApp({ render: () => h(RouterView) }); app.use(pinia); app.use(router)
    const html = await renderToString(app)
    expect(html).toContain('停止本地等待')
    const operation = state.pendingOperation
    expect(state.writeWaiting).toBe(true)
    vi.stubGlobal('window', { confirm: () => true })
    await router.push('/shopping')
    expect(router.currentRoute.value.fullPath).toBe('/shopping')
    expect(state.writeWaiting).toBe(false)
    resolveMatch(match); await writing
    expect(signal.aborted).toBe(true)
    expect(state.pendingOperation).toBe(operation)
    const homeApp = createSSRApp({ render: () => h(RouterView) }); homeApp.use(pinia); homeApp.use(router)
    await renderToString(homeApp)
    expect(state.scope?.kind).toBe('home')
  })
  it('成功创建先结束busy/待确认，再由清单路由激活只读回看', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    const identity = useIdentityStore(); identity.user = { id: 'user-a', username: 'cook' }; identity.status = 'authenticated'
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/shopping/new', name: 'shopping-new', component: ShoppingView }, { path: '/shopping/:listId', name: 'shopping-list', component: ShoppingView },
    ] })
    await router.push('/shopping/new?conversationId=1&mealId=20&expectedVersion=3&optionId=30')
    const state = useShoppingStore(); state.activate({ kind: 'new', ownerId: 'user-a', source })
    await state.loadPreview(source)
    watchShoppingListCreation(router, state, computed(() => source))
    expect(state.canCreate()).toBe(true)
    const id = await state.createList()
    expect(state.busy).toBe(false); expect(state.pendingOperation).toBeNull(); expect(id).toBe('55')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('shopping-list'))
    expect(router.currentRoute.value.params.listId).toBe('55')
    const app = createSSRApp({ render: () => h(RouterView) }); app.use(pinia); app.use(router)
    await renderToString(app); for (let index = 0; index < 12; index++) await Promise.resolve()
    expect(api.create).toHaveBeenCalledOnce(); expect(api.getList).toHaveBeenCalled()
    expect(api.createMatch).not.toHaveBeenCalled()
  })

  it('原报价未知后经实际页面返回链接保留matchId并只在确认后同键重放', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    const identity = useIdentityStore(); identity.user = { id: 'user-a', username: 'cook' }; identity.status = 'authenticated'
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/shopping', name: 'shopping-home', component: ShoppingView },
      { path: '/shopping/:listId', name: 'shopping-list', component: ShoppingView },
    ] })
    api.getList.mockResolvedValue(list); api.getMatch.mockResolvedValue(match)
    api.createMatch.mockRejectedValueOnce(new ApiError(0, 'REQUEST_TIMEOUT', '未知')).mockResolvedValueOnce(match)
    await router.push('/shopping/55?matchId=66'); await router.isReady()
    const app = createSSRApp({ render: () => h(RouterView) }); app.use(pinia); app.use(router)
    const renderPage = () => renderToString(app)
    await renderPage(); for (let i = 0; i < 12; i++) await Promise.resolve()
    const state = useShoppingStore()
    await state.createMatch()
    const html = await renderPage()
    const href = /href="([^"]+)"[^>]*>返回原操作上下文/.exec(html)?.[1]
    expect(href).toBe('/shopping/55?matchId=66')
    const requestId = state.pendingOperation!.payload.requestId
    await router.push('/shopping'); await renderPage()
    await router.push(href!); await renderPage(); for (let i = 0; i < 12; i++) await Promise.resolve()
    expect(state.scope?.matchId).toBe('66')
    vi.stubGlobal('window', { confirm: () => true })
    await state.retryPending()
    expect(api.createMatch).toHaveBeenCalledTimes(2)
    expect(api.createMatch.mock.calls[1]?.[0]).toBe('55')
    expect(api.createMatch.mock.calls[1]?.[1]).toEqual({ requestId, cityCode: '110000' })
  })
})
