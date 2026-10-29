<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { InfoFilled, Lock, Key, Clock, ArrowRight } from '@element-plus/icons-vue'
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

/** 安全策略行：图标锚点 + 标题 + 说明（替代原 kv 长文案挤压感） */
const securities = [
  { icon: Lock, title: '登录密码', desc: '企业内部系统不开放自助改密，请联系管理员在「用户管理」中重置' },
  { icon: Key, title: '会话安全', desc: '登录凭证有时效，过期自动退出' },
  { icon: Clock, title: '操作审计', desc: '助手对话与资金操作全程留痕' },
]

const about = [
  { label: '平台版本', value: 'v1.0.0', dot: false },
  { label: '运行环境', value: '生产环境', dot: true },
  { label: '数据存储', value: '全量加密存储，操作留痕审计', dot: false },
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
</script>

<template>
  <section class="settings-page">
    <div class="page-inner">
      <!-- 页头：面包屑 + 大标题（对齐甲方原型） -->
      <div class="section-head">
        <div class="crumb"><span>账户中心</span><el-icon class="crumb-sep"><ArrowRight /></el-icon></div>
        <h2 class="section-title">设置</h2>
        <p class="section-sub">查看账号资料、安全策略与平台信息。</p>
      </div>

      <div class="set-grid">
        <!-- 个人资料 -->
        <div class="panel">
          <div class="panel-head">
            <div class="panel-head-main">
              <span class="panel-title">个人资料</span>
              <span class="panel-desc">企业统一目录中的身份信息</span>
            </div>
            <span class="head-tag">管理员维护</span>
          </div>
          <div class="profile-top">
            <span class="avatar">{{ auth.user?.displayName?.charAt(0) ?? '?' }}</span>
            <div class="p-id">
              <div class="p-name">{{ auth.user?.displayName ?? '—' }}</div>
              <div class="p-tags">
                <span class="p-tag primary">{{ roleLabel(auth.user?.platformRole) }}</span>
                <span class="p-tag">{{ IDENTITY_LABELS[auth.user?.identityId ?? ''] ?? '' }}</span>
              </div>
            </div>
          </div>
          <div v-for="p in profile" :key="p.label" class="kv">
            <span class="k">{{ p.label }}</span>
            <span class="v">{{ p.value }}</span>
          </div>
          <div class="maintain-note">
            <el-icon><InfoFilled /></el-icon>
            账号信息由企业管理员统一维护，如需变更请联系管理员。
          </div>
        </div>

        <div class="col">
          <!-- 安全 -->
          <div class="panel">
            <div class="panel-head">
              <div class="panel-head-main">
                <span class="panel-title">安全</span>
                <span class="panel-desc">平台级安全策略，全局生效</span>
              </div>
            </div>
            <div v-for="s in securities" :key="s.title" class="sec-row">
              <span class="sec-icon"><el-icon><component :is="s.icon" /></el-icon></span>
              <div class="sec-main">
                <div class="sec-title">{{ s.title }}</div>
                <div class="sec-desc">{{ s.desc }}</div>
              </div>
              <el-icon class="sec-go"><ArrowRight /></el-icon>
            </div>
          </div>

          <!-- 关于平台 -->
          <div class="panel">
            <div class="panel-head">
              <div class="panel-head-main">
                <span class="panel-title">关于平台</span>
                <span class="panel-desc">版本与环境信息</span>
              </div>
            </div>
            <div v-for="a in about" :key="a.label" class="kv">
              <span class="k">{{ a.label }}</span>
              <!-- 运行环境按原型渲染为绿点 + 文字 -->
              <span class="v">
                <template v-if="a.dot"><i class="env-dot" />{{ a.value }}</template>
                <template v-else>{{ a.value }}</template>
              </span>
            </div>
            <div class="panel-foot">
              <el-button class="danger-btn" @click="confirmLogout">退出登录</el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="copyright">© 2026 智汇工作台 v1.0.0 · 京ICP备20260088号</div>
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
/* 页头：面包屑「账户中心 ›」+ 大标题（对齐原型） */
.section-head { margin-bottom: 22px; }
.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #8a97a8;
  margin-bottom: 10px;
}
.crumb-sep { font-size: 12px; color: #c0c8d2; }
.section-title {
  margin: 0;
  font-size: 30px;
  font-weight: 800;
  color: #1f2d3d;
  letter-spacing: 1px;
}
.section-sub {
  margin: 8px 0 0;
  font-size: 13.5px;
  color: #8a97a8;
}

.set-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  align-items: start;
}
.col {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.panel {
  background: #fff;
  border: 1px solid #e8ecf1;
  border-radius: 14px;
  padding: 20px 22px;
  box-shadow: 0 2px 8px rgba(31, 45, 61, 0.04);
}
/* 卡片头：标题 + 副题 + 右侧标签，底部细分隔线 */
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid #f2f4f7;
}
.panel-head-main { display: flex; flex-direction: column; min-width: 0; }
.panel-title {
  font-size: 16px;
  font-weight: 700;
  color: #1f2d3d;
}
.panel-desc {
  font-size: 12.5px;
  color: #98a2b0;
  margin-top: 3px;
}
/* 原型中的蓝色描边标签 */
.head-tag {
  font-size: 12px;
  font-weight: 600;
  color: #0b4f9e;
  background: #fff;
  border: 1px solid #9fc0e4;
  border-radius: 6px;
  padding: 4px 12px;
  flex-shrink: 0;
  white-space: nowrap;
}

/* 头像区：大头像 + 姓名 + 角色/身份双标签 */
.profile-top {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 6px 0 16px;
}
.avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: #1f6fd6;
  color: #fff;
  font-size: 28px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.p-id { min-width: 0; }
.p-name {
  font-size: 22px;
  font-weight: 800;
  color: #1f2d3d;
}
.p-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.p-tag {
  font-size: 12px;
  font-weight: 600;
  color: #4a5a6d;
  background: #f5f6f8;
  border: 1px solid #e5e8ec;
  border-radius: 5px;
  padding: 3px 10px;
  white-space: nowrap;
}
.p-tag.primary {
  color: #0b4f9e;
  background: #eaf2fb;
  border-color: #c3daf0;
}

/* 字段行：标签灰、值加粗深色，浅分隔线 */
.kv {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 13px 0;
  border-bottom: 1px solid #f2f4f7;
  font-size: 13.5px;
}
.kv:last-of-type { border-bottom: 0; }
.k {
  width: 96px;
  flex-shrink: 0;
  color: #8a97a8;
}
.v {
  color: #1f2d3d;
  font-weight: 600;
  line-height: 1.6;
}
.env-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #0a8f3c;
  margin-right: 8px;
  vertical-align: 1px;
}

/* 「管理员维护」说明：浅蓝信息条（对齐原型） */
.maintain-note {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding: 12px 14px;
  background: #eaf2fb;
  border: 1px solid #d2e2f2;
  border-radius: 10px;
  font-size: 12.5px;
  color: #44607e;
}
.maintain-note .el-icon { color: #0b4f9e; flex-shrink: 0; font-size: 15px; }

/* 安全策略行：圆形图标锚点 + 标题/说明 + 行尾箭头 */
.sec-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 0;
  border-bottom: 1px solid #f2f4f7;
}
.sec-row:last-of-type { border-bottom: 0; padding-bottom: 4px; }
.sec-icon {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: #e3eefb;
  color: #1f6fd6;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 18px;
}
.sec-main { min-width: 0; flex: 1; }
.sec-title {
  font-size: 14px;
  font-weight: 700;
  color: #1f2d3d;
}
.sec-desc {
  font-size: 12.5px;
  color: #98a2b0;
  line-height: 1.6;
  margin-top: 3px;
}
.sec-go { color: #c0c8d2; font-size: 14px; flex-shrink: 0; }

/* 退出登录：红描边按钮右对齐 */
.panel-foot {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}
.danger-btn {
  color: #d03050;
  border-color: #efbcc7;
}
.danger-btn:hover {
  background: #fdf0f2;
  border-color: #d03050;
  color: #d03050;
}

/* 版权：页面级页脚居中 */
.copyright {
  margin-top: 24px;
  text-align: center;
  font-size: 12px;
  color: #b6bcc6;
}

@media (max-width: 900px) {
  .set-grid { grid-template-columns: 1fr; }
}
</style>
