export interface Page<T>{items:T[];page:number;size:number;total:number}
export interface ConversationView{id:string;title:string;createdAt:string;updatedAt:string}
export interface ChatMessageView{id:string;turnId:string;role:'USER'|'ASSISTANT';content:string;sequence:number;createdAt:string}
export type TurnStatus='PENDING'|'RUNNING'|'SUCCEEDED'|'FAILED'
export interface TurnView{
 id:string;conversationId:string;requestId:string;turnNo:number;status:TurnStatus
 createdAt:string;startedAt:string|null;finishedAt:string|null;error:{code:string;message:string}|null
}
export interface RecommendationConstraints{
 query:string;maxCookingMinutes:number|null;availableToolNames:string[]|null
 excludedIngredientNames:string[];availableIngredientNames:string[];budgetYuan:string|null
}
export interface RecipeRecommendation{recipeId:string;name:string;summary:string|null;estimatedMinutes:number|null;reasonCodes:string[]}
export type RecommendationOutcome='RECOMMENDED'|'NO_MATCH'|'CLARIFICATION'|'UNSUPPORTED'
export interface ExecutionMetrics{
 modelGatewayAttempts:number;retrievalCalls:number;queryEmbeddingRequests:number|null;elapsedMs:number
 modelUsages:Array<{attempt:number;promptTokens:number|null;completionTokens:number|null;totalTokens:number|null}>
}
export interface RecommendationResult{
 schemaVersion:1;outcome:RecommendationOutcome;answer:string;constraints:RecommendationConstraints
 recipes:RecipeRecommendation[];noticeCodes:string[];metrics:ExecutionMetrics
}
export interface RecommendationTurnView{turn:TurnView;result:RecommendationResult|null}
export interface TurnInput{readonly requestId:string;readonly content:string}
export interface ConversationApi{
 list(page:number,signal?:AbortSignal):Promise<Page<ConversationView>>
 create(title?:string,signal?:AbortSignal):Promise<ConversationView>
 detail(id:string,signal?:AbortSignal):Promise<ConversationView>
 messages(id:string,page:number,signal?:AbortSignal):Promise<Page<ChatMessageView>>
 turn(id:string,turnId:string,signal?:AbortSignal):Promise<TurnView>
 recommendation(id:string,turnId:string,signal?:AbortSignal):Promise<RecommendationTurnView>
 send(id:string,input:TurnInput,signal?:AbortSignal):Promise<RecommendationTurnView>
}
