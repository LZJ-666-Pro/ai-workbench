/**
 * 平台知识库 API 封装（/api/kb，登录即可用）。
 *
 * 知识库 = 空间（kb_space）+ 文档（kb_document）+ 切片（kb_chunk）+ 绑定（kb_binding）。
 * 上传走 multipart（FormData，不能带 JSON 的 Content-Type，让浏览器自动生成 boundary）。
 */
import { authHeaders, handleUnauthorized } from './auth'

const PREFIX = '/bank/api/kb'

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const resp = await fetch(`${PREFIX}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...authHeaders(), ...(init.headers as Record<string, string> | undefined) },
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

// ---------- 类型 ----------

export type KbStatus = 'READY' | 'BUILDING' | 'FAILED'
export type DocStatus = 'READY' | 'PARSING' | 'FAILED'

export interface KbSpace {
  id: number
  name: string
  description: string | null
  status: KbStatus
  chunkSize: number
  chunkOverlap: number
  docCount: number
  chunkTotal: number
  owner: string
  createdAt: string
  updatedAt: string
  agents: string[]
}

export interface KbDoc {
  id: number
  title: string
  docType: string
  sizeKb: number
  words: number
  chunks: number
  status: DocStatus
  enabled: boolean
  errorMsg: string | null
  createdAt: string
}

export interface KbSpaceDetail {
  space: KbSpace
  documents: KbDoc[]
}

export interface KbSaveInput {
  name: string
  description?: string
  chunkSize?: number
  chunkOverlap?: number
  agents: string[]
}

export interface KbHit {
  docId: number
  docTitle: string
  seq: number
  content: string
  score: number
}

export interface KbSearchResult {
  question: string
  tookMs: number
  note: string
  hits: KbHit[]
}

// ---------- 接口 ----------

export function loadKbSpaces(): Promise<KbSpace[]> {
  return request<KbSpace[]>('/spaces')
}

export function loadKbSpace(id: number | string): Promise<KbSpaceDetail> {
  return request<KbSpaceDetail>(`/spaces/${id}`)
}

export function createKbSpace(input: KbSaveInput): Promise<{ id: number; message: string }> {
  return request('/spaces', { method: 'POST', body: JSON.stringify(input) })
}

export function updateKbSpace(id: number | string, input: KbSaveInput): Promise<{ message: string }> {
  return request(`/spaces/${id}`, { method: 'PUT', body: JSON.stringify(input) })
}

export function deleteKbSpace(id: number | string): Promise<{ message: string }> {
  return request(`/spaces/${id}`, { method: 'DELETE' })
}

/** 上传文档：multipart，不带 Content-Type 让浏览器补 boundary */
export function uploadKbDocuments(spaceId: number | string, files: File[]): Promise<{ message: string }> {
  const form = new FormData()
  for (const file of files) {
    form.append('files', file)
  }
  return request(`/spaces/${spaceId}/documents`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  })
}

export function updateKbDocEnabled(
  spaceId: number | string, docId: number, enabled: boolean,
): Promise<{ message: string }> {
  return request(`/spaces/${spaceId}/documents/${docId}`, {
    method: 'PUT',
    body: JSON.stringify({ enabled }),
  })
}

export function deleteKbDoc(spaceId: number | string, docId: number): Promise<{ message: string }> {
  return request(`/spaces/${spaceId}/documents/${docId}`, { method: 'DELETE' })
}

export function rebuildKbSpace(spaceId: number | string): Promise<{ message: string }> {
  return request(`/spaces/${spaceId}/rebuild`, { method: 'POST' })
}

export function searchKbSpace(spaceId: number | string, question: string, topN = 5): Promise<KbSearchResult> {
  return request(`/spaces/${spaceId}/search`, { method: 'POST', body: JSON.stringify({ question, topN }) })
}

// ---------- 展示字典 ----------

export const KB_STATUS_META: Record<KbStatus, { label: string; cls: string }> = {
  READY: { label: '就绪', cls: 'ok' },
  BUILDING: { label: '构建中', cls: 'building' },
  FAILED: { label: '失败', cls: 'error' },
}

export const DOC_STATUS_META: Record<DocStatus, { label: string; cls: string }> = {
  READY: { label: '已解析', cls: 'ok' },
  PARSING: { label: '解析中', cls: 'building' },
  FAILED: { label: '解析失败', cls: 'error' },
}
