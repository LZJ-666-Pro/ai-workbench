/**
 * 个人知识库管理端 API 封装（/knowledge/api/kb/admin，app-knowledge 8082 提供）。
 *
 * 与 admin.ts（银行后台）同款封装模式：统一前缀、401 清退登录、非 2xx 透出 {"message"}。
 * 平台空间候选列表沿用 KbController 的 /kb/spaces（前端走 /knowledge/api/kb 代理）。
 */
import { authHeaders, handleUnauthorized } from './auth'
import type { KbSpace } from './kb'

// 管理端接口挂 /admin 子前缀；平台空间候选走 KbController 的 /kb/spaces（同源不同子前缀）
const PREFIX = '/knowledge/api/kb'
const ADMIN = '/admin'

/** 统一请求：附带登录 token；401 回登录页；非 2xx 透出 {"message"} */
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
    // 后端拒绝统一是 {"message": "..."}（400 非平台空间 / 403/404 归属限制同形），透出原文比 HTTP 码有用
    const data = (await resp.json().catch(() => null)) as { message?: string } | null
    throw new Error(data?.message || `请求失败: HTTP ${resp.status}`)
  }
  return (await resp.json()) as T
}

async function get<T>(path: string, params: Record<string, string | number | undefined> = {}): Promise<T> {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') qs.set(k, String(v))
  }
  const q = qs.toString()
  return await request<T>(`${path}${q ? `?${q}` : ''}`)
}

/** 带可选 JSON 体的写请求（POST/PUT） */
async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  return await request<T>(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
}

// ---------- 类型 ----------

/** 总览统计：卡片数字 + 近 7 日检索趋势 */
export interface KbOverview {
  personalSpaces: number
  readySpaces: number
  totalDocs: number
  failedDocs: number
  totalSizeMb: number
  totalWords: number
  totalChunks: number
  trend: KbTrendPoint[]
}

/** 趋势点：date 为 "MM-DD" */
export interface KbTrendPoint {
  date: string
  count: number
}

/** 用户库治理列表行：一个个人空间一行 */
export interface KbLibraryRow {
  spaceId: number
  owner: string
  displayName: string
  /** 空间内部名（与 displayName 的展示名区分） */
  name: string
  docCount: number
  chunkTotal: number
  sizeKb: number
  createdAt: string
}

export interface KbLibraryPage {
  total: number
  items: KbLibraryRow[]
}

/** 管理端下钻的文档行（个人库专属；非个人库接口 404） */
export interface KbAdminDoc {
  id: number
  title: string
  docType: string
  sizeKb: number
  words: number
  chunks: number
  status: 'READY' | 'PARSING' | 'FAILED'
  enabled: boolean
  /** 非空表示文档来自网页抓取，值即来源链接 */
  sourceUrl: string | null
  createdAt: string
}

/** 绑定关系：知识库应用把某个个人空间接入对话检索 */
export interface KbBindingRow {
  spaceId: number
  spaceName: string
  docCount: number
  createdAt: string
}

/** 检索审计行 */
export interface KbAuditRow {
  id: number
  createdAt: string
  username: string
  action: string
  detail: string
  result: string
}

export interface KbAuditPage {
  total: number
  items: KbAuditRow[]
}

// ---------- 接口 ----------

export const kbAdminApi = {
  overview: () => get<KbOverview>(`${ADMIN}/overview`),
  libraries: (page: number, size: number, keyword = '') =>
    get<KbLibraryPage>(`${ADMIN}/libraries`, { page, size, keyword }),
  /** 下钻某个人库的文档；非个人库 404（message 透出） */
  libraryDocuments: (spaceId: number | string) => get<KbAdminDoc[]>(`${ADMIN}/libraries/${spaceId}/documents`),
  setDocEnabled: (docId: number, enabled: boolean) =>
    send<{ ok: true }>('PUT', `${ADMIN}/documents/${docId}/enabled`, { enabled }),
  deleteDoc: (docId: number) => request<{ ok: true; message: string }>(`${ADMIN}/documents/${docId}`, { method: 'DELETE' }),
  bindings: () => get<KbBindingRow[]>(`${ADMIN}/bindings`),
  /** 绑定平台空间；非平台空间 400（message 透出） */
  bind: (spaceId: number) => send<{ ok: true }>('POST', `${ADMIN}/bindings`, { spaceId }),
  unbind: (spaceId: number) => request<{ ok: true }>(`${ADMIN}/bindings/${spaceId}`, { method: 'DELETE' }),
  audit: (page: number, size: number) => get<KbAuditPage>(`${ADMIN}/audit`, { page, size }),
}

/** 平台空间候选列表（KbController /kb/spaces，返回平台空间数组；仅取选择器需要的字段） */
export function loadPlatformSpaces(): Promise<Array<Pick<KbSpace, 'id' | 'name'>>> {
  return request<Array<Pick<KbSpace, 'id' | 'name'>>>('/spaces')
}
