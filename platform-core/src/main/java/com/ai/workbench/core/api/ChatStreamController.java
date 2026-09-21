package com.ai.workbench.core.api;

import java.util.Map;
import java.util.Set;

import com.ai.workbench.core.agent.AgentRegistry;
import com.ai.workbench.core.agent.Assistant;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   {"type":"delta",  "content":"token"}   增量内容
 *   {"type":"done",   "totalTokens":123}   结束，附 token 用量
 *   {"type":"error",  "content":"..."}      出错
 */
@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    private final AgentRegistry registry;

    public ChatStreamController(AgentRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/{agent}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String agent,
                             @RequestParam(defaultValue = "demo") String memoryId,
                             @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(0L);
        // 断开/超时登记：TokenStream 无法取消，断开后本次模型调用会跑完，这里至少留痕可观测
        emitter.onTimeout(() -> log.warn("SSE 超时断开: agent={}, memoryId={}", agent, memoryId));
        emitter.onCompletion(() -> log.debug("SSE 连接结束: agent={}, memoryId={}", agent, memoryId));
        emitter.onError(e -> log.warn("SSE 客户端断开: agent={}, memoryId={}", agent, memoryId));
        Assistant assistant = registry.get(agent);

        assistant.chat(memoryId, message)
                .onPartialResponse(token -> send(emitter,
                        Map.of("type", "delta", "content", token == null ? "" : token)))
                .onCompleteResponse(response -> {
                    TokenUsage usage = response.metadata() != null
                            ? response.metadata().tokenUsage() : null;
                    send(emitter, Map.of("type", "done",
                            "totalTokens", usage != null ? usage.totalTokenCount() : -1));
                    emitter.complete();
                })
                .onError(error -> {
                    log.error("流式对话出错: {}", error.getMessage(), error);
                    send(emitter, Map.of("type", "error",
                            "content", error.getMessage() == null ? "模型调用失败" : error.getMessage()));
                    emitter.complete();
                })
                .start();
        return emitter;
    }

    /** 已注册的 Agent 列表，前端/调试用 */
    @GetMapping("/agents")
    public Set<String> agents() {
        return registry.names();
    }

    private void send(SseEmitter emitter, Object payload) {
        try {
            emitter.send(payload);
        } catch (Exception e) {
            // 客户端断开等场景，静默结束本次推送
        }
    }
}
