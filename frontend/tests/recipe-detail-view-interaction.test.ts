import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {computed,effectScope,ref,type EffectScope} from 'vue'
import {createPinia,setActivePinia} from 'pinia'
import {useCookingCompletion} from '@/modules/recipe/useCookingCompletion'
import {useIdentityStore} from '@/stores/identity'
import {useCookingStore} from '@/stores/cooking'
import {clearSessionScopedState} from '@/shared/auth/sessionCleanup'
import {ApiError} from '@/shared/api/types'
import {recipeApi} from '@/modules/recipe/api'
import type {CookingRecord,CookingRecordInput} from '@/modules/recipe/types'

vi.mock('@/modules/recipe/api',()=>({recipeApi:{
 list:vi.fn(),detail:vi.fn(),favorites:vi.fn(),addFavorite:vi.fn(),removeFavorite:vi.fn(),
 completeCooking:vi.fn(),cookedRecipes:vi.fn(),
}}))
function deferred<T>(){let resolve!:(value:T)=>void;let reject!:(error:unknown)=>void;const promise=new Promise<T>((done,fail)=>{resolve=done;reject=fail});return {promise,resolve,reject}}
async function settle(){await new Promise(resolve=>setTimeout(resolve,0))}
function createPageState(){
 const identity=useIdentityStore(),cooking=useCookingStore(),routeId=ref('44')
 const scope=effectScope()
 const state=scope.run(()=>useCookingCompletion(
  computed(()=>routeId.value),
  computed(()=>identity.user?.id??null),
  computed(()=>true),
  cooking,
 ))
 if(!state)throw new Error('无法创建页面完成确认状态')
 return {identity,cooking,routeId,scope,state}
}
let scope:EffectScope|undefined
let uuid=0
beforeEach(()=>{
 setActivePinia(createPinia())
 const identity=useIdentityStore();identity.user={id:'user-a',username:'a'};identity.status='authenticated'
 uuid=0
 vi.spyOn(globalThis.crypto,'randomUUID').mockImplementation(()=>{uuid++;return 'uuid-'+uuid})
 vi.mocked(recipeApi.completeCooking).mockImplementation(async payload=>({id:'event-'+uuid,recipeId:payload.recipeId,cookedAt:'2026-10-01T10:00:00Z',rating:payload.rating}))
 vi.mocked(recipeApi.cookedRecipes).mockResolvedValue({items:[],page:1,size:20,total:0})
})
afterEach(()=>{scope?.stop();scope=undefined;clearSessionScopedState();vi.restoreAllMocks()})

describe('菜谱详情页完成确认状态',()=>{
 it('取消关闭实际页面确认状态且不创建操作',async()=>{
  const page=createPageState();scope=page.scope
  page.state.openConfirmation()
  expect(page.state.dialogVisible.value).toBe(true)
  page.state.cancelConfirmation()
  expect(page.state.dialogVisible.value).toBe(false)
  expect(recipeApi.completeCooking).not.toHaveBeenCalled()
 })
 it('首次结果未知后同键重试成功会关闭弹窗并清空评分',async()=>{
  vi.mocked(recipeApi.completeCooking)
   .mockRejectedValueOnce(new ApiError(0,'REQUEST_TIMEOUT','请求超时'))
   .mockResolvedValueOnce({id:'event-1',recipeId:'44',cookedAt:'2026-10-01T10:00:00Z',rating:5})
   .mockResolvedValueOnce({id:'event-2',recipeId:'44',cookedAt:'2026-10-01T10:05:00Z',rating:null})
  const page=createPageState();scope=page.scope
  page.state.openConfirmation();page.state.selectedRating.value='5'
  expect(await page.state.confirmCooking()).toBe(false)
  expect(page.cooking.pendingCooking?.status).toBe('unknown')
  expect(page.state.dialogVisible.value).toBe(false)
  expect(await page.state.retryCooking()).toBe(true)
  expect(page.cooking.pendingCooking).toBeNull()
  expect(page.state.dialogVisible.value).toBe(false)
  expect(page.state.selectedRating.value).toBe('')
  page.state.openConfirmation()
  expect(page.state.dialogVisible.value).toBe(true)
  expect(await page.state.confirmCooking()).toBe(true)
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(1,{recipeId:'44',idempotencyKey:'uuid-1',rating:5})
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(2,{recipeId:'44',idempotencyKey:'uuid-1',rating:5})
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(3,{recipeId:'44',idempotencyKey:'uuid-2',rating:null})
 })
 it('首次成功清理弹窗；重试再次未知时冻结并保留相同请求',async()=>{
  vi.mocked(recipeApi.completeCooking)
   .mockResolvedValueOnce({id:'event-1',recipeId:'44',cookedAt:'2026-10-01T10:00:00Z',rating:1})
   .mockRejectedValueOnce(new ApiError(0,'REQUEST_TIMEOUT','请求超时'))
   .mockRejectedValueOnce(new ApiError(0,'NETWORK_ERROR','网络失败'))
   .mockResolvedValueOnce({id:'event-2',recipeId:'44',cookedAt:'2026-10-01T10:05:00Z',rating:3})
  const page=createPageState();scope=page.scope
  page.state.openConfirmation();page.state.selectedRating.value='1'
  expect(await page.state.confirmCooking()).toBe(true)
  expect(page.state.dialogVisible.value).toBe(false)
  expect(page.state.selectedRating.value).toBe('')
  page.state.openConfirmation();page.state.selectedRating.value='3'
  expect(await page.state.confirmCooking()).toBe(false)
  expect(await page.state.retryCooking()).toBe(false)
  expect(page.cooking.pendingCooking?.status).toBe('unknown')
  expect(page.state.selectedRating.value).toBe('3')
  expect(await page.state.retryCooking()).toBe(true)
  expect(page.state.selectedRating.value).toBe('')
  for(const index of [2,3,4])expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(index,{recipeId:'44',idempotencyKey:'uuid-2',rating:3})
 })
 it('用户切换后旧重试不能清除新用户页面正在选择的评分',async()=>{
  const retryResponse=deferred<CookingRecord>()
  vi.mocked(recipeApi.completeCooking)
   .mockRejectedValueOnce(new ApiError(0,'REQUEST_TIMEOUT','请求超时'))
   .mockReturnValueOnce(retryResponse.promise)
   .mockResolvedValueOnce({id:'event-b',recipeId:'44',cookedAt:'2026-10-01T10:00:00Z',rating:2})
  const page=createPageState();scope=page.scope
  page.state.openConfirmation();page.state.selectedRating.value='5'
  expect(await page.state.confirmCooking()).toBe(false)
  const oldRetry=page.state.retryCooking()
  useIdentityStore().user={id:'user-b',username:'b'}
  page.state.openConfirmation();page.state.selectedRating.value='2'
  retryResponse.resolve({id:'event-a',recipeId:'44',cookedAt:'2026-10-01T09:00:00Z',rating:5})
  expect(await oldRetry).toBe(false)
  expect(page.state.dialogVisible.value).toBe(true)
  expect(page.state.selectedRating.value).toBe('2')
  expect(await page.state.confirmCooking()).toBe(true)
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(3,{recipeId:'44',idempotencyKey:'uuid-2',rating:2})
 })
 it('菜谱路由变化清除旧确认草稿，迟到成功不会打开新菜谱弹窗',async()=>{
  const retryResponse=deferred<CookingRecord>()
  vi.mocked(recipeApi.completeCooking).mockRejectedValueOnce(new ApiError(0,'REQUEST_TIMEOUT','请求超时')).mockReturnValueOnce(retryResponse.promise)
  const page=createPageState();scope=page.scope
  page.state.openConfirmation();page.state.selectedRating.value='4'
  expect(await page.state.confirmCooking()).toBe(false)
  const oldRetry=page.state.retryCooking()
  page.routeId.value='55'
  expect(page.state.selectedRating.value).toBe('')
  expect(page.state.dialogVisible.value).toBe(false)
  retryResponse.resolve({id:'event-1',recipeId:'44',cookedAt:'2026-10-01T10:00:00Z',rating:4})
  expect(await oldRetry).toBe(true)
  expect(page.state.dialogVisible.value).toBe(false)
  expect(page.state.selectedRating.value).toBe('')
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(1,{recipeId:'44',idempotencyKey:'uuid-1',rating:4})
  expect(recipeApi.completeCooking).toHaveBeenNthCalledWith(2,{recipeId:'44',idempotencyKey:'uuid-1',rating:4})
 })
})
