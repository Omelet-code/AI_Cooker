import {defineStore} from 'pinia'
import {identityApi} from '@/modules/identity/api'
import type {UserProfile} from '@/modules/identity/types'
import {ApiError} from '@/shared/api/types'
import {clearSessionScopedState} from '@/shared/auth/sessionCleanup'
export type AuthStatus='idle'|'loading'|'authenticated'|'anonymous'
const anonymousAuthCodes=new Set(['AUTH_REQUIRED','ACCESS_TOKEN_EXPIRED','REFRESH_REJECTED','REFRESH_CONFLICT'])
export const useIdentityStore=defineStore('identity',{
 state:()=>({user:null as UserProfile|null,status:'idle' as AuthStatus,errorMessage:'',initialization:null as Promise<void>|null}),
 actions:{
  async initialize(){
   if(this.status==='authenticated'||this.status==='anonymous')return
   if(this.initialization)return this.initialization
   this.status='loading'
   this.initialization=(async()=>{
    try{
     await identityApi.initializeCsrf()
     this.user=await identityApi.currentUser()
     this.status='authenticated';this.errorMessage=''
    }catch(error){
     this.user=null;this.status='anonymous'
     const expectedAnonymous=error instanceof ApiError&&anonymousAuthCodes.has(error.code)
     this.errorMessage=expectedAnonymous?'':error instanceof Error?error.message:'登录状态检查失败'
    }finally{this.initialization=null}
   })()
   return this.initialization
  },
  async login(username:string,password:string,rememberMe:boolean){
   this.errorMessage=''
   const user=await identityApi.login({username:username.trim().toLowerCase(),password,rememberMe})
   this.user=user;this.status='authenticated'
   await identityApi.refreshCsrf()
   return user
  },
  async register(username:string,password:string){
   this.errorMessage=''
   return identityApi.register({username:username.trim().toLowerCase(),password})
  },
  async logout(){
   this.errorMessage=''
   try{if(this.status==='authenticated')await identityApi.logout()}
   finally{
    this.user=null;this.status='anonymous';this.initialization=null
    clearSessionScopedState()
    try{await identityApi.refreshCsrf()}catch{/* 网络异常时下次写请求会重新初始化 CSRF。 */}
   }
  },
  clearUser(){
   this.user=null;this.status='anonymous';this.errorMessage='';this.initialization=null
  },
 },
})
