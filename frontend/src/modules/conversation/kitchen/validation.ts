import { ApiError } from '@/shared/api/types'
import { isResourceId, parsePage } from '../validation'
import type {
  GuideBlock, GuideContent, MealActionView, MealGuideView, MealMaterials, MealRecipeFact, MealView,
} from './types'
import type { Page } from '../types'

type Dict = Record<string, unknown>

function invalid(): never {
  throw new ApiError(200, 'INVALID_RESPONSE', '餐单或指导响应格式异常，请重新读取核对。')
}
function record(value: unknown): Dict {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  return value as Dict
}
function str(value: unknown, allowEmpty = false): value is string {
  return typeof value === 'string' && (allowEmpty || value.trim().length > 0)
}
function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}
function integer(value: unknown, min = 0): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min
}
function nullableInteger(value: unknown): value is number | null {
  return value === null || integer(value)
}
function strings(value: unknown, max = 80): value is string[] {
  return Array.isArray(value) && value.every(item => str(item) && Array.from(item).length <= max)
}
function oneOf<T extends string>(value: unknown, values: readonly T[]): value is T {
  return typeof value === 'string' && values.includes(value as T)
}
function decimalOrNull(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value))
}
function uuid(value: unknown): value is string {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
}

function parseIngredient(value: unknown): MealRecipeFact['ingredients'][number] {
  const v = record(value)
  if (!isResourceId(v.recipeIngredientId) || !(v.ingredientId === null || isResourceId(v.ingredientId))
    || !str(v.normalizationStatus, true) || !str(v.rawName, true) || !str(v.rawLine, true)
    || !decimalOrNull(v.amountMin) || !decimalOrNull(v.amountMax)
    || !nullableString(v.unitCode) || !nullableString(v.unitLabel) || typeof v.optional !== 'boolean'
    || !integer(v.sortOrder) || !isResourceId(v.sourceRecipeId)) return invalid()
  return value as MealRecipeFact['ingredients'][number]
}

export function parseMealRecipeFact(value: unknown): MealRecipeFact {
  const v = record(value), role = record(v.role)
  if (!isResourceId(v.recipeId) || !str(v.name) || !str(v.categoryCode, true)
    || !oneOf(role.role, ['DISH', 'SOUP', 'OTHER', 'UNKNOWN'] as const)
    || !str(role.basis, true) || !str(role.ruleVersion, true)
    || !isResourceId(v.sourceDocumentId) || !str(v.sourceContentHash, true)
    || !nullableInteger(v.servings) || !nullableInteger(v.estimatedMinutes)
    || !Array.isArray(v.ingredients) || !Array.isArray(v.steps) || !Array.isArray(v.tools)
    || !nullableString(v.additionalContent) || !str(v.factFingerprint)) return invalid()
  for (const item of v.ingredients) parseIngredient(item)
  for (const raw of v.steps) {
    const step = record(raw)
    if (!isResourceId(step.stepId) || !integer(step.stepNo, 1) || !str(step.content, true)) return invalid()
  }
  for (const raw of v.tools) {
    const tool = record(raw)
    if (!isResourceId(tool.toolId) || !str(tool.name, true) || typeof tool.optional !== 'boolean') return invalid()
  }
  return value as MealRecipeFact
}

function parseMaterials(value: unknown): MealMaterials {
  const v = record(value)
  if (!integer(v.targetDiningCount, 1) || !Array.isArray(v.recipes) || !Array.isArray(v.summary)
    || !Array.isArray(v.pending) || !str(v.summaryStatus, true)) return invalid()
  for (const raw of v.recipes) {
    const recipe = record(raw)
    parseMealRecipeFact(recipe.facts)
    if (!Array.isArray(recipe.materials)) return invalid()
    for (const materialRaw of recipe.materials) {
      const material = record(materialRaw)
      parseIngredient(material.original)
      if (!decimalOrNull(material.referenceAmount)
        || !oneOf(material.status, ['REFERENCE_SCALED', 'ORIGINAL_ONLY', 'UNKNOWN'] as const)
        || !nullableString(material.reason) || typeof material.needsConfirmation !== 'boolean') return invalid()
    }
  }
  for (const raw of v.summary) {
    const item = record(raw)
    if (!(item.ingredientId === null || isResourceId(item.ingredientId))
      || !nullableString(item.unitCode) || !nullableString(item.unitLabel) || typeof item.optional !== 'boolean'
      || !decimalOrNull(item.referenceAmount) || !Array.isArray(item.sources)
      || !oneOf(item.status, ['REFERENCE_SCALED', 'ORIGINAL_ONLY', 'UNKNOWN'] as const)
      || !nullableString(item.reason)) return invalid()
    for (const sourceRaw of item.sources) {
      const source = record(sourceRaw)
      if (!isResourceId(source.recipeId) || !isResourceId(source.recipeIngredientId)
        || !decimalOrNull(source.referenceAmount)) return invalid()
    }
  }
  for (const raw of v.pending) {
    const pending = record(raw)
    if (!isResourceId(pending.recipeId) || !str(pending.reason)) return invalid()
    const material = record(pending.material)
    parseIngredient(material.original)
    if (!decimalOrNull(material.referenceAmount)
      || !oneOf(material.status, ['REFERENCE_SCALED', 'ORIGINAL_ONLY', 'UNKNOWN'] as const)
      || !nullableString(material.reason) || typeof material.needsConfirmation !== 'boolean') return invalid()
  }
  return value as MealMaterials
}

function parseRequirements(value: unknown) {
  const v = record(value)
  if (!nullableInteger(v.dishCount) || !nullableInteger(v.soupCount)
    || !(v.mode === null || oneOf(v.mode, ['RANDOM', 'SEMANTIC'] as const))
    || !nullableString(v.query) || !nullableInteger(v.maxCookingMinutes)
    || !(v.availableToolNames === null || strings(v.availableToolNames))
    || !strings(v.excludedIngredientNames) || !strings(v.availableIngredientNames)
    || !decimalOrNull(v.budgetYuan)) return invalid()
  return value as MealView['requirements']
}

export function parseMealView(value: unknown, conversationId?: string, expectedMealId?: string): MealView {
  const v = record(value)
  if (v.schemaVersion !== 1 || !isResourceId(v.id) || !isResourceId(v.conversationId)
    || (conversationId !== undefined && v.conversationId !== conversationId)
    || (expectedMealId !== undefined && v.id !== expectedMealId)
    || !integer(v.version, 1) || !oneOf(v.status, ['PENDING', 'CLARIFICATION', 'OPTIONS_READY', 'SELECTED', 'FAILED'] as const)
    || !nullableInteger(v.diningCount) || !(v.diningCountSource === null || oneOf(v.diningCountSource, ['EXPLICIT', 'PROFILE'] as const))
    || !Array.isArray(v.noticeCodes) || !v.noticeCodes.every(code => str(code))
    || !oneOf(v.factsStatus, ['CURRENT', 'CHANGED', 'UNAVAILABLE'] as const)
    || !Array.isArray(v.options) || !nullableString(v.selectedOptionId)
    || !(v.guide === null || typeof v.guide === 'object')
    || typeof v.guideAvailable !== 'boolean' || !str(v.answer, true)
    || !str(v.createdAt) || !str(v.updatedAt)) return invalid()
  parseRequirements(v.requirements)
  const optionIds = new Set<string>()
  for (const raw of v.options) {
    const option = record(raw)
    if (!isResourceId(option.id) || optionIds.has(option.id) || !integer(option.ordinal, 1) || ![1, 2].includes(option.ordinal)
      || !str(option.explanation) || !Array.isArray(option.dishes) || !Array.isArray(option.soups)) return invalid()
    optionIds.add(option.id)
    const optionRecipeIds = new Set<string>()
    for (const fact of [...option.dishes, ...option.soups].map(parseMealRecipeFact)) {
      if (optionRecipeIds.has(fact.recipeId)) return invalid()
      optionRecipeIds.add(fact.recipeId)
    }
    const materials = parseMaterials(option.materials)
    if (materials.recipes.length !== optionRecipeIds.size
      || materials.recipes.some(item => !optionRecipeIds.has(item.facts.recipeId))) return invalid()
  }
  if (v.selectedOptionId !== null && !optionIds.has(v.selectedOptionId)) return invalid()
  if (v.guide !== null) {
    const guide = record(v.guide)
    if (!isResourceId(guide.id) || guide.schemaVersion !== 1 || !isResourceId(guide.optionId)
      || !integer(guide.version, 1) || guide.version !== v.version || !optionIds.has(guide.optionId)
      || guide.optionId !== v.selectedOptionId || v.status !== 'SELECTED') return invalid()
  }
  if ((v.diningCount === null) !== (v.diningCountSource === null)) return invalid()
  if (v.guideAvailable !== (v.guide !== null)) return invalid()
  if (v.factsStatus === 'UNAVAILABLE' && (v.options.length !== 0 || v.selectedOptionId !== null
    || v.guide !== null || v.guideAvailable)) return invalid()
  return value as MealView
}

export function parseGuideForMeal(value: unknown, meal: MealView): MealGuideView {
  const guide = parseMealGuide(value, meal.conversationId, meal.id, meal.guide?.id)
  if (guide.version !== meal.version || guide.optionId !== meal.selectedOptionId) return invalid()
  const option = meal.options.find(item => item.id === guide.optionId)
  if (guide.factsStatus !== 'UNAVAILABLE') {
    if (!option) return invalid()
    const expected = [...option.dishes, ...option.soups].map(item => item.recipeId).sort()
    const actual = guide.recipes.map(item => item.recipeId).sort()
    if (expected.length !== actual.length || expected.some((id, index) => id !== actual[index])) return invalid()
  }
  return guide
}

function parseAction(value: unknown, conversationId: string): MealActionView {
  const v = record(value), a = record(v.action)
  const meal = parseMealView(v.meal, conversationId)
  if (!isResourceId(a.id) || !isResourceId(a.mealId) || a.mealId !== meal.id
    || !uuid(a.requestId) || !oneOf(a.type, ['CREATE', 'REVISE', 'SELECT', 'GUIDE'] as const)
    || !integer(a.version, 1) || !oneOf(a.status, ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'] as const)
    || !str(a.createdAt) || !nullableString(a.startedAt) || !nullableString(a.finishedAt)) return invalid()
  if (a.error !== null) {
    const error = record(a.error)
    if (!str(error.code) || !str(error.message)) return invalid()
  }
  if ((a.status === 'FAILED') !== (a.error !== null)) return invalid()
  const metrics = record(a.metrics)
  if (!integer(metrics.modelGatewayAttempts) || !integer(metrics.candidateServiceCalls)
    || !nullableInteger(metrics.semanticSearchCalls)
    || !(metrics.queryEmbeddingRequests === null || integer(metrics.queryEmbeddingRequests))
    || !integer(metrics.durationMs) || !Array.isArray(metrics.modelUsages)) return invalid()
  for (const raw of metrics.modelUsages) {
    const usage = record(raw)
    if (!integer(usage.attempt, 1) || !nullableInteger(usage.promptTokens)
      || !nullableInteger(usage.completionTokens) || !nullableInteger(usage.totalTokens)) return invalid()
  }
  return value as MealActionView
}

export function parseMealAction(value: unknown, conversationId: string, mealId?: string): MealActionView {
  const action = parseAction(value, conversationId)
  if (mealId !== undefined && action.meal.id !== mealId) return invalid()
  return action
}

function parseBlock(value: unknown, recipes: Map<string, MealRecipeFact>): GuideBlock {
  const v = record(value)
  if (!str(v.text) || !Array.isArray(v.evidence) || v.evidence.length < 1 || v.evidence.length > 10) return invalid()
  for (const raw of v.evidence) {
    const evidence = record(raw), recipeId = evidence.recipeId
    if (!isResourceId(recipeId) || !recipes.has(recipeId) || !Array.isArray(evidence.stepNos)
      || !evidence.stepNos.every(step => integer(step, 1)) || !Array.isArray(evidence.ingredientIds)
      || !evidence.ingredientIds.every(isResourceId)
      || (!evidence.stepNos.length && !evidence.ingredientIds.length)) return invalid()
    const recipe = recipes.get(recipeId)!
    if (evidence.stepNos.some(step => !recipe.steps.some(item => item.stepNo === step))
      || evidence.ingredientIds.some(id => !recipe.ingredients.some(item => item.recipeIngredientId === id))) return invalid()
  }
  return value as GuideBlock
}

function parseGuideContent(value: unknown, facts: MealRecipeFact[]): GuideContent {
  const v = record(value), byId = new Map(facts.map(fact => [fact.recipeId, fact]))
  if (!str(v.overview) || !Array.isArray(v.preparation) || !Array.isArray(v.recipes)
    || !Array.isArray(v.cookingOrder) || !Array.isArray(v.cautions) || !Array.isArray(v.optionalSuggestions)) return invalid()
  v.preparation.forEach(item => parseBlock(item, byId))
  v.cookingOrder.forEach(item => parseBlock(item, byId))
  v.cautions.forEach(item => parseBlock(item, byId))
  const mentioned = new Set<string>()
  for (const raw of v.recipes) {
    const item = record(raw)
    if (!isResourceId(item.recipeId) || !byId.has(item.recipeId) || mentioned.has(item.recipeId)
      || !Array.isArray(item.explanations) || item.explanations.length < 1) return invalid()
    mentioned.add(item.recipeId)
    item.explanations.forEach(block => parseBlock(block, byId))
  }
  if (mentioned.size !== byId.size || [...byId.keys()].some(id => !mentioned.has(id))) return invalid()
  for (const raw of v.optionalSuggestions) {
    const item = record(raw)
    if (item.title !== '模型补充：可选建议' || !str(item.text) || !Array.isArray(item.recipeIds)
      || item.recipeIds.length < 1 || !item.recipeIds.every(id => isResourceId(id) && byId.has(id))) return invalid()
  }
  return value as GuideContent
}

export function parseMealGuide(value: unknown, conversationId: string, mealId: string, referenceId?: string): MealGuideView {
  const v = record(value)
  if (v.schemaVersion !== 1 || !isResourceId(v.id) || (referenceId !== undefined && v.id !== referenceId)
    || v.conversationId !== conversationId || v.mealId !== mealId || !isResourceId(v.optionId)
    || !isResourceId(v.actionId) || !integer(v.version, 1) || !integer(v.diningCount, 1)
    || !str(v.personaVersion) || !str(v.promptVersion) || !str(v.createdAt)
    || !oneOf(v.factsStatus, ['CURRENT', 'CHANGED', 'UNAVAILABLE'] as const)
    || !Array.isArray(v.noticeCodes) || !v.noticeCodes.every(code => str(code))
    || !Array.isArray(v.recipes)) return invalid()
  const facts = v.recipes.map(parseMealRecipeFact)
  if (facts.some(fact => !v.optionId || !isResourceId(fact.recipeId))) return invalid()
  if (new Set(facts.map(fact => fact.recipeId)).size !== facts.length) return invalid()
  if (v.materials !== null) parseMaterials(v.materials)
  if (v.factsStatus === 'UNAVAILABLE') {
    if (v.content !== null || facts.length !== 0 || v.materials !== null) return invalid()
  } else if (v.content === null) return invalid()
  else parseGuideContent(v.content, facts)
  return value as MealGuideView
}

export function parseMealPage(value: unknown, conversationId: string): Page<MealView> {
  return parsePage(value, item => parseMealView(item, conversationId))
}
