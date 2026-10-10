import {computed,onScopeDispose,ref,watch,type ComputedRef} from 'vue'
import {ApiError} from '@/shared/api/types'
import type {ModelCredentialSettingsApi,ModelCredentialStatus} from './types'

export type CredentialStatusState='loading'|'ready'|'error'
export type CredentialWriteAction='save'|'delete'
function validStatus(value:unknown):value is ModelCredentialStatus{
 return value!==null&&typeof value==='object'&&typeof (value as ModelCredentialStatus).configured==='boolean'
}
function isUnknownResult(error:unknown):boolean{
 return error instanceof ApiError&&error.status===0&&(error.code==='REQUEST_TIMEOUT'||error.code==='NETWORK_ERROR')
}
function safeErrorMessage(error:unknown,action:'read'|'save'|'delete'):string{
 if(error instanceof ApiError){
  if(error.status===0){
   return error.code==='REQUEST_TIMEOUT'?'请求超时，结果尚未确认。请检查网络后再决定是否重试。':'网络连接失败，结果尚未确认。请检查网络后再决定是否重试。'
  }
  if(error.status===401)return '登录状态已失效，请重新登录后再管理模型 Key。'
  if(error.status===400)return 'Key 未通过服务端校验，请检查输入内容。'
  if(error.status===403&&error.code==='CSRF_INVALID')return '安全校验未通过，请重新读取页面后重试。'
  if(error.status===403)return '当前账号无权管理模型 Key。'
  if(error.status===500)return '凭证服务暂不可用，请稍后重试。'
  if(error.status===404)return '模型凭证接口暂不可用，请稍后重试。'
  if(error.code==='INVALID_RESPONSE')return '服务器响应格式异常，未能确认操作结果。'
 }
 return action==='read'?'读取配置状态失败，请稍后重试。':'操作未完成，请检查网络后重试。'
}

/** 页面私有状态不进入Pinia或Web Storage，身份与页面生命周期变化时清空。 */
export function useModelCredentialSettings(
 api:ModelCredentialSettingsApi,
 userId:ComputedRef<string|null>,
 confirmDelete:()=>boolean=()=>window.confirm('确定删除已保存的模型 Key 吗？删除后，后续模型功能将无法使用该凭证。'),
){
 const apiKey=ref(''),configured=ref<boolean|null>(null)
 const statusState=ref<CredentialStatusState>('loading'),statusError=ref('')
 const actionError=ref(''),actionMessage=ref(''),writeUncertain=ref<CredentialWriteAction|null>(null)
 const validationError=ref(''),busyAction=ref<CredentialWriteAction|null>(null)
 const isBusy=computed(()=>busyAction.value!==null)
 let generation=0,statusRequestId=0,disposed=false

 function isCurrent(requestGeneration:number,ownerId:string|null){
  return !disposed&&generation===requestGeneration&&userId.value===ownerId&&ownerId!==null
 }
 function clearLocalState(){
  apiKey.value='';configured.value=null;statusError.value='';actionError.value='';actionMessage.value=''
  validationError.value='';writeUncertain.value=null;busyAction.value=null
 }
 async function loadStatus(){
  const ownerId=userId.value
  if(!ownerId){statusState.value='error';configured.value=null;statusError.value='登录状态无效，请重新登录。';return false}
  if(isBusy.value)return false
  const requestGeneration=generation,requestId=++statusRequestId
  configured.value=null;statusState.value='loading';statusError.value='';actionError.value=''
  try{
   const result=await api.getStatus()
   if(!isCurrent(requestGeneration,ownerId)||requestId!==statusRequestId)return false
   if(!validStatus(result))throw new ApiError(200,'INVALID_RESPONSE','')
   configured.value=result.configured;statusState.value='ready'
   return true
  }catch(error){
   if(!isCurrent(requestGeneration,ownerId)||requestId!==statusRequestId)return false
   configured.value=null;statusState.value='error';statusError.value=safeErrorMessage(error,'read')
   return false
  }
 }
 function clearValidation(){validationError.value=''}
 function validateKey(value:string){
  if(value.trim().length===0){validationError.value='请输入模型 Key。';return false}
  if(Array.from(value).length>4096){validationError.value='Key 最多可包含 4096 个 Unicode 字符。';return false}
  validationError.value='';return true
 }
 async function save(){
  if(isBusy.value||statusState.value!=='ready'||userId.value===null||!validateKey(apiKey.value))return false
  const ownerId=userId.value,requestGeneration=generation,submittedKey=apiKey.value
  busyAction.value='save';actionError.value='';actionMessage.value='';writeUncertain.value=null
  try{
   const result=await api.save(submittedKey)
   if(!isCurrent(requestGeneration,ownerId))return false
   if(!validStatus(result)||result.configured!==true)throw new ApiError(200,'INVALID_RESPONSE','')
   configured.value=true;apiKey.value='';validationError.value='';actionMessage.value='Key 已保存。服务端仅确认凭证已配置，并未验证 Key 是否有效。'
   return true
  }catch(error){
   if(!isCurrent(requestGeneration,ownerId))return false
   if(isUnknownResult(error))writeUncertain.value='save'
   actionError.value=safeErrorMessage(error,'save')
   return false
  }finally{if(isCurrent(requestGeneration,ownerId)&&busyAction.value==='save')busyAction.value=null}
 }
 async function remove(){
  if(isBusy.value||statusState.value!=='ready'||configured.value!==true||userId.value===null)return false
  if(!confirmDelete())return false
  const ownerId=userId.value,requestGeneration=generation
  busyAction.value='delete';actionError.value='';actionMessage.value='';writeUncertain.value=null
  try{
   const result=await api.remove()
   if(!isCurrent(requestGeneration,ownerId))return false
   if(!validStatus(result)||result.configured!==false)throw new ApiError(200,'INVALID_RESPONSE','')
   configured.value=false;apiKey.value='';validationError.value='';actionMessage.value='已删除保存的 Key。'
   return true
  }catch(error){
   if(!isCurrent(requestGeneration,ownerId))return false
   if(isUnknownResult(error))writeUncertain.value='delete'
   actionError.value=safeErrorMessage(error,'delete')
   return false
  }finally{if(isCurrent(requestGeneration,ownerId)&&busyAction.value==='delete')busyAction.value=null}
 }

 watch(userId,(next,previous)=>{
  if(next===previous)return
  generation++;statusRequestId++;clearLocalState()
  if(next){statusState.value='loading';void loadStatus()}
  else{statusState.value='error';statusError.value='登录状态已失效，请重新登录。'}
 },{flush:'sync'})
 onScopeDispose(()=>{disposed=true;generation++;statusRequestId++;clearLocalState();statusState.value='loading'})

 return {apiKey,configured,statusState,statusError,actionError,actionMessage,writeUncertain,validationError,busyAction,isBusy,loadStatus,save,remove,clearValidation}
}
