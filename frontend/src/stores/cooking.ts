import {defineStore} from 'pinia'
import {getCurrentScope,onScopeDispose} from 'vue'
import {recipeApi} from '@/modules/recipe/api'
import type {CookingRecord,CookingRecordInput,CookedRecipeCard,CookedRecipePage} from '@/modules/recipe/types'
import {useIdentityStore} from '@/stores/identity'
import {ApiError} from '@/shared/api/types'
import {registerSessionCleanup} from '@/shared/auth/sessionCleanup'
export type CookingOperationStatus='submitting'|'unknown'|'failed'|'conflict'
export interface CookingOperation extends CookingRecordInput {status:CookingOperationStatus;message:string}
const registeredStores=new WeakSet<object>()
function describeError(error:unknown):{status:CookingOperationStatus;message:string}{
 if(error instanceof ApiError&&error.status===409&&error.code==='IDEMPOTENCY_CONFLICT')return {status:'conflict',message:'服务器发现本次编号对应的内容不同。不会自动更换编号；请结束本次操作后再决定下一步。'}
 if(!(error instanceof ApiError)||error.status===0||error.status>=500)return {status:'unknown',message:'请求结果未知，服务器可能已经收到本次记录。重试会沿用相同编号和内容。'}
 return {status:'failed',message:error.message||'记录失败；可以使用相同编号和内容重试。'}
}
export const useCookingStore=defineStore('cooking',{
 state:()=>({
  items:[] as CookedRecipeCard[],page:1,pageSize:20,total:0,loading:false,errorMessage:'',loaded:false,
  pendingCooking:null as CookingOperation|null,lastCompletedRecord:null as CookingRecord|null,
  ownerId:null as string|null,generation:0,historyRequestId:0,
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
    if(getCurrentScope())onScopeDispose(()=>{unregister();stopIdentityWatch();registeredStores.delete(store)})
   }
   if(this.ownerId!==identity.user?.id){this.resetPrivateState();this.ownerId=identity.user?.id??null}
   return {userId:identity.user?.id??null,generation:this.generation}
  },
  isCurrent(userId:string|null,generation:number){
   const identity=useIdentityStore()
   return Boolean(userId&&identity.status==='authenticated'&&identity.user?.id===userId&&this.ownerId===userId&&this.generation===generation)
  },
  async loadCookedRecipes(page=1){
   const session=this.ensureSession();if(!session.userId)return
   const generation=session.generation,requestId=++this.historyRequestId
   this.loading=true;this.errorMessage=''
   try{
    const result:CookedRecipePage=await recipeApi.cookedRecipes(page,this.pageSize)
    if(!this.isCurrent(session.userId,generation)||requestId!==this.historyRequestId)return
    this.items=result.items;this.page=result.page;this.total=result.total;this.loaded=true
   }catch(error){if(this.isCurrent(session.userId,generation)&&requestId===this.historyRequestId)this.errorMessage=error instanceof Error?error.message:'读取做菜历史失败'}
   finally{if(this.isCurrent(session.userId,generation)&&requestId===this.historyRequestId)this.loading=false}
  },
  async completeCooking(recipeId:string,rating:number|null){
   const session=this.ensureSession();if(!session.userId||this.pendingCooking)return false
   if(rating!==null&&(!Number.isInteger(rating)||rating<1||rating>5))return false
   const payload=Object.freeze({recipeId,idempotencyKey:globalThis.crypto.randomUUID(),rating})
   this.pendingCooking={...payload,status:'submitting',message:''}
   return this.sendPendingCooking(session.userId,session.generation,payload.idempotencyKey)
  },
  async retryCooking(){
   const operation=this.pendingCooking
   if(!operation||(operation.status!=='unknown'&&operation.status!=='failed'))return false
   const session=this.ensureSession();if(!session.userId)return false
   this.pendingCooking={...operation,status:'submitting',message:''}
   return this.sendPendingCooking(session.userId,session.generation,operation.idempotencyKey)
  },
  async sendPendingCooking(userId:string,generation:number,idempotencyKey:string){
   const operation=this.pendingCooking
   if(!operation||operation.idempotencyKey!==idempotencyKey)return false
   const payload:CookingRecordInput={recipeId:operation.recipeId,idempotencyKey:operation.idempotencyKey,rating:operation.rating}
   try{
    const result=await recipeApi.completeCooking(payload)
    if(!this.isCurrent(userId,generation)||this.pendingCooking?.idempotencyKey!==idempotencyKey)return false
    this.lastCompletedRecord=result;this.pendingCooking=null
    void this.loadCookedRecipes(1)
    return true
   }catch(error){
    if(!this.isCurrent(userId,generation)||this.pendingCooking?.idempotencyKey!==idempotencyKey)return false
    const failure=describeError(error)
    this.pendingCooking={...operation,status:failure.status,message:failure.message}
    return false
   }
  },
  endCookingOperation(){
   if(!this.pendingCooking||this.pendingCooking.status==='submitting')return false
   this.pendingCooking=null
   return true
  },
  clearForSessionEnd(){this.resetPrivateState();this.ownerId=null},
  resetPrivateState(){
   this.generation++
   this.items=[];this.page=1;this.total=0;this.loading=false;this.errorMessage='';this.loaded=false
   this.pendingCooking=null;this.lastCompletedRecord=null;this.historyRequestId++
  },
 },
})
