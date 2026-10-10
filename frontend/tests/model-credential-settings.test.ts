import {computed,effectScope,ref} from 'vue'
import {afterEach,describe,expect,it,vi} from 'vitest'
import {createApiClient} from '@/shared/api/client'
import {ApiError} from '@/shared/api/types'
import {createModelCredentialSettingsApi} from '@/modules/settings/api'
import {useModelCredentialSettings} from '@/modules/settings/useModelCredentialSettings'
import type {ModelCredentialSettingsApi,ModelCredentialStatus} from '@/modules/settings/types'
import {envelope,failure} from './helpers'

type Fetcher=typeof fetch
function fetcher(impl:(url:string,init?:RequestInit)=>Promise<Response>):Fetcher{return (async(input,init)=>impl(String(input),init)) as Fetcher}
function deferred<T>(){let resolve!:(value:T)=>void;let reject!:(reason:unknown)=>void;const promise=new Promise<T>((done,fail)=>{resolve=done;reject=fail});return {promise,resolve,reject}}
function harness(overrides:Partial<ModelCredentialSettingsApi>={}){
 const identity=ref<string|null>('user-a')
 const api:ModelCredentialSettingsApi={getStatus:vi.fn(async()=>({configured:false})),save:vi.fn(async()=>({configured:true})),remove:vi.fn(async()=>({configured:false})),...overrides}
 const scope=effectScope()
 const settings=scope.run(()=>useModelCredentialSettings(api,computed(()=>identity.value),()=>true))!
 return {api,identity,scope,settings}
}

afterEach(()=>vi.restoreAllMocks())

describe('模型凭证设置 API',()=>{
 it('使用T8相对路径、受保护请求和原样Key请求体',async()=>{
  const request=vi.fn(async()=>({configured:true}))
  const api=createModelCredentialSettingsApi({request})
  const exactKey='  假-key-🧑‍🍳  '
  await api.getStatus();await api.save(exactKey);await api.remove()
  expect(request).toHaveBeenNthCalledWith(1,'/users/me/llm-credential/status','GET',undefined,{auth:'required'})
  expect(request).toHaveBeenNthCalledWith(2,'/users/me/llm-credential','PUT',{apiKey:exactKey},{auth:'required'})
  expect(request).toHaveBeenNthCalledWith(3,'/users/me/llm-credential','DELETE',undefined,{auth:'required'})
 })

 it('PUT与DELETE经共享客户端携带Cookie和CSRF，不自行重试凭证写入',async()=>{
  const calls:Array<{url:string;init?:RequestInit}> = []
  const client=createApiClient({mode:'real',fetchImpl:fetcher(async(url,init)=>{
   calls.push({url,init})
   if(url==='/api/auth/csrf')return envelope({token:'csrf-test',headerName:'X-XSRF-TOKEN'})
   return envelope(url==='/api/users/me/llm-credential'&&init?.method==='DELETE'?{configured:false}:{configured:true})
  })})
  const api=createModelCredentialSettingsApi(client)
  await api.save('fake-key-only');await api.remove()
  expect(calls.map(call=>call.url)).toEqual(['/api/auth/csrf','/api/users/me/llm-credential','/api/users/me/llm-credential'])
  for(const call of calls.slice(1)){
   expect(call.init?.credentials).toBe('include')
   expect(new Headers(call.init?.headers).get('X-XSRF-TOKEN')).toBe('csrf-test')
  }
  expect(calls[1].init?.body).toBe(JSON.stringify({apiKey:'fake-key-only'}))
 })

 it('开发 Mock 未实现接口时返回明确404，不回退真实网络',async()=>{
  const fetchImpl=vi.fn()
  const api=createModelCredentialSettingsApi(createApiClient({mode:'mock',fetchImpl}))
  await expect(api.getStatus()).rejects.toMatchObject({status:404,code:'NOT_FOUND'})
  expect(fetchImpl).not.toHaveBeenCalled()
 })
})

describe('模型凭证设置状态与页面私有输入',()=>{
 it('加载失败保持未知状态，不能伪装成未配置',async()=>{
  const {api,settings}=harness({getStatus:vi.fn().mockRejectedValue(new ApiError(500,'INTERNAL_ERROR','不得透传的服务端内容'))})
  expect(await settings.loadStatus()).toBe(false)
  expect(settings.configured.value).toBeNull();expect(settings.statusState.value).toBe('error')
  expect(settings.statusError.value).toContain('凭证服务');expect(settings.statusError.value).not.toContain('不得透传')
  expect(api.getStatus).toHaveBeenCalledTimes(1)
 })

 it('拒绝空白和超过4096 Unicode码点输入，并原样提交合法内容且成功后清空',async()=>{
  const {api,settings}=harness({getStatus:vi.fn(async()=>({configured:true}))})
  await settings.loadStatus()
  settings.apiKey.value=' \t\n '
  expect(await settings.save()).toBe(false);expect(api.save).not.toHaveBeenCalled()
  settings.apiKey.value='🧑'.repeat(4097)
  expect(await settings.save()).toBe(false);expect(api.save).not.toHaveBeenCalled()
  const exactKey='  假-key-🧑‍🍳  '
  settings.apiKey.value=exactKey
  expect(await settings.save()).toBe(true)
  expect(api.save).toHaveBeenCalledWith(exactKey);expect(settings.apiKey.value).toBe('')
  expect(settings.actionMessage.value).toContain('未验证')
 })

 it('连续提交只发送一次写请求',async()=>{
  const pending=deferred<ModelCredentialStatus>(),{api,settings}=harness({save:vi.fn(()=>pending.promise)})
  await settings.loadStatus();settings.apiKey.value='fake-key'
  const first=settings.save(),second=settings.save()
  expect(await second).toBe(false);expect(api.save).toHaveBeenCalledTimes(1)
  pending.resolve({configured:true});await first
 })

 it('删除需要确认，取消不发请求，确认后清除输入',async()=>{
  const confirm=vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true)
  const api:ModelCredentialSettingsApi={getStatus:vi.fn(async()=>({configured:true})),save:vi.fn(),remove:vi.fn(async()=>({configured:false}))}
  const userId=ref<string|null>('user-a'),scope=effectScope()
  const settings=scope.run(()=>useModelCredentialSettings(api,computed(()=>userId.value),confirm))!
  await settings.loadStatus();settings.apiKey.value='unsaved-fake-key'
  expect(await settings.remove()).toBe(false);expect(api.remove).not.toHaveBeenCalled()
  expect(await settings.remove()).toBe(true);expect(api.remove).toHaveBeenCalledTimes(1)
  expect(settings.configured.value).toBe(false);expect(settings.apiKey.value).toBe('')
  scope.stop()
 })

 it('写入超时不自动重试或回读，只有用户触发时回读状态',async()=>{
  const {api,settings}=harness({save:vi.fn().mockRejectedValue(new ApiError(0,'REQUEST_TIMEOUT','timeout'))})
  await settings.loadStatus();settings.apiKey.value='fake-key'
  expect(await settings.save()).toBe(false)
  expect(api.save).toHaveBeenCalledTimes(1);expect(api.getStatus).toHaveBeenCalledTimes(1)
  expect(settings.writeUncertain.value).toBe('save')
  await settings.loadStatus();expect(api.getStatus).toHaveBeenCalledTimes(2)
 })

 it('账号切换会清空输入并丢弃旧用户迟到的状态响应',async()=>{
  const pending=deferred<ModelCredentialStatus>()
  const getStatus=vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValueOnce({configured:true})
  const {identity,settings}=harness({getStatus})
  const oldRequest=settings.loadStatus();settings.apiKey.value='user-a-fake-key'
  identity.value='user-b'
  pending.resolve({configured:false});await oldRequest
  await Promise.resolve()
  expect(settings.apiKey.value).toBe('');expect(settings.configured.value).toBe(true)
  expect(getStatus).toHaveBeenCalledTimes(2)
 })

 it('页面销毁时清除输入，失败消息不回显凭证内容',async()=>{
  const {api,scope,settings}=harness({save:vi.fn().mockRejectedValue(new ApiError(403,'ACCESS_DENIED','fake-key-must-not-appear'))})
  await settings.loadStatus();settings.apiKey.value='fake-key-must-not-appear'
  expect(await settings.save()).toBe(false)
  expect(settings.actionError.value).not.toContain('fake-key-must-not-appear')
  scope.stop();expect(settings.apiKey.value).toBe('')
 })
})
