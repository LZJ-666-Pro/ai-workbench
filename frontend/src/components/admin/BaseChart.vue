<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

echarts.use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{ option: EChartsOption; height?: string }>()

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  chart.setOption(props.option)
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(el.value)
})

watch(() => props.option, opt => chart?.setOption(opt, true))

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" :style="{ width: '100%', height: height || '320px' }"></div>
</template>
