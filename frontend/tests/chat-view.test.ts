import {createSSRApp,h,reactive} from 'vue'
import {createPinia,setActivePinia} from 'pinia'
import {renderToString} from '@vue/server-renderer'
import {beforeEach,describe,expect,it,vi} from 'vitest'
import {RouterView} from 'vue-router'
import {useIdentityStore} from '@/stores/identity'
import {conversationFixture,messageFixture,pageFixture,recommendationFixture,requestId} from './chat-fixtures'

const holder=vi.hoisted(()=>({chat:null as any,cooking:{pendingCooking:null as any}}))
vi.mock('@/modules/conversation/store',()=>({useConversationStore:()=>holder.chat}))
vi.mock('@/stores/cooking',()=>({useCookingStore:()=>holder.cooking}))
// SSR没有真实页面离开；导航确认在待人工清单中，路由登录守卫继续执行原实现。
vi.mock('vue-router',async original=>{
 const actual=await original<typeof import('vue-router')>()
 return {...actual,createWebHistory:actual.createMemoryHistory,onBeforeRouteLeave:vi.fn(),onBeforeRouteUpdate:vi.fn()}
})
import router from '@/router'

let pinia:ReturnType<typeof createPinia>
beforeEach(async()=>{
 pinia=createPinia();setActivePinia(pinia)
 const identity=useIdentityStore();identity.user={id:'1',username:'fake'};identity.status='authenticated'
 holder.cooking.pendingCooking=null
 holder.chat=reactive({conversations:pageFixture([conversationFixture()]),listState:'ready',listError:'',titleDraft:'',creating:false,createUnknown:false,createError:'',activeId:'1',conversation:conversationFixture(),messages:pageFixture([messageFixture()]),historyState:'ready',historyError:'',selectedTurnId:'2',selected:recommendationFixture(),selectionState:'ready',selectionError:'',draft:'',inputError:'',operationError:'',pending:null,pendingForActive:null,isSending:false,canSend:true,polling:false,pollCount:0,pollMessage:'',activate:vi.fn(),loadConversations:vi.fn(),loadMessages:vi.fn(),selectTurn:vi.fn(),send:vi.fn(),createConversation:vi.fn(),allowAnotherCreation:vi.fn(),checkPending:vi.fn(),discardPending:vi.fn(),leaveView:vi.fn()})
 await router.push('/chat/1')
})
async function render(){const app=createSSRApp({render:()=>h(RouterView)});app.use(pinia);app.use(router);return renderToString(app)}

describe('实际聊天视图与受保护路由',()=>{
 it('两个聊天入口继承登录守卫，大ID参数不转换为数字',async()=>{
  expect(router.resolve('/chat').meta.requiresAuth).toBe(true)
  await router.push('/chat/90071992547409930');expect(router.currentRoute.value.params.conversationId).toBe('90071992547409930')
  const identity=useIdentityStore();identity.user=null;identity.status='anonymous'
  await router.push('/chat/2');expect(router.currentRoute.value.name).toBe('login');expect(router.currentRoute.value.query.redirect).toBe('/chat/2')
 })
 it.each(['RECOMMENDED','NO_MATCH','CLARIFICATION','UNSUPPORTED'] as const)('展示%s、合法null和必要声明',async outcome=>{
  holder.chat.selected=recommendationFixture('SUCCEEDED',outcome)
  const html=await render()
  expect(html).toContain('虚构固定回答');expect(html).toContain('预算尚未核实');expect(html).toContain('未读取或抵扣家庭库存');expect(html).toContain('不作诊断或治疗承诺')
  expect(html).toContain('query Embedding 请求数：未知');expect(html).toContain('输入 token 未知')
  if(outcome==='RECOMMENDED'){expect(html).toContain('预计时间：未知');expect(html).toContain('href="/recipes/90071992547409930"')}
  else expect(html).not.toContain('class="chat-recommendation"')
 })
 it('FAILED和旧成功无快照均不制造推荐，凭证缺失引导模型设置',async()=>{
  holder.chat.selected=recommendationFixture('FAILED')
  holder.chat.selected.turn.error.message='<script>不能回显</script>'
  let html=await render();expect(html).toContain('该轮执行失败');expect(html).toContain('前往模型设置');expect(html).not.toContain('不能回显');expect(html).not.toContain('虚构固定回答')
  holder.chat.selected={...recommendationFixture(),result:null}
  html=await render();expect(html).toContain('没有结构化推荐快照');expect(html).not.toContain('class="chat-recommendation"')
 })
 it('恶意HTML与自由URL只作文本，详情只生成合法本地路由',async()=>{
  const dangerous='<img src=x onerror=alert(1)> https://invalid.example/buy'
  holder.chat.messages.items[0].content=dangerous
  holder.chat.selected.result.answer=dangerous
  holder.chat.selected.result.recipes[0].name='<script>恶意名字</script>'
  holder.chat.selected.result.recipes[0].summary=dangerous
  let html=await render();expect(html).toContain('&lt;img');expect(html).not.toContain('<img');expect(html).not.toContain('<script>');expect(html).not.toContain('href="https://invalid.example')
  holder.chat.selected.result.recipes[0].recipeId='javascript:alert(1)'
  html=await render();expect(html).toContain('菜谱编号不可用');expect(html).not.toContain('href="javascript:')
 })
 it('厨具null与空数组含义不同，预算字符串保持且不夸大',async()=>{
  holder.chat.selected.result.constraints.budgetYuan='30.50'
  let html=await render();expect(html).toContain('未知 / 未限定');expect(html).toContain('30.50 元（未核实）')
  holder.chat.selected.result.constraints.availableToolNames=[]
  html=await render();expect(html).toContain('明确没有厨具')
 })
 it('未知发送显示原键核对及费用风险提示，与做菜待确认共存',async()=>{
  const pending={userId:'1',conversationId:'1',payload:{requestId,content:'虚构需求'},turnId:null,status:'unknown',message:'结果尚未确认'}
  holder.chat.pending=pending;holder.chat.pendingForActive=pending;holder.chat.canSend=false
  holder.cooking.pendingCooking={recipeId:'12',status:'unknown'}
  const html=await render()
  expect(html).toContain('可能产生模型费用');expect(html).toContain('核对/重试本次发送');expect(html).toContain('做菜记录结果未知');expect(html).toContain('核对本次发送')
  expect(holder.chat.send).not.toHaveBeenCalled();expect(holder.chat.checkPending).not.toHaveBeenCalled()
 })
 it('历史加载失败有明确展示，不伪装空消息',async()=>{
  holder.chat.historyState='error';holder.chat.historyError='服务暂不可用'
  const html=await render();expect(html).toContain('服务暂不可用');expect(html).not.toContain('还没有消息，发送第一条需求吧')
 })
 it('加载与已读空历史分别展示，不伪造助手内容',async()=>{
  holder.chat.historyState='loading'
  let html=await render();expect(html).toContain('正在读取消息历史');expect(html).not.toContain('还没有消息，发送第一条需求吧')
  holder.chat.historyState='ready';holder.chat.messages=pageFixture();holder.chat.selected=null;holder.chat.selectedTurnId=null
  html=await render();expect(html).toContain('还没有消息，发送第一条需求吧');expect(html).not.toContain('虚构固定回答')
 })
})
