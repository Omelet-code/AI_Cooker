import { describe, expect, it, vi } from 'vitest'
import { createShoppingApi } from '@/modules/shopping/api'
import { list, match, preview } from './shopping-fixtures'

describe('F3-D 采购API路径与写请求', () => {
  it('只使用冻结接口；清单成功201和同键复用200均解析为已知快照', async () => {
    const request = vi.fn(async (path: string, method?: string) => path.includes('shopping-preview') ? preview : path.endsWith('/latest') ? null : path.endsWith('/product-matches') && method === 'POST' ? match : path.endsWith('/66') ? match : list)
    const api = createShoppingApi({ request } as never), signal = new AbortController().signal
    await api.preview({ conversationId: '1', mealId: '20', expectedVersion: 3, optionId: '30' }, signal)
    await api.create({ requestId: crypto.randomUUID(), conversationId: '1', mealId: '20', expectedVersion: 3, optionId: '30', sourceFingerprint: 'a'.repeat(64), confirmations: [] })
    await api.getList('55', signal); await api.latestMatch('55', signal); await api.getMatch('55', '66', signal)
    await api.createMatch('55', { requestId: crypto.randomUUID(), cityCode: '110000' }, signal)
    expect(request.mock.calls.map(call => call[0])).toEqual([
      '/conversations/1/meals/20/shopping-preview?expectedVersion=3&optionId=30', '/shopping-lists', '/shopping-lists/55',
      '/shopping-lists/55/product-matches/latest', '/shopping-lists/55/product-matches/66', '/shopping-lists/55/product-matches',
    ])
    expect(request.mock.calls[1]?.[3]).toMatchObject({ auth: 'required', signal: undefined })
    expect(request.mock.calls[1]?.[3]).not.toHaveProperty('timeoutMs')
    expect(request.mock.calls[5]?.[3]).toMatchObject({ auth: 'required', signal })
    expect(request.mock.calls[5]?.[3]).not.toHaveProperty('timeoutMs')
  })
  it('latest null是合法的未匹配，并拒绝非法路径标识', async () => {
    const request = vi.fn(async () => null)
    const api = createShoppingApi({ request } as never)
    await expect(api.latestMatch('55')).resolves.toBeNull()
    await expect(api.getList('1/2')).rejects.toThrow()
  })
})
