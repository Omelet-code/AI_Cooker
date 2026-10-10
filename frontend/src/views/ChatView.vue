<script setup lang="ts">
import {computed,onBeforeUnmount,watch} from 'vue'
import {onBeforeRouteLeave,onBeforeRouteUpdate,useRoute,useRouter} from 'vue-router'
import {useIdentityStore} from '@/stores/identity'
import {apiClient} from '@/shared/api/client'
import {useConversationStore} from '@/modules/conversation/store'
import {leaveWarning,replayWarning} from '@/modules/conversation/presentation'
import RecommendationPanel from '@/modules/conversation/components/RecommendationPanel.vue'

const chat=useConversationStore(),identity=useIdentityStore(),route=useRoute(),router=useRouter()
const routeId=computed(()=>typeof route.params.conversationId==='string'?route.params.conversationId:null)
const owner=computed(()=>identity.user?.id??null)
const isMock=apiClient.mode==='mock'
watch([routeId,owner],([id])=>{void chat.activate(id)},{immediate:true,flush:'sync'})
watch(owner,()=>{if(owner.value)void chat.loadConversations(1)},{immediate:true})
onBeforeUnmount(()=>chat.leaveView())
const confirmLeave=()=>!chat.pendingForActive||window.confirm(leaveWarning)
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate(to=>to.params.conversationId===route.params.conversationId||confirmLeave())
const confirmReplay=()=>window.confirm(replayWarning)
const discard=()=>chat.discardPending(()=>window.confirm('只放弃本地等待和核对信息，服务器可能仍在执行；请从历史核对后再决定是否发起新轮。是否放弃？'))
const allowCreation=()=>chat.allowAnotherCreation(()=>window.confirm('新建对话结果未知，重复创建可能产生另一段对话。你已核对列表并决定再次创建吗？'))
async function create(){const id=await chat.createConversation();if(id)await router.push({name:'chat-conversation',params:{conversationId:id}})}
</script>
<template>
 <section class="chat-page" aria-labelledby="chat-title">
  <header class="chat-heading"><div><p class="eyebrow">COOKING ASSISTANT</p><h1 id="chat-title">烹饪助手</h1></div><RouterLink :to="{name:'model-settings'}">模型设置</RouterLink></header>
  <p class="chat-intro">描述本次饮食需求，等待完整推荐结果。偏好不保证、预算未核实；已有食材是你的声明，系统未读取家庭库存。仅提供一般饮食建议。</p>
  <p v-if="isMock" class="chat-warning">开发 Mock 暂不支持聊天接口。测试数据不会替代真实后端。</p>
  <div class="chat-grid">
   <aside class="chat-sidebar" aria-label="对话列表">
    <form class="chat-create" @submit.prevent="create"><label for="conversation-title">新对话标题（可选）</label><input id="conversation-title" v-model="chat.titleDraft" :disabled="chat.creating" placeholder="留空使用新对话"><button class="secondary-button" type="submit" :disabled="chat.creating||chat.createUnknown">{{chat.creating?'正在创建…':'新建对话'}}</button></form>
    <p v-if="chat.createError" class="chat-error" role="alert">{{chat.createError}}</p>
    <button v-if="chat.createUnknown" class="text-button" type="button" @click="allowCreation">已核对列表，允许再次创建</button>
    <div class="chat-list-heading"><h2>对话历史</h2><button class="text-button" type="button" :disabled="chat.listState==='loading'" @click="chat.loadConversations(chat.conversations.page)">刷新列表</button></div>
    <p v-if="chat.listState==='loading'" role="status">正在读取对话…</p>
    <p v-else-if="chat.listState==='error'" class="chat-error" role="alert">{{chat.listError}}</p>
    <p v-else-if="chat.conversations.items.length===0" class="chat-muted">还没有对话。</p>
    <nav v-else class="chat-conversation-list"><RouterLink v-for="item in chat.conversations.items" :key="item.id" :to="{name:'chat-conversation',params:{conversationId:item.id}}">{{item.title}}</RouterLink></nav>
    <div class="chat-pagination"><button type="button" class="secondary-button" :disabled="chat.listState==='loading'||chat.conversations.page<=1" @click="chat.loadConversations(chat.conversations.page-1)">上一页</button><span>第 {{chat.conversations.page}} 页</span><button type="button" class="secondary-button" :disabled="chat.listState==='loading'||chat.conversations.page*chat.conversations.size>=chat.conversations.total" @click="chat.loadConversations(chat.conversations.page+1)">下一页</button></div>
   </aside>
   <main class="chat-main">
    <template v-if="!routeId"><div class="chat-empty"><h2>从一段新对话开始</h2><p>先新建或选择一段对话，再发送本次需求。</p></div></template>
    <template v-else>
     <header class="chat-thread-heading"><h2>{{chat.conversation?.title??'读取对话'}}</h2><button type="button" class="text-button" :disabled="chat.historyState==='loading'||chat.isSending" @click="chat.loadMessages()">读取最新消息</button></header>
     <p v-if="chat.historyState==='loading'" role="status">正在读取消息历史…</p>
     <p v-else-if="chat.historyState==='error'" class="chat-error" role="alert">{{chat.historyError}} <button class="text-button" type="button" @click="chat.activate(routeId)">重新读取</button></p>
     <template v-else-if="chat.historyState==='ready'">
      <p class="chat-muted">消息按序号从早到晚排列，第 {{chat.messages.page}} 页 / {{Math.max(1,Math.ceil(chat.messages.total/chat.messages.size))}} 页。</p>
      <p v-if="chat.messages.items.length===0" class="chat-muted">还没有消息，发送第一条需求吧。</p>
      <ol class="chat-messages"><li v-for="message in chat.messages.items" :key="message.id" :class="{'chat-user-message':message.role==='USER'}"><div class="chat-message-label"><strong>{{message.role==='USER'?'你':'助手'}}</strong><span>序号 {{message.sequence}}</span><button type="button" class="text-button" :disabled="chat.isSending" @click="chat.selectTurn(message.turnId)">查看这一轮</button></div><p>{{message.content}}</p></li></ol>
      <div class="chat-pagination"><button type="button" class="secondary-button" :disabled="chat.isSending||chat.messages.page<=1" @click="chat.loadMessages(chat.messages.page-1)">更早消息</button><span>{{chat.messages.total}} 条消息</span><button type="button" class="secondary-button" :disabled="chat.isSending||chat.messages.page*chat.messages.size>=chat.messages.total" @click="chat.loadMessages(chat.messages.page+1)">更晚消息</button></div>
     </template>
     <div class="chat-selection">
      <p v-if="chat.selectionState==='loading'" role="status">正在读取选中轮次…</p><p v-if="chat.selectionError" class="chat-error" role="alert">{{chat.selectionError}}</p>
      <RecommendationPanel v-if="chat.selected" :data="chat.selected"/>
      <button v-if="chat.selectedTurnId" class="text-button" type="button" :disabled="chat.isSending||chat.selectionState==='loading'" @click="chat.selectTurn(chat.selectedTurnId!)">只读刷新本轮状态</button>
      <p v-if="chat.pollMessage" class="chat-muted" role="status">{{chat.pollMessage}} 本次自动读取 {{chat.pollCount}} 次。</p>
     </div>
     <section v-if="chat.pendingForActive" class="chat-pending" aria-label="发送待确认"><h3>{{chat.isSending?'正在等待结果':'发送尚未确认'}}</h3><p>{{chat.pendingForActive.message}}</p><p>本次原编号与原文已固定，编辑草稿不会改变它们。前端停止等待不代表服务器执行已取消。</p>
      <template v-if="chat.pendingForActive.status==='unknown'&&!chat.pendingForActive.turnId"><p>{{replayWarning}}</p><button type="button" class="secondary-button" @click="chat.checkPending(confirmReplay)">核对/重试本次发送</button></template>
      <button v-else-if="chat.pendingForActive.turnId&&!chat.isSending" type="button" class="secondary-button" @click="chat.checkPending(confirmReplay)">只读核对本轮状态</button>
      <button class="text-button" type="button" @click="discard">放弃本地等待/核对信息</button>
     </section>
     <p v-if="chat.pending&&!chat.pendingForActive" class="chat-warning">另一段对话的发送仍待确认，请先从顶部入口处理。</p>
     <p v-if="chat.operationError" class="chat-error" role="alert">{{chat.operationError}}</p>
     <form class="chat-send" @submit.prevent="chat.send"><label for="chat-content">本次饮食需求</label><textarea id="chat-content" v-model="chat.draft" rows="4" :disabled="!chat.conversation" aria-describedby="chat-send-help" placeholder="例如：20分钟内，家里有鸡蛋和番茄，想吃点清淡的。"/><p id="chat-send-help" class="chat-muted">只发送饮食需求，请勿粘贴模型 Key。同步请求会等待完整结果；刷新和读取历史不会重新发送。</p><p v-if="chat.inputError" class="chat-error" role="alert">{{chat.inputError}}</p><button class="primary-button" type="submit" :disabled="!chat.canSend">{{chat.isSending?'等待结果…':chat.pending?'先处理待确认发送':'发送需求'}}</button></form>
    </template>
   </main>
  </div>
 </section>
</template>
<style scoped>
.chat-page{padding:42px 0 60px}.chat-heading,.chat-thread-heading,.chat-list-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.chat-heading h1{margin:9px 0;color:#304032;font-size:30px}.chat-heading a{color:#5c7354;font-size:12px}.chat-intro{max-width:900px;color:#7b8477;font-size:13px;line-height:1.7}.chat-grid{display:grid;grid-template-columns:230px minmax(0,1fr);gap:25px;margin-top:26px}.chat-sidebar,.chat-main{min-width:0;padding:20px;border:1px solid #e3e7dc;border-radius:12px;background:#fff}.chat-main{padding:25px}.chat-sidebar h2,.chat-thread-heading h2{margin:5px 0;font-size:17px;color:#41513c}.chat-create,.chat-send{display:grid;gap:9px}.chat-create label,.chat-send label{color:#4d5d47;font-size:12px;font-weight:600}.chat-create input,.chat-send textarea{width:100%;padding:11px;border:1px solid #dce3d4;border-radius:8px;outline:none;background:#fff;color:#34442f}.chat-send textarea{resize:vertical;line-height:1.6}.chat-create input:focus,.chat-send textarea:focus{border-color:#849b6f;box-shadow:0 0 0 3px #eef2e7}.chat-list-heading{margin:24px 0 10px}.chat-conversation-list{display:grid;gap:6px}.chat-conversation-list a{padding:9px;border-radius:7px;color:#72816a;font-size:13px;text-decoration:none;overflow-wrap:anywhere}.chat-conversation-list a.router-link-active{background:#edf2e5;color:#47633d;font-weight:600}.chat-pagination{display:flex;align-items:center;justify-content:space-between;gap:7px;margin:18px 0 8px;color:#7d8877;font-size:11px}.chat-pagination .secondary-button{padding:0 9px;font-size:11px}.chat-messages{display:grid;gap:13px;list-style:none;padding:0;margin:20px 0}.chat-messages li{padding:16px;border-radius:10px;background:#f3f5ef}.chat-messages li.chat-user-message{background:#f0f4e5}.chat-message-label{display:flex;align-items:center;gap:13px;color:#637458;font-size:11px}.chat-message-label .text-button{margin-left:auto}.chat-messages p{margin:6px 0 0;color:#3d4c36;font-size:13px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.chat-selection{margin:20px 0}.chat-muted{margin:8px 0;color:#8a9384;font-size:11px;line-height:1.6}.chat-error{color:#a34c42;font-size:12px;line-height:1.6}.chat-warning,.chat-pending{padding:15px;border:1px solid #e4cf9e;border-radius:9px;background:#fff9e9;color:#79642f;font-size:12px;line-height:1.7}.chat-pending h3{margin:0;font-size:15px}.chat-pending p{margin:8px 0}.chat-pending .text-button{margin-left:12px}.chat-send{margin-top:25px;padding-top:22px;border-top:1px solid #edf0e6}.chat-send .primary-button{min-width:115px;padding:0 18px;justify-self:end}.chat-empty{padding:70px 15px;text-align:center;color:#73836a}.chat-empty h2{font-size:20px}.chat-empty p{font-size:13px}@media(max-width:760px){.chat-grid{grid-template-columns:1fr;gap:16px}.chat-main{padding:19px}.chat-conversation-list{max-height:180px;overflow:auto}.chat-page{padding:30px 0 45px}}
</style>
