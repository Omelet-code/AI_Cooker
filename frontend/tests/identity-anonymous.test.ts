import {beforeEach,describe,expect,it,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import {ApiError} from '../src/shared/api/types'
vi.mock('../src/modules/identity/api',()=>({identityApi:{register:vi.fn(),login:vi.fn(),currentUser:vi.fn(),logout:vi.fn(),initializeCsrf:vi.fn(),refreshCsrf:vi.fn()}}))
import {identityApi} from '../src/modules/identity/api'
import {useIdentityStore} from '../src/stores/identity'
import {registerSessionCleanup} from '../src/shared/auth/sessionCleanup'
const api=vi.mocked(identityApi)
describe('匿名身份恢复',()=>{
 beforeEach(()=>{setActivePinia(createPinia());vi.clearAllMocks()})
 it('初次访问被拒续期视为正常匿名，不展示续期无效错误',async()=>{
  api.initializeCsrf.mockResolvedValue({token:'c',headerName:'X-XSRF-TOKEN'})
  api.currentUser.mockRejectedValue(new ApiError(401,'REFRESH_REJECTED','续期凭证无效，请重新登录'))
  const store=useIdentityStore();await store.initialize()
  expect(store.status).toBe('anonymous');expect(store.user).toBeNull();expect(store.errorMessage).toBe('')
 })
 it('退出时调用已注册的私有状态清理入口',async()=>{
  api.logout.mockResolvedValue(null);api.refreshCsrf.mockResolvedValue({token:'c',headerName:'X-XSRF-TOKEN'})
  const clean=vi.fn(),remove=registerSessionCleanup(clean)
  const store=useIdentityStore();store.user={id:'1',username:'cook'};store.status='authenticated'
  await store.logout()
  expect(clean).toHaveBeenCalledOnce();remove()
 })
 it('退出后保持正常匿名，再次初始化不显示续期无效错误',async()=>{
  api.logout.mockResolvedValue(null);api.refreshCsrf.mockResolvedValue({token:'c2',headerName:'X-XSRF-TOKEN'})
  const store=useIdentityStore();store.user={id:'1',username:'cook'};store.status='authenticated'
  await store.logout();await store.initialize()
  expect(store.status).toBe('anonymous');expect(store.errorMessage).toBe('')
  expect(api.currentUser).not.toHaveBeenCalled()
 })
})
