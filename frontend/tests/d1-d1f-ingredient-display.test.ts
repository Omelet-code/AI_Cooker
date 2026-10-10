import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it, vi } from 'vitest'
import MealGuidePanel from '@/modules/conversation/kitchen/components/MealGuidePanel.vue'
import ShoppingLines from '@/modules/shopping/components/ShoppingLines.vue'
import KitchenMaterialsView from '@/views/KitchenMaterialsView.vue'
import type { PreviewLine } from '@/modules/shopping/types'
import { meal, guide } from './kitchen-fixtures'
import { createPinia, setActivePinia } from 'pinia'
import { useIdentityStore } from '@/stores/identity'

const materialPage = vi.hoisted(() => ({
  state: { status: { value: 'ready' }, error: { value: '' }, snapshot: { value: null as unknown }, load: vi.fn(), clear: vi.fn() },
  route: { params: { conversationId: '1', mealId: '30' }, query: { version: '1', optionId: '100' } },
}))
vi.mock('@/modules/conversation/kitchen/materialsState', () => ({ createKitchenMaterialsState: () => materialPage.state }))
vi.mock('vue-router', async original => ({ ...(await original<typeof import('vue-router')>()), useRoute: () => materialPage.route }))

async function render(component: Parameters<typeof createSSRApp>[0]) {
  const app = createSSRApp(component)
  app.component('RouterLink', {
    props: ['to'],
    setup(_props, { slots }) { return () => h('a', { href: '#' }, slots.default?.()) },
  })
  return renderToString(app)
}

const rawLine: PreviewLine = {
  lineKey: 'line-1', recipeId: '10', recipeIngredientId: '101', ingredientId: null,
  name: '酱油 每个生蚝', rawLine: '酱油 每个生蚝 1 ml', optional: false,
  originalAmountMin: null, originalAmountMax: null, unitCode: null, unitLabel: null,
  referenceAmount: null, scaleStatus: 'UNKNOWN', reason: '比例用量不可直接作为本餐总量',
  needsConfirmation: true, availability: null, confirmedAmount: null, confirmedUnitCode: null,
}

describe('D1-D1F原文用料展示', () => {
  it('指导原菜谱事实保留原文，不重复未知参考数量列', async () => {
    const savedGuide = guide()
    const original = savedGuide.recipes[0]!.ingredients[0]!
    Object.assign(original, { rawName: '酱油 每个生蚝', rawLine: rawLine.rawLine })
    const material = savedGuide.materials.recipes[0]!.materials[0]!
    Object.assign(material, { referenceAmount: null, status: 'UNKNOWN', reason: rawLine.reason, needsConfirmation: true })

    const html = await render(h(MealGuidePanel, { guide: savedGuide, state: 'ready', error: '' }))

    expect(html.match(/酱油 每个生蚝 1 ml/g)).toHaveLength(1)
    expect(html).toContain('按原文准备；采购总量待确认')
    expect(html).not.toContain('数量未知')
    expect(html).toContain('比例用量不可直接作为本餐总量')
  })

  it('采购确认原文项隐藏重复未知数量，保留选择和确认输入', async () => {
    const html = await render(h(ShoppingLines, {
      lines: [rawLine], choices: { 'line-1': 'NEED_BUY' }, amounts: { 'line-1': '' }, units: { 'line-1': '' },
    }))

    expect(html.match(/酱油 每个生蚝 1 ml/g)).toHaveLength(1)
    expect(html).toContain('按原文准备；采购总量待确认')
    expect(html).not.toContain('原菜谱：未知')
    expect(html).not.toContain('本餐参考：未知')
    expect(html).toContain('我已有足量')
    expect(html).toContain('需要购买')
    expect(html).toContain('确认用量')
    expect(html).toContain('可留空表示未知')
  })

  it('已知原量不随人数未知而隐藏', async () => {
    const known = { ...rawLine, name: '猪肉', rawLine: '猪肉 300g', originalAmountMin: '300', originalAmountMax: '300', unitCode: 'g', unitLabel: '克', reason: '人数未知' }
    const html = await render(h(ShoppingLines, {
      lines: [known], choices: {}, amounts: {}, units: {},
    }))

    expect(html).toContain('原菜谱：300')
    expect(html).toContain('克')
    expect(html).toContain('本餐参考：未知 克')
    expect(html).toContain('人数未知')
    expect(html).not.toContain('按原文准备；采购总量待确认')
  })

  it('材料详情避免重复未知列，并保留已知原量和人数原因', async () => {
    const mealView = meal()
    const option = mealView.options[0]!
    const first = option.materials.recipes[0]!
    Object.assign(first.facts.ingredients[0]!, { rawName: '姜 1 小块', rawLine: '姜 1 小块', amountMin: null, amountMax: null, unitCode: null, unitLabel: null })
    Object.assign(first.materials[0]!, { referenceAmount: null, status: 'UNKNOWN', reason: '原量未知', needsConfirmation: true })
    option.materials.pending = [{ recipeId: first.facts.recipeId, material: first.materials[0]!, reason: '待确认' }]
    const second = option.materials.recipes[1]!
    Object.assign(second.facts.ingredients[0]!, { rawName: '猪肉', rawLine: '猪肉 300g', amountMin: '300', amountMax: '300', unitCode: 'g', unitLabel: '克' })
    Object.assign(second.materials[0]!, { referenceAmount: null, status: 'UNKNOWN', reason: '人数未知', needsConfirmation: true })

    materialPage.state.snapshot.value = { meal: mealView, option }
    materialPage.state.status.value = 'ready'
    const pinia = createPinia()
    setActivePinia(pinia)
    const identity = useIdentityStore()
    identity.user = { id: 'account-a', username: 'cook' }
    identity.status = 'authenticated'
    const app = createSSRApp(KitchenMaterialsView)
    app.use(pinia)
    app.component('RouterLink', { props: ['to'], setup(_props, { slots }) { return () => h('a', { href: '#' }, slots.default?.()) } })
    const html = await renderToString(app)

    expect(html.match(/>姜 1 小块/g)).toHaveLength(1)
    expect(html).toContain('按原文准备；采购总量待确认')
    expect(html).not.toContain('原量 未知')
    expect(html).toContain('原量 300')
    expect(html).toContain('本餐参考 未知')
    expect(html).toContain('人数未知')
    expect(materialPage.state.load).toHaveBeenCalledOnce()
  })
})
