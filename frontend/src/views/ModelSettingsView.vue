<script setup lang="ts">
import {computed,onMounted} from 'vue'
import {useIdentityStore} from '@/stores/identity'
import {modelCredentialSettingsApi} from '@/modules/settings/api'
import {useModelCredentialSettings} from '@/modules/settings/useModelCredentialSettings'

const identity=useIdentityStore()
const userId=computed(()=>identity.user?.id??null)
const settings=useModelCredentialSettings(modelCredentialSettingsApi,userId)
const {apiKey,configured,statusState,statusError,actionError,actionMessage,writeUncertain,validationError,busyAction,isBusy}=settings
onMounted(()=>{void settings.loadStatus()})
</script>

<template>
 <section class="model-settings-page">
  <header class="settings-heading">
   <div><p class="eyebrow">账户偏好</p><h1>模型设置</h1></div>
  </header>
  <p class="settings-intro">设置用于 DeepSeek 的模型 Key。页面不会读取或回显已保存的 Key；保存成功只代表凭证已配置，不代表 Key 已验证有效。</p>
  <section class="credential-card" aria-labelledby="credential-title">
   <div class="credential-title-row"><div><p class="eyebrow">DEEPSEEK</p><h2 id="credential-title">模型凭证</h2></div><span v-if="statusState==='ready'&&configured" class="credential-badge">已配置</span></div>

   <div v-if="statusState==='loading'" class="settings-state" role="status">正在读取配置状态…</div>
   <div v-else-if="statusState==='error'" class="settings-state settings-error" role="alert">
    <p>{{statusError}}</p><button class="secondary-button" type="button" :disabled="isBusy" @click="settings.loadStatus">重新读取</button>
   </div>
   <template v-else>
    <p class="credential-status" role="status">{{configured?'当前账号已保存模型 Key。':'当前账号尚未配置模型 Key。'}}</p>
    <form class="credential-form" @submit.prevent="settings.save">
     <label for="model-api-key">{{configured?'输入新 Key 以替换':'模型 Key'}}</label>
     <input id="model-api-key" v-model="apiKey" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" :disabled="isBusy" :aria-invalid="Boolean(validationError)" aria-describedby="key-help key-error" @input="settings.clearValidation">
     <p id="key-help" class="settings-help">输入内容只在本页内存中暂存，不会预填。请勿把 Key 发到聊天中。</p>
     <p v-if="validationError" id="key-error" class="settings-error" role="alert">{{validationError}}</p>
     <div class="settings-actions"><button class="primary-button" type="submit" :disabled="isBusy||statusState!=='ready'">{{busyAction==='save'?'正在保存…':configured?'保存并替换':'保存 Key'}}</button><button v-if="configured" class="secondary-button" type="button" :disabled="isBusy" @click="settings.remove">{{busyAction==='delete'?'正在删除…':'删除已保存 Key'}}</button></div>
    </form>
   </template>

   <p v-if="actionError" class="settings-error" role="alert">{{actionError}}</p>
   <p v-if="actionMessage" class="settings-success" role="status">{{actionMessage}}</p>
   <div v-if="writeUncertain" class="uncertain-notice" role="status">
    <p>服务器可能已处理此次{{writeUncertain==='save'?'保存':'删除'}}，请先重新读取状态确认。状态只表示是否存在已配置凭证，不能证明新 Key 是否已替换或有效。</p>
    <button class="secondary-button" type="button" :disabled="isBusy" @click="settings.loadStatus">重新读取配置状态</button>
   </div>
  </section>
 </section>
</template>

<style scoped>
.model-settings-page{max-width:760px;margin:0 auto;padding:52px 0 70px}.settings-heading,.credential-title-row{display:flex;align-items:center;justify-content:space-between;gap:18px}.settings-heading h1{margin:9px 0 0;color:#304032;font-size:32px;letter-spacing:-.04em}.settings-intro{max-width:650px;margin:17px 0 27px;color:#737e72;font-size:13px;line-height:1.8}.credential-card{padding:25px;border:1px solid #e4e8de;border-radius:13px;background:#fff}.credential-title-row h2{margin:6px 0 0;color:#374639;font-size:18px}.credential-badge{padding:6px 10px;border-radius:99px;background:#edf3e5;color:#526c40;font-size:11px;font-weight:700}.credential-status{margin:21px 0;color:#626f62;font-size:13px}.credential-form{display:grid;gap:9px;margin-top:21px}.credential-form label{color:#3b493d;font-size:13px;font-weight:600}.credential-form input{width:100%;height:46px;padding:0 13px;border:1px solid #dce2d6;border-radius:8px;outline:none;background:#fff;color:#354538}.credential-form input:focus{border-color:#718d54;box-shadow:0 0 0 3px #e8eedc}.credential-form input[aria-invalid=true]{border-color:#bd6b5d}.settings-help{margin:0;color:#858d82;font-size:11px;line-height:1.6}.settings-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:8px}.settings-actions .primary-button{height:42px;padding:0 16px;margin:0}.settings-state{display:flex;align-items:center;gap:13px;flex-wrap:wrap;padding:19px 0;color:#687568;font-size:13px}.settings-state p{margin:0}.settings-error{color:#a34c42;font-size:12px;line-height:1.6}.settings-success{color:#4f7042;font-size:12px;line-height:1.6}.uncertain-notice{display:grid;gap:10px;margin-top:17px;padding:14px;border:1px solid #ead8a9;border-radius:9px;background:#fff9e9;color:#765c22;font-size:12px;line-height:1.7}.uncertain-notice p{margin:0}.uncertain-notice .secondary-button{justify-self:start}@media(max-width:600px){.model-settings-page{padding:35px 0 50px}.settings-heading h1{font-size:28px}.credential-card{padding:19px}.settings-actions{align-items:stretch;flex-direction:column}.settings-actions button{width:100%}}
</style>
