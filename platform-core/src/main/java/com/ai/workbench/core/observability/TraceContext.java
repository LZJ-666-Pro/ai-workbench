package com.ai.workbench.core.observability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * traceId：把一次请求散落在各处的日志串成一条线。
 *
 * 为什么需要它：一次银行助手对话会横跨 Servlet 请求线程、LLM 的 HTTP 客户端线程
 * （SSE 增量回调与工具调用都在那里）、以及数据库写入。默认情况下这些日志除了
 * memoryId 之外没有任何共同标识，而 memoryId 是「会话」粒度的——同一会话的多次提问
 * 混在一起，排查一次具体故障只能靠猜时间。traceId 是「单次请求」粒度，一条命令即可拉全。
 *
 * 与 memoryId 的分工：
 *   - memoryId 标识会话（谁在跟谁聊），是业务概念，落库进审计表；
 *   - traceId 标识一次调用（这次为什么失败），是运维概念，进日志与响应头。
 * 两者都写进审计表，才能既按客户追溯、又按故障追溯。
 */
public final class TraceContext {

    /** MDC 键名，须与 logback 日志 pattern 里的 %X{traceId} 一致 */
    public static final String MDC_KEY = "traceId";

    /** 请求/响应头名：调用方可带进来做链路对齐，服务端每次都会回写 */
    public static final String HEADER = "X-Trace-Id";

    /**
     * 只接受十六进制：traceId 会进日志，若允许任意字符，调用方就能塞入换行
     * 伪造日志行（log injection）。限长同理，避免超长字符串撑爆日志。
     * 16~32 位与 W3C trace-id（32 位十六进制）兼容，将来接 OpenTelemetry 不用换格式。
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[0-9a-fA-F]{16,32}$");

    private TraceContext() {
    }

    /** 当前线程绑定的 traceId，可能为 null（如启动期、定时任务等无请求上下文） */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 采纳调用方传入的 traceId（合法时），否则新生成一个 */
    public static String adoptOrCreate(String incoming) {
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming.toLowerCase();
        }
        return newTraceId();
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 在指定 traceId 下执行 —— 用于跨线程边界传递。
     *
     * 必须显式传递的原因：MDC 底层是 ThreadLocal，而 SSE 的增量回调、工具调用
     * 由 LLM 客户端的线程执行，Servlet 请求线程上的 MDC 不会跟过去。
     * 执行完恢复原值而非直接清除，以便支持嵌套。
     */
    public static void runWith(String traceId, Runnable action) {
        String previous = MDC.get(MDC_KEY);
        if (traceId != null) {
            MDC.put(MDC_KEY, traceId);
        }
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    /** {@link #runWith} 的带返回值版本 */
    public static <T> T callWith(String traceId, Supplier<T> action) {
        String previous = MDC.get(MDC_KEY);
        if (traceId != null) {
            MDC.put(MDC_KEY, traceId);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    // ------------------------------------------------------------------
    // 按 memoryId 传递 traceId
    // ------------------------------------------------------------------

    /**
     * memoryId → traceId 的短期登记表。
     *
     * 为什么还需要它（MDC + attributes 不够用）：工具调用由 LangChain4j 在内部线程池上发起
     * （实测线程名为 onPool-worker-N），MDC 是 ThreadLocal，不随线程切换传递；
     * 而工具层能拿到的上下文只有 chatMemoryId——LangChain4j 没有把请求线程的上下文
     * 透传到工具执行的位置。控制器发起流式对话前登记、流结束时注销，
     * 工具执行处按 memoryId 取回并重新绑定 MDC，审计表因此也能带上 trace_id。
     *
     * 用 memoryId 作键是可行的：同一条会话同时只应有一条流（前端按会话串行发送），
     * 登记表因此很小且很快被清理。真出现并发同会话时，最坏结果是其中一条的 trace_id
     * 记成另一条的——只影响排障时的归属，不影响权限与资金正确性。
     */
    private static final Map<String, String> TRACE_BY_MEMORY_ID = new ConcurrentHashMap<>();

    public static void bindMemoryId(String memoryId, String traceId) {
        if (memoryId != null && traceId != null) {
            TRACE_BY_MEMORY_ID.put(memoryId, traceId);
        }
    }

    public static void unbindMemoryId(String memoryId) {
        if (memoryId != null) {
            TRACE_BY_MEMORY_ID.remove(memoryId);
        }
    }

    /** 取该会话当前在途请求的 traceId，没有则返回 null */
    public static String forMemoryId(String memoryId) {
        return memoryId == null ? null : TRACE_BY_MEMORY_ID.get(memoryId);
    }
}
