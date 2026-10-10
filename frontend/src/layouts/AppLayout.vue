<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref} from 'vue'
import {useRouter} from 'vue-router'
import {useIdentityStore} from '@/stores/identity'
import {useCookingStore} from '@/stores/cooking'
import {useConversationStore} from '@/modules/conversation/store'
import {useKitchenStore} from '@/modules/conversation/kitchen/store'
import {useShoppingStore} from '@/modules/shopping/store'
import {pendingShoppingLocation} from '@/modules/shopping/navigation'
import {apiClient} from '@/shared/api/client'
const identity=useIdentityStore(),cooking=useCookingStore(),router=useRouter(),busy=ref(false),errorMessage=ref('')
const chat=useConversationStore()
const kitchen=useKitchenStore()
const shopping=useShoppingStore()
const isMock=computed(()=>apiClient.mode==='mock')
const pending=computed(()=>cooking.pendingCooking)
const pendingStatus=computed(()=>pending.value?.status)
const pendingPath=computed(()=>pending.value?{name:'recipe-detail',params:{id:pending.value.recipeId}}:undefined)
const pendingLabel=computed(()=>pendingStatus.value==='submitting'?'做菜记录正在提交':pendingStatus.value==='unknown'?'做菜记录结果未知，可重试':pendingStatus.value==='conflict'?'做菜记录发生编号冲突':'做菜记录提交失败，可重试')
const kitchenPending=computed(()=>kitchen.pending[0]??null)
const shoppingPendingPath=computed(()=>shopping.pendingOperation?pendingShoppingLocation(shopping.pendingOperation):{name:'shopping-home'})
function warnBeforeLeaving(event:BeforeUnloadEvent){if(!pending.value&&!chat.pending&&!kitchen.anyPending&&!shopping.anyPending)return;event.preventDefault();event.returnValue=''}
onMounted(()=>window.addEventListener('beforeunload',warnBeforeLeaving))
onUnmounted(()=>window.removeEventListener('beforeunload',warnBeforeLeaving))
async function signOut(){if(pending.value&&!window.confirm('做菜记录尚未确认完成。服务器可能已经收到这次记录；仍要退出并放弃当前重试状态吗？'))return;if(chat.pending&&!window.confirm('聊天发送尚未确认，服务器可能仍在执行。退出会清除本地原编号和正文，之后需从历史核对。是否退出？'))return;if(kitchen.anyPending&&!window.confirm('厨房操作尚未确认。退出会清除本地请求编号，服务器可能仍在处理；之后需登录并从厨房历史核对。仍要退出吗？'))return;if(shopping.anyPending&&!window.confirm('采购操作尚未确认。退出会清除本地原编号，服务器可能仍在处理；重新登录后请回到原清单核对。仍要退出吗？'))return;busy.value=true;errorMessage.value='';try{await identity.logout();await router.replace({name:'login'})}catch{errorMessage.value='退出请求未完成，请检查网络后重试。';await router.replace({name:'login',query:{logout:'failed'}})}finally{busy.value=false}}
</script>
<template>
 <div class="app-frame">
  <header class="topbar">
   <RouterLink class="brand" to="/recipes"><span class="brand-mark">C</span><span>cooker</span></RouterLink>
   <nav class="main-nav" aria-label="主导航"><RouterLink to="/recipes">浏览菜谱</RouterLink><RouterLink to="/kitchen">烹饪助手</RouterLink><RouterLink to="/shopping">采购清单</RouterLink><RouterLink to="/settings/profile">个人档案</RouterLink><RouterLink to="/settings/model">模型设置</RouterLink></nav>
   <div class="account-area"><span v-if="isMock" class="mock-badge">开发 Mock</span><span class="account-name">{{identity.user?.username}}</span><button class="text-button" type="button" :disabled="busy" @click="signOut">{{busy?'正在退出…':'退出登录'}}</button></div>
  </header>
  <div v-if="pending&&pendingPath" class="pending-banner" role="status"><span>{{pendingLabel}}</span><RouterLink :to="pendingPath">查看并处理</RouterLink></div>
  <div v-if="chat.pending" class="pending-banner" role="status"><span>聊天发送尚未确认，服务器可能仍在处理。</span><RouterLink :to="{name:'chat-conversation',params:{conversationId:chat.pending.conversationId}}">核对本次发送</RouterLink></div>
  <div v-if="kitchenPending" class="pending-banner" role="status"><span>厨房操作尚未确认；指导结果可能仍在生成。</span><RouterLink :to="{name:'kitchen-conversation',params:{conversationId:kitchenPending.conversationId}}">回到原会话核对</RouterLink></div>
  <div v-else-if="kitchen.createUnknown" class="pending-banner" role="status"><span>厨房会话创建结果未知，请先从历史列表核对。</span><RouterLink to="/kitchen">打开厨房助手</RouterLink></div>
  <div v-if="shopping.pendingOperation" class="pending-banner" role="status"><span>采购操作结果尚未确认；不会自动重试。</span><RouterLink :to="shoppingPendingPath">返回原采购上下文核对</RouterLink></div>
  <main class="app-content"><RouterView/><p v-if="errorMessage" class="form-error" role="alert">{{errorMessage}}</p></main>
  <footer class="app-footer"><span>COOK WELL, LIVE WELL.</span><span>一日三餐，也可以很有灵感。</span></footer>
 </div>
</template>


