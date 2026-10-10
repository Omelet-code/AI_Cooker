<script setup lang="ts">
import type { MealOption } from '../types'

defineProps<{
  option: MealOption
  selected: boolean
  disabled: boolean
  conversationId: string
  mealId: string
  version: number
}>()
const emit = defineEmits<{ select: [optionId: string] }>()
</script>

<template>
  <article class="meal-option" :aria-label="'餐单方案 ' + option.ordinal">
    <header class="option-heading">
      <h3>方案 {{ option.ordinal }}</h3>
      <span v-if="selected" class="selected-label">本餐已选</span>
    </header>
    <p class="option-explanation">{{ option.explanation }}</p>
    <section v-for="group in [
      { label: '菜', recipes: option.dishes },
      { label: '汤', recipes: option.soups },
    ]" :key="group.label" class="option-group">
      <h4>{{ group.label }}（{{ group.recipes.length }}）</h4>
      <ul class="option-recipes">
        <li v-for="recipe in group.recipes" :key="recipe.recipeId">
          <RouterLink :to="{ name: 'recipe-detail', params: { id: recipe.recipeId } }">{{ recipe.name }}</RouterLink>
          <span>单菜时间：{{ recipe.estimatedMinutes === null ? '未知' : recipe.estimatedMinutes + ' 分钟' }}</span>
        </li>
      </ul>
    </section>
    <p v-if="option.materials.summaryStatus !== 'COMPLETE' || option.materials.pending.length" class="materials-note">
      部分材料数量或单位需要查看并确认；未知值会继续标为未知。
    </p>
    <RouterLink class="materials-link" :to="{ name: 'kitchen-materials', params: { conversationId, mealId }, query: { version: String(version), optionId: option.id } }">查看材料清单</RouterLink>
    <button v-if="!selected" class="primary-button" type="button" :disabled="disabled" @click="emit('select', option.id)">
      {{ disabled ? '正在处理…' : '选这套并生成指导' }}
    </button>
  </article>
</template>

<style scoped>
.meal-option{min-width:0;padding:19px;border:1px solid #dfe6d7;border-radius:12px;background:#fff}.option-heading{display:flex;align-items:center;justify-content:space-between;gap:10px}.option-heading h3{margin:0;color:#344932;font-size:17px}.selected-label{padding:4px 9px;border-radius:99px;background:#e8f0df;color:#4b693f;font-size:11px;font-weight:700}.option-explanation{color:#687762;font-size:13px;line-height:1.7}.option-group{margin-top:15px}.option-group h4{margin:0 0 8px;color:#4b5d46;font-size:13px}.option-recipes{display:grid;gap:7px;padding-left:19px;color:#64705f;font-size:12px}.option-recipes li{display:flex;justify-content:space-between;gap:10px;min-width:0}.option-recipes a{color:#486843;overflow-wrap:anywhere}.option-recipes span{color:#818a7c;font-size:11px;white-space:nowrap}.materials-note{color:#7b7054;font-size:11px;line-height:1.6}.materials-link{display:inline-flex;margin:8px 0;padding:8px 11px;border:1px solid #d9e2d1;border-radius:7px;color:#486843;font-size:12px;text-decoration:none}.materials-link:hover{background:#f3f7ee}.primary-button{display:block;min-height:39px;margin-top:6px;padding:0 15px}
@media(max-width:450px){.option-recipes li{align-items:flex-start;flex-direction:column;gap:3px}}
</style>
