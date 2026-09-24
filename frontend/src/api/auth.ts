import { reactive } from 'vue'

/**
 * 平台登录态：不引 Pinia，用 reactive 单例 + localStorage 持久化。
 * token 附带到所有 /xxx/api 请求；401 时清登录态并回登录页。
 */
export interface AuthUser {
  username: string
  displayName: string
  /** ADMIN（可进管理后台）/ USER */
  platformRole: 'ADMIN' | 'USER'
  /** 银行助手身份（BankIdentity.id），登录后用它生成 memoryId，会话按身份隔离 */
  identityId: string
}

const TOKEN_KEY = 'aiwb-token'
const USER_KEY = 'aiwb-user'

/** 平台角色 → 展示名（顶栏胶囊、首页问候语用） */
export const ROLE_LABELS: Record<string, string> = {
  ADMIN: '总行管理员',
  USER: '普通用户',
}

export const auth = reactive<{
  token: string
  user: AuthUser | null
}>({
  token: localStorage.getItem(TOKEN_KEY) || '',
  user: JSON.parse(localStorage.getItem(USER_KEY) || 'null'),
})

export function roleLabel(role?: string | null): string {
  return (role && ROLE_LABELS[role]) || '用户'
}

/** 带认证的请求头；未登录返回空对象（登录页自身） */
export function authHeaders(): Record<string, string> {
  return auth.token ? { Authorization: `Bearer ${auth.token}` } : {}
}

/** 401 统一处理：清登录态并跳登录页（登录页自身不跳） */
export function handleUnauthorized() {
  logout()
  if (location.pathname !== '/login') {
    location.href = '/login'
  }
}

export async function login(username: string, password: string): Promise<AuthUser> {
  const resp = await fetch('/bank/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const data = await resp.json().catch(() => null) as
    | { token?: string; user?: AuthUser; message?: string }
    | null
  if (!resp.ok || !data?.token || !data.user) {
    throw new Error(data?.message || `登录失败（HTTP ${resp.status}）`)
  }
  auth.token = data.token
  auth.user = data.user
  localStorage.setItem(TOKEN_KEY, data.token)
  localStorage.setItem(USER_KEY, JSON.stringify(data.user))
  return data.user
}

export function logout() {
  auth.token = ''
  auth.user = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/** 刷新页面后用 token 换最新用户信息：token 失效清退，后端不在线沿用本地缓存 */
export async function refreshMe(): Promise<void> {
  if (!auth.token) return
  try {
    const resp = await fetch('/bank/api/auth/me', { headers: authHeaders() })
    if (resp.status === 401) {
      handleUnauthorized()
      return
    }
    if (resp.ok) {
      auth.user = await resp.json() as AuthUser
      localStorage.setItem(USER_KEY, JSON.stringify(auth.user))
    }
  } catch { /* 后端不在线：沿用本地缓存登录态，由各接口的 401 处理兜底 */ }
}
