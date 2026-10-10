import {ApiError,type ApiEnvelope,type RequestOptions} from './types'
export type AuthenticationFailureReason='refresh-rejected'|'replay-unauthorized'|'refresh-conflict'
type AuthenticationFailureHandler=(reason:AuthenticationFailureReason)=>void
type Mode='real'|'mock'
interface ClientOptions{fetchImpl?:typeof fetch;mode?:Mode;timeoutMs?:number}
export interface CsrfData{token:string;headerName:'X-XSRF-TOKEN'}
const safeMethods=new Set(['GET','HEAD','OPTIONS'])
const refreshableCodes=new Set(['AUTH_REQUIRED','ACCESS_TOKEN_EXPIRED'])
const authenticationFailureCodes=new Set(['AUTH_REQUIRED','ACCESS_TOKEN_EXPIRED','REFRESH_REJECTED'])
function parseError(status:number,payload:unknown):ApiError{
 if(payload&&typeof payload==='object'){
  const body=payload as Partial<ApiEnvelope<unknown>>
  if(typeof body.code==='string'&&typeof body.message==='string')return new ApiError(status,body.code,body.message,body.fieldErrors??[])
 }
 return new ApiError(status,'INTERNAL_ERROR','请求失败，请稍后重试')
}
function isCsrfError(error:unknown):error is ApiError{return error instanceof ApiError&&error.status===403&&error.code==='CSRF_INVALID'}
function isRefreshable(error:unknown):error is ApiError{return error instanceof ApiError&&error.status===401&&refreshableCodes.has(error.code)}
function isAuthenticationFailure(error:unknown):error is ApiError{return error instanceof ApiError&&error.status===401&&authenticationFailureCodes.has(error.code)}
function abortedRequest(){return new ApiError(0,'REQUEST_ABORTED','已停止本地等待，服务器可能仍在处理。')}
function assertActive(signal?:AbortSignal){if(signal?.aborted)throw abortedRequest()}
/** 取消单个等待者，不取消其他请求共用的CSRF或续期请求。 */
function waitActive<T>(promise:Promise<T>,signal?:AbortSignal):Promise<T>{
 if(!signal)return promise
 assertActive(signal)
 return new Promise<T>((resolve,reject)=>{
  const abort=()=>{signal.removeEventListener('abort',abort);reject(abortedRequest())}
  signal.addEventListener('abort',abort,{once:true})
  promise.then(value=>{signal.removeEventListener('abort',abort);if(signal.aborted)reject(abortedRequest());else resolve(value)},error=>{signal.removeEventListener('abort',abort);reject(error)})
 })
}
export function createApiClient(options:ClientOptions={}){
 const fetchImpl=options.fetchImpl??fetch,timeoutMs=options.timeoutMs??10_000
 const mode:Mode=options.mode??(import.meta.env.VITE_API_MODE==='mock'?'mock':'real')
 let csrf:CsrfData|null=null,csrfPromise:Promise<CsrfData>|null=null,refreshPromise:Promise<void>|null=null
 let authFailureNotified=false
 const authFailureHandlers=new Set<AuthenticationFailureHandler>()
 let mockUser:{id:string;username:string}|null=null,mockAuthenticated=false

 function notifyAuthenticationFailure(reason:AuthenticationFailureReason){
  if(authFailureNotified)return
  authFailureNotified=true
  for(const handler of authFailureHandlers){
   try{handler(reason)}catch{/* 认证失效通知不能改变请求错误处理结果。 */}
  }
 }
 async function mockResponse<T>(path:string,method:string,body:unknown):Promise<T>{
  await Promise.resolve()
  const input=(body??{}) as Record<string,unknown>
  if(path==='/auth/csrf'&&method==='GET')return {token:'development-csrf-token',headerName:'X-XSRF-TOKEN'} as T
  if(path==='/auth/register'&&method==='POST'){mockUser={id:'mock-user-1',username:String(input.username??'').trim().toLowerCase()};return mockUser as T}
  if(path==='/auth/login'&&method==='POST'){
   const username=String(input.username??'').trim().toLowerCase()
   mockUser=mockUser?.username===username?mockUser:{id:'mock-user-1',username};mockAuthenticated=true
   return mockUser as T
  }
  if(path==='/auth/refresh'&&method==='POST'&&mockAuthenticated&&mockUser)return mockUser as T
  if(path==='/auth/logout'&&method==='POST'){mockAuthenticated=false;return null as T}
  if(path==='/users/me'&&method==='GET'&&mockAuthenticated&&mockUser)return mockUser as T
  if(path==='/users/me'&&method==='GET')throw new ApiError(401,'AUTH_REQUIRED','请先登录')
  throw new ApiError(404,'NOT_FOUND','开发 Mock 未实现此接口')
 }
 async function getCsrf(force=false):Promise<CsrfData>{
  if(csrf&&!force)return csrf
  if(csrfPromise)return csrfPromise
  csrfPromise=(async()=>{
   if(mode==='mock')csrf=await mockResponse<CsrfData>('/auth/csrf','GET',undefined)
   else csrf=await raw<CsrfData>('/auth/csrf','GET',undefined,false)
   return csrf
  })().finally(()=>{csrfPromise=null})
  return csrfPromise
 }
 async function raw<T>(path:string,method:string,body:unknown,withCsrf:boolean,signal?:AbortSignal,requestTimeoutMs=timeoutMs):Promise<T>{
  assertActive(signal)
  if(mode==='mock')return waitActive(mockResponse<T>(path,method,body),signal)
  if(withCsrf&&!safeMethods.has(method))await waitActive(getCsrf(),signal)
  assertActive(signal)
  const headers=new Headers({Accept:'application/json'})
  if(body!==undefined)headers.set('Content-Type','application/json')
  if(withCsrf&&!safeMethods.has(method)){
   const token=await waitActive(getCsrf(),signal)
   headers.set(token.headerName,token.token)
  }
  const controller=new AbortController()
  let timedOut=false
  const timer=setTimeout(()=>{timedOut=true;controller.abort()},requestTimeoutMs)
  const abort=()=>controller.abort()
  signal?.addEventListener('abort',abort,{once:true})
  try{
   assertActive(signal)
   const response=await fetchImpl('/api'+path,{
    method,credentials:'include',headers,cache:'no-store',
    body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal,
   })
   let payload:unknown=null
   try{payload=await response.json()}catch{payload=null}
   assertActive(signal)
   if(timedOut)throw new ApiError(0,'REQUEST_TIMEOUT','请求超时，请检查网络后重试')
   if(!response.ok)throw parseError(response.status,payload)
   const envelope=payload as Partial<ApiEnvelope<T>>|null
   if(!envelope||envelope.code!=='OK'||!('data'in envelope))throw new ApiError(response.status,'INVALID_RESPONSE','服务器响应格式不正确')
   return envelope.data as T
  }catch(error){
   assertActive(signal)
   if(error instanceof ApiError)throw error
   throw new ApiError(0,timedOut?'REQUEST_TIMEOUT':'NETWORK_ERROR',timedOut?'请求超时，请检查网络后重试':'网络连接失败，请稍后重试')
  }finally{
   clearTimeout(timer);signal?.removeEventListener('abort',abort)
  }
 }
 async function execute<T>(
  path:string,method:string,body:unknown,requestOptions:RequestOptions={},
  state={csrfRetried:false,replayed:false,allowRefresh:true},
 ):Promise<T>{
  try{
   assertActive(requestOptions.signal)
   const result=await raw<T>(path,method,body,true,requestOptions.signal,requestOptions.timeoutMs??timeoutMs)
   if(path==='/auth/login'||path==='/auth/refresh'||path==='/users/me')authFailureNotified=false
   return result
  }catch(error){
   assertActive(requestOptions.signal)
   if(isCsrfError(error)&&!state.csrfRetried&&path!=='/auth/csrf'){
    state.csrfRetried=true
    await waitActive(getCsrf(true),requestOptions.signal)
    return execute<T>(path,method,body,requestOptions,state)
   }
   if(isRefreshable(error)&&state.replayed){
    notifyAuthenticationFailure('replay-unauthorized')
    throw error
   }
   if(isRefreshable(error)&&requestOptions.auth!=='public'&&state.allowRefresh&&!state.replayed&&path!=='/auth/refresh'){
    state.replayed=true
    await waitActive(refreshOnce(),requestOptions.signal)
    return execute<T>(path,method,body,requestOptions,state)
   }
   throw error
  }
 }
 async function refreshOnce():Promise<void>{
  if(refreshPromise)return refreshPromise
  refreshPromise=(async()=>{
   try{
    await execute<unknown>('/auth/refresh','POST',{}, {auth:'public'},{csrfRetried:false,replayed:false,allowRefresh:false})
   }catch(error){
    if(error instanceof ApiError&&error.code==='REFRESH_CONFLICT'&&error.status===409){
     try{
      await execute<unknown>('/users/me','GET',undefined,{auth:'public'},{csrfRetried:false,replayed:false,allowRefresh:false})
      return
     }catch(meError){
      if(isAuthenticationFailure(meError))notifyAuthenticationFailure('refresh-conflict')
      else throw meError
      throw error
     }
    }
    if(isAuthenticationFailure(error))notifyAuthenticationFailure('refresh-rejected')
    throw error
   }
  })().finally(()=>{refreshPromise=null})
  return refreshPromise
 }
 return{
  mode,
  getCsrf:()=>getCsrf(),
  refreshCsrf:()=>getCsrf(true),
  onAuthenticationFailure(handler:AuthenticationFailureHandler){
   authFailureHandlers.add(handler)
   return()=>authFailureHandlers.delete(handler)
  },
  request:<T>(path:string,method='GET',body?:unknown,requestOptions:RequestOptions={})=>execute<T>(path,method,body,requestOptions),
  resetForTests(){
   csrf=null;csrfPromise=null;refreshPromise=null;authFailureNotified=false;authFailureHandlers.clear()
   mockUser=null;mockAuthenticated=false
  },
 }
}
export const apiClient=createApiClient()
