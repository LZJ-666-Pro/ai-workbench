/**
 * 平台工作台 API 封装（经 Vite 代理 /bank/api → 8081）。
 *
 * 两个接口权限不同：
 *   - workbench 任何登录用户可读（首页门户，只有聚合数字）
 *   - platform-logs 挂在 /api/admin 前缀下，拦截器统一要求 ADMIN
 */
import { authHeaders, handleUnauthorized } from './auth'

const PREFIX = '/bank/api'

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const resp = await fetch(`${PREFIX}${path}`, {
    ...init,
    headers: { ...authHeaders(), ...(init.headers as Record<string, string> | undefined) },
  })
  if (resp.status === 401) {
    handleUnauthorized()
    throw new Error('登录已过期，请重新登录')
  }
  if (!resp.ok) {
    const data = (await resp.json().catch(() => null)) as { message?: string } | null
    throw new Error(data?.message || `请求失败: HTTP ${resp.status}`)
  }
  return (await resp.json()) as T
}

// ---------- 首页 ----------

export interface HeroCaps {
  models: number
  /** 近 7 天对话耗时 P95；无样本时为 null，页面显示 — */
  sseP95Ms: number | null
  memorySegments: number
  tools: number
}

export interface AppMetric {
  label: string
  value: string
}

export interface AppCard {
  key: string
  name: string
  type: string
  tone: 'blue' | 'violet' | 'green'
  status: 'running' | 'ready' | 'offline'
  statusText: string
  metrics: AppMetric[]
  to: string
}

export interface PlatformStats {
  eventsToday: number
  avgLatencyMs: number | null
  successRate: number
  endpoints: number
  updatedAt: string
}

export interface PlatformNotification {
  level: 'info' | 'warn'
  text: string
  to: string | null
}

export interface Workbench {
  hero: HeroCaps
  apps: AppCard[]
  platform: PlatformStats
  notifications: PlatformNotification[]
  /** 是否 ADMIN：通知中心只对管理员返回内容 */
  admin: boolean
}

export function loadWorkbench(): Promise<Workbench> {
  return request<Workbench>('/api/platform/workbench')
}

/**
 * 探测某个应用是否在线：问它自己的 Agent 列表接口。
 *
 * 为什么不能只信后端返回的 status：工作台接口跑在 app-bank 里，它的 AgentRegistry
 * 只登记了银行助手；知识库与面试模拟器是各自独立的进程，从 app-bank 看过去永远是"未注册"。
 * 而首页三个卡片却都要显示状态——只靠本进程的注册表得出的状态必然是错的。
 * 真正请求一次才知道对方在不在，这也正是「状态」二字该有的含义。
 *
 * 刻意不走 request()：探测失败只代表对方没起，不该触发登录过期处理。
 */
export async function probeAppRunning(basePath: string, agent: string): Promise<boolean> {
  try {
    const resp = await fetch(`${basePath}/api/chat/agents`, { headers: authHeaders() })
    if (!resp.ok) return false
    const names = (await resp.json()) as unknown
    return Array.isArray(names) && names.includes(agent)
  } catch {
    return false
  }
}

// ---------- 平台运行日志 ----------

export interface LogRow {
  id: number
  time: string
  app: string
  category: string
  action: string
  actor: string | null
  memoryId: string | null
  detail: string
  result: 'SUCCESS' | 'DENY' | 'FAIL'
  durationMs: number | null
  tokens: number | null
  traceId: string | null
}

/** 汇总不随分页变化，否则统计条会随翻页跳动，读起来像 bug */
export interface LogSummary {
  total: number
  success: number
  deny: number
  fail: number
}

export interface LogPage {
  list: LogRow[]
  total: number
  page: number
  size: number
  summary: LogSummary
}

export interface LogQuery {
  app?: string
  category?: string
  result?: string
  keyword?: string
  page?: number
  size?: number
}

export function loadPlatformLogs(query: LogQuery): Promise<LogPage> {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== '' && v !== null) qs.set(k, String(v))
  }
  return request<LogPage>(`/api/admin/platform-logs?${qs.toString()}`)
}

/** 应用标识 → 展示名与配色（日志表里只存 key） */
export const APP_LABELS: Record<string, { name: string; tone: string }> = {
  bank: { name: '银行助手', tone: 'blue' },
  knowledge: { name: '个人知识库', tone: 'violet' },
  interview: { name: '面试模拟器', tone: 'green' },
  platform: { name: '平台', tone: 'grey' },
}

export const CATEGORY_LABELS: Record<string, string> = {
  chat: '对话',
  tool: '工具调用',
  auth: '登录',
  guard: '护栏',
  search: '知识检索',
  interview: '面试',
}
