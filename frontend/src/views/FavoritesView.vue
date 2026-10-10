<script setup lang="ts">
import {computed,onMounted} from 'vue'
import RecipeCard from '@/modules/recipe/components/RecipeCard.vue'
import {useRecipeStore} from '@/stores/recipe'
const recipes=useRecipeStore(),pages=computed(()=>Math.max(1,Math.ceil(recipes.favoriteTotal/recipes.pageSize)))
onMounted(()=>{void recipes.loadFavorites()})
function changePage(next:number){if(next>=1&&next<=pages.value)void recipes.loadFavorites(next)}
async function remove(recipe:{id:string}){
 const removed=await recipes.setFavorite(recipe.id,false)
 if(removed&&recipes.favorites.length===0&&recipes.favoritePage>1)await recipes.loadFavorites(recipes.favoritePage-1)
}
</script>
<template>
 <section class="recipe-page" aria-labelledby="favorites-title">
  <div class="page-heading"><div><p class="eyebrow">COOKER · YOUR COLLECTION</p><h1 id="favorites-title">我的收藏</h1><p class="page-intro">收藏的菜谱会保存在你的账号中。</p></div></div>
  <div v-if="recipes.favoritesLoading" class="page-state" role="status">正在加载收藏…</div>
  <div v-else-if="recipes.favoritesError" class="page-state error-state" role="alert"><p>{{recipes.favoritesError}}</p><button class="secondary-button" type="button" @click="recipes.loadFavorites(recipes.favoritePage)">重试</button></div>
  <div v-else-if="recipes.favorites.length===0" class="page-state"><strong>还没有收藏菜谱</strong><p>浏览菜谱并点按爱心，即可在这里找到它们。</p><RouterLink class="secondary-button link-button" :to="{name:'recipes'}">去浏览菜谱</RouterLink></div>
  <template v-else><p class="result-count">共收藏 {{recipes.favoriteTotal}} 道菜谱</p><div class="recipe-grid"><RecipeCard v-for="recipe in recipes.favorites" :key="recipe.id" :recipe="recipe" :show-favorite="true" :busy="recipes.favoriteBusyIds.includes(recipe.id)" @favorite="remove(recipe)"/></div><p v-if="recipes.favoriteError" class="form-error" role="alert">{{recipes.favoriteError}}</p><nav v-if="pages>1" class="pagination" aria-label="收藏分页"><button class="secondary-button" :disabled="recipes.favoritePage<=1" @click="changePage(recipes.favoritePage-1)">上一页</button><span>第 {{recipes.favoritePage}} / {{pages}} 页</span><button class="secondary-button" :disabled="recipes.favoritePage>=pages" @click="changePage(recipes.favoritePage+1)">下一页</button></nav></template>
 </section>
</template>
