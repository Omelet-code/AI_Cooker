import {beforeEach,describe,expect,it,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import {ApiError} from '../src/shared/api/types'
vi.mock('../src/modules/identity/api',()=>({identityApi:{register:vi.fn(),login:vi.fn(),currentUser:vi.fn(),logout:vi.fn(),initializeCsrf:vi.fn(),refreshCsrf:vi.fn()}}))
import {identityApi} from '../src/modules/identity/api'
import {useIdentityStore} from '../src/stores/identity'
const api=vi.mocked(identityApi)
describe('身份 Store',()=>{
 beforeEach(()=>{setActivePinia(createPinia());vi.clearAllMocks()})
 it('先初始化 CSRF 再恢复当前用户',async()=>{
  const events:string[]=[]
  api.initializeCsrf.mockImplementation(async()=>{events.push('csrf');return {token:'memory',headerName:'X-XSRF-TOKEN'}})
  api.currentUser.mockImplementation(async()=>{events.push('me');return {id:'41',username:'cook'}})
  const store=useIdentityStore();await store.initialize()
  expect(events).toEqual(['csrf','me']);expect(store.status).toBe('authenticated');expect(store.user?.id).toBe('41')
 })
 it('记住我字段传入登录请求，密码不留在 Pinia 状态',async()=>{
  api.login.mockResolvedValue({id:'7',username:'cook'});api.refreshCsrf.mockResolvedValue({token:'next',headerName:'X-XSRF-TOKEN'})
  const store=useIdentityStore();await store.login(' Cook ','a-private-password',true)
  expect(api.login).toHaveBeenCalledWith({username:'cook',password:'a-private-password',rememberMe:true})
  expect(JSON.stringify(store.$state)).not.toContain('a-private-password')
  expect(JSON.stringify(store.$state)).not.toContain('token')
 })
 it('403 权限错误不会被当作匿名成功初始化并保留中文提示',async()=>{
  api.initializeCsrf.mockResolvedValue({token:'c',headerName:'X-XSRF-TOKEN'})
  api.currentUser.mockRejectedValue(new ApiError(403,'ACCESS_DENIED','无权访问'))
  const store=useIdentityStore();await store.initialize()
  expect(store.status).toBe('anonymous');expect(store.errorMessage).toBe('无权访问')
 })
 it('退出无论网络结果如何都会清理用户状态和重新获取 CSRF',async()=>{
  api.logout.mockRejectedValue(new Error('offline'))
  api.refreshCsrf.mockResolvedValue({token:'fresh',headerName:'X-XSRF-TOKEN'})
  const store=useIdentityStore();store.user={id:'2',username:'cook'};store.status='authenticated'
  await expect(store.logout()).rejects.toThrow('offline')
  expect(store.user).toBeNull();expect(store.status).toBe('anonymous');expect(api.refreshCsrf).toHaveBeenCalledOnce()
 })
})
