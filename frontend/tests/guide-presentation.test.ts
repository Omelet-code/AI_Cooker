import { describe, expect, it } from 'vitest'
import { projectGuide } from '@/modules/conversation/kitchen/components/guidePresentation'
import { guide, meal } from './kitchen-fixtures'

describe('指导展示投影', () => {
  it('按菜谱聚合事实和单菜补充，跨菜安排保持整餐区，末尾引用只按证据去重', () => {
    const view = guide(meal({ status: 'SELECTED', selectedOptionId: '100' }))
    const content = view.content!
    content.preparation[0].evidence = [{ recipeId: '10', stepNos: [1], ingredientIds: ['101'] }]
    content.cookingOrder[0].evidence = [{ recipeId: '10', stepNos: [1], ingredientIds: [] }]
    content.cautions = [{ text: '整餐提醒。', evidence: [{ recipeId: '10', stepNos: [], ingredientIds: [] }] }]
    content.recipes = [
      { recipeId: '10', explanations: [{ text: '菜谱10讲解。', evidence: [{ recipeId: '10', stepNos: [1], ingredientIds: [] }] }] },
      { recipeId: '20', explanations: [{ text: '菜谱20讲解。', evidence: [] }] },
    ]
    content.optionalSuggestions = [
      { title: '可选建议', text: '仅适用于菜谱10。', recipeIds: ['10'] },
      { title: '跨菜建议', text: '涉及两道菜。', recipeIds: ['10', '20'] },
    ]

    const projection = projectGuide(view)
    expect(projection.recipes.map(item => item.fact.name)).toEqual(['虚构菜谱10', '虚构菜谱20'])
    expect(projection.recipes[0].explanations[0].text).toBe('菜谱10讲解。')
    expect(projection.recipes[0].suggestions.map(item => item.text)).toEqual(['仅适用于菜谱10。'])
    expect(projection.recipes[1].suggestions).toEqual([])
    expect(projection.preparation[0].text).toBe('依据原步骤安排操作。')
    expect(projection.cookingOrder[0].text).toBe('依据原步骤安排操作。')
    expect(projection.cautions[0].text).toBe('整餐提醒。')
    expect(projection.sharedSuggestions.map(item => item.text)).toEqual(['涉及两道菜。'])
    expect(projection.references.map(item => item.recipeId)).toEqual(['10'])
  })
})
