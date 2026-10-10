import {createSSRApp} from 'vue'
import {renderToString} from '@vue/server-renderer'
import {createMemoryHistory,createRouter} from 'vue-router'
import {createPinia} from 'pinia'
import {describe,expect,it} from 'vitest'
import RecipeDetailView from '@/views/RecipeDetailView.vue'
import {useRecipeStore} from '@/stores/recipe'

describe('菜谱详情食材渲染',()=>{
 it('原文项只显示完整原文，已知原量保持数值且HTML按文本呈现',async()=>{
  const pinia=createPinia()
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/recipes',name:'recipes',component:RecipeDetailView},{path:'/recipes/:id',name:'recipe-detail',component:RecipeDetailView}]})
  await router.push('/recipes/44');await router.isReady()
  const store=useRecipeStore(pinia)
  store.detail={
   recipe:{id:'44',name:'炖菜',categoryCode:'MAIN',summary:null,difficulty:null,estimatedMinutes:null,isFavorite:false},
   servings:null,
   ingredients:[
    {name:'姜 1 小块',rawLine:'姜 1 小块',amountMin:null,amountMax:null,unitLabel:null,optional:true},
    {name:'酱油 每个生蚝',rawLine:'酱油 每个生蚝 1 ml',amountMin:null,amountMax:null,unitLabel:null,optional:false},
    {name:'生姜',rawLine:'生姜 1 小块（约 15g）',amountMin:null,amountMax:null,unitLabel:null,optional:false},
    {name:'猪肉',rawLine:'猪肉 300g',amountMin:'300',amountMax:'300',unitLabel:'克',optional:false},
    {name:'安全文本',rawLine:'<img src=x onerror=alert(1)>',amountMin:null,amountMax:null,unitLabel:null,optional:false},
    {name:'回退名称',rawLine:'  ',amountMin:null,amountMax:null,unitLabel:null,optional:false},
   ],
   steps:[],tools:[],additionalContent:null,
  }
  const app=createSSRApp(RecipeDetailView);app.use(pinia);app.use(router)
  const html=await renderToString(app)
  expect(html.match(/>姜 1 小块/g)).toHaveLength(1)
  expect(html.match(/酱油 每个生蚝 1 ml/g)).toHaveLength(1)
  expect(html.match(/生姜 1 小块（约 15g）/g)).toHaveLength(1)
  expect(html).toContain('猪肉')
  expect(html).toContain('300 克')
  expect(html).toContain('（可选）')
  expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;')
  expect(html).not.toContain('<img src=x onerror=alert(1)>')
  expect(html).not.toContain('按原文准备；采购总量待确认')
  const ingredientList=/class="ingredient-list"[^>]*>(.*?)<\/ul>/s.exec(html)![1]!
  const rows=[...ingredientList.matchAll(/<li[^>]*>(.*?)<\/li>/gs)].map(match=>match[1]!)
  // 原文行只渲染左侧文本，右侧没有数量、替代符号或提示节点。
  for(const index of [0,1,2,4,5]) expect(rows[index]!.match(/<span\b/g)).toHaveLength(1)
  expect(rows[3]!.match(/<span\b/g)).toHaveLength(2)
  expect(html).toContain('回退名称')
 })
})

