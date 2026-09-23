import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/bank', name: 'bank', component: () => import('../views/BankView.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
    { path: '/interview', name: 'interview', component: () => import('../views/InterviewView.vue') },
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
      ],
    },
  ],
})

export default router
