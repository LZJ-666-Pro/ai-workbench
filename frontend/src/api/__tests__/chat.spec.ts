import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getMemoryId, newMemoryId, streamChat } from '../chat'

/**
 * 用最小假 Response 代替 fetch 的返回值：只实现被测代码真正用到的字段。
 * 不依赖 jsdom 是否提供 ReadableStream / Response——手写 reader 更稳，
 * 测试也就不会因为环境差异而假失败。
 */
function fakeStreamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder()
  const queue = chunks.map(c => encoder.encode(c))
  let index = 0
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: async () =>
          index < queue.length
            ? { done: false, value: queue[index++] }
            : { done: true, value: undefined },
      }),
    },
  } as unknown as Response
}

/** 非 2xx：后端拒绝统一是 {"message": "..."} */
function fakeErrorResponse(status: number, body: unknown): Response {
  return {
    ok: false,
    status,
    json: async () => {
      if (body instanceof Error) throw body
      return body
    },
  } as unknown as Response
}

describe('memoryId 与身份绑定', () => {
  beforeEach(() => localStorage.clear())

  it('带身份时编码为 agent:identity:uuid', () => {
    const id = newMemoryId('bank', 'zhangsan')

    expect(id.split(':')).toHaveLength(3)
    expect(id.startsWith('bank:zhangsan:')).toBe(true)
  })

  it('不带身份时退化为 agent:uuid（知识库 / 面试等无身份应用）', () => {
    const id = newMemoryId('knowledge')

    expect(id.split(':')).toHaveLength(2)
    expect(id.startsWith('knowledge:')).toBe(true)
  })

  it('切换身份后不复用上一个身份的会话', () => {
    // 身份段是后端权限校验的依据：若复用，用户会拿着 corp001 的身份
    // 继续读写 zhangsan 的会话，正是拦截器要拦的越权
    const retail = getMemoryId('bank', 'zhangsan')
    const corporate = getMemoryId('bank', 'corp001')

    expect(corporate).not.toBe(retail)
    expect(corporate.startsWith('bank:corp001:')).toBe(true)
  })

  it('同一身份下刷新页面沿用当前会话', () => {
    const first = getMemoryId('bank', 'zhangsan')

    expect(getMemoryId('bank', 'zhangsan')).toBe(first)
  })
})

describe('streamChat 的 SSE 帧解析', () => {
  it('按事件类型分发 delta / done / confirm_request', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fakeStreamResponse([
      'data:{"type":"delta","content":"您"}\n\n',
      'data:{"type":"delta","content":"好"}\n\n',
      'data:{"type":"confirm_request","confirmId":"c-1","fromAccount":"62220001",' +
        '"toAccount":"62220002","toOwner":"李四","amount":200,"reason":"货款"}\n\n',
      'data:{"type":"done","totalTokens":42}\n\n',
    ])))

    const deltas: string[] = []
    let tokens = -1
    const cards: unknown[] = []
    await streamChat({
      basePath: '',
      agent: 'bank',
      memoryId: 'bank:zhangsan:s1',
      message: '给李四转 200 元',
      onDelta: t => deltas.push(t),
      onDone: t => { tokens = t },
      onConfirmRequest: c => cards.push(c),
    })

    expect(deltas.join('')).toBe('您好')
    expect(tokens).toBe(42)
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({ confirmId: 'c-1', amount: 200, toOwner: '李四' })
  })

  it('一帧被网络切成两个 chunk 时仍能正确拼装', async () => {
    // 真实 SSE 的分帧与 TCP 分片无关，帧被截断是常态；
    // 拼接逻辑写错会表现为「偶尔丢字」，且只在慢网络下复现——必须测
    vi.stubGlobal('fetch', vi.fn(async () => fakeStreamResponse([
      'data:{"type":"del',
      'ta","content":"余额"}\n\ndata:{"type":"done","totalTokens":3}\n\n',
    ])))

    const deltas: string[] = []
    let tokens = -1
    await streamChat({
      basePath: '',
      agent: 'bank',
      memoryId: 'bank:zhangsan:s1',
      message: '查余额',
      onDelta: t => deltas.push(t),
      onDone: t => { tokens = t },
    })

    expect(deltas.join('')).toBe('余额')
    expect(tokens).toBe(3)
  })

  it('error 事件把 traceId 一并交给调用方（用户可凭编号报障）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fakeStreamResponse([
      'data:{"type":"error","content":"模型调用失败","traceId":"abc123"}\n\n',
    ])))

    let message = ''
    let traceId: string | undefined
    await streamChat({
      basePath: '',
      agent: 'bank',
      memoryId: 'bank:zhangsan:s1',
      message: 'hi',
      onDelta: () => {},
      onError: (m, t) => { message = m; traceId = t },
    })

    expect(message).toBe('模型调用失败')
    expect(traceId).toBe('abc123')
  })

  it('跳过无法解析的帧，不让一帧坏数据中断整条流', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fakeStreamResponse([
      'data:{这不是合法 JSON\n\n',
      'data:{"type":"delta","content":"继续"}\n\n',
    ])))

    const deltas: string[] = []
    await streamChat({
      basePath: '',
      agent: 'bank',
      memoryId: 'bank:zhangsan:s1',
      message: 'hi',
      onDelta: t => deltas.push(t),
    })

    expect(deltas.join('')).toBe('继续')
  })
})

describe('streamChat 的失败提示', () => {
  it('被护栏限流（429）时透出后端原因，而不是只显示 HTTP 429', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fakeErrorResponse(429, {
      message: '提问过于频繁（每个会话每分钟最多 20 次），请稍后再试。',
      reason: 'rate_limit',
    })))

    await expect(streamChat({
      basePath: '',
      agent: 'bank',
      memoryId: 'bank:zhangsan:s1',
      message: 'hi',
      onDelta: () => {},
    })).rejects.toThrow('提问过于频繁')
  })

  it('错误体不是 JSON 时回退到带状态码的文案', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fakeErrorResponse(502, new Error('not json'))))

    await expect(streamChat({
      basePath: '',
      agent: 'bank',
      memoryId: 'bank:zhangsan:s1',
      message: 'hi',
      onDelta: () => {},
    })).rejects.toThrow('HTTP 502')
  })
})
