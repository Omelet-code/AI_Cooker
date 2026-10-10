import {ApiError} from '@/shared/api/types'

export const replayWarning='核对会使用本次原编号和原文。服务器若已接收，会回读同一轮；若此前未接收，将开始执行并可能产生模型费用。是否继续？'
export const leaveWarning='这次发送尚未确认。离开会停止本地等待，服务器可能仍在执行；本次编号与原文仅在当前页面会话内保留，刷新或退出后需从服务器历史核对。是否离开？'
export const outcomeLabels={RECOMMENDED:'菜谱推荐',NO_MATCH:'未找到符合条件的菜谱',CLARIFICATION:'需要补充条件',UNSUPPORTED:'暂不支持这个请求'}
export const reasonLabels:Record<string,string>={RETRIEVAL_MATCH:'检索命中（偏好未保证）',TIME_CONFIRMED:'已登记时间满足上限',TOOLS_COMPATIBLE:'已登记厨具条件匹配',AVAILABLE_INGREDIENT_MATCH:'声明食材名称匹配（用量未核验）'}
export const noticeLabels:Record<string,string>={BUDGET_NOT_VERIFIED:'预算尚未核实，不能保证费用满足预算。',AVAILABLE_INGREDIENTS_NOT_INVENTORY:'已有食材来自本次声明，系统未读取或抵扣家庭库存。',PREFERENCE_NOT_GUARANTEED:'清淡等偏好不保证已结构化证实，食材匹配不代表过敏安全。',GENERAL_DIET_ONLY:'仅提供一般饮食建议，不作诊断或治疗承诺。'}
const turnErrors:Record<string,string>={
 RUNTIME_INTERRUPTED:'该轮执行已中断，请核对历史后明确发起新轮。',CREDENTIAL_NOT_CONFIGURED:'尚未配置模型 Key，请先前往模型设置。',
 CREDENTIAL_SERVICE_UNAVAILABLE:'模型凭证服务暂不可用。',MODEL_AUTHENTICATION_FAILED:'模型凭证未获供应商接受，请检查设置。',
 MODEL_RATE_LIMITED:'模型服务限流，请稍后明确发起新轮。',MODEL_TIMEOUT:'该轮模型请求超时。',MODEL_UNAVAILABLE:'模型服务暂不可用。',
 MODEL_INVALID_RESPONSE:'模型结果未通过校验。',RETRIEVAL_FAILED:'菜谱检索未完成。',RESOURCE_LIMIT:'该轮达到资源或时间限制。',EXECUTION_FAILED:'该轮执行未完成。',
}
export function turnErrorMessage(code?:string){return turnErrors[code??'']??'该轮执行失败，请核对记录后再决定是否发起新轮。'}
export function isUnknownWrite(error:unknown){
 return !(error instanceof ApiError)||error.status===0||error.code==='INVALID_RESPONSE'||error.status>=500&&error.code!=='AGENT_EXECUTION_DISABLED'
}
export function chatErrorMessage(error:unknown){
 if(error instanceof ApiError){
  if(error.code==='AGENT_EXECUTION_DISABLED')return '后端尚未启用推荐执行，请等待服务部署与启用。'
  if(error.code==='CONVERSATION_BUSY')return '这段对话已有在途轮次，请先从历史核对其状态。系统不会自动换编号。'
  if(error.code==='IDEMPOTENCY_CONFLICT')return '本次编号与服务器已有内容冲突，系统不会自动换编号。'
  if(error.status===401)return '登录状态已失效，请重新登录。'
  if(error.status===403)return '安全校验未通过或当前账号无权操作，请重新读取页面。'
  if(error.status===404)return '对话或聊天接口不可用，后端可能尚未部署此接口。'
  if(error.status===400)return '请求未通过校验，请检查编号、正文或标题。'
  if(error.code==='INVALID_RESPONSE')return '响应格式异常，操作结果尚未确认。'
  if(error.code==='REQUEST_ABORTED')return '已停止本地等待，服务器可能仍在处理。'
  if(error.code==='REQUEST_TIMEOUT')return '请求超时，操作结果尚未确认。'
  if(error.status===0)return '网络连接失败，操作结果尚未确认。'
 }
 return '服务暂不可用，操作结果尚未确认。'
}
