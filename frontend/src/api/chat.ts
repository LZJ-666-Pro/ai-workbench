/**
 * SSE 流式对话客户端：fetch + ReadableStream 手动解析 text/event-stream 帧。
 *
 * 后端事件格式（platform-core ChatStreamController）：
 *   data:{"type":"delta","content":"token"}                  增量内容
 *   data:{"type":"done","totalTokens":123}                   结束
 *   data:{"type":"error","content":"..."}                    出错
 *   data:{"type":"confirm_request","confirmId":"...",...}    转账确认卡片（app-bank）
 */

export interface ChatEvent {
  type: 'delta' | 'done' | 'error' | 'confirm_request'
  content?: string
  totalTokens?: number
  confirmId?: string
  fromAccount?: string
  toAccount?: string
  toOwner?: string
  amount?: number
  reason?: string
}

export interface ConfirmRequestData {
  confirmId: string
  fromAccount: string
  toAccount: string
  toOwner: string
  amount: number
  reason: string
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
  onConfirmRequest?: (card: ConfirmRequestData) => void
  /** 历史消息（对话开始前传入，会被追加到当前消息列表） */
  initialHistory?: Msg[]
}

const memoryKey = (agent: string) => `aiwb-memory-${agent}`

/** 从数据库加载历史消息 */
export async function loadHistoryMessages(
  basePath: string,
  agent: string,
  memoryId: string,
): Promise<Msg[]> {
  try {
    const resp = await fetch(`${basePath}/api/memory/${agent}/${encodeURIComponent(memoryId)}`)
    if (!resp.ok) {
      console.warn(`加载历史消息失败: HTTP ${resp.status}`)
      return []
    }
    const msgs: Msg[] = await resp.json()
    return msgs
  } catch (e) {
    console.error('加载历史消息异常:', e)
    return []
  }
}

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

  // 先追加历史消息
  if (opts.initialHistory && opts.initialHistory.length > 0) {
    opts.initialHistory.forEach(msg => {
      messages.value.push(msg)
    })
    scrollBottom()
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
      } else if (evt.type === 'confirm_request' && evt.confirmId) {
        opts.onConfirmRequest?.({
          confirmId: evt.confirmId,
          fromAccount: evt.fromAccount ?? '',
          toAccount: evt.toAccount ?? '',
          toOwner: evt.toOwner ?? '',
          amount: evt.amount ?? 0,
          reason: evt.reason ?? '',
        })
      }
    }
  }
}

/** 确认/取消转账卡片。幂等性由后端状态机保证：重复提交只会得到"已处理过"提示 */
export async function respondTransferConfirm(
  basePath: string,
  memoryId: string,
  confirmId: string,
  action: 'confirm' | 'cancel',
): Promise<{ ok: boolean; message: string }> {
  const resp = await fetch(`${basePath}/api/transfer/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ memoryId, confirmId, action }),
  })
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status}`)
  }
  return await resp.json() as { ok: boolean; message: string }
}
