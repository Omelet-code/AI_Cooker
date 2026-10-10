import type { GuideInput, MealActionView, MealGuideView, MealOption, MealRecipeFact, MealView } from '@/modules/conversation/kitchen/types'

export const kitchenRequestId = '00000000-0000-4000-8000-000000000001'

export function fact(id = '10'): MealRecipeFact {
  return {
    recipeId: id, name: '虚构菜谱' + id, categoryCode: 'meat_dish',
    role: { role: id === '20' ? 'SOUP' : 'DISH', basis: '测试分类', ruleVersion: 'meal-role-v1' },
    sourceDocumentId: '90071992547409930', sourceContentHash: 'hash-' + id,
    servings: null, estimatedMinutes: 20,
    ingredients: [{
      recipeIngredientId: id + '1', ingredientId: '5', normalizationStatus: 'REVIEWED',
      rawName: '番茄', rawLine: '番茄适量', amountMin: null, amountMax: null,
      unitCode: null, unitLabel: null, optional: false, sortOrder: 1, sourceRecipeId: id,
    }],
    steps: [{ stepId: id + '2', stepNo: 1, content: '按原菜谱操作。' }],
    tools: [{ toolId: id + '3', name: '炒锅', optional: false }],
    additionalContent: '原始注意事项。', factFingerprint: 'fingerprint-' + id,
  }
}

export function option(id = '100', dishId = '10', soupId = '20'): MealOption {
  const dishes = [fact(dishId)], soups = [fact(soupId)]
  const facts = [...dishes, ...soups]
  return {
    id, ordinal: id === '100' ? 1 : 2, explanation: '搭配说明来自服务端。',
    dishes, soups,
    materials: {
      targetDiningCount: 3,
      recipes: facts.map(item => ({
        facts: item,
        materials: [{
          original: item.ingredients[0], referenceAmount: null, status: 'UNKNOWN',
          reason: '原量未知', needsConfirmation: true,
        }],
      })),
      summary: [],
      pending: facts.map(item => ({
        recipeId: item.recipeId,
        material: {
          original: item.ingredients[0], referenceAmount: null, status: 'UNKNOWN',
          reason: '原量未知', needsConfirmation: true,
        },
        reason: '请核对原材料。',
      })),
      summaryStatus: 'PARTIAL',
    },
  }
}

export function meal(overrides: Partial<MealView> = {}): MealView {
  const options = [option(), option('101', '11', '21')]
  return {
    schemaVersion: 1, id: '30', conversationId: '1', version: 1, status: 'OPTIONS_READY',
    diningCount: 3, diningCountSource: 'PROFILE',
    requirements: {
      dishCount: 1, soupCount: 1, mode: 'RANDOM', query: null, maxCookingMinutes: null,
      availableToolNames: null, excludedIngredientNames: [], availableIngredientNames: [], budgetYuan: null,
    },
    answer: '餐单已准备好。', options, selectedOptionId: null, factsStatus: 'CURRENT',
    noticeCodes: ['PREFERENCE_NOT_GUARANTEED'], guide: null, guideAvailable: false,
    createdAt: '2026-10-04T00:00:00Z', updatedAt: '2026-10-04T00:00:00Z',
    ...overrides,
  }
}

export function fourDishMeal(): MealView {
  const options = [option(), option('101', '21', '25')]
  options.forEach((value, index) => {
    value.dishes = (index === 0 ? ['10', '12', '13', '14'] : ['21', '22', '23', '24']).map(id => fact(id))
    value.soups = [fact(index === 0 ? '20' : '25')]
    value.soups[0].role.role = 'SOUP'
    const facts = [...value.dishes, ...value.soups]
    const known = facts[0]
    known.servings = 2
    Object.assign(known.ingredients[0], { amountMin: '2.000', amountMax: '2.000', unitCode: 'g', unitLabel: '克', rawLine: '番茄2克' })
    value.materials.recipes = facts.map(item => ({
      facts: item,
      materials: [{ original: item.ingredients[0], referenceAmount: item === known ? '3.000' : null,
        status: item === known ? 'REFERENCE_SCALED' : 'UNKNOWN', reason: item === known ? null : '原量未知', needsConfirmation: item !== known }],
    }))
    value.materials.summary = [{ ingredientId: '5', unitCode: 'g', unitLabel: '克', optional: false,
      referenceAmount: '3.000', sources: [{ recipeId: known.recipeId, recipeIngredientId: known.ingredients[0].recipeIngredientId, referenceAmount: '3.000' }],
      status: 'REFERENCE_SCALED', reason: null }]
    value.materials.pending = value.materials.recipes.slice(1).map(item => ({
      recipeId: item.facts.recipeId, material: item.materials[0], reason: '请核对原材料。',
    }))
  })
  const result = meal({ options })
  result.requirements.dishCount = 4
  return result
}

export function guide(mealView = meal({ status: 'SELECTED', selectedOptionId: '100' })): MealGuideView {
  const chosen = mealView.options.find(item => item.id === '100') ?? option()
  const recipes = [...chosen.dishes, ...chosen.soups]
  const block = (recipe: MealRecipeFact) => ({
    text: '依据原步骤安排操作。',
    evidence: [{ recipeId: recipe.recipeId, stepNos: [1], ingredientIds: [recipe.ingredients[0].recipeIngredientId] }],
  })
  return {
    schemaVersion: 1, id: '700', conversationId: '1', mealId: '30', optionId: '100', actionId: '501',
    version: 1, diningCount: 3, personaVersion: 'xiaochu-v1', promptVersion: 'meal-guide-v1',
    createdAt: '2026-10-04T00:00:00Z', factsStatus: 'CURRENT', noticeCodes: [],
    recipes,
    materials: chosen.materials,
    content: {
      overview: '按原菜谱准备这一餐。',
      preparation: [block(recipes[0])],
      recipes: recipes.map(recipe => ({ recipeId: recipe.recipeId, explanations: [block(recipe)] })),
      cookingOrder: [block(recipes[0])], cautions: [],
      optionalSuggestions: [{ title: '模型补充：可选建议', text: '可选补充。', recipeIds: [recipes[0].recipeId] }],
    },
  }
}

export function actionView(
  type: 'CREATE' | 'REVISE' | 'SELECT' | 'GUIDE',
  requestId: string,
  mealView: MealView,
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' = 'SUCCEEDED',
): MealActionView {
  return {
    action: {
      id: type === 'GUIDE' ? '501' : '500', mealId: mealView.id, requestId, type, version: mealView.version,
      status, createdAt: '2026-10-04T00:00:00Z', startedAt: null, finishedAt: null,
      error: status === 'FAILED' ? { code: 'MODEL_UNAVAILABLE', message: '本次餐单操作未完成，请确认条件后再试。' } : null,
      metrics: {
        modelGatewayAttempts: type === 'GUIDE' ? 1 : type === 'CREATE' || type === 'REVISE' ? 2 : 0,
        candidateServiceCalls: type === 'CREATE' || type === 'REVISE' ? 1 : 0,
        semanticSearchCalls: 0, queryEmbeddingRequests: 0, modelUsages: [], durationMs: 12,
      },
    },
    meal: mealView,
  }
}

export function guideInput(requestId: string): GuideInput {
  return { requestId, expectedVersion: 1, optionId: '100' }
}
