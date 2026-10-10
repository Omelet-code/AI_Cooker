import {apiClient} from '@/shared/api/client'
import {ApiError} from '@/shared/api/types'
import {invalidResponse,isResourceId,parseConversation,parseMessage,parsePage,parseRecommendation,parseTurn} from './validation'
import type {ConversationApi} from './types'

function path(id:string){
 if(!isResourceId(id))throw new ApiError(400,'VALIDATION_ERROR','对话编号不合法。')
 return '/conversations/'+id
}
function turnPath(id:string,turnId:string){
 if(!isResourceId(turnId))throw new ApiError(400,'VALIDATION_ERROR','轮次编号不合法。')
 return path(id)+'/turns/'+turnId
}
export function createConversationApi(client:Pick<typeof apiClient,'request'>=apiClient):ConversationApi{
 return {
  async list(page,signal){return parsePage(await client.request('/conversations?page='+page+'&size=20','GET',undefined,{auth:'required',signal}),parseConversation)},
  async create(title,signal){return parseConversation(await client.request('/conversations','POST',title===undefined?{}:{title},{auth:'required',signal}))},
  async detail(id,signal){const value=parseConversation(await client.request(path(id),'GET',undefined,{auth:'required',signal}));if(value.id!==id)return invalidResponse();return value},
  async messages(id,page,signal){return parsePage(await client.request(path(id)+'/messages?page='+page+'&size=20','GET',undefined,{auth:'required',signal}),parseMessage)},
  async turn(id,turnId,signal){const value=parseTurn(await client.request(turnPath(id,turnId),'GET',undefined,{auth:'required',signal}));if(value.id!==turnId||value.conversationId!==id)return invalidResponse();return value},
  async recommendation(id,turnId,signal){return parseRecommendation(await client.request(turnPath(id,turnId)+'/recommendation','GET',undefined,{auth:'required',signal}),id,turnId)},
  async send(id,input,signal){return parseRecommendation(await client.request(path(id)+'/turns','POST',input,{auth:'required',signal,timeoutMs:125000}),id,undefined,input.requestId)},
 }
}
export const conversationApi=createConversationApi()
