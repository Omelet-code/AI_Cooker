import {computed,ref,watch,type ComputedRef} from 'vue'
import type {CookingOperation} from '@/stores/cooking'

interface CookingCompletionStore {
 pendingCooking:CookingOperation|null
 completeCooking(recipeId:string,rating:number|null):Promise<boolean>
 retryCooking():Promise<boolean>
 endCookingOperation():boolean
}

/** 菜谱详情页的完成确认状态；异步结果只允许清理发起操作时的页面和账号。 */
export function useCookingCompletion(
 recipeId:ComputedRef<string>,
 userId:ComputedRef<string|null>,
 hasRecipe:ComputedRef<boolean>,
 cooking:CookingCompletionStore,
){
 const showCompletion=ref(false),selectedRating=ref('')
 const anyPending=computed(()=>cooking.pendingCooking!==null)
 const dialogVisible=computed(()=>showCompletion.value&&hasRecipe.value&&!anyPending.value)
 function clearCompletion(){showCompletion.value=false;selectedRating.value=''}
 watch([recipeId,userId],clearCompletion,{flush:'sync'})
 function openConfirmation(){if(!anyPending.value)showCompletion.value=true}
 function cancelConfirmation(){clearCompletion()}
 async function confirmCooking(){
  if(!recipeId.value||!hasRecipe.value||anyPending.value)return false
  const operationRecipeId=recipeId.value,operationUserId=userId.value
  const rating=selectedRating.value===''?null:Number(selectedRating.value)
  const succeeded=await cooking.completeCooking(operationRecipeId,rating)
  if(succeeded&&recipeId.value===operationRecipeId&&userId.value===operationUserId)clearCompletion()
  return succeeded
 }
 async function retryCooking(){
  const operation=cooking.pendingCooking
  if(!operation)return false
  const operationUserId=userId.value
  const succeeded=await cooking.retryCooking()
  if(succeeded&&recipeId.value===operation.recipeId&&userId.value===operationUserId)clearCompletion()
  return succeeded
 }
 function endPending(){
  const ended=cooking.endCookingOperation()
  if(ended)clearCompletion()
  return ended
 }
 return {selectedRating,anyPending,dialogVisible,openConfirmation,cancelConfirmation,confirmCooking,retryCooking,endPending}
}
