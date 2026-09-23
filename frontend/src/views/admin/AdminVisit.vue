<template>
  <div>
    <div class="page-head">
      <h2>走访管理</h2>
      <span class="page-sub">客户经理上门/远程走访记录与客户标签数字化管理</span>
    </div>

    <div class="stat-grid">
      <div class="stat-card"><div class="stat-label">本月计划走访</div><div class="stat-value">10</div><div class="stat-sub">覆盖对公客户 8 家</div></div>
      <div class="stat-card"><div class="stat-label">已完成</div><div class="stat-value">7</div><div class="stat-sub">完成率 70%</div></div>
      <div class="stat-card"><div class="stat-label">上门走访</div><div class="stat-value">5</div><div class="stat-sub">远程（电话/视频）2 次</div></div>
      <div class="stat-card"><div class="stat-label">待回访</div><div class="stat-value">3</div><div class="stat-sub">本周内安排</div></div>
    </div>

    <el-card shadow="never">
      <template #header><b>走访记录</b></template>
      <el-table :data="records" size="small" stripe>
        <el-table-column prop="date" label="日期" width="100" />
        <el-table-column prop="company" label="企业客户" min-width="120" />
        <el-table-column prop="manager" label="客户经理" width="100" />
        <el-table-column label="方式" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.way === '上门' ? 'primary' : 'info'">{{ row.way }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="topic" label="走访主题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="summary" label="纪要摘要" min-width="260" show-overflow-tooltip />
        <el-table-column label="客户标签" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="t in row.tags" :key="t" size="small" effect="plain" class="cust-tag">{{ t }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
const records: {
  date: string; company: string; manager: string; way: string
  topic: string; summary: string; tags: string[]
}[] = [
  { date: '2026-09-22', company: '星辰科技', manager: '陈铭', way: '上门', topic: '年度授信额度复盘', summary: '企业明年扩产，拟申请流动资金贷款 500 万，已收集财报与纳税记录，进入尽调流程。', tags: ['重点客户', '授信需求'] },
  { date: '2026-09-19', company: '嘉禾制造', manager: '赵倩', way: '上门', topic: '票据业务推介', summary: '企业每月有稳定应收票据，推介票据贴现与供应链融资方案，财务负责人有明确意向。', tags: ['活跃客户', '票据需求'] },
  { date: '2026-09-16', company: '远航贸易', manager: '林芳', way: '电话', topic: '代发工资进度跟进', summary: 'HR 确认 60 人代发规模，已约定下周提交员工名单模板并测试批量文件。', tags: ['代发意向'] },
  { date: '2026-09-12', company: '恒通物流', manager: '赵倩', way: '视频', topic: '收单产品演示', summary: '演示聚合收单后台与对账功能，企业关注费率与结算时效，等待报价单。', tags: ['潜客'] },
  { date: '2026-09-09', company: '云启数据', manager: '林芳', way: '上门', topic: '理财到期续作回访', summary: '100 万对公理财即将到期，客户倾向续作并追加 50 万，已预约起息日。', tags: ['重点客户', '理财复购'] },
  { date: '2026-09-05', company: '星辰科技', manager: '陈铭', way: '上门', topic: '季度例行走访', summary: '回款正常，对网银转账体验满意，反馈希望增加批量付款模板功能。', tags: ['重点客户', '产品反馈'] },
]
</script>

<style scoped>
.page-head { margin-bottom: 14px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; color: #12263f; }
.page-sub { font-size: 12px; color: #8a97a8; }
.stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; }
.stat-card { background: #fff; border: 1px solid #e5e8ec; border-radius: 4px; padding: 14px 16px; box-shadow: 0 1px 3px rgba(16, 42, 83, 0.06); }
.stat-label { font-size: 12.5px; color: #7a8798; margin-bottom: 6px; }
.stat-value { font-size: 22px; font-weight: 700; color: #0b4f9e; font-variant-numeric: tabular-nums; }
.stat-sub { font-size: 12px; color: #98a2b0; margin-top: 4px; }
.cust-tag { margin-right: 6px; color: #0b4f9e; }
</style>
