export function envelope<T>(data:T,code='OK'){return new Response(JSON.stringify({code,message:'成功',data}),{status:200,headers:{'Content-Type':'application/json'}})}
export function failure(status:number,code:string,message='中文错误'){return new Response(JSON.stringify({code,message,data:null}),{status,headers:{'Content-Type':'application/json'}})}
