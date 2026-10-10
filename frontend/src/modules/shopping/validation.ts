import { ApiError } from '@/shared/api/types'
import type { FactsStatus, PreviewLine, ProductLine, ProductMatchView, ShoppingCosts, ShoppingListView, ShoppingPreview, ShoppingSource } from './types'

const bad = (): never => { throw new ApiError(200, 'INVALID_RESPONSE', '采购服务返回的数据格式不正确。') }
const obj = (v: unknown): Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : bad()
const str = (v: unknown, nullable = false): string | null => typeof v === 'string' && v.length > 0 ? v : nullable && v === null ? null : bad()
const positiveId = (v: unknown, nullable = false): string | null => { const value = str(v, nullable); return value === null || /^[1-9]\d*$/.test(value) ? value : bad() }
const dec = (v: unknown, nullable = false): string | null => {
  const s = str(v, nullable)
  return s === null || /^(0|[1-9]\d*)(\.\d+)?$/.test(s) ? s : bad()
}
const integer = (v: unknown, min = 0): number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min ? v : bad()
const bool = (v: unknown): boolean => typeof v === 'boolean' ? v : bad()
const list = <T>(v: unknown, parse: (v: unknown) => T): T[] => Array.isArray(v) ? v.map(parse) : bad()
const facts = (v: unknown): FactsStatus => v === 'CURRENT' || v === 'CHANGED' || v === 'UNAVAILABLE' ? v : bad()
const unit = (v: unknown): string | null => v === null ? null : ['G', 'KG', 'ML', 'L', 'COUNT', 'CUSTOM'].includes(String(v)) ? v as string : bad()
const budget = (v: unknown): string => ['NOT_SET', 'UNKNOWN', 'OVER_BUDGET', 'WITHIN_BUDGET'].includes(String(v)) ? v as string : bad()
const listStatus = (v: unknown): string => ['DRAFT', 'READY_FOR_MATCHING'].includes(String(v)) ? v as string : bad()
const matchingStatus = (v: unknown): string => ['NOT_MATCHED', 'NOT_NEEDED', 'NO_MATCH', 'PARTIAL', 'MATCHED'].includes(String(v)) ? v as string : bad()
const itemStatus = (v: unknown): string => ['HAVE_ENOUGH', 'NEED_BUY', 'QUANTITY_PENDING', 'MATCHED', 'OUT_OF_STOCK', 'NO_PRODUCT', 'QUOTE_UNKNOWN', 'UNIT_UNSUPPORTED'].includes(String(v)) ? v as string : bad()

function source(value: unknown): ShoppingSource {
  const v = obj(value)
  return {
    conversationId: positiveId(v.conversationId)!, mealId: positiveId(v.mealId)!, version: integer(v.version, 1), optionId: positiveId(v.optionId)!,
    diningCount: integer(v.diningCount, 1), budgetYuan: dec(v.budgetYuan, true), sourceFingerprint: typeof v.sourceFingerprint === 'string' && /^[0-9a-f]{64}$/.test(v.sourceFingerprint) ? v.sourceFingerprint : bad(),
  }
}
function line(value: unknown): PreviewLine {
  const v = obj(value)
  const availability = v.availability === null ? null : v.availability === 'HAVE_ENOUGH' || v.availability === 'NEED_BUY' ? v.availability : bad()
  const scaleStatus = v.scaleStatus === 'REFERENCE_SCALED' || v.scaleStatus === 'ORIGINAL_ONLY' || v.scaleStatus === 'UNKNOWN' ? v.scaleStatus : bad()
  return {
    lineKey: typeof v.lineKey === 'string' && /^[1-9]\d*:[1-9]\d*$/.test(v.lineKey) ? v.lineKey : bad(), recipeId: positiveId(v.recipeId)!, recipeIngredientId: positiveId(v.recipeIngredientId)!, ingredientId: positiveId(v.ingredientId, true), name: str(v.name)!, rawLine: str(v.rawLine)!,
    optional: bool(v.optional), originalAmountMin: dec(v.originalAmountMin, true), originalAmountMax: dec(v.originalAmountMax, true), unitCode: unit(v.unitCode), unitLabel: str(v.unitLabel, true),
    referenceAmount: dec(v.referenceAmount, true), scaleStatus, reason: str(v.reason, true), needsConfirmation: bool(v.needsConfirmation), availability,
    confirmedAmount: dec(v.confirmedAmount, true), confirmedUnitCode: unit(v.confirmedUnitCode),
  }
}
function costs(value: unknown, estimated = false): ShoppingCosts {
  const v = obj(value)
  return {
    currency: str(v.currency)!, estimatedDemandCost: dec(v.estimatedDemandCost, true), consumptionCost: dec(v.consumptionCost, true), purchaseOutlay: dec(v.purchaseOutlay, true),
    knownSubtotal: dec(v.knownSubtotal)!, unknownItemCount: integer(v.unknownItemCount),
    ...(estimated ? { estimatedKnownSubtotal: dec(v.estimatedKnownSubtotal, true), estimatedUnknownItemCount: integer(v.estimatedUnknownItemCount) } : {}),
    budgetStatus: budget(v.budgetStatus),
  }
}
export function parseShoppingPreview(value: unknown): ShoppingPreview {
  const v = obj(value)
  if (integer(v.schemaVersion, 1) !== 1) return bad()
  return { schemaVersion: 1, source: source(v.source), lines: list(v.lines, line) }
}
export function parseShoppingList(value: unknown): ShoppingListView {
  const v = obj(value)
  if (integer(v.schemaVersion, 1) !== 1) return bad()
  return {
    schemaVersion: 1, id: positiveId(v.id)!, createdAt: typeof v.createdAt === 'string' && Number.isFinite(Date.parse(v.createdAt)) ? v.createdAt : bad(), factsStatus: facts(v.factsStatus), source: source(v.source), lines: list(v.lines, line),
    items: list(v.items, raw => { const i = obj(raw); return { itemKey: str(i.itemKey)!, lineKeys: list(i.lineKeys, x => str(x)!), ingredientId: positiveId(i.ingredientId, true), name: str(i.name)!, unitCode: unit(i.unitCode), unitLabel: str(i.unitLabel, true), optional: bool(i.optional), requiredAmount: dec(i.requiredAmount, true), shortageAmount: dec(i.shortageAmount, true), status: itemStatus(i.status), reason: str(i.reason, true) } }),
    status: listStatus(v.status), matchingStatus: matchingStatus(v.matchingStatus), costs: costs(v.costs),
  }
}
function productLine(value: unknown): ProductLine {
  const v = obj(value); let product: ProductLine['product'] = null
  if (v.product !== null) { const p = obj(v.product); product = { productId: str(p.productId)!, ingredientId: str(p.ingredientId, true), name: str(p.name)!, packageAmount: dec(p.packageAmount, true), packageUnit: unit(p.packageUnit), packagePrice: dec(p.packagePrice, true), currency: str(p.currency, true), purchaseUrl: str(p.purchaseUrl, true), referenceUnitPrice: dec(p.referenceUnitPrice, true), referenceUnit: unit(p.referenceUnit) } }
  return { itemKey: str(v.itemKey)!, lineKeys: list(v.lineKeys, x => str(x)!), ingredientId: positiveId(v.ingredientId, true), name: str(v.name)!, requiredAmount: dec(v.requiredAmount, true), shortageAmount: dec(v.shortageAmount, true), unitCode: unit(v.unitCode), status: itemStatus(v.status), reason: str(v.reason, true), product, packageCount: dec(v.packageCount, true), consumptionCost: dec(v.consumptionCost, true), purchaseOutlay: dec(v.purchaseOutlay, true), estimatedDemandCost: dec(v.estimatedDemandCost, true), estimateSource: str(v.estimateSource, true) }
}
export function parseProductMatch(value: unknown, expectedListId: string): ProductMatchView {
  if (value === null) return bad()
  const v = obj(value)
  if (integer(v.schemaVersion, 1) !== 1 || positiveId(v.listId) !== expectedListId || v.provider !== 'MOCK') return bad()
  const matchCosts = costs(v.costs, true)
  return {
    schemaVersion: 1, id: positiveId(v.id)!, listId: expectedListId, sourceFingerprint: typeof v.sourceFingerprint === 'string' && /^[0-9a-f]{64}$/.test(v.sourceFingerprint) ? v.sourceFingerprint : bad(), factsStatus: facts(v.factsStatus), provider: 'MOCK', realData: bool(v.realData),
    catalogVersion: str(v.catalogVersion)!, cityCode: typeof v.cityCode === 'string' && /^\d{6}$/.test(v.cityCode) ? v.cityCode : bad(), capturedAt: typeof v.capturedAt === 'string' && Number.isFinite(Date.parse(v.capturedAt)) ? v.capturedAt : bad(), matchingStatus: matchingStatus(v.matchingStatus)!,
    lines: list(v.lines, productLine), purchaseGroups: list(v.purchaseGroups, raw => { const g = obj(raw); const p = g.product === null ? null : productLine({ itemKey:'group',lineKeys:[],ingredientId:null,name:'group',requiredAmount:null,shortageAmount:null,unitCode:null,status:'MATCHED',reason:null,product:g.product,packageCount:null,consumptionCost:null,purchaseOutlay:null,estimatedDemandCost:null,estimateSource:null }).product; return { groupKey: str(g.groupKey)!, itemKeys: list(g.itemKeys, x => str(x)!), quantity: dec(g.quantity, true), unitCode: unit(g.unitCode), product: p, packageCount: dec(g.packageCount, true), purchaseOutlay: dec(g.purchaseOutlay, true) } }),
    costs: { ...matchCosts, estimatedKnownSubtotal: matchCosts.estimatedKnownSubtotal ?? null, estimatedUnknownItemCount: matchCosts.estimatedUnknownItemCount ?? 0 }, message: str(v.message, true),
  }
}
export function parseLatestMatch(value: unknown, expectedListId: string): ProductMatchView | null { return value === null ? null : parseProductMatch(value, expectedListId) }
export function isSourceRoute(value: unknown): value is { conversationId: string; mealId: string; expectedVersion: number; optionId: string } {
  if (value === null || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  return typeof v.conversationId === 'string' && /^[1-9]\d*$/.test(v.conversationId) && typeof v.mealId === 'string' && /^[1-9]\d*$/.test(v.mealId)
    && typeof v.expectedVersion === 'number' && Number.isSafeInteger(v.expectedVersion) && v.expectedVersion > 0
    && typeof v.optionId === 'string' && /^[1-9]\d*$/.test(v.optionId)
}
export function isDecimalInput(v: string): boolean {
  if (!/^(0|[1-9]\d{0,9})(\.\d{1,8})?$/.test(v) || /^0(?:\.0+)?$/.test(v)) return false
  const [whole = '', fraction = ''] = v.split('.')
  if (whole.length > 10 || (whole.length === 10 && whole > '1000000000')) return false
  return whole !== '1000000000' || /^0*$/.test(fraction)
}
export function allowedUnits(lineValue: PreviewLine): string[] {
  const unitCode = lineValue.unitCode
  if (unitCode === 'G' || unitCode === 'KG') return ['G', 'KG']
  if (unitCode === 'ML' || unitCode === 'L') return ['ML', 'L']
  if (unitCode) return [unitCode]
  return ['G', 'KG', 'ML', 'L']
}
export function validatePreviewAgainstRoute(preview: ShoppingPreview, route: { conversationId: string; mealId: string; expectedVersion: number; optionId: string }): void {
  if (preview.source.conversationId !== route.conversationId || preview.source.mealId !== route.mealId || preview.source.version !== route.expectedVersion || preview.source.optionId !== route.optionId) bad()
}
