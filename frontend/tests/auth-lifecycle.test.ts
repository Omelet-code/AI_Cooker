import {afterEach,describe,expect,it,vi} from 'vitest'
import {createApiClient} from '../src/shared/api/client'
import {ApiError} from '../src/shared/api/types'
import {installAuthenticationLifecycle} from '../src/app/authLifecycle'
import {registerSessionCleanup} from '../src/shared/auth/sessionCleanup'
import {envelope,failure} from './helpers'
type Fetcher=typeof fetch
function fetcher(impl:(url:string,init?:RequestInit)=>Promise<Response>):Fetcher{return (async(input,init)=>impl(String(input),init)) as Fetcher}
function deferred<T>(){let resolve:(value:T)=>void=()=>{};const promise=new Promise<T>(done=>{resolve=done});return {promise,resolve}}
describe('最终认证失效通知与应用装配',()=>{
 afterEach(()=>vi.useRealTimers())
 it('并发请求共享一个被拒续期，并只通知一次',async()=>{
  let refreshCount=0;const refreshGate=deferred<Response>(),reasons:string[]=[]
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/refresh'){refreshCount++;return refreshGate.promise}
   if(url==='/api/auth/csrf')return envelope({token:'c',headerName:'X-XSRF-TOKEN'})
   return failure(401,'ACCESS_TOKEN_EXPIRED')
  })})
  client.onAuthenticationFailure(reason=>reasons.push(reason))
  const first=client.request('/one'),second=client.request('/two')
  for(let i=0;i<30&&refreshCount===0;i++)await Promise.resolve()
  expect(refreshCount).toBe(1)
  refreshGate.resolve(failure(401,'REFRESH_REJECTED'))
  await expect(Promise.all([first,second])).rejects.toBeInstanceOf(ApiError)
  expect(refreshCount).toBe(1)
  expect(reasons).toEqual(['refresh-rejected'])
 })
 it('refresh 后受保护请求仍为认证 401 时通知一次',async()=>{
  const reasons:string[]=[],counts:Record<string,number>={}
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/csrf')return envelope({token:'c',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/auth/refresh')return envelope({id:'1',username:'cook'})
   if(url==='/api/a'||url==='/api/b'){counts[url]=(counts[url]??0)+1;return counts[url]===1?failure(401,'AUTH_REQUIRED'):failure(401,'ACCESS_TOKEN_EXPIRED')}
   return envelope(null)
  })})
  client.onAuthenticationFailure(reason=>reasons.push(reason))
  await Promise.allSettled([client.request('/a'),client.request('/b')])
  expect(counts).toEqual({'/api/a':2,'/api/b':2})
  expect(reasons).toEqual(['replay-unauthorized'])
 })
 it('CSRF 403、权限 403 和网络超时不触发身份失效通知',async()=>{
  const reasons:string[]=[];let csrfVersion=0
  const csrfClient=createApiClient({mode:'real',fetchImpl:fetcher(async(url,init)=>{
   if(url==='/api/auth/csrf'){csrfVersion++;return envelope({token:'csrf-'+csrfVersion,headerName:'X-XSRF-TOKEN'})}
   if(url==='/api/write'){const token=new Headers(init?.headers).get('X-XSRF-TOKEN');return token==='csrf-1'?failure(403,'CSRF_INVALID'):envelope({ok:true})}
   return failure(403,'ACCESS_DENIED')
  })})
  csrfClient.onAuthenticationFailure(reason=>reasons.push(reason))
  await csrfClient.request('/write','POST',{})
  await expect(csrfClient.request('/denied')).rejects.toMatchObject({status:403,code:'ACCESS_DENIED'})
  const networkClient=createApiClient({mode:'real',fetchImpl:fetcher(async()=>{throw new Error('network down')})})
  networkClient.onAuthenticationFailure(reason=>reasons.push(reason))
  await expect(networkClient.request('/offline')).rejects.toMatchObject({code:'NETWORK_ERROR'})
  const timeoutClient=createApiClient({mode:'real',timeoutMs:5,fetchImpl:(async(_input,init)=>new Promise<Response>((_resolve,reject)=>{
   init?.signal?.addEventListener('abort',()=>reject(new Error('aborted')),{once:true})
  })) as Fetcher})
  timeoutClient.onAuthenticationFailure(reason=>reasons.push(reason))
  vi.useFakeTimers()
  const pending=timeoutClient.request('/slow')
  const timeoutAssertion=expect(pending).rejects.toMatchObject({code:'REQUEST_TIMEOUT'})
  await vi.advanceTimersByTimeAsync(6)
  await timeoutAssertion
  expect(reasons).toEqual([])
 })
 it('REFRESH_CONFLICT 后一次 me 成功可恢复原请求且不通知失效',async()=>{
  const reasons:string[]=[];let refreshCount=0,meCount=0,resourceCount=0
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/csrf')return envelope({token:'c',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/private'){resourceCount++;return resourceCount===1?failure(401,'ACCESS_TOKEN_EXPIRED'):envelope({ok:true})}
   if(url==='/api/auth/refresh'){refreshCount++;return failure(409,'REFRESH_CONFLICT')}
   if(url==='/api/users/me'){meCount++;return envelope({id:'1',username:'cook'})}
   throw new Error('unexpected')
  })})
  client.onAuthenticationFailure(reason=>reasons.push(reason))
  await expect(client.request('/private')).resolves.toEqual({ok:true})
  expect({refreshCount,meCount,resourceCount}).toEqual({refreshCount:1,meCount:1,resourceCount:2})
  expect(reasons).toEqual([])
 })
 it('REFRESH_CONFLICT 后 me 认证 401 是终态，通知一次且不循环',async()=>{
  const reasons:string[]=[];let refreshCount=0,meCount=0,resourceCount=0
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/csrf')return envelope({token:'c',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/private'){resourceCount++;return failure(401,'ACCESS_TOKEN_EXPIRED')}
   if(url==='/api/auth/refresh'){refreshCount++;return failure(409,'REFRESH_CONFLICT')}
   if(url==='/api/users/me'){meCount++;return failure(401,'AUTH_REQUIRED')}
   throw new Error('unexpected')
  })})
  client.onAuthenticationFailure(reason=>reasons.push(reason))
  await expect(client.request('/private')).rejects.toMatchObject({status:409,code:'REFRESH_CONFLICT'})
  expect({refreshCount,meCount,resourceCount}).toEqual({refreshCount:1,meCount:1,resourceCount:1})
  expect(reasons).toEqual(['refresh-conflict'])
 })
 it('应用装配清理身份及已注册私有状态，并将并发失效合并为一次路由跳转',async()=>{
  const identity={status:'authenticated' as string,clearUser:vi.fn(function(this:{status:string}){this.status='anonymous'})}
  const route={name:'recipes'}
  const router={currentRoute:{value:route},replace:vi.fn(async()=>{route.name='login'})}
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/csrf')return envelope({token:'c',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/auth/refresh')return failure(401,'REFRESH_REJECTED')
   return failure(401,'ACCESS_TOKEN_EXPIRED')
  })})
  const clean=vi.fn(),removeCleanup=registerSessionCleanup(clean)
  const uninstall=installAuthenticationLifecycle(identity as never,router as never,client)
  await Promise.allSettled([client.request('/one'),client.request('/two')])
  await Promise.resolve()
  expect(identity.clearUser).toHaveBeenCalledOnce()
  expect(clean).toHaveBeenCalledOnce()
  expect(router.replace).toHaveBeenCalledOnce()
  expect(router.replace).toHaveBeenCalledWith({name:'login',query:{reason:'session-expired'}})
  uninstall();removeCleanup()
 })
 it('匿名用户收到终态通知时不清理或重复跳转',async()=>{
  const identity={status:'anonymous',clearUser:vi.fn()}
  const route={name:'login'}
  const router={currentRoute:{value:route},replace:vi.fn()}
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/csrf')return envelope({token:'c',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/auth/refresh')return failure(401,'REFRESH_REJECTED')
   return failure(401,'AUTH_REQUIRED')
  })})
  const uninstall=installAuthenticationLifecycle(identity as never,router as never,client)
  await expect(client.request('/private')).rejects.toMatchObject({code:'REFRESH_REJECTED'})
  expect(identity.clearUser).not.toHaveBeenCalled();expect(router.replace).not.toHaveBeenCalled()
  uninstall()
 })
})
