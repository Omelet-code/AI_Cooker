export interface ApiEnvelope<T> { code:string; message:string; data:T; fieldErrors?:Array<{field:string;message:string}> }
export class ApiError extends Error { constructor(public readonly status:number,public readonly code:string,message:string,public readonly fieldErrors:Array<{field:string;message:string}>=[]) { super(message); this.name='ApiError' } }
export interface RequestOptions { body?:unknown; auth?:'required'|'public'; signal?:AbortSignal; timeoutMs?:number }
