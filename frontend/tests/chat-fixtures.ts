import type {ConversationView,Page,ChatMessageView,RecommendationOutcome,RecommendationTurnView,TurnStatus} from '@/modules/conversation/types'
export const requestId='00000000-0000-4000-8000-000000000001'
export const conversationFixture=(id='1'):ConversationView=>({id,title:'新对话',createdAt:'2026-10-02T00:00:00Z',updatedAt:'2026-10-02T00:00:00Z'})
export const pageFixture=<T>(items:T[]=[],page=1,total=items.length):Page<T>=>({items,page,size:20,total})
export const messageFixture=(turnId='2',sequence=1,role:'USER'|'ASSISTANT'='USER'):ChatMessageView=>({id:String(sequence),turnId,sequence,role,content:'虚构饮食需求',createdAt:'2026-10-02T00:00:00Z'})
export function recommendationFixture(status:TurnStatus='SUCCEEDED',outcome:RecommendationOutcome='RECOMMENDED'):RecommendationTurnView{
 return {
  turn:{id:'2',conversationId:'1',requestId,turnNo:1,status,createdAt:'2026-10-02T00:00:00Z',startedAt:status==='PENDING'?null:'2026-10-02T00:00:01Z',finishedAt:['SUCCEEDED','FAILED'].includes(status)?'2026-10-02T00:00:02Z':null,error:status==='FAILED'?{code:'CREDENTIAL_NOT_CONFIGURED',message:'未配置凭证'}:null},
  result:status!=='SUCCEEDED'?null:{schemaVersion:1,outcome,answer:'虚构固定回答',constraints:{query:'家常菜',maxCookingMinutes:null,availableToolNames:null,excludedIngredientNames:[],availableIngredientNames:[],budgetYuan:null},recipes:outcome==='RECOMMENDED'?[{recipeId:'90071992547409930',name:'番茄炒蛋',summary:null,estimatedMinutes:null,reasonCodes:['RETRIEVAL_MATCH']}]:[],noticeCodes:['BUDGET_NOT_VERIFIED','AVAILABLE_INGREDIENTS_NOT_INVENTORY','PREFERENCE_NOT_GUARANTEED','GENERAL_DIET_ONLY'],metrics:{modelGatewayAttempts:2,retrievalCalls:1,queryEmbeddingRequests:null,elapsedMs:100,modelUsages:[{attempt:1,promptTokens:null,completionTokens:null,totalTokens:null}]}}
 }
}
export function deferred<T>(){let resolve!:(value:T)=>void;let reject!:(error:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
export async function microtasks(){for(let i=0;i<30;i++)await Promise.resolve()}
