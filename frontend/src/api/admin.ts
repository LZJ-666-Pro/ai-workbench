/** 银行后台管理端 API 封装（全部只读 GET，经 Vite 代理 /bank/api → 8081） */

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

async function get<T>(path: string, params: Record<string, string | number | undefined> = {}): Promise<T> {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') qs.set(k, String(v))
  }
  const q = qs.toString()
  const resp = await fetch(`/bank/api/admin${path}${q ? `?${q}` : ''}`)
  if (!resp.ok) {
    throw new Error(`请求失败: HTTP ${resp.status}`)
  }
  return await resp.json() as T
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
  // 交易限额
  limits: () => get<LimitPackage[]>('/limits'),
}
