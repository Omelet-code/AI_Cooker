import { describe, expect, it } from 'vitest'
import { allowedUnits, isDecimalInput, parseLatestMatch, parseProductMatch, parseShoppingList, parseShoppingPreview, validatePreviewAgainstRoute } from '@/modules/shopping/validation'
import { list, match, preview, source } from './shopping-fixtures'

describe('F3-D 严格响应与用户输入校验', () => {
  it('保留普通十进制字符串与未知金额，不转成JS数值', () => {
    expect(parseShoppingPreview(preview).lines[0]?.referenceAmount).toBe('200')
    expect(parseShoppingList(list).costs.estimatedDemandCost).toBeNull()
    expect(parseProductMatch(match, '55').costs.purchaseOutlay).toBe('10.00')
    expect(parseLatestMatch(null, '55')).toBeNull()
  })
  it('校验来源归属、报价清单ID和金额字段类型', () => {
    expect(() => validatePreviewAgainstRoute(preview, { ...source, mealId: '21' })).toThrow()
    expect(() => parseProductMatch(match, '56')).toThrow()
    expect(() => parseShoppingList({ ...list, costs: { ...list.costs, purchaseOutlay: 0 } })).toThrow()
  })
  it('金额输入使用十进制文本并遵循单位维度', () => {
    expect(isDecimalInput('0.00000001')).toBe(true)
    expect(isDecimalInput('1000000000')).toBe(true)
    expect(isDecimalInput('1000000000.00000001')).toBe(false)
    expect(isDecimalInput('1000000001')).toBe(false)
    expect(isDecimalInput('0')).toBe(false)
    expect(isDecimalInput('1e3')).toBe(false)
    expect(isDecimalInput('1.123456789')).toBe(false)
    expect(allowedUnits(preview.lines[0]!)).toEqual(['G', 'KG'])
    expect(allowedUnits({ ...preview.lines[0]!, unitCode: null })).toEqual(['G', 'KG', 'ML', 'L'])
  })
  it('不把包装支出、商品URL或Mock来源补造成真实信息', () => {
    const value = parseProductMatch(match, '55')
    expect(value.provider).toBe('MOCK'); expect(value.realData).toBe(false)
    expect(value.lines[0]?.product?.purchaseUrl).toBeNull()
    expect(value.costs.purchaseOutlay).toBe('10.00')
  })
})
