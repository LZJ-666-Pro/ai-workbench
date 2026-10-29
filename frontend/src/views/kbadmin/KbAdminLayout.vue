<template>
  <div class="kb-admin-layout">
    <el-container class="kb-admin-container">
      <el-aside width="210px" class="kb-admin-aside">
        <div class="kb-admin-brand">📚 个人知识库 · 管理后台</div>
        <el-menu :default-active="$route.fullPath" router background-color="#0e2a4d" text-color="#a8bcd4" active-text-color="#ffffff">
          <el-menu-item-group title="概览">
            <el-menu-item index="/kb-admin/dashboard">
              <el-icon><Odometer /></el-icon><span>总览</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="库与文档">
            <el-menu-item index="/kb-admin/libraries">
              <el-icon><FolderOpened /></el-icon><span>用户库治理</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="应用集成">
            <el-menu-item index="/kb-admin/bindings">
              <el-icon><Link /></el-icon><span>绑定管理</span>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group title="监控与审计">
            <el-menu-item index="/kb-admin/audit">
              <el-icon><Document /></el-icon><span>检索审计</span>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
        <!-- 返回个人知识库应用：后台与聊天应用分离，入口对齐银行后台的返回习惯 -->
        <div class="kb-admin-back">
          <el-button link @click="$router.push('/knowledge')">
            <el-icon><Back /></el-icon><span>返回知识库应用</span>
          </el-button>
        </div>
      </el-aside>
      <el-main class="kb-admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { Odometer, FolderOpened, Link, Document, Back } from '@element-plus/icons-vue'
</script>

<style scoped>
/* 作用域内覆盖 Element Plus 主色为企业蓝，并收敛卡片圆角，与银行后台（AdminLayout）视觉一致 */
.kb-admin-layout {
  --el-color-primary: #0b4f9e;
  --el-color-primary-light-3: #3d7bc0;
  --el-color-primary-light-5: #79a5d4;
  --el-color-primary-light-7: #b5cde8;
  --el-color-primary-light-8: #d2e2f2;
  --el-color-primary-light-9: #e9f1f9;
  --el-color-primary-dark-2: #09417f;
  --el-card-border-radius: 4px;
}

.kb-admin-container {
  height: calc(100vh - 56px);
  background: #f5f7fa;
}

.kb-admin-aside {
  background: #0e2a4d;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.kb-admin-brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.5px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.kb-admin-aside :deep(.el-menu) {
  border-right: none;
  flex: 1;
}

.kb-admin-aside :deep(.el-menu-item-group__title) {
  padding: 12px 20px 4px;
  font-size: 11px;
  letter-spacing: 1px;
  color: #5f7d9c;
}

.kb-admin-aside :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
}

.kb-admin-aside :deep(.el-menu-item .el-icon) {
  color: inherit;
  margin-right: 8px;
}

/* 返回入口固定在侧栏底部 */
.kb-admin-back {
  padding: 14px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.kb-admin-back .el-button {
  color: #a8bcd4;
  font-size: 13px;
}

.kb-admin-back .el-button:hover {
  color: #fff;
}

.kb-admin-back .el-icon {
  margin-right: 6px;
}

.kb-admin-main {
  padding: 16px 20px;
  overflow-y: auto;
  /* 统计数字等宽对齐 */
  font-variant-numeric: tabular-nums;
}
</style>
