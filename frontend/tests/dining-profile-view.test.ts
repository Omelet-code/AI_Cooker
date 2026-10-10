import { createSSRApp, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { renderToString } from '@vue/server-renderer'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useIdentityStore } from '@/stores/identity'
import type { DiningProfileState, DiningProfileWrite } from '@/modules/identity/diningProfile/useDiningProfile'

const state = vi.hoisted(() => ({ profile: null as any }))
vi.mock('@/modules/identity/diningProfile/useDiningProfile', () => ({
  useDiningProfile: () => state.profile,
}))
import DiningProfileView from '@/views/DiningProfileView.vue'

function setState(overrides: Record<string, unknown> = {}) {
  state.profile = Object.assign({
    savedCount: ref<number | null>(null),
    countInput: ref(''),
    state: ref<DiningProfileState>('ready'),
    statusError: ref(''),
    actionError: ref(''),
    actionMessage: ref(''),
    validationError: ref(''),
    writeUncertain: ref<DiningProfileWrite | null>(null),
    busyAction: ref<DiningProfileWrite | 'read' | null>(null),
    isBusy: ref(false),
    load: vi.fn(),
    save: vi.fn(),
    clear: vi.fn(),
    clearValidation: vi.fn(),
    ...overrides,
  }, overrides)
}

afterEach(() => vi.clearAllMocks())

async function render() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const identity = useIdentityStore()
  identity.user = { id: 'user-a', username: 'cook' }
  identity.status = 'authenticated'
  const app = createSSRApp(DiningProfileView)
  app.use(pinia)
  return renderToString(app)
}

describe('默认人数档案实际页面模板', () => {
  it('null明确展示未设置、独立清空操作和人数优先规则', async () => {
    setState()
    const html = await render()
    expect(html).toContain('当前已保存：')
    expect(html).toContain('未设置')
    expect(html).toContain('清空默认人数')
    expect(html).toContain('本餐指定人数优先')
    expect(html).toContain('修改也不会影响已有餐单')
  })

  it('加载错误不伪装成未设置', async () => {
    setState({
      state: ref<DiningProfileState>('error'),
      statusError: ref('读取个人档案失败，请稍后重试。'),
    })
    const html = await render()
    expect(html).toContain('读取个人档案失败')
    expect(html).not.toContain('当前已保存：')
  })

  it('未知写入明确展示手动 GET 核对说明', async () => {
    setState({
      writeUncertain: ref<DiningProfileWrite | null>('save'),
      actionError: ref('网络结果尚未确认，请先重新读取档案。'),
    })
    const html = await render()
    expect(html).toContain('服务器可能已处理这次保存')
    expect(html).toContain('页面不会自动重发写入')
    expect(html).toContain('重新读取档案')
  })
})
