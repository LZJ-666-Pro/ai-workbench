/**
 * 「后端不在」与「程序出错」的区分，供各数据页统一降级提示。
 *
 * 背景：平台级数据接口（知识库、数据源、统计等）都跑在 app-bank（8081）——
 * platform-core 是依赖库，没有自己的进程。该服务未启动时 Vite 代理连不上目标
 * 直接回 500；fetch 阶段断网则抛 "Failed to fetch"。这两种是「后端不在」，
 * 不是「程序出错」——页面应降级为 info 提示条，而不是弹 HTTP 500 的告警
 * 让人以为出了 bug（服务在跑但接口真的 500 才值得警觉）。
 */
export function isOfflineError(message: string): boolean {
  return /HTTP 5\d\d|Failed to fetch|fetch failed|NetworkError|load failed/i.test(message)
}
