import {computed,effectScope,ref} from 'vue'
import {afterEach,describe,expect,it,vi} from 'vitest'
import {useChatState} from '@/modules/conversation/useChatState'
import {ApiError} from '@/shared/api/types'
import {clearSessionScopedState} from '@/shared/auth/sessionCleanup'
import type {ConversationApi,RecommendationTurnView} from '@/modules/conversation/types'
import {conversationFixture,deferred,messageFixture,microtasks,pageFixture,recommendationFixture,requestId} from './chat-fixtures'

const scopes:ReturnType<typeof effectScope>[]=[]
function harness(overrides:Partial<ConversationApi>={}){
 const owner=ref<string|null>('user-a'),scope=effectScope();scopes.push(scope)
 let serial=0
 const uuid=vi.fn(()=>{serial++;return '00000000-0000-4000-8000-'+String(serial).padStart(12,'0')})
 const api:ConversationApi={list:vi.fn(async page=>pageFixture([conversationFixture()],page)),create:vi.fn(async()=>conversationFixture()),detail:vi.fn(async id=>conversationFixture(id)),messages:vi.fn(async(_id,page)=>pageFixture([],page)),turn:vi.fn(async()=>recommendationFixture().turn),recommendation:vi.fn(async(id,turnId)=>{const value=recommendationFixture();value.turn.id=turnId;value.turn.conversationId=id;return value}),send:vi.fn(async(id,input)=>{const value=recommendationFixture();value.turn.conversationId=id;value.turn.requestId=input.requestId;return value}),...overrides}
 const state=scope.run(()=>useChatState(api,computed(()=>owner.value),{uuid}))!
 return {state,api,owner,scope,uuid}
}
afterEach(()=>{for(const scope of scopes.splice(0))scope.stop();vi.useRealTimers();vi.restoreAllMocks()})

describe('实际聊天视图共用状态',()=>{
 it('消息先读最早页并定位最后页，仅预取选中轮次；刷新不POST',async()=>{
  const {api,state}=harness({messages:vi.fn(async(_id,page)=>pageFixture(page===1?[messageFixture('2',1)]:[messageFixture('8',21),messageFixture('9',22)],page,22))})
  await state.activate('1')
  expect(api.messages).toHaveBeenNthCalledWith(1,'1',1,expect.any(AbortSignal));expect(api.messages).toHaveBeenNthCalledWith(2,'1',2,expect.any(AbortSignal))
  expect(state.messages.value.page).toBe(2);expect(api.recommendation).toHaveBeenCalledTimes(1)
  expect(api.recommendation).toHaveBeenCalledWith('1','9',expect.any(AbortSignal))
  await state.loadMessages(1);expect(state.messages.value.page).toBe(1);expect(api.send).not.toHaveBeenCalled()
 })
 it('加载错误不伪造空历史，分页/选择的迟到响应被丢弃',async()=>{
  const old=deferred<ReturnType<typeof pageFixture>>(),latest=pageFixture([conversationFixture('3')],2,21)
  const {api,state}=harness({list:vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(latest)})
  const first=state.loadConversations(1);await state.loadConversations(2);old.resolve(pageFixture([],1));await first
  expect(state.conversations.value.page).toBe(2)
  vi.mocked(api.messages).mockRejectedValueOnce(new ApiError(500,'INTERNAL_ERROR','不得回显正文'))
  await state.activate('1');expect(state.historyState.value).toBe('error');expect(state.historyError.value).not.toContain('不得回显')
  const selection=deferred<RecommendationTurnView>()
  vi.mocked(api.recommendation).mockReturnValueOnce(selection.promise)
  const older=state.selectTurn('2');await state.selectTurn('3');selection.resolve(recommendationFixture());await older
  expect(state.selectedTurnId.value).toBe('3')
 })
 it('创建连点只发一次，未知创建不自动重试且需要显式核对',async()=>{
  const pending=deferred<ReturnType<typeof conversationFixture>>()
  const {state,api}=harness({create:vi.fn(()=>pending.promise)})
  const first=state.createConversation();expect(await state.createConversation()).toBeNull();expect(api.create).toHaveBeenCalledTimes(1)
  pending.reject(new ApiError(0,'NETWORK_ERROR',''));await first
  expect(state.createUnknown.value).toBe(true);expect(await state.createConversation()).toBeNull()
  state.allowAnotherCreation(()=>false);expect(state.createUnknown.value).toBe(true)
  state.allowAnotherCreation(()=>true);expect(state.createUnknown.value).toBe(false)
 })
 it('固定UUID与原文，连点不重复；编辑下一草稿不改变已发送正文',async()=>{
  const pending=deferred<RecommendationTurnView>(),{state,api,uuid}=harness({send:vi.fn(()=>pending.promise)})
  await state.activate('1');state.draft.value='  虚构正文🧑  '
  const first=state.send();expect(await state.send()).toBe(false)
  state.draft.value='下一条草稿';expect(api.send).toHaveBeenCalledWith('1',{requestId,content:'  虚构正文🧑  '},expect.any(AbortSignal))
  pending.resolve(recommendationFixture());expect(await first).toBe(true)
  expect(state.draft.value).toBe('下一条草稿');expect(uuid).toHaveBeenCalledTimes(1)
 })
 it('空白与4001码点被拒绝，4000代理对码点原样发送',async()=>{
  const {state,api}=harness();await state.activate('1')
  state.draft.value=' \n ';expect(await state.send()).toBe(false)
  state.draft.value='🧑'.repeat(4001);expect(await state.send()).toBe(false);expect(api.send).not.toHaveBeenCalled()
  state.draft.value='🧑'.repeat(4000);expect(await state.send()).toBe(true)
  expect(vi.mocked(api.send).mock.calls[0][1].content.length).toBe(8000)
 })
 it.each([[0,'REQUEST_TIMEOUT'],[0,'NETWORK_ERROR'],[0,'REQUEST_ABORTED'],[200,'INVALID_RESPONSE'],[500,'INTERNAL_ERROR']])('HTTP%s/%s保留未知原键，只在确认后核对',async(status,code)=>{
  const {state,api,uuid}=harness({send:vi.fn().mockRejectedValueOnce(new ApiError(status as number,code as string,'原始正文不得透传')).mockResolvedValueOnce(recommendationFixture())})
  await state.activate('1');state.draft.value=' 原始虚构需求 '
  expect(await state.send()).toBe(false);expect(state.pending.value?.status).toBe('unknown')
  expect(api.send).toHaveBeenCalledTimes(1);expect(api.recommendation).not.toHaveBeenCalled();expect(state.pending.value?.message).not.toContain('不得透传')
  await state.checkPending(()=>false);expect(api.send).toHaveBeenCalledTimes(1)
  expect(await state.checkPending(()=>true)).toBe(true)
  expect(vi.mocked(api.send).mock.calls[0][1]).toEqual(vi.mocked(api.send).mock.calls[1][1]);expect(uuid).toHaveBeenCalledTimes(1)
 })
 it('503关闭明确拒绝、409不换键，FAILED不伪造助手或自动执行新轮',async()=>{
  const {state,api,uuid}=harness({send:vi.fn().mockRejectedValueOnce(new ApiError(503,'AGENT_EXECUTION_DISABLED','')).mockRejectedValueOnce(new ApiError(409,'CONVERSATION_BUSY','')).mockImplementationOnce(async(_id,input)=>{const value=recommendationFixture('FAILED');value.turn.requestId=input.requestId;return value}).mockImplementationOnce(async(_id,input)=>{const value=recommendationFixture();value.turn.requestId=input.requestId;return value})})
  await state.activate('1');state.draft.value='虚构需求'
  expect(await state.send()).toBe(false);expect(state.operationError.value).toContain('尚未启用');expect(state.pending.value).toBeNull()
  await state.send();expect(state.pending.value?.status).toBe('conflict');await state.checkPending(()=>true);expect(api.send).toHaveBeenCalledTimes(2)
  state.discardPending(()=>true);expect(await state.send()).toBe(false)
  expect(state.selected.value?.turn.status).toBe('FAILED');expect(state.operationError.value).toContain('模型 Key');expect(state.messages.value.items).toEqual([])
  expect(api.send).toHaveBeenCalledTimes(3);expect(uuid).toHaveBeenCalledTimes(3);expect(state.pending.value).toBeNull()
  state.draft.value='用户明确发起新轮';expect(await state.send()).toBe(true)
  expect(api.send).toHaveBeenCalledTimes(4);expect(uuid).toHaveBeenCalledTimes(4)
  expect(vi.mocked(api.send).mock.calls[3][1].requestId).not.toBe(vi.mocked(api.send).mock.calls[2][1].requestId)
 })
 it('已知在途使用GET，终态立即停止且不重发POST',async()=>{
  vi.useFakeTimers()
  const {state,api}=harness({send:vi.fn(async()=>recommendationFixture('RUNNING')),recommendation:vi.fn().mockResolvedValueOnce(recommendationFixture('RUNNING')).mockResolvedValueOnce(recommendationFixture())})
  await state.activate('1');state.draft.value='虚构需求';const request=state.send();await microtasks()
  await vi.advanceTimersByTimeAsync(6000);expect(await request).toBe(true)
  expect(api.send).toHaveBeenCalledTimes(1);expect(api.recommendation).toHaveBeenCalledTimes(2);expect(state.pending.value).toBeNull()
  await vi.advanceTimersByTimeAsync(120000);expect(api.recommendation).toHaveBeenCalledTimes(2)
 })
 it('GET自动读取达到30次停止，保留尚未确认状态',async()=>{
  vi.useFakeTimers()
  const {state,api}=harness({send:vi.fn(async()=>recommendationFixture('RUNNING')),recommendation:vi.fn(async()=>recommendationFixture('RUNNING'))})
  await state.activate('1');state.draft.value='虚构需求';const request=state.send();await microtasks()
  await vi.advanceTimersByTimeAsync(120000);expect(await request).toBe(false)
  expect(api.recommendation).toHaveBeenCalledTimes(30);expect(state.pending.value?.status).toBe('unknown');expect(state.pollMessage.value).toContain('尚未确认')
  await state.checkPending(()=>{throw new Error('已知turnId不能要求POST确认')});expect(api.send).toHaveBeenCalledTimes(1)
 })
 it('120秒墙钟预算取消未完成GET，不能无限等待',async()=>{
  vi.useFakeTimers()
  const {state,api}=harness({send:vi.fn(async()=>recommendationFixture('RUNNING')),recommendation:vi.fn((_id,_turnId,signal)=>new Promise((_resolve,reject)=>signal?.addEventListener('abort',()=>reject(new ApiError(0,'REQUEST_ABORTED','')),{once:true})))})
  await state.activate('1');state.draft.value='虚构需求';const request=state.send();await microtasks();await vi.advanceTimersByTimeAsync(120000)
  expect(await request).toBe(false);expect(api.recommendation).toHaveBeenCalledTimes(1);expect(state.pending.value?.status).toBe('unknown');expect(state.pollMessage.value).toContain('120秒')
 })
 it('轮询返回同turnId但不同requestId时不当作本次发送成功',async()=>{
  vi.useFakeTimers()
  const wrong=recommendationFixture();wrong.turn.requestId='00000000-0000-4000-8000-000000000099'
  const {state,api}=harness({send:vi.fn(async()=>recommendationFixture('RUNNING')),recommendation:vi.fn(async()=>wrong)})
  await state.activate('1');state.draft.value='虚构需求';const request=state.send();await microtasks();await vi.advanceTimersByTimeAsync(3000)
  expect(await request).toBe(false);expect(state.pending.value?.status).toBe('unknown');expect(state.selected.value?.turn.status).toBe('RUNNING');expect(api.send).toHaveBeenCalledTimes(1)
 })
 it('切换路由停止等待并隔离旧响应，保留原会话核对信息',async()=>{
  const late=deferred<RecommendationTurnView>(),{state,api}=harness({send:vi.fn(()=>late.promise)})
  await state.activate('1');state.draft.value='user-a虚构需求';const request=state.send()
  await state.activate('3');late.resolve(recommendationFixture());await request
  expect(state.activeId.value).toBe('3');expect(state.selected.value).toBeNull();expect(state.draft.value).toBe('');expect(state.pending.value?.conversationId).toBe('1');expect(state.pending.value?.status).toBe('unknown')
  expect(api.send).toHaveBeenCalledTimes(1)
 })
 it('账号变化、退出/最终401共用清理后，迟到响应不能恢复私有数据',async()=>{
  const late=deferred<RecommendationTurnView>(),{state,owner}=harness({send:vi.fn(()=>late.promise)})
  await state.activate('1');state.draft.value='user-a虚构需求';const request=state.send()
  owner.value='user-b';expect(state.pending.value).toBeNull();expect(state.messages.value.items).toEqual([])
  late.resolve(recommendationFixture());await request;expect(state.selected.value).toBeNull()
  await state.activate('1');state.draft.value='user-b草稿';clearSessionScopedState()
  expect(state.draft.value).toBe('');expect(state.conversations.value.items).toEqual([]);expect(state.selected.value).toBeNull()
 })
 it('路由变化取消GET定时器，后续历史读取不会自动POST',async()=>{
  vi.useFakeTimers()
  const {state,api}=harness({send:vi.fn(async()=>recommendationFixture('RUNNING'))})
  await state.activate('1');state.draft.value='虚构需求';const request=state.send();await microtasks()
  await state.activate('3');await request;await vi.advanceTimersByTimeAsync(120000)
  expect(api.recommendation).not.toHaveBeenCalled();expect(api.send).toHaveBeenCalledTimes(1)
  await state.activate('1');await state.loadMessages();expect(api.send).toHaveBeenCalledTimes(1)
 })
 it('未知发送可由服务器消息中的turnId只读核对，不重发POST',async()=>{
  const {state,api}=harness({send:vi.fn().mockRejectedValueOnce(new ApiError(0,'NETWORK_ERROR',''))})
  await state.activate('1');state.draft.value='虚构需求';await state.send()
  vi.mocked(api.messages).mockResolvedValueOnce(pageFixture([messageFixture()]))
  await state.loadMessages()
  expect(api.recommendation).toHaveBeenCalledTimes(1);expect(api.send).toHaveBeenCalledTimes(1);expect(state.pending.value).toBeNull()
 })
 it('已发出的旧GET忽略取消时仍丢弃，旧定时器清除且不能借新页面成功返回true',async()=>{
  vi.useFakeTimers()
  const late=deferred<RecommendationTurnView>(),other=recommendationFixture();other.turn.conversationId='3';other.turn.id='9'
  const {state}=harness({send:vi.fn(async()=>recommendationFixture('RUNNING')),messages:vi.fn(async(id,page)=>pageFixture(id==='3'?[messageFixture('9')]:[],page)),recommendation:vi.fn().mockReturnValueOnce(late.promise).mockResolvedValueOnce(other)})
  await state.activate('1');state.draft.value='虚构需求';const request=state.send();await microtasks();await vi.advanceTimersByTimeAsync(3000)
  await state.activate('3');expect(vi.getTimerCount()).toBe(0)
  late.resolve(recommendationFixture());expect(await request).toBe(false);expect(state.selected.value?.turn.conversationId).toBe('3')
 })
})
