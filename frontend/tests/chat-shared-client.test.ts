import {afterEach,describe,expect,it,vi} from 'vitest'
import {createApiClient} from '@/shared/api/client'
import {envelope,failure} from './helpers'
import {deferred,microtasks} from './chat-fixtures'
const csrf=()=>envelope({token:'fake-csrf',headerName:'X-XSRF-TOKEN'})
function clientWith(handler:(url:string,init?:RequestInit)=>Promise<Response>){return createApiClient({mode:'real',fetchImpl:(async(url,init)=>handler(String(url),init)) as typeof fetch})}
afterEach(()=>vi.useRealTimers())

describe('聊天单请求超时与共享取消接线',()=>{
 it('默认10秒不变，发送的125秒覆盖仅影响本次请求',async()=>{
  vi.useFakeTimers()
  const calls:string[]=[]
  const client=clientWith(async(url,init)=>{
   calls.push(url);if(url==='/api/auth/csrf')return csrf()
   return new Promise<Response>((_resolve,reject)=>init?.signal?.addEventListener('abort',()=>reject(new Error('abort')),{once:true}))
  })
  const get=client.request('/ordinary').catch(error=>error)
  const send=client.request('/conversations/1/turns','POST',{content:'虚构需求'},{timeoutMs:125000}).catch(error=>error)
  await microtasks();await vi.advanceTimersByTimeAsync(10000)
  expect(await get).toMatchObject({code:'REQUEST_TIMEOUT'})
  let sendSettled=false;void send.then(()=>{sendSettled=true});await microtasks();expect(sendSettled).toBe(false)
  await vi.advanceTimersByTimeAsync(115000);expect(await send).toMatchObject({code:'REQUEST_TIMEOUT'})
  expect(calls.filter(path=>path.endsWith('/turns'))).toHaveLength(1)
  const next=client.request('/another').catch(error=>error)
  await microtasks();await vi.advanceTimersByTimeAsync(10000);expect(await next).toMatchObject({code:'REQUEST_TIMEOUT'})
 })
 it('已经取消的signal不发任何请求',async()=>{
  const handler=vi.fn(async()=>csrf()),client=clientWith(handler),abort=new AbortController();abort.abort()
  await expect(client.request('/conversations/1/turns','POST',{}, {signal:abort.signal})).rejects.toMatchObject({code:'REQUEST_ABORTED'})
  expect(handler).not.toHaveBeenCalled()
 })
 it('CSRF初始等待时取消，不在令牌回来后发旧POST',async()=>{
  const pending=deferred<Response>(),paths:string[]=[],abort=new AbortController()
  const client=clientWith(async url=>{paths.push(url);return url==='/api/auth/csrf'?pending.promise:envelope({})})
  const request=client.request('/conversations/1/turns','POST',{}, {signal:abort.signal}).catch(error=>error)
  await microtasks();abort.abort();expect(await request).toMatchObject({code:'REQUEST_ABORTED'})
  pending.resolve(csrf());await microtasks();expect(paths).toEqual(['/api/auth/csrf'])
 })
 it('强制CSRF更新等待时取消，不发原请求的第二次POST',async()=>{
  const pending=deferred<Response>(),abort=new AbortController();let csrfCalls=0,writes=0
  const client=clientWith(async url=>{
   if(url==='/api/auth/csrf'){csrfCalls++;return csrfCalls===1?csrf():pending.promise}
   writes++;return failure(403,'CSRF_INVALID')
  })
  const request=client.request('/conversations/1/turns','POST',{}, {signal:abort.signal}).catch(error=>error)
  await microtasks();expect(csrfCalls).toBe(2);abort.abort();expect(await request).toMatchObject({code:'REQUEST_ABORTED'})
  pending.resolve(csrf());await microtasks();expect(writes).toBe(1)
 })
 it('续期等待时取消仍允许共用续期完成，但不重放旧POST',async()=>{
  const pending=deferred<Response>(),abort=new AbortController();let writes=0,refreshes=0
  const client=clientWith(async url=>{
   if(url==='/api/auth/csrf')return csrf()
   if(url==='/api/auth/refresh'){refreshes++;return pending.promise}
   writes++;return failure(401,'ACCESS_TOKEN_EXPIRED')
  })
  const request=client.request('/conversations/1/turns','POST',{}, {signal:abort.signal}).catch(error=>error)
  await microtasks();expect(refreshes).toBe(1);abort.abort();expect(await request).toMatchObject({code:'REQUEST_ABORTED'})
  pending.resolve(envelope({id:'1',username:'fake'}));await microtasks();expect(writes).toBe(1)
 })
 it('CSRF与401有限重放沿用原正文和编号，不新增业务重试',async()=>{
  const bodies:Array<unknown>=[];let writes=0,refreshes=0
  const client=clientWith(async(url,init)=>{
   if(url==='/api/auth/csrf')return csrf()
   if(url==='/api/auth/refresh'){refreshes++;return envelope({id:'1',username:'fake'})}
   bodies.push(init?.body);writes++
   return writes===1?failure(403,'CSRF_INVALID'):writes===2?failure(401,'ACCESS_TOKEN_EXPIRED'):envelope({ok:true})
  })
  const input={requestId:'00000000-0000-4000-8000-000000000001',content:' 原始虚构需求 '}
  await expect(client.request('/conversations/1/turns','POST',input,{timeoutMs:125000})).resolves.toEqual({ok:true})
  expect(writes).toBe(3);expect(refreshes).toBe(1);expect(new Set(bodies).size).toBe(1)
 })
})
