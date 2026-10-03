<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auth, logout, roleLabel } from '../api/auth'

const router = useRouter()

/** 银行身份展示名（与后端 BankIdentity 枚举对应） */
const IDENTITY_LABELS: Record<string, string> = {
  zhangsan: '零售客户',
  staff001: '内部员工',
  corp001: '对公客户',
}

const profile = [
  { label: '用户名', value: auth.user?.username ?? '—' },
  { label: '姓名', value: auth.user?.displayName ?? '—' },
  { label: '平台角色', value: roleLabel(auth.user?.platformRole) },
  { label: '银行身份', value: IDENTITY_LABELS[auth.user?.identityId ?? ''] ?? auth.user?.identityId ?? '—' },
]

const about = [
  { label: '平台版本', value: 'v1.0.0' },
  { label: '运行环境', value: '生产环境' },
  { label: '数据存储', value: '全量加密存储，操作留痕审计' },
]

async function confirmLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  logout()
  router.push('/login')
}

function copyName() {
  ElMessage.info('账号信息由企业管理员统一维护')
}
</script>

<template>
  <section class="settings-page">
    <div class="page-inner">
      <div class="section-head">
        <h2 class="section-title">设置</h2>
        <p class="section-sub">查看账号与平台信息。</p>
      </div>

      <div class="set-grid">
        <!-- 个人资料 -->
        <div class="panel">
          <div class="panel-title">个人资料</div>
          <div class="profile-top">
            <span class="avatar">{{ auth.user?.displayName?.charAt(0) ?? '?' }}</span>
            <div>
              <div class="p-name">{{ auth.user?.displayName ?? '—' }}</div>
              <div class="p-sub">{{ roleLabel(auth.user?.platformRole) }} · {{ IDENTITY_LABELS[auth.user?.identityId ?? ''] ?? '' }}</div>
            </div>
          </div>
          <div v-for="p in profile" :key="p.label" class="kv">
            <span class="k">{{ p.label }}</span>
            <span class="v">{{ p.value }}</span>
          </div>
          <el-button class="ghost-btn" @click="copyName">账号信息由管理员维护</el-button>
        </div>

        <div class="col">
          <!-- 安全 -->
          <div class="panel">
            <div class="panel-title">安全</div>
            <div class="kv">
              <span class="k">登录密码</span>
              <span class="v">企业内部系统不开放自助改密，请联系管理员在「用户管理」中重置</span>
            </div>
            <div class="kv">
              <span class="k">会话安全</span>
              <span class="v">登录凭证有时效，过期自动退出</span>
            </div>
            <div class="kv">
              <span class="k">操作审计</span>
              <span class="v">助手对话与资金操作全程留痕</span>
            </div>
          </div>

          <!-- 关于平台 -->
          <div class="panel">
            <div class="panel-title">关于平台</div>
            <div v-for="a in about" :key="a.label" class="kv">
              <span class="k">{{ a.label }}</span>
              <span class="v">{{ a.value }}</span>
            </div>
            <el-button class="danger-btn" @click="confirmLogout">退出登录</el-button>
            <div class="copyright">© 2026 智汇工作台 v1.0.0 · 京ICP备20260088号</div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.settings-page {
  background: #f5f7fa;
  min-height: calc(100vh - 56px);
}
.page-inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 22px 24px 30px;
}
.section-head { margin-bottom: 16px; }
.section-title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: #1f2d3d;
}
.section-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8a97a8;
}

.set-grid {
  display: grid;
  grid-template-columns: 2fr 3fr;
  gap: 14px;
  align-items: start;
}
.col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.panel {
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  padding: 18px 20px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.panel-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
  margin-bottom: 12px;
}

.profile-top {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid #f2f4f7;
  margin-bottom: 6px;
}
.avatar {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2f7bff, #0b4f9e);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.p-name {
  font-size: 16px;
  font-weight: 700;
  color: #1f2d3d;
}
.p-sub {
  font-size: 12.5px;
  color: #98a2b0;
  margin-top: 2px;
}

.kv {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 9px 0;
  border-bottom: 1px solid #f6f8fa;
  font-size: 13px;
}
.kv:last-of-type { border-bottom: 0; }
.k {
  width: 72px;
  flex-shrink: 0;
  color: #98a2b0;
}
.v {
  color: #1f2d3d;
  line-height: 1.6;
}

.ghost-btn {
  width: 100%;
  margin-top: 12px;
  border-style: dashed;
  color: #98a2b0;
}
.danger-btn {
  width: 100%;
  margin-top: 14px;
  color: #d03050;
  border-color: #efbcc7;
}
.danger-btn:hover {
  background: #fdf0f2;
  border-color: #d03050;
  color: #d03050;
}
.copyright {
  margin-top: 12px;
  text-align: center;
  font-size: 11.5px;
  color: #b6bcc6;
}

@media (max-width: 900px) {
  .set-grid { grid-template-columns: 1fr; }
}
</style>
