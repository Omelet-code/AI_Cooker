import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/types'
import { createApiClient } from '@/shared/api/client'
import { createKitchenApi } from '@/modules/conversation/kitchen/api'
import { parseMealGuide, parseMealView } from '@/modules/conversation/kitchen/validation'
import { envelope } from './helpers'
import { actionView, guide, kitchenRequestId, meal } from './kitchen-fixtures'

describe('UF2 餐单与指导HTTP契约', () => {
  it('使用冻结路径；只有创建/修订/指导POST覆盖125秒超时', async () => {
    const request = vi.fn(async (path: string, method?: string, body?: unknown) => {
      if (path.endsWith('/guides/700')) return guide()
      if (path.endsWith('/actions/500')) return actionView('SELECT', kitchenRequestId, meal())
      if (path.endsWith('/selection')) return actionView('SELECT', kitchenRequestId, meal({ status: 'SELECTED', selectedOptionId: '100' }))
      if (path.endsWith('/guides')) return actionView('GUIDE', kitchenRequestId, meal({ status: 'SELECTED', selectedOptionId: '100' }))
      if (path.endsWith('/revisions')) return actionView('REVISE', kitchenRequestId, meal({ version: 2 }))
      if (method === 'POST') return actionView('CREATE', kitchenRequestId, meal())
      if (path.includes('?page=')) return { items: [meal()], page: 1, size: 20, total: 1 }
      if (path.endsWith('/actions/500')) return actionView('SELECT', kitchenRequestId, meal())
      return meal()
    })
    const api = createKitchenApi({ request } as never)
    const signal = new AbortController().signal
    await api.listMeals('1', 1, signal)
    await api.getMeal('1', '30', signal)
    const createInput = { requestId: kitchenRequestId, content: '保留空格的需求', diningCount: null }
    await api.createMeal('1', createInput, signal)
    const revision = { ...createInput, expectedVersion: 1 }
    await api.reviseMeal('1', '30', revision, signal)
    const selection = { requestId: kitchenRequestId, expectedVersion: 1, optionId: '100' }
    await api.selectOption('1', '30', selection, signal)
    await api.getAction('1', '30', '500', signal)
    await api.generateGuide('1', '30', selection, signal)
    await api.getGuide('1', '30', '700', signal)
    expect(request.mock.calls.map(call => call[0])).toEqual([
      '/conversations/1/meals?page=1&size=20',
      '/conversations/1/meals/30',
      '/conversations/1/meals',
      '/conversations/1/meals/30/revisions',
      '/conversations/1/meals/30/selection',
      '/conversations/1/meals/30/actions/500',
      '/conversations/1/meals/30/guides',
      '/conversations/1/meals/30/guides/700',
    ])
    expect(request).toHaveBeenNthCalledWith(3, '/conversations/1/meals', 'POST', createInput,
      { auth: 'required', signal, timeoutMs: 125000 })
    expect(request).toHaveBeenNthCalledWith(4, '/conversations/1/meals/30/revisions', 'POST', revision,
      { auth: 'required', signal, timeoutMs: 125000 })
    expect(request).toHaveBeenNthCalledWith(5, '/conversations/1/meals/30/selection', 'POST', selection,
      { auth: 'required', signal })
    expect(request).toHaveBeenNthCalledWith(7, '/conversations/1/meals/30/guides', 'POST', selection,
      { auth: 'required', signal, timeoutMs: 125000 })
  })

  it('真实共享Cookie/CSRF请求层承接API；不把HTTP201当作动作成功', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const failed = actionView('GUIDE', kitchenRequestId, meal({
      status: 'SELECTED', selectedOptionId: '100',
    }), 'FAILED')
    const client = createApiClient({
      mode: 'real',
      fetchImpl: (async (url, init) => {
        calls.push({ url: String(url), init })
        if (String(url) === '/api/auth/csrf') return envelope({ token: 'fake-csrf', headerName: 'X-XSRF-TOKEN' })
        return new Response(JSON.stringify({ code: 'OK', message: '成功', data: failed }), { status: 201 })
      }) as typeof fetch,
    })
    const value = await createKitchenApi(client).generateGuide('1', '30', {
      requestId: kitchenRequestId, expectedVersion: 1, optionId: '100',
    })
    expect(value.action.status).toBe('FAILED')
    expect(calls.at(-1)?.url).toBe('/api/conversations/1/meals/30/guides')
    expect(calls.at(-1)?.init?.credentials).toBe('include')
    expect(new Headers(calls.at(-1)?.init?.headers).get('X-XSRF-TOKEN')).toBe('fake-csrf')
    expect(calls.at(-1)?.init?.cache).toBe('no-store')
  })

  it('拒绝数值BIGINT、错会话/动作归属、错版本及无效引用', async () => {
    const invalidNumericId = meal() as unknown as Record<string, unknown>
    invalidNumericId.id = 90071992547409930
    expect(() => parseMealView(invalidNumericId, '1')).toThrowError(ApiError)
    expect(() => parseMealView(meal(), '2')).toThrowError(ApiError)
    const badGuide = { ...guide(), conversationId: '2' }
    expect(() => parseMealGuide(badGuide, '1', '30')).toThrowError(ApiError)
    const badEvidence = guide()
    badEvidence.content!.recipes[0].explanations[0].evidence[0].stepNos = [99]
    expect(() => parseMealGuide(badEvidence, '1', '30')).toThrowError(ApiError)
  })

  it('real模式HTTP无效2xx成为结果未知输入，不由API层自动重试POST', async () => {
    const calls: string[] = []
    const client = createApiClient({
      mode: 'real',
      fetchImpl: (async url => {
        calls.push(String(url))
        if (String(url) === '/api/auth/csrf') return envelope({ token: 'fake-csrf', headerName: 'X-XSRF-TOKEN' })
        return new Response(JSON.stringify({ code: 'OK', message: '成功', data: { action: {} } }), { status: 200 })
      }) as typeof fetch,
    })
    await expect(createKitchenApi(client).createMeal('1', { requestId: kitchenRequestId, content: '需求' }))
      .rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(calls.filter(url => url === '/api/conversations/1/meals')).toHaveLength(1)
  })
})
