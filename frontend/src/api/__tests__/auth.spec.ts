import { beforeEach, describe, expect, it, vi } from 'vitest'
import { auth, authHeaders, login, logout, roleLabel, type AuthUser } from '../auth'

const ADMIN_USER: AuthUser = {
  username: 'zhangsan',
  displayName: '张三',
  platformRole: 'ADMIN',
  identityId: 'zhangsan',
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response
}

describe('登录态', () => {
  beforeEach(() => {
    localStorage.clear()
    logout()
  })

  it('roleLabel 映射已知角色，未知角色回退到「用户」', () => {
    expect(roleLabel('ADMIN')).toBe('总行管理员')
    expect(roleLabel('USER')).toBe('普通用户')
    expect(roleLabel(null)).toBe('用户')
    expect(roleLabel(undefined)).toBe('用户')
    expect(roleLabel('SOMETHING_NEW')).toBe('用户')
  })

  it('未登录时不附带 Authorization 头（登录页自身可用）', () => {
    expect(authHeaders()).toEqual({})
  })

  it('登录成功后写入响应式状态与 localStorage，并带出 Bearer 头', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, { token: 'tk-1', user: ADMIN_USER })))

    const user = await login('zhangsan', 'Demo@2026')

    expect(user.platformRole).toBe('ADMIN')
    expect(auth.token).toBe('tk-1')
    expect(auth.user?.identityId).toBe('zhangsan')
    expect(authHeaders()).toEqual({ Authorization: 'Bearer tk-1' })
    // 刷新页面要能恢复登录态，落盘是必须的
    expect(localStorage.getItem('aiwb-token')).toBe('tk-1')
    expect(localStorage.getItem('aiwb-user')).toContain('zhangsan')
  })

  it('登录失败时抛出后端返回的 message（而不是「登录失败」这类无信息文案）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { message: '用户名或密码错误' })))

    await expect(login('zhangsan', 'wrong')).rejects.toThrow('用户名或密码错误')
    expect(auth.token).toBe('')
  })

  it('响应缺 token 时按失败处理，不写入半截登录态', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, { user: ADMIN_USER })))

    await expect(login('zhangsan', 'Demo@2026')).rejects.toThrow('登录失败')
    expect(auth.token).toBe('')
    expect(auth.user).toBeNull()
  })

  it('logout 清空内存状态与本地缓存', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, { token: 'tk-1', user: ADMIN_USER })))
    await login('zhangsan', 'Demo@2026')

    logout()

    expect(auth.token).toBe('')
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('aiwb-token')).toBeNull()
    expect(localStorage.getItem('aiwb-user')).toBeNull()
    expect(authHeaders()).toEqual({})
  })
})
