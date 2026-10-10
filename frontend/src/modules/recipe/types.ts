export interface RecipeCard {
  id: string
  name: string
  categoryCode: string
  summary: string | null
  difficulty: number | null
  estimatedMinutes: number | null
  isFavorite: boolean
}
export interface IngredientView {
  name: string
  rawLine: string
  amountMin: string | null
  amountMax: string | null
  unitLabel: string | null
  optional: boolean
}
export interface StepView { stepNo: number; content: string }
export interface ToolView { name: string; optional: boolean }
export interface RecipeDetail {
  recipe: RecipeCard
  servings: number | null
  ingredients: IngredientView[]
  steps: StepView[]
  tools: ToolView[]
  additionalContent: string | null
}
export interface RecipePage {
  items: RecipeCard[]
  page: number
  size: number
  total: number
}
export interface FavoriteState { recipeId: string; isFavorite: boolean }
export interface CookingRecordInput {
  recipeId: string
  idempotencyKey: string
  rating: number | null
}
export interface CookingRecord {
  id: string
  recipeId: string
  cookedAt: string
  rating: number | null
}
export type FamiliarityCode = 'FIRST_TRY' | 'FAMILIAR' | 'FREQUENT'
export interface CookedRecipeCard {
  recipe: RecipeCard
  cookCount: number
  lastCookedAt: string
  familiarityCode: FamiliarityCode
  familiarityLabel: '初尝' | '熟悉' | '常做'
  latestRating: number | null
}
export interface CookedRecipePage {
  items: CookedRecipeCard[]
  page: number
  size: number
  total: number
}
