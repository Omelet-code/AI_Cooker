import {defineStore} from 'pinia'
import {onScopeDispose} from 'vue'
import {recipeApi} from '@/modules/recipe/api'
import type {RecipeCard,RecipeDetail,RecipePage} from '@/modules/recipe/types'
import {useIdentityStore} from '@/stores/identity'
import {ApiError} from '@/shared/api/types'
import {registerSessionCleanup} from '@/shared/auth/sessionCleanup'
const registeredStores=new WeakSet<object>()
function messageFor(error:unknown):string{
 if(error instanceof ApiError){
  if(error.status===404)return '这道菜谱不存在或当前账号无权查看。'
  if(error.status===400)return error.message
  if(error.status===0)return error.message
  return error.message||'请求失败，请稍后重试。'
 }
 return error instanceof Error?error.message:'请求失败，请稍后重试。'
}
export const useRecipeStore=defineStore('recipe',{
 state:()=>({
  list:[] as RecipeCard[],favorites:[] as RecipeCard[],detail:null as RecipeDetail|null,
  page:1,pageSize:20,total:0,favoritePage:1,favoriteTotal:0,keyword:'',
  listLoading:false,favoritesLoading:false,detailLoading:false,
  listLoaded:false,favoritesLoaded:false,detailLoaded:false,
  listRequestedPage:1,favoritesRequestedPage:1,detailRequestedId:'',
  listError:'',favoritesError:'',detailError:'',favoriteError:'',
  favoriteBusyIds:[] as string[],ownerId:null as string|null,generation:0,
  listRequestId:0,favoritesRequestId:0,detailRequestId:0,
 }),
 actions:{
  ensureSession(){
   const identity=useIdentityStore()
   if(!registeredStores.has(this)){
    registeredStores.add(this)
    const store=this
    const unregister=registerSessionCleanup(()=>store.clearForSessionEnd())
    const stopIdentityWatch=identity.$subscribe((_mutation,state)=>{
     const currentId=state.user?.id??null
     if(store.ownerId!==currentId){store.resetPrivateState();store.ownerId=currentId}
    },{flush:'sync'})
    onScopeDispose(()=>{unregister();stopIdentityWatch();registeredStores.delete(store)})
   }
   if(this.ownerId!==identity.user?.id){this.resetPrivateState();this.ownerId=identity.user?.id??null}
   return {userId:identity.user?.id??null,generation:this.generation}
  },
  isCurrent(userId:string|null,generation:number){
   const identity=useIdentityStore()
   return Boolean(userId&&identity.status==='authenticated'&&identity.user?.id===userId&&this.ownerId===userId&&this.generation===generation)
  },
  async loadRecipes(page=1,keyword?:string){
   const session=this.ensureSession();if(!session.userId)return
   const generation=session.generation,requestId=++this.listRequestId,searchKeyword=keyword??this.keyword
   this.listRequestedPage=page;this.keyword=searchKeyword;this.listLoading=true;this.listError=''
   try{
    const result:RecipePage=await recipeApi.list(page,this.pageSize,searchKeyword)
    if(!this.isCurrent(session.userId,generation)||requestId!==this.listRequestId)return
    this.list=result.items;this.page=result.page;this.total=result.total;this.listLoaded=true
   }catch(error){if(this.isCurrent(session.userId,generation)&&requestId===this.listRequestId)this.listError=messageFor(error)}
   finally{if(this.isCurrent(session.userId,generation)&&requestId===this.listRequestId)this.listLoading=false}
  },
  async loadFavorites(page=1){
   const session=this.ensureSession();if(!session.userId)return
   const generation=session.generation,requestId=++this.favoritesRequestId
   this.favoritesRequestedPage=page;this.favoritesLoading=true;this.favoritesError=''
   try{
    const result=await recipeApi.favorites(page,this.pageSize)
    if(!this.isCurrent(session.userId,generation)||requestId!==this.favoritesRequestId)return
    const lastPage=Math.max(1,Math.ceil(result.total/this.pageSize))
    if(page>lastPage){this.favoriteTotal=result.total;this.favorites=[];this.favoritePage=lastPage;void this.loadFavorites(lastPage);return}
    this.favorites=result.items;this.favoritePage=result.page;this.favoriteTotal=result.total;this.favoritesLoaded=true
   }catch(error){if(this.isCurrent(session.userId,generation)&&requestId===this.favoritesRequestId)this.favoritesError=messageFor(error)}
   finally{if(this.isCurrent(session.userId,generation)&&requestId===this.favoritesRequestId)this.favoritesLoading=false}
  },
  async loadDetail(id:string){
   const session=this.ensureSession();if(!session.userId)return
   const generation=session.generation,requestId=++this.detailRequestId
   this.detailRequestedId=id;this.detail=null;this.detailLoading=true;this.detailError=''
   try{
    const result=await recipeApi.detail(id)
    if(!this.isCurrent(session.userId,generation)||requestId!==this.detailRequestId)return
    this.detail=result;this.detailLoaded=true
   }catch(error){if(this.isCurrent(session.userId,generation)&&requestId===this.detailRequestId)this.detailError=messageFor(error)}
   finally{if(this.isCurrent(session.userId,generation)&&requestId===this.detailRequestId)this.detailLoading=false}
  },
  async setFavorite(id:string,desired:boolean){
   const session=this.ensureSession();if(!session.userId)return false
   const generation=session.generation
   if(this.favoriteBusyIds.includes(id))return false
   this.favoriteBusyIds=[...this.favoriteBusyIds,id];this.favoriteError=''
   try{
    const result=desired?await recipeApi.addFavorite(id):await recipeApi.removeFavorite(id)
    if(!this.isCurrent(session.userId,generation))return false
    const previous=this.detail?.recipe.id===id?this.detail.recipe:this.list.find(item=>item.id===id)??this.favorites.find(item=>item.id===id)
    const wasFavorite=previous?.isFavorite
    const update=(items:RecipeCard[])=>items.map(item=>item.id===id?{...item,isFavorite:result.isFavorite}:item)
    this.list=update(this.list);this.favorites=update(this.favorites)
    if(result.isFavorite&&this.favoritesLoaded&&this.favoritePage===1&&previous&&!this.favorites.some(item=>item.id===id))this.favorites=[{...previous,isFavorite:true},...this.favorites]
    if(!result.isFavorite)this.favorites=this.favorites.filter(item=>item.id!==id)
    if(this.favoritesLoaded&&wasFavorite!==undefined&&wasFavorite!==result.isFavorite)this.favoriteTotal=Math.max(0,this.favoriteTotal+(result.isFavorite?1:-1))
    if(this.detail?.recipe.id===id)this.detail={...this.detail,recipe:{...this.detail.recipe,isFavorite:result.isFavorite}}
    this.refreshLoadedViewsAfterFavoriteWrite()
    return true
   }catch(error){if(this.isCurrent(session.userId,generation))this.favoriteError=messageFor(error);return false}
   finally{if(this.isCurrent(session.userId,generation))this.favoriteBusyIds=this.favoriteBusyIds.filter(item=>item!==id)}
  },
  /** 成功写收藏后重读已加载视图；新请求序号使旧查询结果失效。 */
  refreshLoadedViewsAfterFavoriteWrite(){
   const refreshes:Promise<void>[]=[]
   if(this.listLoaded||this.listLoading)refreshes.push(this.loadRecipes(this.listRequestedPage,this.keyword))
   if(this.favoritesLoaded||this.favoritesLoading)refreshes.push(this.loadFavorites(this.favoritesRequestedPage))
   if((this.detailLoaded||this.detailLoading)&&this.detailRequestedId)refreshes.push(this.loadDetail(this.detailRequestedId))
   void Promise.all(refreshes)
  },
  clearForSessionEnd(){this.resetPrivateState();this.ownerId=null},
  resetPrivateState(){
   this.generation++
   this.list=[];this.favorites=[];this.detail=null;this.page=1;this.total=0;this.favoritePage=1;this.favoriteTotal=0;this.keyword=''
   this.listLoading=false;this.favoritesLoading=false;this.detailLoading=false
   this.listLoaded=false;this.favoritesLoaded=false;this.detailLoaded=false
   this.listRequestedPage=1;this.favoritesRequestedPage=1;this.detailRequestedId=''
   this.listError='';this.favoritesError='';this.detailError='';this.favoriteError='';this.favoriteBusyIds=[]
  },
 },
})
