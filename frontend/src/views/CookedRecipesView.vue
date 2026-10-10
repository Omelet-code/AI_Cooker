<script setup lang="ts">
import {computed,onMounted} from 'vue'
import {useCookingStore} from '@/stores/cooking'
const cooking=useCookingStore()
const lastPage=computed(()=>Math.max(1,Math.ceil(cooking.total/cooking.pageSize)))
function load(page:number){void cooking.loadCookedRecipes(page)}
function displayDate(value:string){
 const date=new Date(value)
 return Number.isNaN(date.getTime())?value:new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'short'}).format(date)
}
onMounted(()=>{if(!cooking.loaded)load(1)})
</script>
<template>
 <section class="recipe-page cooked-history-page" aria-labelledby="history-title">
  <header class="page-heading"><div><p class="eyebrow">COOKING HISTORY</p><h1 id="history-title">做菜历史</h1><p class="page-intro">记录来自服务器。完成烹饪不会自动扣减库存。</p></div><p v-if="cooking.loaded" class="result-count">共 {{cooking.total}} 道</p></header>
  <div v-if="cooking.loading&&!cooking.loaded" class="page-state" role="status">正在读取做菜历史…</div>
  <div v-else-if="cooking.errorMessage&&(!cooking.loaded||cooking.items.length===0)" class="page-state error-state" role="alert"><strong>暂时无法读取做菜历史</strong><p>{{cooking.errorMessage}}</p><button class="secondary-button" type="button" @click="load(cooking.page)">重试</button></div>
  <div v-else-if="cooking.items.length===0" class="page-state"><strong>还没有做菜记录</strong><p>完成一道菜后，记录会显示在这里。</p></div>
  <template v-else>
   <p v-if="cooking.errorMessage" class="form-error" role="alert">{{cooking.errorMessage}}</p>
   <div class="recipe-grid cooked-grid">
    <article v-for="item in cooking.items" :key="item.recipe.id" class="recipe-card cooked-card">
     <div class="recipe-card-top"><span class="category-label">{{item.recipe.categoryCode}}</span><span class="familiarity-tag">{{item.familiarityLabel}}</span></div>
     <RouterLink class="recipe-title-link" :to="{name:'recipe-detail',params:{id:item.recipe.id}}"><h2>{{item.recipe.name}}</h2></RouterLink>
     <p class="recipe-summary">{{item.recipe.summary??'暂无简介'}}</p>
     <div class="recipe-meta"><span>做过 {{item.cookCount}} 次</span><span>最近：{{displayDate(item.lastCookedAt)}}</span><span>{{item.latestRating===null?'未评分':'评分 '+item.latestRating+'/5'}}</span></div>
    </article>
   </div>
   <nav v-if="lastPage>1" class="pagination" aria-label="做菜历史分页"><button class="secondary-button" type="button" :disabled="cooking.loading||cooking.page<=1" @click="load(cooking.page-1)">上一页</button><span>第 {{cooking.page}} / {{lastPage}} 页</span><button class="secondary-button" type="button" :disabled="cooking.loading||cooking.page>=lastPage" @click="load(cooking.page+1)">下一页</button></nav>
  </template>
 </section>
</template>

