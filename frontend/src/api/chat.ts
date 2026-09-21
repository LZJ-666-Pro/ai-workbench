/**
 * SSE 流式对话客户端：fetch + ReadableStream 手动解析 text/event-stream 帧。
 *
 * 后端事件格式（platform-core ChatStreamController）：
 *   data:{"type":"delta","content":"token"}   增量内容
 *   data:{"type":"done","totalTokens":123}    结束
 *   data:{"type":"error","content":"..."}     出错
 */

export interface ChatEvent {
  type: 'delta' | 'done' | 'error'
  content?: string
  totalTokens?: number
}

export interface StreamChatOptions {
  /** 后端代理前缀：/bank | /knowledge | /interview */
  basePath: string
  /** Agent 标识（与后端 AgentSpec.name 一致） */
  agent: string
  memoryId: string
  message: string
  signal?: AbortSignal
  onDelta: (text: string) => void
  onDone?: (totalTokens: number) => void
  onError?: (message: string) => void
}

const memoryKey = (agent: string) => `aiwb-memory-${agent}`

/** 取当前会话 ID；没有则新建（会话按 Agent 隔离，刷新页面不丢） */
export function getMemoryId(agent: string): string {
  const key = memoryKey(agent)
  let id = localStorage.getItem(key)
  if (!id) {
    id = newMemoryId(agent)
  }
  return id
}

/** 新开一个会话（后端按 memoryId 隔离上下文） */
export function newMemoryId(agent: string): string {
  const id = crypto.randomUUID()
  localStorage.setItem(memoryKey(agent), id)
  return id
}

export async function streamChat(opts: StreamChatOptions): Promise<void> {
  const url = `${opts.basePath}/api/chat/${opts.agent}/stream`
    + `?memoryId=${encodeURIComponent(opts.memoryId)}`
    + `&message=${encodeURIComponent(opts.message)}`
  const resp = await fetch(url, { signal: opts.signal })
  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status}（请确认对应后端已启动）`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    const frames = buf.split('\n\n')
    buf = frames.pop() ?? ''
    for (const frame of frames) {
      const dataLine = frame.split('\n').find(l => l.startsWith('data:'))
      if (!dataLine) continue
      let evt: ChatEvent
      try {
        evt = JSON.parse(dataLine.slice(5).trim()) as ChatEvent
      } catch {
        continue
      }
      if (evt.type === 'delta') {
        opts.onDelta(evt.content ?? '')
      } else if (evt.type === 'done') {
        opts.onDone?.(evt.totalTokens ?? -1)
      } else if (evt.type === 'error') {
        opts.onError?.(evt.content ?? '模型调用失败')
      }
    }
  }
}
