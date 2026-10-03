/**
 * 数据源管理 API 封装（/api/datasources，登录即可用）。
 *
 * 数据源是平台级连接登记：列表全员可见，连通性由后端真实探活得出。
 * config 里的密码后端返回时已脱敏为 ******，编辑时密码留空 = 沿用原值。
 */
import { authHeaders, handleUnauthorized } from './auth'

const PREFIX = '/bank/api/datasources'

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

export type SourceType = 'DOCUMENT' | 'DATABASE' | 'API' | 'VECTOR'
export type SourceStatus = 'OK' | 'ERROR' | 'SYNCING' | 'UNTESTED'

export interface SourceBinding {
  agentKey: string
  scope: 'READ' | 'WRITE'
}

export interface DataSource {
  id: number
  name: string
  type: SourceType
  engine: string
  description: string | null
  config: Record<string, string | number>
  status: SourceStatus
  statusMsg: string | null
  lastSyncAt: string | null
  lastLatencyMs: number | null
  owner: string
  createdAt: string
  bindings: SourceBinding[]
}

export interface DataSourceInput {
  name: string
  type: SourceType
  engine: string
  description?: string
  config: Record<string, string | number>
  bindings: SourceBinding[]
}

export interface TestResult {
  ok: boolean
  latencyMs: number
  message: string
  status: SourceStatus
}

// ---------- 接口 ----------

export function loadDataSources(): Promise<DataSource[]> {
  return request<DataSource[]>('')
}

export function createDataSource(input: DataSourceInput): Promise<{ message: string }> {
  return request('', { method: 'POST', body: JSON.stringify(input) })
}

export function updateDataSource(id: number, input: DataSourceInput): Promise<{ message: string }> {
  return request(`/${id}`, { method: 'PUT', body: JSON.stringify(input) })
}

export function deleteDataSource(id: number): Promise<{ message: string }> {
  return request(`/${id}`, { method: 'DELETE' })
}

export function testDataSource(id: number): Promise<TestResult> {
  return request(`/${id}/test`, { method: 'POST' })
}

export function syncDataSource(id: number): Promise<{ message: string }> {
  return request(`/${id}/sync`, { method: 'POST' })
}

// ---------- 展示字典 ----------

export const TYPE_LABELS: Record<SourceType, string> = {
  DOCUMENT: '文档/文件',
  DATABASE: '数据库',
  API: 'API 接口',
  VECTOR: '向量库',
}

export const ENGINE_LABELS: Record<string, string> = {
  mysql: 'MySQL',
  postgresql: 'PostgreSQL',
  pgvector: 'pgvector',
  http: 'HTTP',
  oss: 'OSS',
  file: '本地文件',
}
