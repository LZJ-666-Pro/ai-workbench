<template>
  <div>
    <div class="page-head">
      <h2>系统配置中心</h2>
      <span class="page-sub">用户与角色 · 安全认证 · 系统参数 —— 静态演示配置，全部只读</span>
    </div>

    <el-tabs v-model="activeTab" class="config-tabs">
      <!-- ============ 用户与角色 ============ -->
      <el-tab-pane label="🔑 用户与角色" name="users">
        <el-card shadow="never" class="block">
          <template #header><b>系统角色定义</b></template>
          <el-table :data="roles" size="small" stripe>
            <el-table-column prop="name" label="角色" width="130" />
            <el-table-column prop="desc" label="职责说明" min-width="260" />
            <el-table-column prop="bound" label="当前绑定对象" min-width="200" />
            <el-table-column label="资金权限" width="160">
              <template #default="{ row }">
                <el-tag :type="row.tagType" size="small">{{ row.fund }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header><b>角色 × 功能权限矩阵</b>（演示口径）</template>
          <el-table :data="matrix" size="small" stripe>
            <el-table-column prop="feature" label="功能" min-width="200" />
            <el-table-column label="操作员" align="center" width="110">
              <template #default="{ row }"><span :class="row.operator ? 'ok' : 'no'">{{ row.operator ? '✓' : '✗' }}</span></template>
            </el-table-column>
            <el-table-column label="授权员" align="center" width="110">
              <template #default="{ row }"><span :class="row.authorizer ? 'ok' : 'no'">{{ row.authorizer ? '✓' : '✗' }}</span></template>
            </el-table-column>
            <el-table-column label="内部查询员" align="center" width="110">
              <template #default="{ row }"><span :class="row.staff ? 'ok' : 'no'">{{ row.staff ? '✓' : '✗' }}</span></template>
            </el-table-column>
          </el-table>
          <div class="tip">
            真实实现中角色差异由 BankIdentity 枚举固化：RETAIL / CORPORATE 可发起转账（受限额约束），
            STAFF 在 TransferService 中被强制拒绝资金操作；「授权员」对应 HITL 转账确认卡片，
            由人类点击完成最终授权，模型无权直接动账。
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ============ 安全认证 ============ -->
      <el-tab-pane label="🔒 安全认证" name="security">
        <el-row :gutter="14">
          <el-col :span="12">
            <el-card shadow="never" class="block">
              <template #header><b>双因素认证（2FA）</b></template>
              <el-descriptions :column="1" size="small" border>
                <el-descriptions-item label="短信验证码">
                  <el-tag size="small" type="info">规划中</el-tag>
                  高风险交易发送动态口令
                </el-descriptions-item>
                <el-descriptions-item label="UKey 数字证书">
                  <el-tag size="small" type="info">规划中</el-tag>
                  对公大额交易硬件签名
                </el-descriptions-item>
                <el-descriptions-item label="安全问题">
                  <el-tag size="small" type="info">规划中</el-tag>
                  辅助身份核验
                </el-descriptions-item>
                <el-descriptions-item label="HITL 转账确认卡片">
                  <el-tag size="small" type="success">已启用</el-tag>
                  资金变动唯一入口：人类点击确认，确认单 10 分钟内有效，超时自动失效
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card shadow="never" class="block">
              <template #header><b>密码策略</b>（演示值）</template>
              <el-descriptions :column="1" size="small" border>
                <el-descriptions-item label="密码最小长度">8 位</el-descriptions-item>
                <el-descriptions-item label="复杂度要求">必须同时包含字母与数字</el-descriptions-item>
                <el-descriptions-item label="密码有效期">90 天，到期强制修改</el-descriptions-item>
                <el-descriptions-item label="错误锁定">连续 5 次输入错误锁定 30 分钟</el-descriptions-item>
                <el-descriptions-item label="密码重置">仅银行管理员可重置为初始密码</el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="never" class="block">
          <template #header><b>密钥与会话安全</b>（当前真实实现）</template>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="会话标识 memoryId">每会话随机 UUID，编码服务对象身份（bank:{identity}:{uuid}）</el-descriptions-item>
            <el-descriptions-item label="确认单编号 confirmId">每笔随机 UUID，不可枚举猜测</el-descriptions-item>
            <el-descriptions-item label="数据库凭证">application.yml 本地配置，不入代码仓库</el-descriptions-item>
            <el-descriptions-item label="传输加密">开发环境 http + Vite 代理；生产环境应启用 HTTPS</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-tab-pane>

      <!-- ============ 系统参数 ============ -->
      <el-tab-pane label="⚙ 系统参数" name="system">
        <el-row :gutter="14">
          <el-col :span="12">
            <el-card shadow="never" class="block">
              <template #header><b>交易工作窗口</b>（演示值）</template>
              <el-descriptions :column="1" size="small" border>
                <el-descriptions-item label="企业网银渠道">07:00 – 23:00 全年无休</el-descriptions-item>
                <el-descriptions-item label="大额转账窗口">工作日 09:00 – 17:00（大额支付系统）</el-descriptions-item>
                <el-descriptions-item label="对公业务窗口">周一至周五 09:00 – 17:00</el-descriptions-item>
                <el-descriptions-item label="屏蔽窗口">每日 23:00 – 次日 07:00（日终批处理）</el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card shadow="never" class="block">
              <template #header><b>交易屏蔽名单</b>（当前真实生效）</template>
              <el-table :data="blacklist" size="small" stripe>
                <el-table-column prop="account" label="账号" width="130">
                  <template #default="{ row }"><span class="mono">{{ row.account }}</span></template>
                </el-table-column>
                <el-table-column prop="owner" label="户名" width="100" />
                <el-table-column label="状态" width="100">
                  <template #default><el-tag size="small" type="danger">已生效</el-tag></template>
                </el-table-column>
                <el-table-column prop="source" label="来源" min-width="220" />
              </el-table>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="never" class="block">
          <template #header><b>产品映射</b>（金融产品 → 企业网银渠道）</template>
          <el-table :data="products" size="small" stripe>
            <el-table-column prop="name" label="产品" min-width="260" />
            <el-table-column prop="channel" label="映射渠道" min-width="180" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.tag">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div class="tip">
            正式版中产品映射应为配置表：后台勾选产品后即时控制网银渠道展示与可办理状态，无需改代码。
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

type TagType = 'success' | 'primary' | 'info' | 'danger' | 'warning'

const route = useRoute()
const router = useRouter()

const TABS = ['users', 'security', 'system']
const activeTab = ref(typeof route.query.tab === 'string' && TABS.includes(route.query.tab) ? route.query.tab : 'users')

// 页内切换 tab 时同步到地址栏，菜单高亮随之联动
watch(activeTab, (t) => router.replace({ query: { tab: t } }))
watch(() => route.query.tab, (t) => {
  if (typeof t === 'string' && TABS.includes(t)) activeTab.value = t
})

const roles: { name: string; desc: string; bound: string; fund: string; tagType: TagType }[] = [
  { name: '操作员', desc: '企业日常交易操作员：查询余额流水、通过 AI 助手发起转账', bound: '零售客户 张三 / 对公客户 星辰科技', fund: '发起转账（受限额约束）', tagType: 'success' },
  { name: '授权员', desc: '高风险交易二次确认：对 AI 发起的转账执行最终授权（HITL）', bound: '转账确认卡片（人类点击）', fund: '确认 / 驳回', tagType: 'primary' },
  { name: '代发工资专员', desc: '批量代发工资业务，按月批量出账', bound: '未启用', fund: '规划中', tagType: 'info' },
  { name: '内部查询员', desc: '银行内部员工：只读查询，不接触客户资金', bound: '员工 小陈', fund: '无（代码强制）', tagType: 'danger' },
]

const matrix = [
  { feature: '余额 / 流水查询', operator: true, authorizer: true, staff: true },
  { feature: 'AI 转账发起', operator: true, authorizer: false, staff: false },
  { feature: '转账确认 / 驳回', operator: false, authorizer: true, staff: false },
  { feature: '限额与黑名单拦截', operator: true, authorizer: true, staff: true },
  { feature: '管理后台（只读）', operator: false, authorizer: true, staff: true },
]

const blacklist = [
  { account: '62220004', owner: '赵六', source: '风控黑名单 TransferRiskRules.BLACKLIST，命中直接拒绝' },
]

const products: { name: string; channel: string; status: string; tag: TagType }[] = [
  { name: '活期存款（余额查询 / 交易明细）', channel: '企业网银 · AI 助手', status: '已上线', tag: 'success' },
  { name: '转账汇款（单笔，HITL 确认）', channel: '企业网银 · AI 助手', status: '已上线', tag: 'success' },
  { name: '定期存款', channel: '企业网银', status: '演示', tag: 'info' },
  { name: '贷款与授信', channel: '企业网银', status: '规划中', tag: 'info' },
  { name: '代发工资', channel: '企业网银', status: '规划中', tag: 'info' },
  { name: '票据 / 国内信用证', channel: '企业网银', status: '规划中', tag: 'info' },
]
</script>

<style scoped>
.page-head { margin-bottom: 14px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; color: #12263f; }
.page-sub { font-size: 12px; color: #8a97a8; }
.block { margin-bottom: 14px; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
.tip { margin-top: 10px; font-size: 12px; color: #8a97a8; line-height: 1.7; }
.ok { color: #0a8f3c; font-weight: 700; }
.no { color: #c0c6cc; }
.config-tabs :deep(.el-tabs__item) { font-size: 14px; }
</style>
