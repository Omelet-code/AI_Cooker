import {describe,expect,it} from 'vitest'
import {formatIngredientQuantity, formatIngredientSource, hasStructuredIngredientAmount} from '@/modules/recipe/format'
import type {IngredientQuantityInput} from '@/modules/recipe/format'
const ingredient=(overrides:Partial<IngredientQuantityInput>):IngredientQuantityInput=>({rawLine:null,amountMin:null,amountMax:null,unitLabel:null,...overrides})
describe('食材用量格式化',()=>{
 it.each(['香叶：两片','八角：一个','盐：适量'])("保留未数值化的原始说明 %s",rawLine=>{
  expect(formatIngredientQuantity(ingredient({rawLine}))).toBe(rawLine)
 })
 it('原始说明为空或空白时才提示用量未提供',()=>{
  expect(formatIngredientQuantity(ingredient({rawLine:null}))).toBe('用量未提供')
  expect(formatIngredientQuantity(ingredient({rawLine:'  '}))).toBe('用量未提供')
 })
 it('优先保留完整原文，缺失时回退名称并明确提示',()=>{
  expect(formatIngredientSource({rawLine:' 姜 1 小块 ',rawName:'姜'})).toBe('姜 1 小块')
  expect(formatIngredientSource({rawLine:' ',rawName:'酱油 每个生蚝'})).toBe('酱油 每个生蚝')
  expect(formatIngredientSource({rawLine:null,name:'生姜'})).toBe('生姜')
  expect(formatIngredientSource({rawLine:' ',rawName:null,name:' '})).toBe('原文未提供')
 })
 it('只把服务器提供的结构化原量判为可显示数值，不分析原文数字',()=>{
  expect(hasStructuredIngredientAmount({rawLine:'酱油 每个生蚝 1 ml',amountMin:null,amountMax:null})).toBe(false)
  expect(hasStructuredIngredientAmount({rawLine:'调料 每 1 个 1 g',amountMin:null,amountMax:null})).toBe(false)
  expect(hasStructuredIngredientAmount({rawLine:'生姜 1 小块（约 15g）',amountMin:null,amountMax:null})).toBe(false)
  expect(hasStructuredIngredientAmount({rawLine:'姜 1 小块',amountMin:'300',amountMax:'300'})).toBe(true)
 })
 it('用结构化十进制数量及范围格式化，并保留精度',()=>{
  expect(formatIngredientQuantity(ingredient({rawLine:'食用油：0.50 克',amountMin:'0.50',amountMax:null,unitLabel:'克'}))).toBe('0.50 克')
  expect(formatIngredientQuantity(ingredient({rawLine:'盐：1.25-2.50 克',amountMin:'1.25',amountMax:'2.50',unitLabel:'克'}))).toBe('1.25–2.50 克')
  expect(formatIngredientQuantity(ingredient({rawLine:'盐：少许',amountMin:null,amountMax:'0.25',unitLabel:'茶匙'}))).toBe('0.25 茶匙')
 })
})
