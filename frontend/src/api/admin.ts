/** 银行后台管理端 API 封装（经 Vite 代理 /bank/api → 8081；写操作需 ADMIN 角色） */

import { authHeaders, handleUnauthorized } from './auth'

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface OverviewStats {
  totalBalance: number
  accountCount: number
  personalCount: number
  corporateCount: number
  customerCount: number
  todayTxnCount: number
  todayTxnAmount: number
  yesterdayTxnCount: number
  yesterdayTxnAmount: number
}

export interface AccountView {
  accountNo: string
  owner: string
  type: 'personal' | 'corporate'
  balance: number
  txnCount: number
}

export interface TxnView {
  id: number
  accountNo: string
  owner: string
  amount: number
  description: string
  createdAt: string
}

export interface OrderView {
  id: number
  confirmId: string
  memoryId: string
  fromAccount: string
  toAccount: string
  amount: number
  reason: string | null
  status: 'PENDING' | 'EXECUTED' | 'CANCELLED' | 'REJECTED'
  createdAt: string
}

export interface AuditLogView {
  id: number
  memoryId: string
  toolName: string
  detail: string
  result: 'SUCCESS' | 'DENY' | 'FAIL' | 'ALLOW'
  /** 对应后端日志里的 [traceId]；历史数据与后台直调为空 */
  traceId: string | null
  createdAt: string
}

export interface BalancePoint {
  accountNo: string
  owner: string
  balance: number
}

export interface DailyFlowPoint {
  date: string
  income: number
  expense: number
}

// ---------- 客户 360 ----------

export interface AccountBrief {
  owner: string
  accountNo: string
  balance: number
}

export interface CustomerView {
  owner: string
  accountCount: number
  totalBalance: number
  type: 'personal' | 'corporate' | 'mixed'
  accounts: AccountBrief[]
}

export interface CustomerProfile {
  owner: string
  type: 'personal' | 'corporate'
  accounts: AccountBrief[]
  totalBalance: number
  txnCount: number
  txnIncome: number
  txnExpense: number
  orderCount: number
  orderExecuted: number
  orderAmount: number
  auditCount: number
  recentTxns: TxnView[]
  recentOrders: OrderView[]
  recentAudits: AuditLogView[]
}

// ---------- 审批中心 ----------

export interface ApprovalBoard {
  pending: number
  executedToday: number
  cancelled: number
  rejected: number
  pendingOrders: OrderView[]
}

export interface JourneyNode {
  time: string
  type: string
  text: string
  result: string
}

export interface OrderJourney {
  order: OrderView
  timeline: JourneyNode[]
}

// ---------- 交易限额 ----------

export interface LimitPackage {
  identityId: string
  displayName: string
  role: string
  boundAccount: string
  canTransfer: boolean
  maxSingle: string
  maxDaily: string
}

/** 管理端审批操作结果 */
export interface AdminDecision {
  id: number
  status: string
  message: string
}

/** 统一请求：附带登录 token；401 回登录页；非 2xx 抛后端 message */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const resp = await fetch(`/bank/api/admin${path}`, {
    ...init,
    headers: { ...authHeaders(), ...(init.headers as Record<string, string> | undefined) },
  })
  if (resp.status === 401) {
    handleUnauthorized()
    throw new Error('登录已过期，请重新登录')
  }
  if (!resp.ok) {
    const data = await resp.json().catch(() => null) as { message?: string } | null
    throw new Error(data?.message || `请求失败: HTTP ${resp.status}`)
  }
  return await resp.json() as T
}

async function get<T>(path: string, params: Record<string, string | number | undefined> = {}): Promise<T> {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') qs.set(k, String(v))
  }
  const q = qs.toString()
  return await request<T>(`${path}${q ? `?${q}` : ''}`)
}

/** 带可选 JSON 体的写请求（POST/PATCH/PUT） */
async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  return await request<T>(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
}

async function post<T>(path: string): Promise<T> {
  return await send<T>('POST', path)
}

// ---------- 平台用户管理 ----------

export interface UserAdminView {
  id: number
  username: string
  displayName: string
  platformRole: 'ADMIN' | 'USER'
  /** 银行助手身份（BankIdentity.id）：zhangsan / staff001 / corp001 */
  identityId: string
  enabled: boolean
  createdAt: string
}

export interface CreateUserPayload {
  username: string
  password: string
  displayName: string
  platformRole: 'ADMIN' | 'USER'
  identityId: string
}

export interface AdminMessage {
  message: string
}

export const adminApi = {
  overview: () => get<OverviewStats>('/overview'),
  accounts: (keyword: string, type: string, page: number, size: number) =>
    get<PageResult<AccountView>>('/accounts', { keyword, type, page, size }),
  accountTransactions: (accountNo: string, page: number, size: number) =>
    get<PageResult<TxnView>>(`/accounts/${encodeURIComponent(accountNo)}/transactions`, { page, size }),
  transactions: (accountNo: string, direction: string, page: number, size: number) =>
    get<PageResult<TxnView>>('/transactions', { accountNo, direction, page, size }),
  transferOrders: (status: string, page: number, size: number) =>
    get<PageResult<OrderView>>('/transfer-orders', { status, page, size }),
  auditLogs: (result: string, toolName: string, page: number, size: number) =>
    get<PageResult<AuditLogView>>('/audit-logs', { result, toolName, page, size }),
  balanceDistribution: () => get<BalancePoint[]>('/stats/balance-distribution'),
  dailyFlow: (days: number) => get<DailyFlowPoint[]>('/stats/daily-flow', { days }),
  // 客户 360
  customers: () => get<CustomerView[]>('/customers'),
  customerProfile: (owner: string) => get<CustomerProfile>(`/customers/${encodeURIComponent(owner)}/profile`),
  // 审批中心
  approvals: () => get<ApprovalBoard>('/approvals'),
  orderJourney: (id: number) => get<OrderJourney>(`/transfer-orders/${id}/journey`),
  decideOrder: (id: number, approve: boolean) =>
    post<AdminDecision>(`/transfer-orders/${id}/decision?approve=${approve}`),
  // 交易限额
  limits: () => get<LimitPackage[]>('/limits'),
  // 平台用户管理（管理员开通制）
  users: () => get<UserAdminView[]>('/users'),
  createUser: (payload: CreateUserPayload) => send<AdminMessage>('POST', '/users', payload),
  setUserStatus: (id: number, enabled: boolean) => send<AdminMessage>('PATCH', `/users/${id}/status`, { enabled }),
  resetUserPassword: (id: number, password: string) => send<AdminMessage>('PUT', `/users/${id}/password`, { password }),
}
