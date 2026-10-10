import type { Page } from '../types'

export type MealStatus = 'PENDING' | 'CLARIFICATION' | 'OPTIONS_READY' | 'SELECTED' | 'FAILED'
export type FactsStatus = 'CURRENT' | 'CHANGED' | 'UNAVAILABLE'
export type MealActionType = 'CREATE' | 'REVISE' | 'SELECT' | 'GUIDE'
export type MealActionStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface MealRequirements {
  dishCount: number | null
  soupCount: number | null
  mode: 'RANDOM' | 'SEMANTIC' | null
  query: string | null
  maxCookingMinutes: number | null
  availableToolNames: string[] | null
  excludedIngredientNames: string[]
  availableIngredientNames: string[]
  budgetYuan: string | null
}

export interface MealIngredient {
  recipeIngredientId: string
  ingredientId: string | null
  normalizationStatus: string
  rawName: string
  rawLine: string
  amountMin: string | null
  amountMax: string | null
  unitCode: string | null
  unitLabel: string | null
  optional: boolean
  sortOrder: number
  sourceRecipeId: string
}

export interface MealRecipeFact {
  recipeId: string
  name: string
  categoryCode: string
  role: { role: 'DISH' | 'SOUP' | 'OTHER' | 'UNKNOWN'; basis: string; ruleVersion: string }
  sourceDocumentId: string
  sourceContentHash: string
  servings: number | null
  estimatedMinutes: number | null
  ingredients: MealIngredient[]
  steps: Array<{ stepId: string; stepNo: number; content: string }>
  tools: Array<{ toolId: string; name: string; optional: boolean }>
  additionalContent: string | null
  factFingerprint: string
}

export interface MealMaterial {
  original: MealIngredient
  referenceAmount: string | null
  status: 'REFERENCE_SCALED' | 'ORIGINAL_ONLY' | 'UNKNOWN'
  reason: string | null
  needsConfirmation: boolean
}

export interface MealMaterials {
  targetDiningCount: number
  recipes: Array<{ facts: MealRecipeFact; materials: MealMaterial[] }>
  summary: Array<{
    ingredientId: string
    unitCode: string | null
    unitLabel: string | null
    optional: boolean
    referenceAmount: string | null
    sources: Array<{ recipeId: string; recipeIngredientId: string; referenceAmount: string | null }>
    status: 'REFERENCE_SCALED' | 'ORIGINAL_ONLY' | 'UNKNOWN'
    reason: string | null
  }>
  pending: Array<{ recipeId: string; material: MealMaterial; reason: string }>
  summaryStatus: string
}

export interface MealOption {
  id: string
  ordinal: 1 | 2
  explanation: string
  dishes: MealRecipeFact[]
  soups: MealRecipeFact[]
  materials: MealMaterials
}

export interface MealGuideReference {
  id: string
  schemaVersion: 1
  optionId: string
  version: number
}

export interface MealView {
  schemaVersion: 1
  id: string
  conversationId: string
  version: number
  status: MealStatus
  diningCount: number | null
  diningCountSource: 'EXPLICIT' | 'PROFILE' | null
  requirements: MealRequirements
  answer: string
  options: MealOption[]
  selectedOptionId: string | null
  factsStatus: FactsStatus
  noticeCodes: string[]
  guide: MealGuideReference | null
  guideAvailable: boolean
  createdAt: string
  updatedAt: string
}

export interface MealAction {
  id: string
  mealId: string
  requestId: string
  type: MealActionType
  version: number
  status: MealActionStatus
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  error: { code: string; message: string } | null
  metrics: {
    modelGatewayAttempts: number
    candidateServiceCalls: number
    semanticSearchCalls: number | null
    queryEmbeddingRequests: number | null
    modelUsages: Array<{
      attempt: number
      promptTokens: number | null
      completionTokens: number | null
      totalTokens: number | null
    }>
    durationMs: number
  }
}

export interface MealActionView {
  action: MealAction
  meal: MealView
}

export interface GuideEvidence {
  recipeId: string
  stepNos: number[]
  ingredientIds: string[]
}
export interface GuideBlock { text: string; evidence: GuideEvidence[] }
export interface GuideContent {
  overview: string
  preparation: GuideBlock[]
  recipes: Array<{ recipeId: string; explanations: GuideBlock[] }>
  cookingOrder: GuideBlock[]
  cautions: GuideBlock[]
  optionalSuggestions: Array<{ title: string; text: string; recipeIds: string[] }>
}
export interface MealGuideView {
  schemaVersion: 1
  id: string
  conversationId: string
  mealId: string
  optionId: string
  actionId: string
  version: number
  diningCount: number
  personaVersion: string
  promptVersion: string
  createdAt: string
  factsStatus: FactsStatus
  noticeCodes: string[]
  recipes: MealRecipeFact[]
  materials: MealMaterials | null
  content: GuideContent | null
}

export interface MealInput {
  readonly requestId: string
  readonly content: string
  readonly diningCount?: number | null
}
export interface MealRevisionInput extends MealInput {
  readonly expectedVersion: number
}
export interface MealSelectionInput {
  readonly requestId: string
  readonly expectedVersion: number
  readonly optionId: string
}
export interface GuideInput extends MealSelectionInput {}

export interface KitchenApi {
  listMeals(conversationId: string, page: number, signal?: AbortSignal): Promise<Page<MealView>>
  getMeal(conversationId: string, mealId: string, signal?: AbortSignal): Promise<MealView>
  createMeal(conversationId: string, input: MealInput, signal?: AbortSignal): Promise<MealActionView>
  reviseMeal(conversationId: string, mealId: string, input: MealRevisionInput, signal?: AbortSignal): Promise<MealActionView>
  selectOption(conversationId: string, mealId: string, input: MealSelectionInput, signal?: AbortSignal): Promise<MealActionView>
  getAction(conversationId: string, mealId: string, actionId: string, signal?: AbortSignal): Promise<MealActionView>
  generateGuide(conversationId: string, mealId: string, input: GuideInput, signal?: AbortSignal): Promise<MealActionView>
  getGuide(conversationId: string, mealId: string, guideId: string, signal?: AbortSignal): Promise<MealGuideView>
}

export interface PendingKitchenAction {
  key: string
  ownerId: string
  conversationId: string
  mealId: string | null
  readonly originMealId: string | null
  kind: MealActionType
  requestId: string
  payload: MealInput | MealRevisionInput | MealSelectionInput
  actionId: string | null
  guideId: string | null
  status: 'sending' | 'unknown' | 'running'
  message: string
}
