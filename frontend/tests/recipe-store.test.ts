import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import {useIdentityStore} from '@/stores/identity'
import {useRecipeStore} from '@/stores/recipe'
import {clearSessionScopedState} from '@/shared/auth/sessionCleanup'
import {recipeApi} from '@/modules/recipe/api'
import type {RecipeCard,RecipeDetail,RecipePage} from '@/modules/recipe/types'
vi.mock('@/modules/recipe/api',()=>({recipeApi:{list:vi.fn(),detail:vi.fn(),favorites:vi.fn(),addFavorite:vi.fn(),removeFavorite:vi.fn()}}))
const card:RecipeCard={id:'44',name:'清炒时蔬',categoryCode:'SIDE',summary:null,difficulty:null,estimatedMinutes:null,isFavorite:false}
const page=(items:RecipeCard[],number=1,total=items.length):RecipePage=>({items,page:number,size:20,total})
const detail=(isFavorite:boolean):RecipeDetail=>({recipe:{...card,isFavorite},servings:null,ingredients:[],steps:[],tools:[],additionalContent:null})
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(done=>{resolve=done});return {promise,resolve}}
async function settle(){await new Promise(resolve=>setTimeout(resolve,0))}
beforeEach(()=>{setActivePinia(createPinia());const identity=useIdentityStore();identity.user={id:'user-a',username:'a'};identity.status='authenticated'})
afterEach(()=>{clearSessionScopedState();vi.resetAllMocks()})
describe('菜谱私有状态',()=>{
 it('保留分页可空字段，收藏失败不伪装成功，成功后本地卡片同步',async()=>{
  vi.mocked(recipeApi.list).mockResolvedValueOnce(page([card])).mockResolvedValue(page([{...card,isFavorite:true}]))
  const store=useRecipeStore();await store.loadRecipes()
  expect(store.list[0].summary).toBeNull();expect(store.list[0].id).toBe('44')
  vi.mocked(recipeApi.addFavorite).mockRejectedValue(new Error('收藏请求失败'))
  expect(await store.setFavorite('44',true)).toBe(false)
  expect(store.list[0].isFavorite).toBe(false);expect(store.favoriteError).toBe('收藏请求失败')
  vi.mocked(recipeApi.addFavorite).mockResolvedValue({recipeId:'44',isFavorite:true})
  expect(await store.setFavorite('44',true)).toBe(true)
  expect(store.list[0].isFavorite).toBe(true)
 })
 it('身份清理后，迟到的旧用户响应不能恢复列表数据',async()=>{
  const pending=deferred<RecipePage>()
  vi.mocked(recipeApi.list).mockReturnValue(pending.promise)
  const store=useRecipeStore(),request=store.loadRecipes()
  clearSessionScopedState();pending.resolve(page([card]));await request
  expect(store.list).toEqual([]);expect(store.ownerId).toBeNull()
 })
 it('快速切换搜索时只应用最后一次响应',async()=>{
  const resolvers:Array<(value:RecipePage)=>void>=[]
  vi.mocked(recipeApi.list).mockImplementation(()=>new Promise(resolve=>resolvers.push(resolve)))
  const store=useRecipeStore(),oldRequest=store.loadRecipes(1,'旧关键词'),newRequest=store.loadRecipes(2,'新关键词')
  resolvers[1](page([{...card,id:'new'}],2,40));await newRequest
  resolvers[0](page([{...card,id:'old'}],1,40));await oldRequest
  expect(store.page).toBe(2);expect(store.list[0].id).toBe('new');expect(store.keyword).toBe('新关键词')
 })
 it('收藏添加成功后，列表、收藏、详情的旧查询均不能覆盖写入，并重读已加载视图',async()=>{
  const oldList=deferred<RecipePage>(),newList=deferred<RecipePage>()
  const oldFavorites=deferred<RecipePage>(),newFavorites=deferred<RecipePage>()
  const oldDetail=deferred<RecipeDetail>(),newDetail=deferred<RecipeDetail>()
  vi.mocked(recipeApi.list).mockResolvedValueOnce(page([card])).mockReturnValueOnce(oldList.promise).mockReturnValueOnce(newList.promise)
  vi.mocked(recipeApi.favorites).mockResolvedValueOnce(page([])).mockReturnValueOnce(oldFavorites.promise).mockReturnValueOnce(newFavorites.promise)
  vi.mocked(recipeApi.detail).mockResolvedValueOnce(detail(false)).mockReturnValueOnce(oldDetail.promise).mockReturnValueOnce(newDetail.promise)
  vi.mocked(recipeApi.addFavorite).mockResolvedValue({recipeId:'44',isFavorite:true})
  const store=useRecipeStore();await store.loadRecipes();await store.loadFavorites();await store.loadDetail('44')
  const staleList=store.loadRecipes(2,'青菜'),staleFavorites=store.loadFavorites(1),staleDetail=store.loadDetail('44')
  expect(await store.setFavorite('44',true)).toBe(true)
  expect(vi.mocked(recipeApi.list)).toHaveBeenCalledTimes(3)
  expect(vi.mocked(recipeApi.favorites)).toHaveBeenCalledTimes(3)
  expect(vi.mocked(recipeApi.detail)).toHaveBeenCalledTimes(3)
  oldList.resolve(page([{...card,isFavorite:false}],2,40));oldFavorites.resolve(page([],1,0));oldDetail.resolve(detail(false))
  await Promise.all([staleList,staleFavorites,staleDetail])
  expect(store.listLoading).toBe(true);expect(store.favoritesLoading).toBe(true);expect(store.detailLoading).toBe(true)
  newList.resolve(page([{...card,isFavorite:true}],2,40));newFavorites.resolve(page([{...card,isFavorite:true}],1,1));newDetail.resolve(detail(true))
  await settle()
  expect(store.list[0].isFavorite).toBe(true);expect(store.page).toBe(2)
  expect(store.favorites[0].isFavorite).toBe(true);expect(store.favoriteTotal).toBe(1);expect(store.favoritePage).toBe(1)
  expect(store.detail?.recipe.isFavorite).toBe(true)
  expect(store.listLoading||store.favoritesLoading||store.detailLoading).toBe(false)
 })
 it('取消收藏成功后丢弃旧收藏页，刷新总数并在末页变空时回到有效页',async()=>{
  const oldRead=deferred<RecipePage>(),pageTwo=deferred<RecipePage>(),pageOne=deferred<RecipePage>()
  vi.mocked(recipeApi.favorites).mockResolvedValueOnce(page([{...card,isFavorite:true}],2,21)).mockReturnValueOnce(oldRead.promise).mockReturnValueOnce(pageTwo.promise).mockReturnValueOnce(pageOne.promise)
  vi.mocked(recipeApi.removeFavorite).mockResolvedValue({recipeId:'44',isFavorite:false})
  const store=useRecipeStore();await store.loadFavorites(2)
  const staleRead=store.loadFavorites(2)
  expect(await store.setFavorite('44',false)).toBe(true)
  expect(store.favorites).toEqual([]);expect(store.favoriteTotal).toBe(20)
  oldRead.resolve(page([{...card,isFavorite:true}],2,21));await staleRead
  expect(store.favoritesLoading).toBe(true);expect(store.favoriteTotal).toBe(20)
  pageTwo.resolve(page([],2,20));await settle()
  expect(vi.mocked(recipeApi.favorites)).toHaveBeenCalledTimes(4)
  pageOne.resolve(page(Array.from({length:20},(_,index)=>({...card,id:`${100+index}`,isFavorite:true})),1,20));await settle()
  expect(store.favoritePage).toBe(1);expect(store.favoriteTotal).toBe(20)
  expect(store.favorites.some(item=>item.id==='44')).toBe(false);expect(store.favoritesLoading).toBe(false)
 })
 it('收藏成功后的重读失败会结束加载，迟到旧响应仍不能覆盖收藏',async()=>{
  const stale=deferred<RecipePage>()
  vi.mocked(recipeApi.list).mockResolvedValueOnce(page([card])).mockReturnValueOnce(stale.promise).mockRejectedValueOnce(new Error('收藏后重读失败'))
  vi.mocked(recipeApi.addFavorite).mockResolvedValue({recipeId:'44',isFavorite:true})
  const store=useRecipeStore();await store.loadRecipes()
  const oldRequest=store.loadRecipes(1,'新鲜')
  expect(await store.setFavorite('44',true)).toBe(true)
  await settle()
  expect(store.listLoading).toBe(false);expect(store.listError).toBe('收藏后重读失败')
  stale.resolve(page([{...card,isFavorite:false}]));await oldRequest
  expect(store.list[0].isFavorite).toBe(true);expect(store.listLoading).toBe(false)
 })
 it('检测账号切换时清空前一用户数据',async()=>{
  vi.mocked(recipeApi.list).mockResolvedValue(page([card]))
  const store=useRecipeStore();await store.loadRecipes()
  const identity=useIdentityStore();identity.user={id:'user-b',username:'b'}
  expect(store.list).toEqual([])
  vi.mocked(recipeApi.favorites).mockResolvedValue(page([]))
  await store.loadFavorites()
  expect(store.list).toEqual([]);expect(store.ownerId).toBe('user-b')
 })
})

