export interface IngredientQuantityInput {
  rawLine: string | null | undefined
  amountMin: string | null
  amountMax: string | null
  unitLabel: string | null
}

export interface IngredientSourceInput {
  rawLine?: string | null
  rawName?: string | null
  name?: string | null
}

export interface StructuredIngredientAmountInput {
  amountMin?: string | null
  amountMax?: string | null
  originalAmountMin?: string | null
  originalAmountMax?: string | null
}

/** 仅以服务端结构化数值判断原量是否明确，不从原文提取数字或单位。 */
export function hasStructuredIngredientAmount(ingredient:StructuredIngredientAmountInput):boolean {
  return Boolean(ingredient.amountMin?.trim()||ingredient.amountMax?.trim()
    ||ingredient.originalAmountMin?.trim()||ingredient.originalAmountMax?.trim())
}

/** 原文优先；原文缺失时依次回退来源名和展示名。 */
export function formatIngredientSource(ingredient:IngredientSourceInput):string {
  return ingredient.rawLine?.trim()||ingredient.rawName?.trim()||ingredient.name?.trim()||'原文未提供'
}

/** 数值化用量优先；没有数值时保留来源原文，不把可读说明误报成未知。 */
export function formatIngredientQuantity(ingredient:IngredientQuantityInput):string {
  const minimum=ingredient.amountMin?.trim()||null
  const maximum=ingredient.amountMax?.trim()||null
  const unit=ingredient.unitLabel?.trim()||''
  if(minimum!==null||maximum!==null){
    const amount=minimum===maximum||maximum===null?minimum:minimum===null?maximum:`${minimum}–${maximum}`
    return `${amount}${unit?` ${unit}`:''}`
  }
  const original=ingredient.rawLine?.trim()
  return original||'用量未提供'
}
