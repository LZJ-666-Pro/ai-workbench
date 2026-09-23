<template>
  <div class="admin-layout">
    <el-container class="admin-container">
      <el-aside width="210px" class="admin-aside">
        <div class="admin-brand">🏦 智汇银行 · 管理后台</div>
        <el-menu :default-active="$route.fullPath" router background-color="#0e2a4d" text-color="#a8bcd4" active-text-color="#ffffff">
          <el-menu-item-group title="概览">
            <el-menu-item index="/admin/dashboard">
              <el-icon><Odometer /></el-icon><span>仪表盘</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="客户与账户">
            <el-menu-item index="/admin/customer-360">
              <el-icon><User /></el-icon><span>客户管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/customers">
              <el-icon><Wallet /></el-icon><span>账户管理</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="业务运营">
            <el-menu-item index="/admin/funds">
              <el-icon><Money /></el-icon><span>资金管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/approvals">
              <el-icon><DocumentChecked /></el-icon><span>审批中心</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="风控与合规">
            <el-menu-item index="/admin/limits">
              <el-icon><CreditCard /></el-icon><span>交易限额</span>
            </el-menu-item>
            <el-menu-item index="/admin/config">
              <el-icon><Management /></el-icon><span>用户与角色</span>
            </el-menu-item>
            <el-menu-item index="/admin/config?tab=security">
              <el-icon><Lock /></el-icon><span>安全认证</span>
            </el-menu-item>
            <el-menu-item index="/admin/config?tab=system">
              <el-icon><Setting /></el-icon><span>系统参数</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="监控与审计">
            <el-menu-item index="/admin/audit">
              <el-icon><Document /></el-icon><span>审计日志</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="客户经理平台">
            <el-menu-item index="/admin/marketing">
              <el-icon><TrendCharts /></el-icon><span>营销管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/visit">
              <el-icon><MapLocation /></el-icon><span>走访管理</span>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </el-aside>
      <el-main class="admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import {
  Odometer, User, Wallet, Money, DocumentChecked, CreditCard,
  Management, Lock, Setting, Document, TrendCharts, MapLocation,
} from '@element-plus/icons-vue'
</script>

<style scoped>
/* 作用域内覆盖 Element Plus 主色为企业蓝，并收敛卡片圆角，不影响聊天页 */
.admin-layout {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;
  --el-card-border-radius: 4px;
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

.admin-aside :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
}

.admin-aside :deep(.el-menu-item .el-icon) {
  color: inherit;
  margin-right: 8px;
}

.admin-main {
  padding: 16px 20px;
  overflow-y: auto;
  /* 财务数字等宽对齐 */
  font-variant-numeric: tabular-nums;
}
</style>
