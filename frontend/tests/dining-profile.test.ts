import { computed, effectScope, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApiClient } from '@/shared/api/client'
import { ApiError } from '@/shared/api/types'
import { clearSessionScopedState } from '@/shared/auth/sessionCleanup'
import { installAuthenticationLifecycle } from '@/app/authLifecycle'
import { createDiningProfileApi } from '@/modules/identity/diningProfile/api'
import { useDiningProfile } from '@/modules/identity/diningProfile/useDiningProfile'
import { MAX_DINING_COUNT, parseDiningProfile } from '@/modules/identity/diningProfile/validation'
import type { DiningProfile, DiningProfileApi } from '@/modules/identity/diningProfile/types'
import { envelope, failure } from './helpers'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function harness(overrides: Partial<DiningProfileApi> = {}) {
  const identity = ref<string | null>('user-a')
  const api: DiningProfileApi = {
    getProfile: vi.fn(async () => ({ defaultDiningCount: null })),
    saveProfile: vi.fn(async (defaultDiningCount) => ({ defaultDiningCount })),
    ...overrides,
  }
  const scope = effectScope()
  const profile = scope.run(() => useDiningProfile(api, computed(() => identity.value)))!
  return { api, identity, scope, profile }
}

afterEach(() => vi.restoreAllMocks())

describe('默认人数档案 API', () => {
  it('使用精确相对路径、登录态和单字段请求体', async () => {
    const request = vi.fn(async (_path: string, _method?: string, body?: unknown) =>
      body === undefined ? { defaultDiningCount: null } : { defaultDiningCount: 4 })
    const api = createDiningProfileApi({ request })
    const controller = new AbortController()
    await api.getProfile(controller.signal)
    await api.saveProfile(4, controller.signal)
    await api.saveProfile(null)

    expect(request).toHaveBeenNthCalledWith(1, '/users/me/profile', 'GET', undefined, { auth: 'required', signal: controller.signal })
    expect(request).toHaveBeenNthCalledWith(2, '/users/me/profile', 'PUT', { defaultDiningCount: 4 }, { auth: 'required', signal: controller.signal })
    expect(request).toHaveBeenNthCalledWith(3, '/users/me/profile', 'PUT', { defaultDiningCount: null }, { auth: 'required', signal: undefined })
  })

  it('真实模式经共享客户端使用 Cookie 与 CSRF，并将未知字段作为明确的 Mock 404', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const fetchImpl = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/auth/csrf') return envelope({ token: 'csrf-test', headerName: 'X-XSRF-TOKEN' })
      return envelope({ defaultDiningCount: init?.method === 'PUT' ? 4 : null })
    }) as typeof fetch
    const api = createDiningProfileApi(createRequestClient(fetchImpl))
    await api.getProfile()
    await api.saveProfile(4)
    await api.saveProfile(null)

    expect(calls.map(call => call.url)).toEqual([
      '/api/users/me/profile', '/api/auth/csrf', '/api/users/me/profile', '/api/users/me/profile',
    ])
    for (const call of calls.filter(call => call.url === '/api/users/me/profile' && call.init?.method === 'PUT')) {
      expect(call.init?.credentials).toBe('include')
      expect(new Headers(call.init?.headers).get('X-XSRF-TOKEN')).toBe('csrf-test')
    }
    expect(calls[2].init?.body).toBe(JSON.stringify({ defaultDiningCount: 4 }))
    expect(calls[3].init?.body).toBe(JSON.stringify({ defaultDiningCount: null }))

    let mockFetches = 0
    const mockApi = createDiningProfileApi(createApiClient({
      mode: 'mock',
      fetchImpl: (async () => { mockFetches++; return envelope({ defaultDiningCount: 2 }) }) as typeof fetch,
    }))
    await expect(mockApi.getProfile()).rejects.toMatchObject({ status: 404 })
    expect(mockFetches).toBe(0)
  })
})

function createRequestClient(fetchImpl: typeof fetch) {
  // 测试沿用真实共享客户端实现，只替换网络传输，不访问开发服务。
  return createApiClient({ mode: 'real', fetchImpl })
}

describe('默认人数档案状态与页面数据', () => {
  it('null表示未设置；读取失败和非法响应不会伪装成未设置', async () => {
    const { api, profile, scope } = harness()
    expect(await profile.load()).toBe(true)
    expect(api.getProfile).toHaveBeenCalledOnce()
    expect(profile.state.value).toBe('ready')
    expect(profile.savedCount.value).toBeNull()

    const failed = harness({ getProfile: vi.fn().mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'secret detail')) })
    expect(await failed.profile.load()).toBe(false)
    expect(failed.profile.state.value).toBe('error')
    expect(failed.profile.savedCount.value).toBeNull()
    expect(failed.profile.statusError.value).not.toContain('secret detail')

    const invalid = harness({ getProfile: vi.fn(async () => ({ defaultDiningCount: 0 }) as unknown as DiningProfile) })
    expect(await invalid.profile.load()).toBe(false)
    expect(invalid.profile.state.value).toBe('error')
    scope.stop(); failed.scope.stop(); invalid.scope.stop()
  })

  it.each(['', ' ', '0', '-1', '2.5', '2e2', '+2', '01', '2147483648', '🧑‍🍳'])(
    '非法输入 %j 不发送 PUT', async value => {
      const { api, profile, scope } = harness()
      await profile.load()
      profile.countInput.value = value
      expect(await profile.save()).toBe(false)
      expect(api.saveProfile).not.toHaveBeenCalled()
      expect(profile.validationError.value).toContain('正整数')
      scope.stop()
    },
  )

  it('接受正整数边界，草稿与已保存值分开，成功后采用服务器回值', async () => {
    const saveProfile = vi.fn(async (value: number | null) => ({ defaultDiningCount: value === MAX_DINING_COUNT ? 10 : value }))
    const { api, profile, scope } = harness({ getProfile: vi.fn(async () => ({ defaultDiningCount: 3 })), saveProfile })
    await profile.load()
    profile.countInput.value = String(MAX_DINING_COUNT)
    expect(profile.savedCount.value).toBe(3)
    expect(await profile.save()).toBe(true)
    expect(saveProfile).toHaveBeenCalledWith(MAX_DINING_COUNT, expect.any(AbortSignal))
    expect(profile.savedCount.value).toBe(10)
    expect(profile.countInput.value).toBe('10')
    scope.stop()
  })

  it('接受服务器整数边界并拒绝非整数、越界和额外字段响应', () => {
    expect(parseDiningProfile({ defaultDiningCount: 1 })).toEqual({ defaultDiningCount: 1 })
    expect(parseDiningProfile({ defaultDiningCount: MAX_DINING_COUNT })).toEqual({ defaultDiningCount: MAX_DINING_COUNT })
    expect(parseDiningProfile({ defaultDiningCount: null })).toEqual({ defaultDiningCount: null })
    for (const value of [0, -1, 2.5, '4', true, MAX_DINING_COUNT + 1]) {
      expect(() => parseDiningProfile({ defaultDiningCount: value })).toThrow(ApiError)
    }
    expect(() => parseDiningProfile({ defaultDiningCount: 4, userId: 'other-user' })).toThrow(ApiError)
  })

  it('只有明确清空操作才以 null 写入，并在成功响应后更新已保存值', async () => {
    const { api, profile, scope } = harness({ getProfile: vi.fn(async () => ({ defaultDiningCount: 3 })) })
    await profile.load()
    profile.countInput.value = '7'
    expect(profile.savedCount.value).toBe(3)
    expect(await profile.clear()).toBe(true)
    expect(api.saveProfile).toHaveBeenCalledWith(null, expect.any(AbortSignal))
    expect(profile.savedCount.value).toBeNull()
    expect(profile.countInput.value).toBe('')
    scope.stop()
  })

  it('写入忙时抑制连点；明确 400 保留原值和草稿', async () => {
    const pending = deferred<DiningProfile>()
    const { api, profile, scope } = harness({ saveProfile: vi.fn(() => pending.promise) })
    await profile.load()
    profile.countInput.value = '8'
    const first = profile.save()
    expect(await profile.save()).toBe(false)
    expect(api.saveProfile).toHaveBeenCalledOnce()
    pending.resolve({ defaultDiningCount: 8 })
    expect(await first).toBe(true)
    expect(profile.savedCount.value).toBe(8)
    scope.stop()

    const rejected = harness({ getProfile: vi.fn(async () => ({ defaultDiningCount: 3 })), saveProfile: vi.fn().mockRejectedValue(new ApiError(400, 'VALIDATION_ERROR', '')) })
    await rejected.profile.load()
    rejected.profile.countInput.value = '9'
    expect(await rejected.profile.save()).toBe(false)
    expect(rejected.profile.savedCount.value).toBe(3)
    expect(rejected.profile.countInput.value).toBe('9')
    expect(rejected.profile.writeUncertain.value).toBeNull()
    rejected.scope.stop()
  })

  it('未知写入不自动重发或自动回读；用户要求后以 GET 核对', async () => {
    const getProfile = vi.fn().mockResolvedValueOnce({ defaultDiningCount: 2 }).mockResolvedValueOnce({ defaultDiningCount: 6 })
    const saveProfile = vi.fn().mockRejectedValue(new ApiError(0, 'REQUEST_TIMEOUT', ''))
    const { api, profile, scope } = harness({ getProfile, saveProfile })
    await profile.load()
    profile.countInput.value = '6'
    expect(await profile.save()).toBe(false)
    expect(profile.writeUncertain.value).toBe('save')
    expect(saveProfile).toHaveBeenCalledOnce()
    expect(getProfile).toHaveBeenCalledOnce()

    expect(await profile.load()).toBe(true)
    expect(getProfile).toHaveBeenCalledTimes(2)
    expect(saveProfile).toHaveBeenCalledOnce()
    expect(profile.savedCount.value).toBe(6)
    expect(profile.writeUncertain.value).toBeNull()
    scope.stop()
  })

  it('账号切换和页面卸载会取消请求、清空私有状态并丢弃迟到响应', async () => {
    const gate = deferred<DiningProfile>()
    let oldSignal: AbortSignal | undefined
    const getProfile = vi.fn((signal?: AbortSignal) => {
      if (!oldSignal) oldSignal = signal
      return getProfile.mock.calls.length === 1 ? gate.promise : Promise.resolve({ defaultDiningCount: 5 })
    })
    const { api, identity, profile, scope } = harness({ getProfile })
    const oldLoad = profile.load()
    identity.value = 'user-b'
    expect(oldSignal?.aborted).toBe(true)
    gate.resolve({ defaultDiningCount: 99 })
    await oldLoad
    await Promise.resolve()
    expect(profile.savedCount.value).toBe(5)
    expect(api.getProfile).toHaveBeenCalledTimes(2)

    profile.countInput.value = '22'
    scope.stop()
    expect(profile.countInput.value).toBe('')
  })

  it('页面卸载会取消未完成读取并丢弃晚到响应', async () => {
    const gate = deferred<DiningProfile>()
    const { profile, scope } = harness({ getProfile: vi.fn(() => gate.promise) })
    const loading = profile.load()
    scope.stop()
    gate.resolve({ defaultDiningCount: 9 })
    await loading
    expect(profile.savedCount.value).toBeNull()
    expect(profile.countInput.value).toBe('')
  })

  it('账号切换会取消旧用户写等待，旧写回值不能覆盖新账号档案', async () => {
    const writeGate = deferred<DiningProfile>()
    let oldWriteSignal: AbortSignal | undefined
    const getProfile = vi.fn()
      .mockResolvedValueOnce({ defaultDiningCount: 2 })
      .mockResolvedValueOnce({ defaultDiningCount: 7 })
    const saveProfile = vi.fn((value: number | null, signal?: AbortSignal) => {
      oldWriteSignal = signal
      return writeGate.promise
    })
    const { api, identity, profile, scope } = harness({ getProfile, saveProfile })
    await profile.load()
    profile.countInput.value = '8'
    const oldWrite = profile.save()
    identity.value = 'user-b'
    await Promise.resolve()
    await Promise.resolve()
    expect(oldWriteSignal?.aborted).toBe(true)
    expect(profile.savedCount.value).toBe(7)
    writeGate.resolve({ defaultDiningCount: 8 })
    await oldWrite
    expect(profile.savedCount.value).toBe(7)
    expect(api.saveProfile).toHaveBeenCalledOnce()
    scope.stop()
  })

  it('共享客户端最终 401 触发应用会话清理并擦除档案草稿', async () => {
    const client = createApiClient({
      mode: 'real',
      fetchImpl: (async input => {
        const url = String(input)
        if (url === '/api/auth/csrf') return envelope({ token: 'csrf-test', headerName: 'X-XSRF-TOKEN' })
        if (url === '/api/users/me/profile') return failure(401, 'ACCESS_TOKEN_EXPIRED')
        if (url === '/api/auth/refresh') return failure(401, 'REFRESH_REJECTED')
        throw new Error('unexpected request')
      }) as typeof fetch,
    })
    const identity = { user: { id: 'user-a' }, status: 'authenticated', clearUser: vi.fn(function (this: { user: unknown; status: string }) {
      this.user = null
      this.status = 'anonymous'
    }) }
    const route = { name: 'profile-settings' }
    const router = { currentRoute: { value: route }, replace: vi.fn(async () => undefined) }
    const uninstall = installAuthenticationLifecycle(identity as never, router as never, client)
    const scope = effectScope()
    const profile = scope.run(() => useDiningProfile(createDiningProfileApi(client), computed(() => identity.user?.id ?? null)))!
    profile.countInput.value = '12'
    expect(await profile.load()).toBe(false)
    expect(identity.user).toBeNull()
    expect(identity.clearUser).toHaveBeenCalledOnce()
    expect(profile.savedCount.value).toBeNull()
    expect(profile.countInput.value).toBe('')
    expect(profile.statusError.value).toContain('重新登录')
    uninstall()
    scope.stop()
  })

  it('会话清理回调清除当前视图的档案和草稿', async () => {
    const { profile, scope } = harness()
    profile.savedCount.value = 12
    profile.countInput.value = '18'
    clearSessionScopedState()
    expect(profile.savedCount.value).toBeNull()
    expect(profile.countInput.value).toBe('')
    expect(profile.state.value).toBe('error')
    expect(profile.statusError.value).toContain('重新登录')
    scope.stop()
  })
})
