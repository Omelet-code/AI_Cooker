<script setup lang="ts">
import {computed,ref} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {useIdentityStore} from '@/stores/identity'
import {ApiError} from '@/shared/api/types'
import {apiClient} from '@/shared/api/client'
import {validateCredentials} from '../validation'
const route=useRoute(),router=useRouter(),identity=useIdentityStore()
const registerMode=computed(()=>route.name==='register')
const username=ref(''),password=ref(''),rememberMe=ref(false),busy=ref(false),formError=ref('')
const issues=ref<{username?:string;password?:string}>({})
const notice=computed(()=>route.query.registered==='1'?'注册成功，请使用新账号登录。':route.query.logout==='failed'?'退出请求未完成，服务端会话状态尚未确认。':route.query.reason==='session-expired'?'登录状态已失效，请重新登录。':'')
const isMock=computed(()=>apiClient.mode==='mock')
async function submit(){
 formError.value='';issues.value={}
 const invalid=validateCredentials(username.value,password.value)
 if(invalid.length){for(const item of invalid)issues.value[item.field]=item.message;return}
 busy.value=true
 try{
  if(registerMode.value){await identity.register(username.value,password.value);await router.replace({name:'login',query:{registered:'1'}});return}
  await identity.login(username.value,password.value,rememberMe.value)
  const destination=typeof route.query.redirect==='string'&&route.query.redirect.startsWith('/')&&!route.query.redirect.startsWith('//')?route.query.redirect:'/recipes'
  await router.replace(destination)
 }catch(error){
  if(error instanceof ApiError){
   if(error.code==='USERNAME_TAKEN')formError.value='这个用户名已被使用，请换一个用户名。'
   else if(error.code==='LOGIN_FAILED')formError.value='用户名或密码错误。'
   else if(error.code==='VALIDATION_ERROR'){for(const item of error.fieldErrors){if(item.field==='username'||item.field==='password')issues.value[item.field]=item.message}formError.value=error.fieldErrors.length?'请检查标记的输入项。':error.message}
   else formError.value=error.message
  }else formError.value='操作失败，请稍后重试。'
 }finally{busy.value=false}
}
</script>

<template>
 <main class="auth-page">
  <section class="auth-card" :aria-labelledby="registerMode?'register-title':'login-title'">
   <RouterLink class="brand" to="/login" aria-label="Cooker 首页"><span class="brand-mark">C</span><span>cooker</span></RouterLink>
   <p v-if="isMock" class="mock-notice">开发 Mock · 本页面不会连接真实后端</p>
   <p class="eyebrow">为今天，找一道刚刚好的菜</p>
   <h1 :id="registerMode?'register-title':'login-title'">{{registerMode?'创建你的账号':'欢迎回来'}}</h1>
   <p class="auth-intro">{{registerMode?'注册后即可保存你的烹饪记录。':'登录 Cooker，继续你的烹饪日常。'}}</p>
   <p v-if="notice" class="notice" role="status">{{notice}}</p>
   <form class="auth-form" @submit.prevent="submit" novalidate>
    <label for="username">用户名</label>
    <input id="username" v-model="username" name="username" autocomplete="username" inputmode="text" maxlength="32" required :aria-invalid="Boolean(issues.username)" :aria-describedby="issues.username?'username-error':undefined" placeholder="3～32 位字母、数字或下划线">
    <p v-if="issues.username" id="username-error" class="field-error">{{issues.username}}</p>
    <div class="password-label"><label for="password">密码</label><span>至少 12 位</span></div>
    <input id="password" v-model="password" name="password" type="password" :autocomplete="registerMode?'new-password':'current-password'" minlength="12" maxlength="128" required :aria-invalid="Boolean(issues.password)" :aria-describedby="issues.password?'password-error':undefined" placeholder="请输入密码">
    <p v-if="issues.password" id="password-error" class="field-error">{{issues.password}}</p>
    <label v-if="!registerMode" class="remember-option"><input v-model="rememberMe" type="checkbox" name="rememberMe"><span>记住我</span></label>
    <p v-if="formError||identity.errorMessage" class="form-error" role="alert">{{formError||identity.errorMessage}}</p>
    <button class="primary-button" type="submit" :disabled="busy">{{busy?(registerMode?'正在创建…':'正在登录…'):(registerMode?'创建账号':'登录')}}</button>
   </form>
   <p class="auth-switch">{{registerMode?'已经有账号？':'还没有账号？'}}
    <RouterLink :to="registerMode?{name:'login'}:{name:'register'}">{{registerMode?'返回登录':'立即注册'}}</RouterLink>
   </p>
   <p class="privacy-note">账号凭证仅通过安全 Cookie 验证，不会保存在浏览器存储中。</p>
  </section>
  <aside class="auth-aside" aria-label="Cooker 简介">
   <div class="aside-orbit orbit-one"></div><div class="aside-orbit orbit-two"></div>
   <span class="aside-kicker">COOK SOMETHING GOOD</span>
   <div class="plate" aria-hidden="true"><span class="plate-herb herb-one"></span><span class="plate-herb herb-two"></span><span class="plate-herb herb-three"></span><span class="plate-center"></span></div>
   <blockquote>一顿好饭，<br>从一个好主意开始。</blockquote>
   <p>让厨房里的每一天，都多一点灵感。</p>
   <span class="aside-index">01 <i></i> YOUR KITCHEN COMPANION</span>
  </aside>
 </main>
</template>
