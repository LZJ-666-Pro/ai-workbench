import { createRouter, createWebHistory } from 'vue-router'
import { auth, refreshMe } from '../api/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/bank', name: 'bank', component: () => import('../views/BankView.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
    { path: '/interview', name: 'interview', component: () => import('../views/InterviewView.vue') },
    { path: '/developers', name: 'developers', component: () => import('../views/DevelopersView.vue') },
    {
      path: '/admin',
      component: () => import('../views/admin/AdminLayout.vue'),
      redirect: '/admin/dashboard',
      children: [
        { path: 'dashboard', name: 'admin-dashboard', component: () => import('../views/admin/AdminDashboard.vue') },
        { path: 'customer-360', name: 'admin-customer-360', component: () => import('../views/admin/AdminCustomer360.vue') },
        { path: 'customers', name: 'admin-customers', component: () => import('../views/admin/AdminCustomers.vue') },
        { path: 'funds', name: 'admin-funds', component: () => import('../views/admin/AdminFunds.vue') },
        { path: 'approvals', name: 'admin-approvals', component: () => import('../views/admin/AdminApprovals.vue') },
        { path: 'limits', name: 'admin-limits', component: () => import('../views/admin/AdminLimits.vue') },
        { path: 'config', name: 'admin-config', component: () => import('../views/admin/AdminConfig.vue') },
        { path: 'audit', name: 'admin-audit', component: () => import('../views/admin/AdminAudit.vue') },
        { path: 'marketing', name: 'admin-marketing', component: () => import('../views/admin/AdminMarketing.vue') },
        { path: 'visit', name: 'admin-visit', component: () => import('../views/admin/AdminVisit.vue') },
      ],
    },
  ],
})

/**
 * 全局路由守卫：
 *   - 未登录一律去 /login（登录页与公开页除外），并记住原目标
 *   - /admin/** 仅 ADMIN 角色可进
 * 首次导航时顺带用 token 换一次最新用户信息（token 失效会被清退到登录页）
 */
let meChecked = false

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return auth.token ? '/' : true
  }
  if (!auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!meChecked) {
    meChecked = true
    await refreshMe()
    if (!auth.token) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  if (to.path.startsWith('/admin') && auth.user?.platformRole !== 'ADMIN') {
    return '/'
  }
  return true
})

export default router
