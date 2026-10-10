import type { ProductMatchView, ShoppingListView, ShoppingPreview } from '@/modules/shopping/types'
export const source = { conversationId: '1', mealId: '20', expectedVersion: 3, optionId: '30' }
export const preview: ShoppingPreview = {
  schemaVersion: 1, source: { conversationId: '1', mealId: '20', version: 3, optionId: '30', diningCount: 4, budgetYuan: '30.00', sourceFingerprint: 'a'.repeat(64) },
  lines: [{ lineKey: '10:100', recipeId: '10', recipeIngredientId: '100', ingredientId: '9', name: '番茄', rawLine: '番茄 100g', optional: false, originalAmountMin: '100', originalAmountMax: '100', unitCode: 'G', unitLabel: '克', referenceAmount: '200', scaleStatus: 'REFERENCE_SCALED', reason: null, needsConfirmation: false, availability: 'NEED_BUY', confirmedAmount: null, confirmedUnitCode: null }],
}
export const list: ShoppingListView = {
  schemaVersion: 1, id: '55', createdAt: '2026-10-06T12:00:00Z', factsStatus: 'CURRENT', source: preview.source,
  lines: [{ ...preview.lines[0]!, availability: 'NEED_BUY' }],
  items: [{ itemKey: '9:G', lineKeys: ['10:100'], ingredientId: '9', name: '番茄', unitCode: 'G', unitLabel: '克', optional: false, requiredAmount: '200', shortageAmount: '200', status: 'NEED_BUY', reason: null }],
  status: 'READY_FOR_MATCHING', matchingStatus: 'NOT_MATCHED', costs: { currency: 'CNY', estimatedDemandCost: null, consumptionCost: null, purchaseOutlay: '0.00', knownSubtotal: '0.00', unknownItemCount: 1, estimatedKnownSubtotal: null, estimatedUnknownItemCount: 1, budgetStatus: 'UNKNOWN' },
}
export const match: ProductMatchView = {
  schemaVersion: 1, id: '66', listId: '55', sourceFingerprint: 'a'.repeat(64), factsStatus: 'CURRENT', provider: 'MOCK', realData: false, catalogVersion: 'mock-v1', cityCode: '110000', capturedAt: '2026-10-06T12:01:00Z', matchingStatus: 'MATCHED',
  lines: [{ itemKey: '9:G', lineKeys: ['10:100'], ingredientId: '9', name: '番茄', requiredAmount: '200', shortageAmount: '200', unitCode: 'G', status: 'MATCHED', reason: null, product: { productId: 'mock-1', ingredientId: '9', name: '模拟番茄', packageAmount: '500', packageUnit: 'G', packagePrice: '10.00', currency: 'CNY', purchaseUrl: null, referenceUnitPrice: '0.02', referenceUnit: 'G' }, packageCount: '1', consumptionCost: '4.00', purchaseOutlay: '10.00', estimatedDemandCost: null, estimateSource: null }],
  purchaseGroups: [{ groupKey: 'g1', itemKeys: ['9:G'], quantity: '200', unitCode: 'G', product: { productId: 'mock-1', ingredientId: '9', name: '模拟番茄', packageAmount: '500', packageUnit: 'G', packagePrice: '10.00', currency: 'CNY', purchaseUrl: null, referenceUnitPrice: '0.02', referenceUnit: 'G' }, packageCount: '1', purchaseOutlay: '10.00' }],
  costs: { currency: 'CNY', estimatedDemandCost: null, consumptionCost: '4.00', purchaseOutlay: '10.00', knownSubtotal: '4.00', unknownItemCount: 0, estimatedKnownSubtotal: null, estimatedUnknownItemCount: 1, budgetStatus: 'WITHIN_BUDGET' }, message: '模拟结果',
}
