import type { GuideBlock, MealGuideView, MealMaterial, MealRecipeFact } from '../types'

export interface GuideRecipeProjection {
  fact: MealRecipeFact
  explanations: GuideBlock[]
  suggestions: Array<{ title: string; text: string; recipeIds: string[] }>
  materials: MealMaterial[]
}

export interface GuideProjection {
  preparation: GuideBlock[]
  recipes: GuideRecipeProjection[]
  cookingOrder: GuideBlock[]
  cautions: GuideBlock[]
  sharedSuggestions: Array<{ title: string; text: string; recipeIds: string[] }>
  references: MealRecipeFact[]
}

/** 只把结构化 evidence 作为菜谱引用来源；模型补充的 recipeIds 只用于提示适用范围。 */
export function projectGuide(guide: MealGuideView): GuideProjection {
  const content = guide.content
  if (!content || guide.factsStatus === 'UNAVAILABLE') {
    return { preparation: [], recipes: [], cookingOrder: [], cautions: [], sharedSuggestions: [], references: [] }
  }

  const factsById = new Map(guide.recipes.map(fact => [fact.recipeId, fact]))
  const explanationsById = new Map(content.recipes.map(item => [item.recipeId, item.explanations]))
  const suggestions = content.optionalSuggestions
  const recipes = guide.recipes.map(fact => {
    const materials = guide.materials?.recipes.find(item => item.facts.recipeId === fact.recipeId)?.materials ?? []
    return {
      fact,
      explanations: explanationsById.get(fact.recipeId) ?? [],
      suggestions: suggestions.filter(item => item.recipeIds.length === 1 && item.recipeIds[0] === fact.recipeId),
      materials,
    }
  })

  const references = new Set<string>()
  const collect = (blocks: GuideBlock[]) => {
    for (const block of blocks) {
      for (const evidence of block.evidence) {
        if (factsById.has(evidence.recipeId)) references.add(evidence.recipeId)
      }
    }
  }
  collect(content.preparation)
  content.recipes.forEach(item => collect(item.explanations))
  collect(content.cookingOrder)
  collect(content.cautions)

  return {
    preparation: content.preparation,
    recipes,
    cookingOrder: content.cookingOrder,
    cautions: content.cautions,
    sharedSuggestions: suggestions.filter(item => item.recipeIds.length !== 1),
    references: [...references].map(id => factsById.get(id)!).filter(Boolean),
  }
}
