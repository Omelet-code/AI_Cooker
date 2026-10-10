import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import {useIdentityStore} from '@/stores/identity'
import {useCookingStore} from '@/stores/cooking'
import {clearSessionScopedState} from '@/shared/auth/sessionCleanup'
import {ApiError} from '@/shared/api/types'
import {recipeApi} from '@/modules/recipe/api'
import type {CookingRecord,CookingRecordInput,CookedRecipeCard,CookedRecipePage,RecipeCard} from '@/modules/recipe/types'

vi.mock('@/modules/recipe/api',()=>({recipeApi:{
 list:vi.fn(),detail:vi.fn(),favorites:vi.fn(),addFavorite:vi.fn(),removeFavorite:vi.fn(),
 completeCooking:vi.fn(),cookedRecipes:vi.fn(),
}}))
const recipe:RecipeCard={id:'recipe-7',name:'番茄炒蛋',categoryCode:'MAIN',summary:null,difficulty:1,estimatedMinutes:15,isFavorite:false}
const historyCard:CookedRecipeCard={recipe,cookCount:2,lastCookedAt:'2026-10-01T10:00:00Z',familiarityCode:'FAMILIAR',familiarityLabel:'熟悉',latestRating:null}
const history=(items:CookedRecipeCard[]=[],page=1,total=items.length):CookedRecipePage=>({items,page,size:20,total})
const record=(payload:CookingRecordInput):CookingRecord=>({id:'event-1',recipeId:payload.recipeId,cookedAt:'2026-10-01T10:00:00Z',rating:payload.rating})
function deferred<T>(){let resolve!:(value:T)=>void;let reject!:(reason:unknown)=>void;const promise=new Promise<T>((done,fail)=>{resolve=done;reject=fail});return {promise,resolve,reject}}
async function settle(){await new Promise(resolve=>setTimeout(resolve,0))}
let uuid=0
beforeEach(()=>{
 setActivePinia(createPinia())
 const identity=useIdentityStore();identity.user={id:'user-a',username:'a'};identity.status='authenticated'
 uuid=0
 vi.spyOn(globalThis.crypto,'randomUUID').mockImplementation(()=>{uuid++;return 'uuid-'+uuid})
 vi.mocked(recipeApi.completeCooking).mockImplementation(async payload=>record(payload))
 vi.mocked(recipeApi.cookedRecipes).mockResolvedValue(history())
})
afterEach(()=>{clearSessionScopedState();vi.restoreAllMocks()})
describe('做菜记录状态与幂等键',()=>{
 it.each([null,1,2,3,4,5])('固定评分 %s 并提交一次记录',async rating=>{
  const store=useCookingStore()
  expect(await store.completeCooking(recipe.id,rating)).toBe(true)
  expect(recipeApi.completeCooking).toHaveBeenCalledWith({recipeId:recipe.id,idempotencyKey:'uuid-1',rating})
  expect(store.pendingCooking).toBeNull()
  expect(store.lastCompletedRecord?.rating).toBe(rating)
 })
 it('取消弹窗不调用记录接口，提交中重复点击不会多发请求',async()=>{
  const waiting=deferred<CookingRecord>()
  vi.mocked(recipeApi.completeCooking).mockReturnValueOnce(waiting.promise)
  const store=useCookingStore(),first=store.completeCooking(recipe.id,4)
  expect(store.pendingCooking?.status).toBe('submitting')
  expect(await store.completeCooking(recipe.id,4)).toBe(false)
  expect(recipeApi.completeCooking).toHaveBeenCalledTimes(1)
  waiting.resolve(record({recipeId:recipe.id,idempotencyKey:'uuid-1',rating:4}))
  await first
  expect(store.pendingCooking).toBeNull()
 })
 it('超时后重试复用同一编号和评分，历史数量只由服务端回读',async()=>{
  vi.mocked(recipeApi.completeCooking).mockRejectedValueOnce(new ApiError(0,'REQUEST_TIMEOUT','请求超时')).mockResolvedValueOnce(record({recipeId:recipe.id,idempotencyKey:'uuid-1',rating:5}))
  const store=useCookingStore()
  expect(await store.completeCooking(recipe.id,5)).toBe(false)
  expect(store.pendingCooking?.status).toBe('unknown')
  expect(store.total).toBe(0)
  expect(await store.retryCooking()).toBe(true)
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(1,{recipeId:recipe.id,idempotencyKey:'uuid-1',rating:5})
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(2,{recipeId:recipe.id,idempotencyKey:'uuid-1',rating:5})
  await settle()
  expect(store.items).toEqual([])
  expect(store.total).toBe(0)
 })
 it('一次成功结束后，新的烹饪操作使用新UUID',async()=>{
  const store=useCookingStore()
  await store.completeCooking(recipe.id,null)
  await store.completeCooking(recipe.id,3)
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(1,{recipeId:recipe.id,idempotencyKey:'uuid-1',rating:null})
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(2,{recipeId:recipe.id,idempotencyKey:'uuid-2',rating:3})
 })
 it('冲突不自动换键或重试，只有用户明确结束后才能开新操作',async()=>{
  vi.mocked(recipeApi.completeCooking).mockRejectedValueOnce(new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键冲突'))
  const store=useCookingStore()
  expect(await store.completeCooking(recipe.id,null)).toBe(false)
  expect(store.pendingCooking?.status).toBe('conflict')
  expect(await store.retryCooking()).toBe(false)
  expect(recipeApi.completeCooking).toHaveBeenCalledTimes(1)
  expect(store.endCookingOperation()).toBe(true)
  expect(await store.completeCooking(recipe.id,null)).toBe(true)
  expect(recipeApi.completeCooking).toHaveBeenLastCalledWith({recipeId:recipe.id,idempotencyKey:'uuid-2',rating:null})
 })
 it('一般失败保留原请求内容并允许同键重试',async()=>{
  vi.mocked(recipeApi.completeCooking).mockRejectedValueOnce(new ApiError(400,'VALIDATION_ERROR','请求无效'))
  const store=useCookingStore()
  expect(await store.completeCooking(recipe.id,2)).toBe(false)
  expect(store.pendingCooking?.status).toBe('failed')
  expect(await store.retryCooking()).toBe(true)
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(1,{recipeId:recipe.id,idempotencyKey:'uuid-1',rating:2})
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(2,{recipeId:recipe.id,idempotencyKey:'uuid-1',rating:2})
 })
 it('账号切换会清除待处理内容，旧账号迟到响应不能恢复记录',async()=>{
  const waiting=deferred<CookingRecord>()
  vi.mocked(recipeApi.completeCooking).mockReturnValueOnce(waiting.promise)
  const store=useCookingStore(),request=store.completeCooking(recipe.id,null)
  useIdentityStore().user={id:'user-b',username:'b'}
  expect(store.pendingCooking).toBeNull()
  waiting.resolve(record({recipeId:recipe.id,idempotencyKey:'uuid-1',rating:null}))
  await request
  expect(store.pendingCooking).toBeNull()
  expect(store.lastCompletedRecord).toBeNull()
  expect(store.items).toEqual([])
 })
})
describe('做菜历史服务端视图状态',()=>{
 it('按请求页码加载后端统计，并保留未评分null',async()=>{
  vi.mocked(recipeApi.cookedRecipes).mockResolvedValueOnce(history([historyCard],1,21)).mockResolvedValueOnce(history([],2,21))
  const store=useCookingStore()
  await store.loadCookedRecipes(1)
  expect(store.items[0].cookCount).toBe(2)
  expect(store.items[0].latestRating).toBeNull()
  expect(store.items[0].familiarityLabel).toBe('熟悉')
  await store.loadCookedRecipes(2)
  expect(recipeApi.cookedRecipes).toHaveBeenNthCalledWith(1,1,20)
  expect(recipeApi.cookedRecipes).toHaveBeenNthCalledWith(2,2,20)
  expect(store.page).toBe(2)
  expect(store.total).toBe(21)
 })
 it('账号切换后丢弃迟到的历史响应',async()=>{
  const waiting=deferred<CookedRecipePage>()
  vi.mocked(recipeApi.cookedRecipes).mockReturnValueOnce(waiting.promise)
  const store=useCookingStore(),request=store.loadCookedRecipes(1)
  useIdentityStore().user={id:'user-b',username:'b'}
  waiting.resolve(history([historyCard]))
  await request
  expect(store.items).toEqual([])
  expect(store.loaded).toBe(false)
 })
})
