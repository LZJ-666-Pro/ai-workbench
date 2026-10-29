import { beforeEach, describe, expect, it, vi } from 'vitest'
import { auth } from '../auth'
import {
  deletePersonalDoc, importPersonalDocFromUrl, loadPersonalDocs, loadPersonalSpace,
  uploadPersonalDocs,
} from '../personalKb'

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

describe('个人知识库 API', () => {
  it('空间概况与文档列表走 /kb/personal/me 前缀并携带 Bearer 头', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/kb/personal/me')) {
        return jsonResponse(200, { id: 1, name: '我的知识库', docCount: 2, chunkTotal: 35 })
      }
      return jsonResponse(200, [{
        id: 1, title: '笔记.md', docType: 'md', sizeKb: 3, words: 120, chunks: 4,
        status: 'READY', enabled: true, sourceUrl: null, createdAt: '2026-10-28T10:00:00',
      }])
    })

    const space = await loadPersonalSpace()
    const docs = await loadPersonalDocs()

    expect(space).toEqual({ id: 1, name: '我的知识库', docCount: 2, chunkTotal: 35 })
    expect(docs[0].status).toBe('READY')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0][0]).toBe('/knowledge/api/kb/personal/me')
    expect(fetchMock.mock.calls[1][0]).toBe('/knowledge/api/kb/personal/me/documents')
    for (const [, init] of fetchMock.mock.calls) {
      expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer tk-1' })
    }
  })

  it('上传成功：multipart 字段名 files（不带 JSON 的 Content-Type），created/failed 分组返回', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {
      ok: true,
      created: [{ id: 1, title: '笔记.md', status: 'PARSING' }],
      failed: [{ title: '损坏.pdf', message: '解析失败：文件损坏' }],
    }))
    const files = [new File(['# hi'], '笔记.md', { type: 'text/markdown' })]

    const outcome = await uploadPersonalDocs(files)

    expect(outcome.created).toHaveLength(1)
    expect(outcome.created[0].title).toBe('笔记.md')
    expect(outcome.failed).toHaveLength(1)
    expect(outcome.failed[0]).toEqual({ title: '损坏.pdf', message: '解析失败：文件损坏' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/knowledge/api/kb/personal/me/documents')
    const body = init.body as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.getAll('files')).toHaveLength(1)
    const headers = init.headers as Record<string, string>
    // multipart 必须让浏览器自己补 boundary，手工指定 Content-Type 会破坏表单解析
    expect(headers['Content-Type']).toBeUndefined()
    expect(headers.Authorization).toBe('Bearer tk-1')
  })

  it('超过 10 个文件时本地直接拒绝，不发请求', async () => {
    const files = Array.from({ length: 11 }, (_, i) => new File(['x'], `f${i}.txt`))

    await expect(uploadPersonalDocs(files)).rejects.toThrow('最多上传 10 个文件')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('单文件超过 20MB 时拒绝并指出文件名', async () => {
    const big = new File(['x'], 'huge.pdf')
    Object.defineProperty(big, 'size', { value: 21 * 1024 * 1024 })

    await expect(uploadPersonalDocs([big])).rejects.toThrow('「huge.pdf」超过 20MB')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('URL 抓取成功：JSON body {url}，返回 document 概要', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {
      ok: true,
      document: { id: 9, title: '深入 LangGraph', status: 'PARSING' },
    }))

    const res = await importPersonalDocFromUrl('https://example.com/a')

    expect(res.ok).toBe(true)
    expect(res.document).toEqual({ id: 9, title: '深入 LangGraph', status: 'PARSING' })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/knowledge/api/kb/personal/me/documents/url')
    expect(init.body).toBe(JSON.stringify({ url: 'https://example.com/a' }))
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('URL 抓取 400：透出后端拒绝文案而不是 HTTP 码', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { message: '只支持 http/https 链接' }))

    await expect(importPersonalDocFromUrl('ftp://x')).rejects.toThrow('只支持 http/https 链接')
  })

  it('删除文档成功：返回 ok/message', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true, message: '文档已删除' }))

    const res = await deletePersonalDoc(3)

    expect(res).toEqual({ ok: true, message: '文档已删除' })
    expect(fetchMock.mock.calls[0][0]).toBe('/knowledge/api/kb/personal/me/documents/3')
  })

  it('删除文档 403 归属拒绝：透出后端 message', async () => {
    fetchMock.mockResolvedValue(jsonResponse(403, { message: '该文档不属于当前用户' }))

    await expect(deletePersonalDoc(3)).rejects.toThrow('该文档不属于当前用户')
  })
})
