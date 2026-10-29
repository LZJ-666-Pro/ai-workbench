import { beforeEach, describe, expect, it, vi } from 'vitest'
import { auth } from '../auth'
import { kbAdminApi, loadPlatformSpaces } from '../kbAdmin'

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response
}

const fetchMock = vi.fn()

beforeEach(() => {
  auth.token = 'tk-1'
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

describe('个人知识库管理端 API', () => {
  it('overview 与平台空间列表分别走 /kb/admin 与 /kb/spaces 前缀并携带 Bearer 头', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/kb/admin/overview')) {
        return jsonResponse(200, {
          personalSpaces: 3, readySpaces: 2, totalDocs: 12, failedDocs: 1,
          totalSizeMb: 8.5, totalWords: 9600, totalChunks: 210,
          trend: [{ date: '10-22', count: 4 }],
        })
      }
      return jsonResponse(200, [{ id: 7, name: '产品手册' }])
    })

    const overview = await kbAdminApi.overview()
    const spaces = await loadPlatformSpaces()

    expect(overview.personalSpaces).toBe(3)
    expect(overview.trend[0]).toEqual({ date: '10-22', count: 4 })
    expect(spaces).toHaveLength(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/knowledge/api/kb/admin/overview')
    expect(fetchMock.mock.calls[1][0]).toBe('/knowledge/api/kb/spaces')
    for (const [, init] of fetchMock.mock.calls) {
      expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer tk-1' })
    }
  })

  it('libraries 分页参数与 query 拼接：page/size/keyword 逐项落位，空 keyword 不入参', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {
      total: 1,
      items: [{
        spaceId: 1, owner: 'zhangsan', displayName: '我的知识库', name: 'my-kb',
        docCount: 3, chunkTotal: 40, sizeKb: 128.5, createdAt: '2026-10-28T10:00:00',
      }],
    }))

    const page = await kbAdminApi.libraries(2, 20, '笔记')

    expect(page.total).toBe(1)
    expect(page.items[0].displayName).toBe('我的知识库')
    const [url1] = fetchMock.mock.calls[0] as [string]
    expect(url1.startsWith('/knowledge/api/kb/admin/libraries?')).toBe(true)
    const q1 = new URL(url1, 'http://localhost').searchParams
    expect(q1.get('page')).toBe('2')
    expect(q1.get('size')).toBe('20')
    expect(q1.get('keyword')).toBe('笔记')

    // 空 keyword 属于"未过滤"，不应拼出 keyword= 空参数
    await kbAdminApi.libraries(1, 10)
    const [url2] = fetchMock.mock.calls[1] as [string]
    expect(url2).toBe('/knowledge/api/kb/admin/libraries?page=1&size=10')
  })

  it('403 归属拒绝：透出后端 message 而不是 HTTP 码', async () => {
    fetchMock.mockResolvedValue(jsonResponse(403, { message: '该文档不属于当前用户' }))

    await expect(kbAdminApi.deleteDoc(3)).rejects.toThrow('该文档不属于当前用户')
    expect(fetchMock.mock.calls[0][0]).toBe('/knowledge/api/kb/admin/documents/3')
  })

  it('bind 请求体：POST JSON {spaceId}，Content-Type 为 application/json', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true }))

    const res = await kbAdminApi.bind(5)

    expect(res.ok).toBe(true)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/knowledge/api/kb/admin/bindings')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ spaceId: 5 }))
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('deleteDoc 成功：按 {ok, message} 判定结果', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true, message: '文档已删除' }))

    const res = await kbAdminApi.deleteDoc(9)

    expect(res.ok).toBe(true)
    expect(res.message).toBe('文档已删除')
    expect(fetchMock.mock.calls[0][0]).toBe('/knowledge/api/kb/admin/documents/9')
    expect((fetchMock.mock.calls[0] as [string, RequestInit])[1].method).toBe('DELETE')
  })
})
