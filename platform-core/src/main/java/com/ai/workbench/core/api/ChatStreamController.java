package com.ai.workbench.core.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.ai.workbench.core.agent.AgentRegistry;
import com.ai.workbench.core.agent.Assistant;
import com.ai.workbench.core.observability.TraceContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流式对话接口，所有应用共用：
 *   GET /api/chat/{agent}/stream?memoryId=会话ID&message=用户输入
 *
 * 事件格式（JSON）：
 *   {"type":"delta",  "content":"token"}    增量内容
 *   {"type":"done",   "totalTokens":123}    结束，附 token 用量
 *   {"type":"error",  "content":"...", "traceId":"..."}  出错（traceId 供用户/客服报障定位）
 *   {"type":"confirm_request", ...}         业务模块追加的领域事件（AgentStreamListener）
 *
 * traceId 的接续点：本方法在 Servlet 请求线程上执行，返回 emitter 后请求线程即结束，
 * 而 onPartialResponse / onCompleteResponse / onError 由 LLM 客户端的线程回调。
 * 因此必须在返回前捕获 traceId，并在每个回调里用 TraceContext.runWith 重新绑定，
 * 否则「一次对话」的日志会在 SSE 这一段断掉——而这一段恰恰是最需要排查的地方。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    private final AgentRegistry registry;
    private final ObjectProvider<AgentStreamListener> streamListeners;
    private final MeterRegistry meterRegistry;

    public ChatStreamController(AgentRegistry registry,
                                ObjectProvider<AgentStreamListener> streamListeners,
                                MeterRegistry meterRegistry) {
        this.registry = registry;
        this.streamListeners = streamListeners;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping(value = "/{agent}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String agent,
                             // 必填，不能给默认值：memoryId 承载服务对象身份，一旦有默认值，
                             // 省略参数的请求就绕过了拦截器的归属校验，身份还会降级成默认身份
                             @RequestParam String memoryId,
                             @RequestParam String message) {
        String traceId = TraceContext.current();
        SseEmitter emitter = new SseEmitter(0L);
        // 工具调用跑在 LLM 客户端的线程池线程上，拿不到请求线程的 MDC。
        // 按 memoryId 登记本次请求的 traceId，工具层执行时取回并重新绑定 MDC
        // （见 TraceContext.forMemoryId、AgentRegistry.withTraceContext），
        // 这样工具内部的日志与审计落库同样带上 trace_id。
        TraceContext.bindMemoryId(memoryId, traceId);
        // 断开/超时登记：TokenStream 无法取消，断开后本次模型调用会跑完，这里至少留痕可观测
        emitter.onTimeout(() -> {
            TraceContext.unbindMemoryId(memoryId);
            TraceContext.runWith(traceId, () -> log.warn("SSE 超时断开: agent={}, memoryId={}", agent, memoryId));
        });
        emitter.onCompletion(() -> {
            // 正常结束、以及出错后 complete() 都会走到这里，是注销登记表最可靠的时机
            TraceContext.unbindMemoryId(memoryId);
            TraceContext.runWith(traceId, () -> log.debug("SSE 连接结束: agent={}, memoryId={}", agent, memoryId));
        });
        emitter.onError(e -> {
            TraceContext.unbindMemoryId(memoryId);
            TraceContext.runWith(traceId, () -> log.warn("SSE 客户端断开: agent={}, memoryId={}", agent, memoryId));
        });
        Assistant assistant = registry.get(agent);

        long startNanos = System.nanoTime();

        assistant.chat(memoryId, message)
                .onPartialResponse(token -> TraceContext.runWith(traceId, () -> SseSender.send(emitter,
                        Map.of("type", "delta", "content", token == null ? "" : token))))
                .onCompleteResponse(response -> TraceContext.runWith(traceId, () -> {
                    // 先让业务模块追加领域事件（如确认卡片），再发 done 收尾
                    streamListeners.stream().forEach(listener -> {
                        try {
                            listener.onStreamComplete(agent, memoryId, emitter);
                        } catch (Exception e) {
                            log.warn("AgentStreamListener 执行失败: {}", e.getMessage(), e);
                        }
                    });
                    TokenUsage usage = response.metadata() != null
                            ? response.metadata().tokenUsage() : null;
                    SseSender.send(emitter, Map.of("type", "done",
                            "totalTokens", usage != null ? usage.totalTokenCount() : -1));
                    meterRegistry.counter("chat.stream", "agent", agent, "result", "done").increment();
                    recordDuration(agent, startNanos);
                    emitter.complete();
                }))
                .onError(error -> TraceContext.runWith(traceId, () -> {
                    log.error("流式对话出错: {}", error.getMessage(), error);
                    meterRegistry.counter("chat.stream", "agent", agent, "result", "error").increment();
                    recordDuration(agent, startNanos);
                    // 对 GLM 错误码 1305（访问量过大）做友好转换
                    String friendlyMessage = error.getMessage() != null
                            ? error.getMessage()
                            : "模型调用失败";

                    // 检测三种形式的限流错误（消息可能包含 JSON 或原始描述）
                    if (friendlyMessage.contains("1305") || friendlyMessage.contains("当前访问量过大")
                            || friendlyMessage.contains("rate limit") || friendlyMessage.contains("请稍后再试")) {
                        friendlyMessage = "模型服务暂时繁忙，请稍后再试";
                    }
                    // 带 traceId 下发：用户报障时给出这个编号，即可在日志里定位这一次调用；
                    // 用 HashMap 而非 Map.of，因为 traceId 可能为 null（无请求上下文时）
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("type", "error");
                    payload.put("content", friendlyMessage);
                    payload.put("traceId", traceId == null ? "-" : traceId);
                    SseSender.send(emitter, payload);
                    emitter.complete();
                }))
                .start();
        return emitter;
    }

    /** 已注册的 Agent 列表，前端/调试用 */
    @GetMapping("/agents")
    public Set<String> agents() {
        return registry.names();
    }

    private void recordDuration(String agent, long startNanos) {
        meterRegistry.timer("chat.stream.duration", "agent", agent)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
