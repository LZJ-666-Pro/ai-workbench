/**
 * 个人知识库 API 封装（/knowledge/api，登录即可用，app-knowledge 提供）。
 *
 * 个人库不需要建空间：每个登录用户固定拥有一个空间（后端按当前登录人归属），
 * 文档来自本地上传或网页抓取（sourceUrl 非空即抓取来源）。
 * 上传走 multipart（FormData，不能带 JSON 的 Content-Type，让浏览器自动生成 boundary）。
 */
import { authHeaders, handleUnauthorized } from './auth'

const PREFIX = '/knowledge/api'

/** 与 kb.ts 的 request 同款：自动带 Bearer 头；401 清退登录；非 2xx 透出后端 message */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const isForm = init.body instanceof FormData
  const resp = await fetch(`${PREFIX}${path}`, {
    ...init,
    headers: {
      // FormData 时不能手工指定 Content-Type，否则浏览器无法补 multipart boundary
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
      ...authHeaders(),
      ...(init.headers as Record<string, string> | undefined),
    },
  })
  if (resp.status === 401) {
    handleUnauthorized()
    throw new Error('登录已过期，请重新登录')
  }
  if (!resp.ok) {
    // 后端拒绝统一是 {"message": "..."}（400 抓取失败 / 403 归属拒绝同形），透出原文比 HTTP 码有用
    const data = (await resp.json().catch(() => null)) as { message?: string } | null
    throw new Error(data?.message || `请求失败: HTTP ${resp.status}`)
  }
  return (await resp.json()) as T
}

// ---------- 类型 ----------

export type PersonalDocStatus = 'READY' | 'PARSING' | 'FAILED'

export interface PersonalSpace {
  id: number
  name: string
  docCount: number
  chunkTotal: number
}

export interface PersonalDoc {
  id: number
  title: string
  docType: string
  sizeKb: number
  words: number
  chunks: number
  status: PersonalDocStatus
  enabled: boolean
  /** 非空表示文档来自网页抓取，值即来源链接（前端标题可点击回源） */
  sourceUrl: string | null
  createdAt: string
}

/** 上传结果：逐文件分组，部分失败不算整次失败 */
export interface UploadOutcome {
  ok: boolean
  created: { id: number; title: string; status: PersonalDocStatus }[]
  failed: { title: string; message: string }[]
}

/** 网页抓取结果：文档先以 PARSING 落库，解析完成后可检索 */
export interface UrlImportOutcome {
  ok: boolean
  document: Pick<PersonalDoc, 'id' | 'title' | 'status'>
}

// ---------- 上传约束（契约：单次 ≤ 10 个、单文件 ≤ 20MB，前端先挡一道，后端仍会校验） ----------

export const MAX_FILES_PER_UPLOAD = 10
export const MAX_FILE_SIZE_MB = 20

function validateUpload(files: File[]): void {
  if (files.length > MAX_FILES_PER_UPLOAD) {
    throw new Error(`单次最多上传 ${MAX_FILES_PER_UPLOAD} 个文件`)
  }
  const oversize = files.find(f => f.size > MAX_FILE_SIZE_MB * 1024 * 1024)
  if (oversize) {
    throw new Error(`「${oversize.name}」超过 ${MAX_FILE_SIZE_MB}MB 单文件上限`)
  }
}

// ---------- 接口 ----------

/** 当前用户的个人空间概况（库名 / 文档数 / 切片数） */
export function loadPersonalSpace(): Promise<PersonalSpace> {
  return request('/kb/personal/me')
}

export function loadPersonalDocs(): Promise<PersonalDoc[]> {
  return request('/kb/personal/me/documents')
}

/** 上传文档：multipart，字段名固定 files；文件数/大小超限在本地直接拒绝，不发请求 */
export async function uploadPersonalDocs(files: File[]): Promise<UploadOutcome> {
  validateUpload(files)
  const form = new FormData()
  for (const file of files) {
    form.append('files', file)
  }
  return request('/kb/personal/me/documents', { method: 'POST', body: form })
}

/** 抓取网页为文档；后端拒绝（URL 非法 / 归属不允许）以 400/403 {"message"} 返回，由 request 透出 */
export function importPersonalDocFromUrl(url: string): Promise<UrlImportOutcome> {
  return request('/kb/personal/me/documents/url', {
    method: 'POST',
    body: JSON.stringify({ url }),
  })
}

export function deletePersonalDoc(docId: number): Promise<{ ok: boolean; message: string }> {
  return request(`/kb/personal/me/documents/${docId}`, { method: 'DELETE' })
}

// ---------- 展示字典（与 kb.ts 的 DOC_STATUS_META 同款：个人库状态集合一致） ----------

export const DOC_STATUS_META: Record<PersonalDocStatus, { label: string; cls: string }> = {
  READY: { label: '已解析', cls: 'ok' },
  PARSING: { label: '解析中', cls: 'building' },
  FAILED: { label: '解析失败', cls: 'error' },
}
