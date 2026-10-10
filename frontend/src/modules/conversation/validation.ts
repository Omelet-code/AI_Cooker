import {ApiError} from '@/shared/api/types'
import type {ChatMessageView,ConversationView,Page,RecommendationResult,RecommendationTurnView,TurnView} from './types'

/** BIGINT只比较十进制字符串，不经过JavaScript浮点ID转换。 */
export function isResourceId(value:unknown):value is string{
 return typeof value==='string'&&/^[1-9]\d{0,18}$/.test(value)&&(value.length<19||value<='9223372036854775807')
}
export function contentError(content:string){
 if(!content.trim())return '请输入本次饮食需求。'
 return Array.from(content).length>4000?'需求最多可包含 4000 个 Unicode 字符。':''
}
export function titleError(title:string){
 if(!title)return ''
 return !title.trim()?'标题不能只包含空白。':Array.from(title.trim()).length>100?'标题最多可包含 100 个 Unicode 字符。':''
}
export function invalidResponse():never{throw new ApiError(200,'INVALID_RESPONSE','聊天响应格式异常。')}
function object(value:unknown):Record<string,unknown>{
 if(!value||typeof value!=='object'||Array.isArray(value))return invalidResponse()
 return value as Record<string,unknown>
}
function text(value:unknown,max=8000):value is string{return typeof value==='string'&&value.trim().length>0&&Array.from(value).length<=max}
function nullableText(value:unknown){return value===null||typeof value==='string'}
function count(value:unknown,min=0):value is number{return typeof value==='number'&&Number.isSafeInteger(value)&&value>=min}
function nullableCount(value:unknown){return value===null||count(value)}
function names(value:unknown):value is string[]{return Array.isArray(value)&&value.length<=20&&value.every(item=>text(item,80))}
function uuid(value:unknown){return typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)}

export function parseConversation(value:unknown):ConversationView{
 const v=object(value)
 if(!isResourceId(v.id)||!text(v.title,100)||!text(v.createdAt)||!text(v.updatedAt))return invalidResponse()
 return value as ConversationView
}
export function parseMessage(value:unknown):ChatMessageView{
 const v=object(value)
 if(!isResourceId(v.id)||!isResourceId(v.turnId)||!['USER','ASSISTANT'].includes(String(v.role))||!text(v.content)||!count(v.sequence,1)||!text(v.createdAt))return invalidResponse()
 return value as ChatMessageView
}
export function parsePage<T>(value:unknown,parse:(item:unknown)=>T):Page<T>{
 const v=object(value)
 if(!Array.isArray(v.items)||!count(v.page,1)||!count(v.size,1)||v.size>50||!count(v.total)||v.items.length>v.size)return invalidResponse()
 return {items:v.items.map(parse),page:v.page,size:v.size,total:v.total}
}
export function parseTurn(value:unknown):TurnView{
 const v=object(value)
 if(!isResourceId(v.id)||!isResourceId(v.conversationId)||!uuid(v.requestId)||!count(v.turnNo,1)||!['PENDING','RUNNING','SUCCEEDED','FAILED'].includes(String(v.status))||!text(v.createdAt)||!nullableText(v.startedAt)||!nullableText(v.finishedAt))return invalidResponse()
 if(v.status==='FAILED'){
  const error=object(v.error)
  if(!text(error.code,100)||!text(error.message))return invalidResponse()
 }else if(v.error!==null)return invalidResponse()
 return value as TurnView
}
function parseResult(value:unknown):RecommendationResult{
 const v=object(value),c=object(v.constraints),m=object(v.metrics)
 if(v.schemaVersion!==1||!['RECOMMENDED','NO_MATCH','CLARIFICATION','UNSUPPORTED'].includes(String(v.outcome))||!text(v.answer)||!Array.isArray(v.recipes)||v.recipes.length>3)return invalidResponse()
 if(v.outcome==='RECOMMENDED'?v.recipes.length===0:v.recipes.length!==0)return invalidResponse()
 const ids=new Set<string>()
 for(const item of v.recipes){
  const r=object(item)
  if(!isResourceId(r.recipeId)||ids.has(r.recipeId)||!text(r.name)||!nullableText(r.summary)||!nullableCount(r.estimatedMinutes)||!Array.isArray(r.reasonCodes)||!r.reasonCodes.every(code=>['RETRIEVAL_MATCH','TIME_CONFIRMED','TOOLS_COMPATIBLE','AVAILABLE_INGREDIENT_MATCH'].includes(code)))return invalidResponse()
  ids.add(r.recipeId)
 }
 if(!text(c.query,512)||!nullableCount(c.maxCookingMinutes)||(c.availableToolNames!==null&&!names(c.availableToolNames))||!names(c.excludedIngredientNames)||!names(c.availableIngredientNames)||(c.budgetYuan!==null&&(typeof c.budgetYuan!=='string'||!/^\d{1,5}(\.\d{1,2})?$/.test(c.budgetYuan))))return invalidResponse()
 if(!Array.isArray(v.noticeCodes)||!v.noticeCodes.every(code=>typeof code==='string')||!count(m.modelGatewayAttempts)||!count(m.retrievalCalls)||!nullableCount(m.queryEmbeddingRequests)||!count(m.elapsedMs)||!Array.isArray(m.modelUsages))return invalidResponse()
 for(const item of m.modelUsages){
  const usage=object(item)
  if(!count(usage.attempt,1)||!nullableCount(usage.promptTokens)||!nullableCount(usage.completionTokens)||!nullableCount(usage.totalTokens))return invalidResponse()
 }
 return value as RecommendationResult
}
export function parseRecommendation(value:unknown,conversationId:string,turnId?:string,requestId?:string):RecommendationTurnView{
 const v=object(value),turn=parseTurn(v.turn)
 if(turn.conversationId!==conversationId||(turnId&&turn.id!==turnId)||(requestId&&turn.requestId!==requestId))return invalidResponse()
 const result=v.result===null?null:parseResult(v.result)
 if(result!==null&&turn.status!=='SUCCEEDED')return invalidResponse()
 return {turn,result}
}
