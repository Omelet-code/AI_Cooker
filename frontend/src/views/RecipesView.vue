<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import RecipeCard from '@/modules/recipe/components/RecipeCard.vue'
import {useRecipeStore} from '@/stores/recipe'
const recipes=useRecipeStore(),input=ref(recipes.keyword),pages=computed(()=>Math.max(1,Math.ceil(recipes.total/recipes.pageSize)))
onMounted(()=>{void recipes.loadRecipes()})
function search(){void recipes.loadRecipes(1,input.value)}
function changePage(next:number){if(next>=1&&next<=pages.value)void recipes.loadRecipes(next)}
async function toggleFavorite(recipe:{id:string;isFavorite:boolean}){await recipes.setFavorite(recipe.id,!recipe.isFavorite)}
</script>
<template>
 <section class="recipe-page" aria-labelledby="recipe-page-title">
  <div class="page-heading"><div><p class="eyebrow">COOKER · RECIPE LIBRARY</p><h1 id="recipe-page-title">发现今天的灵感</h1><p class="page-intro">从已收录的菜谱中，找到适合这一餐的选择。</p></div></div>
  <form class="recipe-search" role="search" @submit.prevent="search"><label class="sr-only" for="recipe-keyword">按菜名搜索</label><input id="recipe-keyword" v-model="input" maxlength="100" placeholder="搜索菜名"/><button class="primary-button" type="submit">搜索</button></form>
  <p v-if="recipes.favoriteError" class="form-error" role="alert">{{recipes.favoriteError}}</p>
  <div v-if="recipes.listLoading" class="page-state" role="status">正在加载菜谱…</div>
  <div v-else-if="recipes.listError" class="page-state error-state" role="alert"><p>{{recipes.listError}}</p><button class="secondary-button" type="button" @click="search">重试</button></div>
  <div v-else-if="recipes.list.length===0" class="page-state"><strong>{{recipes.keyword?'没有找到匹配的菜谱':'暂时没有可显示的菜谱'}}</strong><p>{{recipes.keyword?'试试其他菜名。':'菜谱数据准备好后会显示在这里。'}}</p></div>
  <template v-else>
   <p class="result-count">共 {{recipes.total}} 道菜谱</p>
   <div class="recipe-grid"><RecipeCard v-for="recipe in recipes.list" :key="recipe.id" :recipe="recipe" :show-favorite="true" :busy="recipes.favoriteBusyIds.includes(recipe.id)" @favorite="toggleFavorite(recipe)"/></div>
   <nav v-if="pages>1" class="pagination" aria-label="菜谱分页"><button class="secondary-button" :disabled="recipes.page<=1" @click="changePage(recipes.page-1)">上一页</button><span>第 {{recipes.page}} / {{pages}} 页</span><button class="secondary-button" :disabled="recipes.page>=pages" @click="changePage(recipes.page+1)">下一页</button></nav>
  </template>
 </section>
</template>
