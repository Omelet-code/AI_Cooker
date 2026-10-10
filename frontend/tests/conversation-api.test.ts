import {describe,expect,it,vi} from 'vitest'
import {createApiClient} from '@/shared/api/client'
import {createConversationApi} from '@/modules/conversation/api'
import {isResourceId} from '@/modules/conversation/validation'
import {envelope} from './helpers'
import {conversationFixture,messageFixture,pageFixture,recommendationFixture,requestId} from './chat-fixtures'

describe('T13/T14前端API契约',()=>{
 it('使用精确相对路径/分页/正文，只有turn POST带125000ms覆盖',async()=>{
  const request=vi.fn(async(path:string,method:string)=>{
   if(path.includes('/recommendation')||path.endsWith('/turns'))return recommendationFixture()
   if(path.includes('/turns/'))return recommendationFixture().turn
   if(path.includes('/messages'))return pageFixture([messageFixture()],2,21)
   if(method==='POST'||path==='/conversations/1')return conversationFixture()
   return pageFixture([conversationFixture()],2,21)
  })
  const api=createConversationApi({request} as never),signal=new AbortController().signal
  await api.list(2,signal);await api.create(undefined,signal);await api.detail('1',signal);await api.messages('1',2,signal)
  await api.turn('1','2',signal);await api.recommendation('1','2',signal)
  const input={requestId,content:'  原始虚构正文🧑  '};await api.send('1',input,signal)
  expect(request.mock.calls.map(call=>call[0])).toEqual(['/conversations?page=2&size=20','/conversations','/conversations/1','/conversations/1/messages?page=2&size=20','/conversations/1/turns/2','/conversations/1/turns/2/recommendation','/conversations/1/turns'])
  expect(request).toHaveBeenLastCalledWith('/conversations/1/turns','POST',input,{auth:'required',signal,timeoutMs:125000})
  expect(request).toHaveBeenNthCalledWith(2,'/conversations','POST',{}, {auth:'required',signal})
  expect(request.mock.calls.slice(0,6).every(call=>!(call[3] as {timeoutMs?:number}).timeoutMs)).toBe(true)
 })
 it('共享Cookie/CSRF真实模式替身接线，HTTP201的FAILED仍是业务失败',async()=>{
  const calls:Array<{url:string;init?:RequestInit}>=[]
  const client=createApiClient({mode:'real',fetchImpl:(async(url,init)=>{
   calls.push({url:String(url),init})
   if(String(url)==='/api/auth/csrf')return envelope({token:'fake-csrf',headerName:'X-XSRF-TOKEN'})
   return new Response(JSON.stringify({code:'OK',message:'成功',data:recommendationFixture('FAILED')}),{status:201})
  }) as typeof fetch})
  const value=await createConversationApi(client).send('1',{requestId,content:'虚构需求'})
  expect(value.turn.status).toBe('FAILED');expect(value.result).toBeNull()
  expect(calls[1].init?.credentials).toBe('include');expect(new Headers(calls[1].init?.headers).get('X-XSRF-TOKEN')).toBe('fake-csrf')
 })
 it('ID与nullable字段保持原义，非法/错会话/非法响应拒绝',async()=>{
  expect(isResourceId('90071992547409930')).toBe(true);expect(isResourceId('9223372036854775808')).toBe(false)
  const result=recommendationFixture(),request=vi.fn(async()=>result)
  const api=createConversationApi({request} as never),value=await api.recommendation('1','2')
  expect(value.result?.recipes[0].recipeId).toBe('90071992547409930')
  expect(value.result?.recipes[0].estimatedMinutes).toBeNull();expect(value.result?.metrics.queryEmbeddingRequests).toBeNull()
  await expect(api.send('javascript:bad',{requestId,content:'虚构'})).rejects.toMatchObject({status:400})
  request.mockResolvedValueOnce({...result,turn:{...result.turn,conversationId:'3'}})
  await expect(api.recommendation('1','2')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  request.mockResolvedValueOnce({turn:result.turn,result:{answer:'缺少其他字段'}} as never)
  await expect(api.send('1',{requestId,content:'虚构'})).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 })
 it('显式Mock不支持聊天时不回退真实fetch',async()=>{
  const fetchImpl=vi.fn(),api=createConversationApi(createApiClient({mode:'mock',fetchImpl}))
  await expect(api.list(1)).rejects.toMatchObject({status:404});expect(fetchImpl).not.toHaveBeenCalled()
 })
})
