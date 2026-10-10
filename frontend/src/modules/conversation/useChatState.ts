import {computed,onScopeDispose,ref,watch,type ComputedRef} from 'vue'
import {registerSessionCleanup} from '@/shared/auth/sessionCleanup'
import {ApiError} from '@/shared/api/types'
import {chatErrorMessage,isUnknownWrite,turnErrorMessage} from './presentation'
import {contentError,invalidResponse,isResourceId,titleError} from './validation'
import type {ChatMessageView,ConversationApi,ConversationView,Page,RecommendationTurnView,TurnInput} from './types'

type LoadState='idle'|'loading'|'ready'|'error'
export interface PendingSend{
 userId:string;conversationId:string;payload:TurnInput;turnId:string|null
 status:'sending'|'polling'|'unknown'|'conflict';message:string
}
interface StateOptions{uuid?:()=>string;now?:()=>number}
const emptyPage=<T>():Page<T>=>({items:[],page:1,size:20,total:0})
const terminal=(data:RecommendationTurnView)=>data.turn.status==='SUCCEEDED'||data.turn.status==='FAILED'
function wait(ms:number,signal:AbortSignal){
 return new Promise<void>((resolve,reject)=>{
  const abort=()=>{clearTimeout(timer);signal.removeEventListener('abort',abort);reject(new ApiError(0,'REQUEST_ABORTED',''))}
  const timer=setTimeout(()=>{signal.removeEventListener('abort',abort);resolve()},ms)
  signal.addEventListener('abort',abort,{once:true})
  if(signal.aborted)abort()
 })
}

/** 视图与测试共用此状态；所有私有内容只在当前页面进程的内存中。 */
export function useChatState(api:ConversationApi,userId:ComputedRef<string|null>,options:StateOptions={}){
 const uuid=options.uuid??(()=>crypto.randomUUID()),now=options.now??Date.now
 const conversations=ref(emptyPage<ConversationView>()),listState=ref<LoadState>('idle'),listError=ref('')
 const titleDraft=ref(''),creating=ref(false),createUnknown=ref(false),createError=ref('')
 const activeId=ref<string|null>(null),conversation=ref<ConversationView|null>(null)
 const messages=ref(emptyPage<ChatMessageView>()),historyState=ref<LoadState>('idle'),historyError=ref('')
 const selectedTurnId=ref<string|null>(null),selected=ref<RecommendationTurnView|null>(null),selectionState=ref<LoadState>('idle'),selectionError=ref('')
 const draft=ref(''),inputError=ref(''),operationError=ref(''),pending=ref<PendingSend|null>(null)
 const polling=ref(false),pollCount=ref(0),pollMessage=ref('')
 const pendingForActive=computed(()=>pending.value?.conversationId===activeId.value?pending.value:null)
 const isSending=computed(()=>pending.value?.status==='sending'||pending.value?.status==='polling')
 const canSend=computed(()=>Boolean(conversation.value)&&historyState.value==='ready'&&!pending.value&&!polling.value)
 let ownerGeneration=0,viewGeneration=0,listGeneration=0,historyGeneration=0,selectionGeneration=0,disposed=false
 let listController:AbortController|null=null,selectionController:AbortController|null=null,historyController:AbortController|null=null
 const controllers=new Set<AbortController>()
 const controller=()=>{const value=new AbortController();controllers.add(value);return value}
 const context=()=>({user:userId.value,owner:ownerGeneration,view:viewGeneration,id:activeId.value})
 function current(c:ReturnType<typeof context>){return !disposed&&c.user!==null&&c.user===userId.value&&c.owner===ownerGeneration&&c.view===viewGeneration&&c.id===activeId.value}
 function stopSelection(){
  selectionGeneration++;selectionController?.abort();selectionController=null;polling.value=false
  if(pending.value?.status==='polling'){pending.value.status='unknown';pending.value.message='已停止本地读取，服务器可能仍在处理。'}
 }
 function cancelView(){
  viewGeneration++;historyGeneration++;stopSelection()
  for(const request of controllers)request.abort()
  controllers.clear();historyController=null
  if(pending.value?.status==='sending'){pending.value.status='unknown';pending.value.message='已停止本地等待，服务器可能仍在处理。'}
  if(creating.value){createUnknown.value=true;createError.value='新建对话结果尚未确认，请先刷新会话列表核对。'}
  creating.value=false
 }
 function clearView(){
  conversation.value=null;messages.value=emptyPage();historyState.value='idle';historyError.value=''
  selected.value=null;selectedTurnId.value=null;selectionState.value='idle';selectionError.value=''
  draft.value='';inputError.value='';operationError.value='';pollCount.value=0;pollMessage.value=''
 }
 function clearAll(){
  ownerGeneration++;listGeneration++;cancelView();listController?.abort();listController=null;clearView()
  activeId.value=null;pending.value=null;conversations.value=emptyPage();listState.value='idle';listError.value=''
  titleDraft.value='';createUnknown.value=false;createError.value=''
 }
 const unregister=registerSessionCleanup(clearAll)
 watch(userId,(next,previous)=>{if(next!==previous)clearAll()},{flush:'sync'})
 onScopeDispose(()=>{clearAll();disposed=true;unregister()})

 async function loadConversations(page=1){
  const owner=userId.value,ownerEpoch=ownerGeneration,request=++listGeneration
  listController?.abort();const abort=new AbortController();listController=abort
  if(!owner)return false
  listState.value='loading';listError.value=''
  const valid=()=>!disposed&&userId.value===owner&&ownerGeneration===ownerEpoch&&listGeneration===request
  try{
   const result=await api.list(page,abort.signal)
   if(!valid())return false
   conversations.value=result;listState.value='ready';return true
  }catch(error){if(valid()){listState.value='error';listError.value=chatErrorMessage(error)}return false}
 }
 async function createConversation(){
  if(creating.value||createUnknown.value||!userId.value)return null
  createError.value=titleError(titleDraft.value)
  if(createError.value)return null
  const c=context(),abort=controller();creating.value=true
  try{
   const value=await api.create(titleDraft.value||undefined,abort.signal)
   if(!current(c))return null
   titleDraft.value='';void loadConversations(1);return value.id
  }catch(error){
   if(current(c)){createUnknown.value=isUnknownWrite(error);createError.value=chatErrorMessage(error)+(createUnknown.value?' 新建接口没有幂等编号，请先核对列表，避免重复创建。':'')}
   return null
  }finally{controllers.delete(abort);if(current(c))creating.value=false}
 }
 function allowAnotherCreation(confirm:()=>boolean){if(createUnknown.value&&confirm()){createUnknown.value=false;createError.value=''}}

 async function activate(id:string|null){
  cancelView();clearView();activeId.value=id
  if(!id||!userId.value)return
  if(!isResourceId(id)){historyState.value='error';historyError.value='对话编号不合法。';return}
  const c=context(),abort=controller();historyState.value='loading'
  try{
   const value=await api.detail(id,abort.signal)
   if(!current(c))return
   conversation.value=value
   await loadMessages()
  }catch(error){if(current(c)){historyState.value='error';historyError.value=chatErrorMessage(error)}}
  finally{controllers.delete(abort)}
 }
 /** 先从最早页取得total，再显式定位最后一页；不会把第一页称为最新。 */
 async function loadMessages(page?:number,chooseTurn=true){
  if(!activeId.value||!conversation.value)return false
  const c=context(),request=++historyGeneration
  historyController?.abort();const abort=controller();historyController=abort
  const valid=()=>current(c)&&request===historyGeneration
  historyState.value='loading';historyError.value=''
  try{
   let value=await api.messages(c.id!,page??1,abort.signal)
   if(!valid())return false
   const last=Math.max(1,Math.ceil(value.total/value.size))
   if(page===undefined&&last>1){value=await api.messages(c.id!,last,abort.signal);if(!valid())return false}
   messages.value={...value,items:[...value.items].sort((a,b)=>a.sequence-b.sequence)};historyState.value='ready'
   if(chooseTurn){
    const turnId=pendingForActive.value?.turnId??messages.value.items.at(-1)?.turnId
    if(turnId)await selectTurn(turnId)
    else{stopSelection();selected.value=null;selectedTurnId.value=null;selectionState.value='idle'}
   }
   return true
  }catch(error){if(valid()){historyState.value='error';historyError.value=chatErrorMessage(error)}return false}
  finally{controllers.delete(abort)}
 }
 function applyResult(value:RecommendationTurnView){
  const outstanding=pendingForActive.value
  if(outstanding?.turnId===value.turn.id&&value.turn.requestId!==outstanding.payload.requestId)return invalidResponse()
  selected.value=value;selectedTurnId.value=value.turn.id;selectionState.value='ready';selectionError.value=''
  if(outstanding&&value.turn.requestId===outstanding.payload.requestId){
   outstanding.turnId=value.turn.id
   if(terminal(value)){
    if(draft.value===outstanding.payload.content)draft.value=''
    pending.value=null
   }
  }
  operationError.value=value.turn.status==='FAILED'?turnErrorMessage(value.turn.error?.code):''
 }
 async function selectTurn(turnId:string){
  if(!activeId.value||isSending.value)return false
  stopSelection();const request=selectionGeneration,c=context(),abort=controller();selectionController=abort
  selectedTurnId.value=turnId;selected.value=null;selectionState.value='loading';selectionError.value='';pollMessage.value=''
  try{
   const value=await api.recommendation(c.id!,turnId,abort.signal)
   if(!current(c)||request!==selectionGeneration)return false
   applyResult(value);return true
  }catch(error){if(current(c)&&request===selectionGeneration){selectionState.value='error';selectionError.value=chatErrorMessage(error)}return false}
  finally{controllers.delete(abort)}
 }
 /** 仅轮次已知在途时进入有界GET；截止计时器也中止最后一次客户端等待。 */
 async function pollTurn(outstanding:PendingSend,c:ReturnType<typeof context>){
  const turnId=outstanding.turnId!
  stopSelection();const request=selectionGeneration,abort=controller();selectionController=abort
  const valid=()=>current(c)&&request===selectionGeneration&&pending.value===outstanding
  const deadline=now()+120000,timer=setTimeout(()=>abort.abort(),120000)
  const clearDeadline=()=>clearTimeout(timer)
  abort.signal.addEventListener('abort',clearDeadline,{once:true})
  polling.value=true;pollCount.value=0;pollMessage.value='正在只读核对服务器轮次…';outstanding.status='polling'
  try{
   while(valid()&&pollCount.value<30&&now()<deadline){
    await wait(Math.min(3000,deadline-now()),abort.signal)
    if(!valid()||now()>=deadline)break
    pollCount.value++
    const value=await api.recommendation(c.id!,turnId,abort.signal)
    if(!valid()||now()>=deadline)break
    applyResult(value)
    if(terminal(value)){pollMessage.value='已读取终态。';await loadMessages(undefined,false);return}
   }
   if(valid()){outstanding.status='unknown';outstanding.message='已达到自动读取次数或等待时限，结果尚未确认。';pollMessage.value=outstanding.message}
  }catch(error){
   if(valid()){outstanding.status='unknown';outstanding.message=now()>=deadline?'已达到120秒读取预算，结果尚未确认。':chatErrorMessage(error);pollMessage.value=outstanding.message}
  }finally{clearTimeout(timer);abort.signal.removeEventListener('abort',clearDeadline);controllers.delete(abort);if(current(c)&&request===selectionGeneration)polling.value=false}
 }
 async function performSend(outstanding:PendingSend){
  const c=context(),abort=controller()
  const valid=()=>current(c)&&pending.value===outstanding
  outstanding.status='sending';outstanding.message='正在等待同步推荐结果…';operationError.value='';stopSelection()
  try{
   const value=await api.send(outstanding.conversationId,outstanding.payload,abort.signal)
   if(!valid())return false
   applyResult(value)
   if(terminal(value)){await loadMessages(undefined,false);void loadConversations(conversations.value.page)}
   else await pollTurn(outstanding,c)
   return current(c)&&selected.value?.turn.conversationId===outstanding.conversationId&&selected.value?.turn.requestId===outstanding.payload.requestId&&selected.value.turn.status==='SUCCEEDED'
  }catch(error){
   if(valid()){
    outstanding.message=chatErrorMessage(error)
    if(isUnknownWrite(error))outstanding.status='unknown'
    else if(error instanceof ApiError&&error.status===409)outstanding.status='conflict'
    else{pending.value=null;operationError.value=outstanding.message}
   }
   return false
  }finally{controllers.delete(abort)}
 }
 async function send(){
  if(!canSend.value||!activeId.value||!userId.value)return false
  inputError.value=contentError(draft.value)
  if(inputError.value)return false
  const outstanding:PendingSend={userId:userId.value,conversationId:activeId.value,payload:Object.freeze({requestId:uuid(),content:draft.value}),turnId:null,status:'sending',message:''}
  pending.value=outstanding
  // 使用ref代理后的对象做后续身份比较，原文payload依然不可变。
  return performSend(pending.value!)
 }
 async function checkPending(confirm:()=>boolean){
  const value=pendingForActive.value
  if(!value||isSending.value)return false
  if(value.turnId)return selectTurn(value.turnId)
  if(value.status!=='unknown'||!confirm())return false
  return performSend(value)
 }
 function discardPending(confirm:()=>boolean){
  if(!pending.value||!confirm())return false
  cancelView();pending.value=null
  if(historyState.value==='loading'){historyState.value='error';historyError.value='本地读取已停止，请重新读取消息历史。'}
  selectionState.value=selected.value?'ready':'idle';return true
 }
 function leaveView(){cancelView();clearView();activeId.value=null}

 return {conversations,listState,listError,titleDraft,creating,createUnknown,createError,activeId,conversation,messages,historyState,historyError,
  selectedTurnId,selected,selectionState,selectionError,draft,inputError,operationError,pending,pendingForActive,isSending,canSend,polling,pollCount,pollMessage,
  loadConversations,createConversation,allowAnotherCreation,activate,loadMessages,selectTurn,send,checkPending,discardPending,leaveView,clearAll}
}
