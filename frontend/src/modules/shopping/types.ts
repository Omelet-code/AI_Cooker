export type FactsStatus = 'CURRENT' | 'CHANGED' | 'UNAVAILABLE'
export type Availability = 'HAVE_ENOUGH' | 'NEED_BUY'

export interface ShoppingSource {
  conversationId: string
  mealId: string
  version: number
  optionId: string
  diningCount: number
  budgetYuan: string | null
  sourceFingerprint: string
}

export interface PreviewLine {
  lineKey: string
  recipeId: string
  recipeIngredientId: string
  ingredientId: string | null
  name: string
  rawLine: string
  optional: boolean
  originalAmountMin: string | null
  originalAmountMax: string | null
  unitCode: string | null
  unitLabel: string | null
  referenceAmount: string | null
  scaleStatus: 'REFERENCE_SCALED' | 'ORIGINAL_ONLY' | 'UNKNOWN'
  reason: string | null
  needsConfirmation: boolean
  availability: Availability | null
  confirmedAmount: string | null
  confirmedUnitCode: string | null
}

export interface ShoppingPreview {
  schemaVersion: number
  source: ShoppingSource
  lines: PreviewLine[]
}

export interface ShoppingCosts {
  currency: string
  estimatedDemandCost: string | null
  consumptionCost: string | null
  purchaseOutlay: string | null
  knownSubtotal: string
  unknownItemCount: number
  estimatedKnownSubtotal?: string | null
  estimatedUnknownItemCount?: number
  budgetStatus: string
}

export interface ShoppingListView {
  schemaVersion: number
  id: string
  createdAt: string
  factsStatus: FactsStatus
  source: ShoppingSource
  lines: PreviewLine[]
  items: Array<{ itemKey: string; lineKeys: string[]; ingredientId: string | null; name: string; unitCode: string | null; unitLabel: string | null; optional: boolean; requiredAmount: string | null; shortageAmount: string | null; status: string; reason: string | null }>
  status: string
  matchingStatus: string
  costs: ShoppingCosts
}

export interface ProductLine {
  itemKey: string
  lineKeys: string[]
  ingredientId: string | null
  name: string
  requiredAmount: string | null
  shortageAmount: string | null
  unitCode: string | null
  status: string
  reason: string | null
  product: null | { productId: string; ingredientId: string | null; name: string; packageAmount: string | null; packageUnit: string | null; packagePrice: string | null; currency: string | null; purchaseUrl: string | null; referenceUnitPrice: string | null; referenceUnit: string | null }
  packageCount: string | null
  consumptionCost: string | null
  purchaseOutlay: string | null
  estimatedDemandCost: string | null
  estimateSource: string | null
}

export interface ProductMatchView {
  schemaVersion: number
  id: string
  listId: string
  sourceFingerprint: string
  factsStatus: FactsStatus
  provider: string
  realData: boolean
  catalogVersion: string
  cityCode: string
  capturedAt: string
  matchingStatus: string
  lines: ProductLine[]
  purchaseGroups: Array<{ groupKey: string; itemKeys: string[]; quantity: string | null; unitCode: string | null; product: ProductLine['product']; packageCount: string | null; purchaseOutlay: string | null }>
  costs: ShoppingCosts & { estimatedKnownSubtotal: string | null; estimatedUnknownItemCount: number }
  message: string | null
}

export interface Confirmation {
  lineKey: string
  availability: Availability
  confirmedAmount: string | null
  confirmedUnitCode: string | null
}

export interface ShoppingCreateRequest {
  requestId: string
  conversationId: string
  mealId: string
  expectedVersion: number
  optionId: string
  sourceFingerprint: string
  confirmations: readonly Confirmation[]
}

export interface ProductMatchRequest { requestId: string; cityCode: string }

export interface ShoppingSourceRoute {
  conversationId: string
  mealId: string
  expectedVersion: number
  optionId: string
}

export type ShoppingSourceQuery = { [key: string]: string | number; conversationId: string; mealId: string; expectedVersion: number; optionId: string }

export type PendingShoppingRoute =
  | { name: 'shopping-new'; query: ShoppingSourceQuery }
  | { name: 'shopping-list'; params: { listId: string }; query?: { matchId: string } }

export interface PendingShoppingOperation {
  kind: 'CREATE_LIST' | 'MATCH_PRODUCTS'
  ownerId: string
  contextKey: string
  path: string
  payload: ShoppingCreateRequest | ProductMatchRequest
  listId: string | null
  originalRoute: PendingShoppingRoute
  summary: string
}
