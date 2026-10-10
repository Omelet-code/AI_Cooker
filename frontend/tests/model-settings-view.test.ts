import {createSSRApp,ref} from 'vue'
import {createPinia,setActivePinia} from 'pinia'
import {renderToString} from '@vue/server-renderer'
import {afterEach,describe,expect,it,vi} from 'vitest'
import {useIdentityStore} from '@/stores/identity'
import type {CredentialStatusState,CredentialWriteAction} from '@/modules/settings/useModelCredentialSettings'

const settingsMock: any=vi.hoisted(()=>({}))
vi.mock('@/modules/settings/useModelCredentialSettings',()=>({useModelCredentialSettings:()=>settingsMock}))
import ModelSettingsView from '@/views/ModelSettingsView.vue'

Object.assign(settingsMock,{
 apiKey:ref(''),configured:ref(false),statusState:ref<CredentialStatusState>('ready'),statusError:ref(''),
 actionError:ref(''),actionMessage:ref(''),writeUncertain:ref<CredentialWriteAction|null>(null),
 validationError:ref(''),busyAction:ref<CredentialWriteAction|null>(null),isBusy:ref(false),
 loadStatus:vi.fn(),save:vi.fn(),remove:vi.fn(),clearValidation:vi.fn(),
})

afterEach(()=>{
 settingsMock.apiKey.value='';settingsMock.configured.value=false;settingsMock.statusState.value='ready';settingsMock.statusError.value=''
 settingsMock.actionError.value='';settingsMock.actionMessage.value='';settingsMock.writeUncertain.value=null
 settingsMock.validationError.value='';settingsMock.busyAction.value=null;settingsMock.isBusy.value=false
 vi.clearAllMocks()
})

describe('模型设置页面',()=>{
 it('展示已配置状态和密码输入，不回显任何已保存Key',async()=>{
  settingsMock.configured.value=true
  const pinia=createPinia();setActivePinia(pinia)
  const identity=useIdentityStore();identity.user={id:'user-a',username:'a'};identity.status='authenticated'
  const app=createSSRApp(ModelSettingsView);app.use(pinia)
  const html=await renderToString(app)
  expect(html).toContain('当前账号已保存模型 Key')
  expect(html).toContain('type="password"')
  expect(html).toContain('保存成功只代表凭证已配置')
  expect(html).not.toContain('fake-saved-key')
 })

 it('读取错误状态不会渲染为尚未配置',async()=>{
  settingsMock.statusState.value='error';settingsMock.statusError.value='读取配置状态失败，请稍后重试。'
  const pinia=createPinia();setActivePinia(pinia)
  const identity=useIdentityStore();identity.user={id:'user-a',username:'a'};identity.status='authenticated'
  const app=createSSRApp(ModelSettingsView);app.use(pinia)
  const html=await renderToString(app)
  expect(html).toContain('读取配置状态失败')
  expect(html).not.toContain('当前账号尚未配置模型 Key')
 })
})
