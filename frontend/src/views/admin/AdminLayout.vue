<template>
  <div class="admin-layout">
    <el-container class="admin-container">
      <el-aside width="210px" class="admin-aside">
        <div class="admin-brand">🏦 智汇银行 · 管理后台</div>
        <el-menu :default-active="$route.fullPath" router background-color="#0e2a4d" text-color="#a8bcd4" active-text-color="#ffffff">
          <el-menu-item-group title="概览">
            <el-menu-item index="/admin/dashboard">📊 仪表盘</el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="客户与账户">
            <el-menu-item index="/admin/customer-360">👤 客户管理</el-menu-item>
            <el-menu-item index="/admin/customers">🗂 账户管理</el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="业务运营">
            <el-menu-item index="/admin/funds">💰 资金管理</el-menu-item>
            <el-menu-item index="/admin/approvals">✅ 审批中心</el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="风控与配置">
            <el-menu-item index="/admin/limits">📏 交易限额</el-menu-item>
            <el-menu-item index="/admin/config">🔑 用户与角色</el-menu-item>
            <el-menu-item index="/admin/config?tab=security">🔒 安全认证</el-menu-item>
            <el-menu-item index="/admin/config?tab=system">⚙ 系统参数</el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="监控与审计">
            <el-menu-item index="/admin/audit">🛡 AI 审计日志</el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="客户经理平台">
            <el-menu-item index="/admin/marketing" disabled>📈 营销管理<span class="demo">演示</span></el-menu-item>
            <el-menu-item index="/admin/visit" disabled>🚶 走访管理<span class="demo">演示</span></el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </el-aside>
      <el-main class="admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </div>
</template>

<style scoped>
/* 作用域内覆盖 Element Plus 主色为企业蓝，不影响聊天页 */
.admin-layout {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;
}

.admin-container {
  height: calc(100vh - 56px);
  background: #f5f7fa;
}

.admin-aside {
  background: #0e2a4d;
  overflow-y: auto;
}

.admin-brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.5px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.admin-aside :deep(.el-menu) {
  border-right: none;
}

.admin-aside :deep(.el-menu-item-group__title) {
  padding: 12px 20px 4px;
  font-size: 11px;
  letter-spacing: 1px;
  color: #5f7d9c;
}

.admin-aside :deep(.el-menu-item.is-disabled) {
  opacity: 0.45;
}

.demo {
  margin-left: 6px;
  font-size: 10px;
  color: #5f7d9c;
  border: 1px solid #33517a;
  border-radius: 3px;
  padding: 0 4px;
}

.admin-main {
  padding: 16px 20px;
  overflow-y: auto;
}
</style>
