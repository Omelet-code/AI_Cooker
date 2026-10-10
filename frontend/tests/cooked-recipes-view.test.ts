import {createSSRApp} from 'vue'
import {renderToString} from '@vue/server-renderer'
import {createMemoryHistory,createRouter} from 'vue-router'
import {createPinia} from 'pinia'
import {describe,expect,it} from 'vitest'
import CookedRecipesView from '@/views/CookedRecipesView.vue'
import {useCookingStore} from '@/stores/cooking'
import type {CookedRecipeCard} from '@/modules/recipe/types'

describe('做菜历史页面',()=>{
 it('展示后端次数、熟悉度和最新非空评分，null明确显示未评分',async()=>{
  const pinia=createPinia(),router=createRouter({history:createMemoryHistory(),routes:[{path:'/cooked-recipes',name:'cooked-recipes',component:CookedRecipesView},{path:'/recipes/:id',name:'recipe-detail',component:CookedRecipesView}]})
  await router.push('/cooked-recipes');await router.isReady()
  const item:CookedRecipeCard={recipe:{id:'recipe-7',name:'番茄炒蛋',categoryCode:'MAIN',summary:'家常菜',difficulty:1,estimatedMinutes:15,isFavorite:false},cookCount:3,lastCookedAt:'2026-10-01T10:00:00Z',familiarityCode:'FAMILIAR',familiarityLabel:'熟悉',latestRating:null}
  const store=useCookingStore(pinia);store.items=[item];store.loaded=true;store.total=1
  const app=createSSRApp(CookedRecipesView);app.use(pinia);app.use(router)
  const html=await renderToString(app)
  expect(html).toContain('做菜历史')
  expect(html).toContain('番茄炒蛋')
  expect(html).toContain('做过 3 次')
  expect(html).toContain('熟悉')
  expect(html).toContain('未评分')
  expect(html).not.toContain('评分 0/5')
 })
})
