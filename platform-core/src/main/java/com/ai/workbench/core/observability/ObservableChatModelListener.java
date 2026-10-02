package com.ai.workbench.core.observability;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 调用的可观测出口：token 用量与调用结果同时产出日志和指标。
 *
 * 为什么指标比日志重要：日志回答「这一次为什么慢/为什么错」，指标回答
 * 「今天错误率是不是涨了、token 花超了没有」。只有日志，问题发生时得先去猜是哪一类。
 *
 * 指标一律以 model 打标签：换模型只改环境变量，成本却按模型结算，
 * 没有这个维度，多模型对比和按模型核算都无从谈起。
 *
 * traceId 的跨界传递：本监听器的 onRequest 在调用方线程（Servlet 请求线程）执行，
 * 此时 MDC 里带着 traceId；onResponse/onError 却是在 LLM 客户端线程上回调的，
 * MDC 跟不过去。这里借 LangChain4j 的 attributes（请求上下文与响应上下文共用同一份 map）
 * 把 traceId 与起始时间带过去，保证 token 日志与指标也归到同一条 trace 上。
 *
 * 已知边界：一次带工具调用的对话会发生多轮模型调用。第一轮的 onRequest 跑在请求线程上，
 * traceId 正常；工具返回后的后续轮次由 LangChain4j 在内部线程池上重新发起，
 * 且每次调用给的是**新的** attributes（实测不与上一轮共用），因此后续轮次的
 * onResponse 拿不到 traceId，其 token 日志会显示空 traceId。
 * 这不影响指标（llm.tokens 按 model 打标，与 trace 无关），也不影响审计表——
 * 工具执行期间由 AgentRegistry 按 memoryId 重新绑定 MDC，审计行的 trace_id 是准确的。
 * 想要完整覆盖需要 LangChain4j 的 InvocationParameters 全程透传，属后续可做项。
 */
public class ObservableChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ObservableChatModelListener.class);

    private static final String ATTR_TRACE_ID = "aiwb.traceId";
    private static final String ATTR_START_NANOS = "aiwb.startNanos";

    private final MeterRegistry registry;
    private final String modelName;

    public ObservableChatModelListener(MeterRegistry registry, String modelName) {
        this.registry = registry;
        this.modelName = modelName;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // attributes 是 LangChain4j 传入的可变 map（请求/响应上下文共用同一份），
        // 但**不接受 null 值**——put 一个 null 会抛 NPE 并被 LangChain4j 静默吞掉，
        // 表现为「监听器悄悄失效」。所以 traceId 为空时干脆不写这个键。
        Map<Object, Object> attributes = requestContext.attributes();
        String traceId = TraceContext.current();
        if (traceId != null) {
            attributes.put(ATTR_TRACE_ID, traceId);
        }
        attributes.put(ATTR_START_NANOS, System.nanoTime());
        log.debug("LLM 请求开始");
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Map<Object, Object> attributes = responseContext.attributes();
        TokenUsage usage = responseContext.chatResponse() != null
                && responseContext.chatResponse().metadata() != null
                ? responseContext.chatResponse().metadata().tokenUsage()
                : null;

        TraceContext.runWith(traceIdOf(attributes), () -> {
            registry.counter("llm.calls", "model", modelName, "result", "success").increment();
            if (usage != null) {
                // 入/出分别计数而不是只记 total：输入决定上下文成本，输出决定生成成本，
                // 两者单价不同，合并成一个数就没法定位「成本涨在哪一头」
                countTokens("input", usage.inputTokenCount());
                countTokens("output", usage.outputTokenCount());
                log.info("LLM 响应: 输入 {} tokens, 输出 {} tokens, 共 {} tokens",
                        usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
            } else {
                log.info("LLM 响应完成（无 token 统计）");
            }
            recordDuration(attributes);
        });
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Throwable error = errorContext.error();
        Map<Object, Object> attributes = errorContext.attributes();
        TraceContext.runWith(traceIdOf(attributes), () -> {
            registry.counter("llm.calls", "model", modelName, "result", "error").increment();
            recordDuration(attributes);
            log.error("LLM 调用失败: {}", error.getMessage(), error);
        });
    }

    private void countTokens(String type, Integer count) {
        if (count != null) {
            registry.counter("llm.tokens", "model", modelName, "type", type).increment(count);
        }
    }

    private void recordDuration(Map<Object, Object> attributes) {
        Object start = attributes.get(ATTR_START_NANOS);
        if (start instanceof Long startNanos) {
            registry.timer("llm.duration", "model", modelName)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }

    private static String traceIdOf(Map<Object, Object> attributes) {
        Object traceId = attributes.get(ATTR_TRACE_ID);
        return traceId instanceof String s ? s : null;
    }
}
