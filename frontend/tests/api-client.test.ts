import {afterEach,describe,expect,it,vi} from 'vitest'
import {createApiClient} from '../src/shared/api/client'
import {ApiError} from '../src/shared/api/types'
import {envelope,failure} from './helpers'
type Fetcher=typeof fetch
function fetcher(impl:(url:string,init?:RequestInit)=>Promise<Response>):Fetcher{return (async(input,init)=>impl(String(input),init)) as Fetcher}
describe('共享 fetch 请求层',()=>{
 afterEach(()=>vi.useRealTimers())
 it('默认 Cookie 凭证且 CSRF 初始化结果只存在内存，并以相对 /api 路径访问',async()=>{
  const calls:Array<{url:string;init?:RequestInit}>=[]
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async(url,init)=>{calls.push({url,init});return envelope({token:'csrf-memory-only',headerName:'X-XSRF-TOKEN'})})})
  await client.getCsrf()
  expect(calls[0].url).toBe('/api/auth/csrf')
  expect(calls[0].init?.credentials).toBe('include')
  expect(calls[0].init?.headers).toBeInstanceOf(Headers)
  expect(JSON.stringify(client)).not.toContain('csrf-memory-only')
 })
 it('并发受保护请求合并为一次 refresh，并各自最多重放一次',async()=>{
  let refreshCount=0,resourceCount=0,releaseRefresh:(response:Response)=>void=()=>{};const perPath:Record<string,number>={}
  const waitingRefresh=new Promise<Response>(resolve=>{releaseRefresh=resolve})
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async(url)=>{
   if(url==='/api/auth/csrf')return envelope({token:'csrf',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/auth/refresh'){refreshCount++;return waitingRefresh}
   if(url==='/api/one'||url==='/api/two'){resourceCount++;const key=url;perPath[key]=(perPath[key]??0)+1;return perPath[key]===1?failure(401,'ACCESS_TOKEN_EXPIRED'):envelope({ok:true})}
   return envelope({ok:true})
  })})
  const one=client.request<{ok:boolean}>('/one'),two=client.request<{ok:boolean}>('/two')
  for(let i=0;i<30&&refreshCount===0;i++)await Promise.resolve()
  expect(refreshCount).toBe(1)
  releaseRefresh(envelope({id:'1',username:'cook'}))
  await expect(Promise.all([one,two])).resolves.toEqual([{ok:true},{ok:true}])
  expect(refreshCount).toBe(1)
  expect(resourceCount).toBe(4)
 })
 it('403 ACCESS_DENIED 不触发 refresh，也不混同为 401',async()=>{
  const paths:string[]=[]
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{paths.push(url);return failure(403,'ACCESS_DENIED')})})
  await expect(client.request('/private')).rejects.toMatchObject({status:403,code:'ACCESS_DENIED'})
  expect(paths).toEqual(['/api/private'])
 })
 it('CSRF_INVALID 只重新初始化 token 并重试写请求一次',async()=>{
  let tokenVersion=0,writeCount=0
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async(url,init)=>{
   if(url==='/api/auth/csrf'){tokenVersion++;return envelope({token:'csrf-'+tokenVersion,headerName:'X-XSRF-TOKEN'})}
   if(url==='/api/auth/register'){writeCount++;const headers=new Headers(init?.headers);return headers.get('X-XSRF-TOKEN')==='csrf-1'?failure(403,'CSRF_INVALID'):envelope({id:'9',username:'cook'})}
   throw new Error('unexpected')
  })})
  await expect(client.request('/auth/register','POST',{username:'cook'})).resolves.toEqual({id:'9',username:'cook'})
  expect(tokenVersion).toBe(2);expect(writeCount).toBe(2)
 })
 it('refresh 自身收到 401 时不递归，也不把原请求无限重放',async()=>{
  let protectedCount=0,refreshCount=0
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/csrf')return envelope({token:'csrf',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/private'){protectedCount++;return failure(401,'ACCESS_TOKEN_EXPIRED')}
   if(url==='/api/auth/refresh'){refreshCount++;return failure(401,'REFRESH_REJECTED')}
   throw new Error('unexpected')
  })})
  await expect(client.request('/private')).rejects.toMatchObject({status:401,code:'REFRESH_REJECTED'})
  expect(protectedCount).toBe(1);expect(refreshCount).toBe(1)
 })
 it('refresh 成功后原请求仍为 401 时，最多只重放一次',async()=>{
  let protectedCount=0,refreshCount=0
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{
   if(url==='/api/auth/csrf')return envelope({token:'csrf',headerName:'X-XSRF-TOKEN'})
   if(url==='/api/private'){protectedCount++;return failure(401,'AUTH_REQUIRED')}
   if(url==='/api/auth/refresh'){refreshCount++;return envelope({id:'1',username:'cook'})}
   throw new Error('unexpected')
  })})
  await expect(client.request('/private')).rejects.toMatchObject({status:401,code:'AUTH_REQUIRED'})
  expect(protectedCount).toBe(2);expect(refreshCount).toBe(1)
 })
 it('登录为公开调用，401 不触发续期',async()=>{
  let refreshCount=0
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async url=>{if(url==='/api/auth/login')return failure(401,'LOGIN_FAILED');if(url==='/api/auth/refresh')refreshCount++;return envelope({token:'csrf',headerName:'X-XSRF-TOKEN'})})})
  await expect(client.request('/auth/login','POST',{username:'cook',password:'not-stored',rememberMe:false},{auth:'public'})).rejects.toMatchObject({code:'LOGIN_FAILED'})
  expect(refreshCount).toBe(0)
 })
 it('不允许在 csrf 或 refresh 请求上建立递归续期链',async()=>{
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async()=>failure(403,'ACCESS_DENIED'))})
  await expect(client.request('/auth/refresh','POST',{}, {auth:'public'})).rejects.toBeInstanceOf(ApiError)
 })
})
