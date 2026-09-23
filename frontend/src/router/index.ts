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
        { path: 'customers', name: 'admin-customers', component: () => import('../views/admin/AdminCustomers.vue') },
        { path: 'funds', name: 'admin-funds', component: () => import('../views/admin/AdminFunds.vue') },
        { path: 'audit', name: 'admin-audit', component: () => import('../views/admin/AdminAudit.vue') },
      ],
    },
  ],
})

export default router
