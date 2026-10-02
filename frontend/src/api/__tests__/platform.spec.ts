import { beforeEach, describe, expect, it, vi } from 'vitest'
import { auth, logout } from '../auth'
import { loadPlatformLogs, loadWorkbench, probeAppRunning, searchPlatform } from '../platform'

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response
}

describe('平台工作台接口', () => {
  beforeEach(() => {
    localStorage.clear()
    logout()
  })

  it('读工作台时带上登录 token，且路径只有一个 /api', async () => {
    auth.token = 'tk-1'
    const fetchMock = vi.fn(async () => jsonResponse(200, { apps: [], hero: {}, platform: {} }))
    vi.stubGlobal('fetch', fetchMock)

    await loadWorkbench()

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    // 这个断言必须盯住「后端真实提供的路径」，而不是「代码当前拼出来的路径」。
    // 教训：这里原先断言的是 /bank/api/api/platform/workbench——拼错了前缀，
    // 但测试是照实现写的，于是把 404 当成了正确契约锁死，首页因此白屏。
    // 契约来源：PlatformConsoleController 的 @GetMapping("/api/platform/workbench")
    //           + Vite 代理 rewrite 去掉 /bank → 前端应请求 /bank/api/platform/workbench
    expect(url).toBe('/bank/api/platform/workbench')
    expect(url).not.toContain('/api/api/')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tk-1')
  })

  it('日志查询只拼接有值的筛选条件', async () => {
    // 空字符串必须被丢掉：否则会变成 app= 这种「筛选了一个空应用」的查询，
    // 后端虽然能容忍，但 URL 会被复制传播，读的人无法判断到底是筛了还是没筛
    const fetchMock = vi.fn(async () => jsonResponse(200, { list: [], total: 0 }))
    vi.stubGlobal('fetch', fetchMock)

    await loadPlatformLogs({ app: 'bank', result: '', keyword: undefined, page: 2, size: 20 })

    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    expect(url).toBe('/bank/api/admin/platform-logs?app=bank&page=2&size=20')
    expect(url).not.toContain('/api/api/')
    expect(url).not.toContain('result=')
    expect(url).not.toContain('keyword=')
  })

  it('关键字里的特殊字符被正确编码（traceId/中文关键字都要能搜）', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, { list: [], total: 0 }))
    vi.stubGlobal('fetch', fetchMock)

    await loadPlatformLogs({ keyword: '转账 & 确认单' })

    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    expect(url).toContain('keyword=%E8%BD%AC%E8%B4%A6+%26+%E7%A1%AE%E8%AE%A4%E5%8D%95')
  })

  it('非 2xx 时抛后端 message，而不是只报状态码', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(403, { message: '需要管理员权限' })))

    await expect(loadPlatformLogs({})).rejects.toThrow('需要管理员权限')
  })
})

describe('全局搜索', () => {
  beforeEach(() => {
    localStorage.clear()
    logout()
  })

  it('请求路径与后端 @GetMapping("/api/platform/search") 对齐，只有一个 /api', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, { apps: [], logs: [], endpoints: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await searchPlatform('转账')

    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    expect(url).toBe('/bank/api/platform/search?q=%E8%BD%AC%E8%B4%A6&limit=5')
    expect(url).not.toContain('/api/api/')
  })

  it('关键字里的 & 与空格被编码，不会截断查询串', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, { apps: [], logs: [], endpoints: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await searchPlatform('a&b c')

    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    // 注意编码器的差别：searchPlatform 用 encodeURIComponent（空格 → %20），
    // loadPlatformLogs 用 URLSearchParams（空格 → +）。两者在查询串里都合法，
    // 但断言必须与实现用的编码器一致，否则测的是另一件事。
    expect(url).toContain('q=a%26b%20c')
    expect(url).toContain('&limit=5')
  })
})

describe('应用在线状态探测', () => {
  beforeEach(() => {
    localStorage.clear()
    logout()
  })

  it('请求各应用自己的 Agent 列表，路径同样只有一个 /api', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, ['bank']))
    vi.stubGlobal('fetch', fetchMock)

    expect(await probeAppRunning('/bank', 'bank')).toBe(true)

    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    expect(url).toBe('/bank/api/chat/agents')
  })

  it('Agent 列表里没有该 Agent 时判定为未在线', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, ['knowledge'])))

    expect(await probeAppRunning('/bank', 'bank')).toBe(false)
  })

  it('对方服务没起（请求抛错）时判定为未在线，且不抛给调用方', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))

    expect(await probeAppRunning('/interview', 'interview')).toBe(false)
  })

  it('探测失败不触发登录过期处理（对方没起不等于我掉线）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { message: '未登录' })))
    auth.token = 'tk-1'

    expect(await probeAppRunning('/bank', 'bank')).toBe(false)
    expect(auth.token).toBe('tk-1')
  })
})
