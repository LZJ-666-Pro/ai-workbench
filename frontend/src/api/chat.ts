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

export interface HistoryMsg {
  role: 'user' | 'assistant'
  content: string
}

/** 前端渲染用的消息 */
export interface Msg {
  role: 'user' | 'assistant'
  kind: 'text' | 'card'
  content: string
  card?: ConfirmRequestData
}

export interface SessionRecord {
  memoryId: string
  updatedAt: string
}

/** 会话列表接口 */
export interface SessionManager {
  loadSessions(basePath: string, agent: string): Promise<SessionRecord[]>
  loadHistory(basePath: string, agent: string, memoryId: string): Promise<HistoryMsg[]>
  switchSession(agent: string, memoryId: string): void
  newSession(agent: string): string
}

/** 前端会话管理实现：基于 localStorage + API */
export const sessionManager: SessionManager = {
  async loadSessions(basePath: string, agent: string): Promise<SessionRecord[]> {
    const sessionsKey = `aiwb-sessions-${agent}`
    const stored = localStorage.getItem(sessionsKey)
    if (stored) {
      return JSON.parse(stored) as SessionRecord[]
    }
    // 如果本地没有，从后端拉取
    const apiSessions = await listSessions(basePath, agent)
    if (apiSessions.length > 0) {
      localStorage.setItem(sessionsKey, JSON.stringify(apiSessions))
    }
    return apiSessions
  },

  async loadHistory(basePath: string, agent: string, memoryId: string): Promise<HistoryMsg[]> {
    return loadHistoryMessages(basePath, agent, memoryId)
  },

  switchSession(agent: string, memoryId: string): void {
    const sessionsKey = `aiwb-sessions-${agent}`
    const stored = localStorage.getItem(sessionsKey)
    const sessions = stored ? JSON.parse(stored) as SessionRecord[] : []
    const idx = sessions.findIndex(s => s.memoryId === memoryId)
    if (idx >= 0) {
      // 把选中的会话移到最前
      const selected = sessions.splice(idx, 1)[0]
      sessions.unshift(selected)
      localStorage.setItem(sessionsKey, JSON.stringify(sessions))
    }
    localStorage.setItem(`aiwb-current-${agent}`, memoryId)
  },

  newSession(agent: string): string {
    const newId = newMemoryId(agent)
    localStorage.setItem(`aiwb-current-${agent}`, newId)
    return newId
  },
}

/** 从数据库加载指定会话的历史消息 */
export async function loadHistoryMessages(
  basePath: string,
  agent: string,
  memoryId: string,
): Promise<HistoryMsg[]> {
  try {
    const resp = await fetch(`${basePath}/api/memory/${agent}/${encodeURIComponent(memoryId)}`)
    if (!resp.ok) {
      console.warn(`加载历史消息失败: HTTP ${resp.status}`)
      return []
    }
    return (await resp.json()) as HistoryMsg[]
  } catch (e) {
    console.error('加载历史消息异常:', e)
    return []
  }
}

/** 从数据库获取指定 Agent 的会话列表 */
export async function listSessions(basePath: string, agent: string): Promise<SessionRecord[]> {
  try {
    const resp = await fetch(`${basePath}/api/sessions/${agent}`)
    if (!resp.ok) {
      console.warn(`加载会话列表失败: HTTP ${resp.status}`)
      return []
    }
    return await resp.json()
  } catch (e) {
    console.error('加载会话列表异常:', e)
    return []
  }
}

/** 获取当前会话 ID；没有则新建（会话按 Agent 隔离，刷新页面不丢） */
export function getMemoryId(agent: string): string {
  const key = `aiwb-current-${agent}`
  let id = localStorage.getItem(key)
  if (!id) {
    id = newMemoryId(agent)
  }
  return id
}

/** 新开一个会话（后端按 memoryId 隔离上下文） */
export function newMemoryId(agent: string): string {
  const id = crypto.randomUUID()
  localStorage.setItem(`aiwb-current-${agent}`, id)
  return id
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

/** SSE 流式对话客户端：fetch + ReadableStream 手动解析 text/event-stream 帧。
 *
 * 后端事件格式（platform-core ChatStreamController）：
 *   data:{"type":"delta","content":"token"}                  增量内容
 *   data:{"type":"done","totalTokens":123}                   结束
 *   data:{"type":"error","content":"..."}                    出错
 *   data:{"type":"confirm_request","confirmId":"...",...}    转账确认卡片（app-bank）
 */

export async function streamChat(opts: {
  basePath: string
  agent: string
  memoryId: string
  message: string
  signal?: AbortSignal
  onDelta: (text: string) => void
  onDone?: (totalTokens: number) => void
  onError?: (message: string) => void
  onConfirmRequest?: (card: ConfirmRequestData) => void
}): Promise<void> {
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
