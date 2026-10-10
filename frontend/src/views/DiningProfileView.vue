<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useIdentityStore } from '@/stores/identity'
import { diningProfileApi } from '@/modules/identity/diningProfile/api'
import { useDiningProfile } from '@/modules/identity/diningProfile/useDiningProfile'

const identity = useIdentityStore()
const userId = computed(() => identity.user?.id ?? null)
const profile = useDiningProfile(diningProfileApi, userId)
const {
  savedCount, countInput, state, statusError, actionError, actionMessage,
  validationError, writeUncertain, busyAction, isBusy,
  load, save, clear, clearValidation,
} = profile

onMounted(() => { void profile.load() })
</script>

<template>
 <section class="dining-profile-page" aria-labelledby="profile-title">
  <header class="profile-heading">
   <div><p class="eyebrow">账户偏好</p><h1 id="profile-title">个人档案</h1></div>
  </header>
  <p class="profile-intro">保存你常用的用餐人数，供之后开始新的一餐时参考。本餐指定人数优先；默认人数不会从聊天中推断，修改也不会影响已有餐单。</p>

  <section class="profile-card" aria-labelledby="dining-count-title">
   <p class="eyebrow">用餐偏好</p>
   <h2 id="dining-count-title">默认用餐人数</h2>
   <div v-if="state==='loading'" class="profile-state" role="status">正在读取个人档案…</div>
   <div v-else-if="state==='error'" class="profile-state profile-error" role="alert">
    <p>{{ statusError }}</p>
    <button class="secondary-button" type="button" :disabled="isBusy" @click="load">重新读取</button>
   </div>
   <template v-else>
    <p class="saved-value" role="status">当前已保存：<strong>{{ savedCount ?? '未设置' }}</strong></p>
    <form class="profile-form" @submit.prevent="save">
     <label for="default-dining-count">人数</label>
     <input
      id="default-dining-count"
      v-model="countInput"
      type="text"
      inputmode="numeric"
      autocomplete="off"
      :disabled="isBusy"
      :aria-invalid="Boolean(validationError)"
      aria-describedby="count-help count-error"
      @input="clearValidation"
     >
     <p id="count-help" class="profile-help">请输入正整数。清空请使用单独的“清空默认人数”操作。</p>
     <p v-if="validationError" id="count-error" class="profile-error" role="alert">{{ validationError }}</p>
     <div class="profile-actions">
      <button class="primary-button" type="submit" :disabled="isBusy">
       {{ busyAction==='save' ? '正在保存…' : '保存人数' }}
      </button>
      <button class="secondary-button" type="button" :disabled="isBusy || savedCount===null" @click="clear">
       {{ busyAction==='clear' ? '正在清空…' : '清空默认人数' }}
      </button>
     </div>
    </form>
   </template>

   <p v-if="actionError" class="profile-error" role="alert">{{ actionError }}</p>
   <p v-if="actionMessage" class="profile-success" role="status">{{ actionMessage }}</p>
   <div v-if="writeUncertain" class="uncertain-notice" role="status">
    <p>服务器可能已处理这次{{ writeUncertain==='clear' ? '清空' : '保存' }}。先重新读取档案核对；页面不会自动重发写入。</p>
    <button class="secondary-button" type="button" :disabled="isBusy" @click="load">重新读取档案</button>
   </div>
  </section>
 </section>
</template>

<style scoped>
.dining-profile-page{max-width:760px;margin:0 auto;padding:52px 0 70px}.profile-heading{display:flex;align-items:center;justify-content:space-between;gap:18px}.profile-heading h1{margin:9px 0 0;color:#304032;font-size:32px;letter-spacing:-.04em}.profile-intro{max-width:650px;margin:17px 0 27px;color:#737e72;font-size:13px;line-height:1.8}.profile-card{padding:25px;border:1px solid #e4e8de;border-radius:13px;background:#fff}.profile-card h2{margin:6px 0 0;color:#374639;font-size:18px}.saved-value{margin:21px 0;color:#626f62;font-size:13px}.saved-value strong{color:#374639}.profile-form{display:grid;gap:9px;margin-top:21px}.profile-form label{color:#3b493d;font-size:13px;font-weight:600}.profile-form input{width:100%;height:46px;padding:0 13px;border:1px solid #dce2d6;border-radius:8px;outline:none;background:#fff;color:#354538}.profile-form input:focus{border-color:#718d54;box-shadow:0 0 0 3px #e8eedc}.profile-form input[aria-invalid=true]{border-color:#bd6b5d}.profile-help{margin:0;color:#858d82;font-size:11px;line-height:1.6}.profile-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:8px}.profile-actions .primary-button{height:42px;padding:0 16px;margin:0}.profile-state{display:flex;align-items:center;gap:13px;flex-wrap:wrap;padding:19px 0;color:#687568;font-size:13px}.profile-state p{margin:0}.profile-error{color:#a34c42;font-size:12px;line-height:1.6}.profile-success{color:#4f7042;font-size:12px;line-height:1.6}.uncertain-notice{display:grid;gap:10px;margin-top:17px;padding:14px;border:1px solid #ead8a9;border-radius:9px;background:#fff9e9;color:#765c22;font-size:12px;line-height:1.7}.uncertain-notice p{margin:0}.uncertain-notice .secondary-button{justify-self:start}@media(max-width:600px){.dining-profile-page{padding:35px 0 50px}.profile-heading h1{font-size:28px}.profile-card{padding:19px}.profile-actions{align-items:stretch;flex-direction:column}.profile-actions button{width:100%}}
</style>
