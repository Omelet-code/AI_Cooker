type SessionCleanup=()=>void
const sessionCleanups=new Set<SessionCleanup>()
/** 后续用户私有模块注册清理回调，退出或身份最终失效时统一清理。 */
export function registerSessionCleanup(cleanup:SessionCleanup):()=>void{
 sessionCleanups.add(cleanup)
 return()=>sessionCleanups.delete(cleanup)
}
export function clearSessionScopedState():void{
 for(const cleanup of [...sessionCleanups]){
  try{cleanup()}catch{/* 单个模块清理失败不应阻止其他私有状态清理。 */}
 }
}
