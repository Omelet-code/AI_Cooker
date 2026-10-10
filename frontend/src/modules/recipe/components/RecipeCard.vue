<script setup lang="ts">
import type {RecipeCard} from '@/modules/recipe/types'
defineProps<{recipe:RecipeCard;busy?:boolean;showFavorite?:boolean}>()
const emit=defineEmits<{favorite:[recipe:RecipeCard]}>()
function categoryLabel(code:string){return code.replaceAll('_',' ').toLowerCase()}
</script>
<template>
 <article class="recipe-card">
  <div class="recipe-card-top"><span class="category-label">{{categoryLabel(recipe.categoryCode)}}</span><button v-if="showFavorite" class="favorite-button" type="button" :disabled="busy" :aria-label="recipe.isFavorite?'取消收藏':'加入收藏'" @click="emit('favorite',recipe)">{{recipe.isFavorite?'♥':'♡'}}</button></div>
  <RouterLink class="recipe-title-link" :to="{name:'recipe-detail',params:{id:recipe.id}}"><h2>{{recipe.name}}</h2></RouterLink>
  <p class="recipe-summary">{{recipe.summary??'暂无简介'}}</p>
  <div class="recipe-meta"><span>难度：{{recipe.difficulty===null?'未知':recipe.difficulty}}</span><span>时间：{{recipe.estimatedMinutes===null?'未知':`${recipe.estimatedMinutes} 分钟`}}</span></div>
 </article>
</template>
