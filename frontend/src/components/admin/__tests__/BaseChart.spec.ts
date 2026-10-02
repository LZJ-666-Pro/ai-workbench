import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * echarts 用替身：真实 echarts 需要 canvas，jsdom 里跑不起来，
 * 而这里要验的不是「图画得对不对」，而是**生命周期**——
 * 挂载时初始化、option 变化时更新、卸载时销毁。
 * 最后一件事最容易漏：漏掉 dispose 会在后台管理页反复切换时泄漏 canvas 与事件监听。
 */
const mocks = vi.hoisted(() => {
  const instance = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }
  return {
    instance,
    init: vi.fn(() => instance),
    observe: vi.fn(),
    disconnect: vi.fn(),
  }
})

vi.mock('echarts/core', () => ({ init: mocks.init, use: vi.fn() }))
vi.mock('echarts/charts', () => ({ LineChart: {}, BarChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({ GridComponent: {}, TooltipComponent: {}, LegendComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import BaseChart from '../BaseChart.vue'

describe('BaseChart 生命周期', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 必须是 class / function：ResizeObserver 是被 new 出来的，
    // 用箭头函数当构造函数会直接抛 "is not a constructor"
    vi.stubGlobal('ResizeObserver', class {
      observe = mocks.observe
      disconnect = mocks.disconnect
      unobserve = vi.fn()
    })
  })

  it('挂载时初始化图表并写入 option', () => {
    mount(BaseChart, { props: { option: { series: [] } } })

    expect(mocks.init).toHaveBeenCalledTimes(1)
    expect(mocks.instance.setOption).toHaveBeenCalledWith({ series: [] })
  })

  it('挂载时监听容器尺寸变化（侧栏折叠等场景需要重绘）', () => {
    mount(BaseChart, { props: { option: { series: [] } } })

    expect(mocks.observe).toHaveBeenCalledTimes(1)
  })

  it('卸载时销毁实例并断开尺寸监听，避免泄漏', () => {
    const wrapper = mount(BaseChart, { props: { option: { series: [] } } })

    wrapper.unmount()

    expect(mocks.instance.dispose).toHaveBeenCalledTimes(1)
    expect(mocks.disconnect).toHaveBeenCalledTimes(1)
  })

  it('option 变化时以不合并的方式重设，避免旧系列残留', async () => {
    const wrapper = mount(BaseChart, { props: { option: { series: [{ type: 'line' }] } } })

    await wrapper.setProps({ option: { series: [{ type: 'pie' }] } })

    // 第二个参数 true 表示 notMerge：图表数据来源变化时必须整份替换
    expect(mocks.instance.setOption).toHaveBeenLastCalledWith({ series: [{ type: 'pie' }] }, true)
  })
})
