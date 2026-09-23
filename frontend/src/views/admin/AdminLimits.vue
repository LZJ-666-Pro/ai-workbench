<template>
  <div>
    <div class="page-head">
      <h2>交易限额管理</h2>
      <span class="page-sub">限额按服务对象身份打包（Limit Package），代码层风控规则强制执行</span>
    </div>

    <el-card shadow="never" class="block">
      <template #header><b>限额包 · 当前生效配置</b></template>
      <el-table :data="limits" size="small" stripe>
        <el-table-column prop="displayName" label="服务对象" min-width="180">
          <template #default="{ row }">
            {{ row.displayName }}
            <el-tag v-if="!row.canTransfer" size="small" type="info" style="margin-left: 6px">只读</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="role" label="角色" min-width="140" />
        <el-table-column prop="boundAccount" label="绑定账户" width="110" />
        <el-table-column prop="maxSingle" label="单笔限额" align="right" width="130">
          <template #default="{ row }"><span class="mono">{{ row.maxSingle }}</span></template>
        </el-table-column>
        <el-table-column prop="maxDaily" label="单日累计限额" align="right" width="150">
          <template #default="{ row }"><span class="mono">{{ row.maxDaily }}</span></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row :gutter="14">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>风控硬规则</b>（TransferRiskRules，确定性代码执行）</template>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="收款黑名单">62220004（赵六）命中直接拒绝</el-descriptions-item>
            <el-descriptions-item label="金额精度">最多两位小数，必须大于 0</el-descriptions-item>
            <el-descriptions-item label="本人账户">禁止向本人账户转账</el-descriptions-item>
            <el-descriptions-item label="余额校验">乐观扣款 balance ≥ amount，并发下不扣成负数</el-descriptions-item>
            <el-descriptions-item label="双段校验">创建确认单预检 + 确认执行时复检（防止卡片展示期间规则变化）</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>日累计口径与新收款人冷却期</b></template>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="日累计统计口径">仅统计已执行（EXECUTED）的转账订单，待确认/取消不计入</el-descriptions-item>
            <el-descriptions-item label="统计窗口">自然日（当日 00:00 起）</el-descriptions-item>
            <el-descriptions-item label="新收款人冷却期">
              <el-tag size="small" type="info">规划中</el-tag>
              新增收款人后 2 小时内禁止转账（演示版暂未启用）
            </el-descriptions-item>
            <el-descriptions-item label="限额调整">当前为代码常量，正式版应做成限额包配置表按客户/角色关联</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, type LimitPackage } from '../../api/admin'

const limits = ref<LimitPackage[]>([])

onMounted(async () => {
  limits.value = await adminApi.limits()
})
</script>

<style scoped>
.page-head { margin-bottom: 14px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; color: #12263f; }
.page-sub { font-size: 12px; color: #8a97a8; }
.block { margin-bottom: 14px; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
</style>
