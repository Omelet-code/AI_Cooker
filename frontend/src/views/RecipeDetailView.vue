<script setup lang="ts">
import {computed,onMounted,watch} from 'vue'
import {useRoute} from 'vue-router'
import {formatIngredientQuantity,formatIngredientSource,hasStructuredIngredientAmount} from '@/modules/recipe/format'
import {useCookingStore} from '@/stores/cooking'
import {useIdentityStore} from '@/stores/identity'
import {useCookingCompletion} from '@/modules/recipe/useCookingCompletion'
import {useRecipeStore} from '@/stores/recipe'
const route=useRoute(),recipes=useRecipeStore(),cooking=useCookingStore(),identity=useIdentityStore()
const recipe=computed(()=>recipes.detail?.recipe),id=computed(()=>String(route.params.id??''))
const pendingForRecipe=computed(()=>cooking.pendingCooking?.recipeId===id.value?cooking.pendingCooking:null)
const userId=computed(()=>identity.user?.id??null)
onMounted(()=>{void recipes.loadDetail(id.value)})
watch(id,value=>{void recipes.loadDetail(value)})
async function toggleFavorite(){if(recipe.value)await recipes.setFavorite(recipe.value.id,!recipe.value.isFavorite)}
const {selectedRating,anyPending,dialogVisible,openConfirmation,cancelConfirmation,confirmCooking,retryCooking,endPending}=useCookingCompletion(id,userId,computed(()=>Boolean(recipe.value)),cooking)
</script>
<template>
 <section class="recipe-page recipe-detail-page" aria-labelledby="detail-title">
  <RouterLink class="back-link" :to="{name:'recipes'}">← 返回菜谱</RouterLink>
  <div v-if="recipes.detailLoading" class="page-state" role="status">正在加载菜谱详情…</div>
  <div v-else-if="recipes.detailError" class="page-state error-state" role="alert"><strong>无法显示这道菜谱</strong><p>{{recipes.detailError}}</p><RouterLink class="secondary-button link-button" :to="{name:'recipes'}">返回菜谱列表</RouterLink></div>
  <article v-else-if="recipes.detail" class="recipe-detail">
   <header class="detail-heading"><p class="eyebrow">{{recipes.detail.recipe.categoryCode}}</p><h1 id="detail-title">{{recipes.detail.recipe.name}}</h1><p class="detail-summary">{{recipes.detail.recipe.summary??'暂无简介'}}</p><div class="recipe-meta"><span>难度：{{recipes.detail.recipe.difficulty===null?'未知':recipes.detail.recipe.difficulty}}</span><span>预计时间：{{recipes.detail.recipe.estimatedMinutes===null?'未知':recipes.detail.recipe.estimatedMinutes+' 分钟'}}</span><span>份量：{{recipes.detail.servings===null?'未知':recipes.detail.servings}}</span></div><button class="secondary-button favorite-action" type="button" :disabled="recipes.favoriteBusyIds.includes(recipes.detail.recipe.id)" @click="toggleFavorite">{{recipes.detail.recipe.isFavorite?'♥ 已收藏':'♡ 收藏菜谱'}}</button><div class="cook-action-row"><button class="primary-button" type="button" :disabled="anyPending" @click="openConfirmation">{{anyPending?'有一笔做菜记录待处理':'完成烹饪'}}</button><p v-if="anyPending&&!pendingForRecipe" class="muted-copy">请先处理顶部提示中的待处理记录。</p></div></header>
   <p v-if="recipes.favoriteError" class="form-error" role="alert">{{recipes.favoriteError}}</p>
   <section v-if="pendingForRecipe" class="cooking-operation" aria-live="polite">
    <h2>做菜记录尚未完成</h2><p>{{pendingForRecipe.message||'正在提交本次记录，请稍候。'}}</p>
    <p v-if="pendingForRecipe.status==='unknown'">本次评分已固定。重试会使用相同请求内容和编号。</p>
    <p v-if="pendingForRecipe.status==='conflict'">服务器拒绝了这组编号对应的请求；系统不会自动换编号。</p>
    <div class="operation-actions">
     <button v-if="pendingForRecipe.status==='unknown'||pendingForRecipe.status==='failed'" class="secondary-button" type="button" @click="retryCooking">用相同内容重试</button>
     <button v-if="pendingForRecipe.status!=='submitting'" class="text-button" type="button" @click="endPending">结束本次操作</button>
    </div>
    <p v-if="pendingForRecipe.status!=='submitting'" class="operation-warning">结束操作会放弃自动重试；若服务器已收下记录，历史仍以服务器返回为准。</p>
   </section>
   <section class="detail-section"><h2>食材</h2><p v-if="recipes.detail.ingredients.length===0" class="muted-copy">暂无食材信息</p><ul v-else class="ingredient-list"><li v-for="(ingredient,index) in recipes.detail.ingredients" :key="ingredient.name+'-'+index"><span>{{hasStructuredIngredientAmount(ingredient)?ingredient.name:formatIngredientSource({rawLine:ingredient.rawLine,name:ingredient.name})}}{{ingredient.optional ? "（可选）" : ""}}</span><span v-if="hasStructuredIngredientAmount(ingredient)" class="ingredient-amount">{{formatIngredientQuantity(ingredient)}}</span></li></ul></section>
   <section class="detail-section"><h2>烹饪步骤</h2><p v-if="recipes.detail.steps.length===0" class="muted-copy">暂无步骤信息</p><ol v-else class="step-list"><li v-for="step in recipes.detail.steps" :key="step.stepNo"><span class="step-number">{{step.stepNo}}</span><p>{{step.content}}</p></li></ol></section>
   <section class="detail-section"><h2>厨具</h2><p v-if="recipes.detail.tools.length===0" class="muted-copy">暂无厨具信息</p><ul v-else class="tool-list"><li v-for="(tool,index) in recipes.detail.tools" :key="tool.name+'-'+index">{{tool.name}}{{tool.optional?'（可选）':''}}</li></ul></section>
   <section v-if="recipes.detail.additionalContent" class="detail-section"><h2>补充说明</h2><p class="preserved-text">{{recipes.detail.additionalContent}}</p></section>
  </article>
  <div v-if="dialogVisible" class="modal-backdrop" @click.self="cancelConfirmation">
   <section class="completion-modal" role="dialog" aria-modal="true" aria-labelledby="completion-title">
    <p class="eyebrow">COOKING RECORD</p><h2 id="completion-title">完成这次烹饪？</h2><p>确认后会为这次记录生成固定编号。库存不会因此扣减。</p>
    <label for="cooking-rating">给这道菜打分（可选）</label>
    <select id="cooking-rating" v-model="selectedRating"><option value="">不评分</option><option v-for="rating in [1,2,3,4,5]" :key="rating" :value="String(rating)">{{rating}} 分</option></select>
    <div class="operation-actions"><button class="primary-button" type="button" @click="confirmCooking">确认完成</button><button class="secondary-button" type="button" @click="cancelConfirmation">取消</button></div>
   </section>
  </div>
 </section>
</template>

