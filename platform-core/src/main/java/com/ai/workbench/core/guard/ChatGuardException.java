package com.ai.workbench.core.guard;

import dev.langchain4j.model.output.TokenUsage;

/**
 * 护栏拒绝：抛出后由 ChatStreamController 转成 HTTP 429 + JSON 错误体。
 *
 * 单独定义而不复用 ResponseStatusException：Spring Boot 默认不在错误响应体里
 * 输出异常 message（server.error.include-message 默认 never），
 * 前端只能看到「HTTP 429」而拿不到「多久之后可以重试」。为了这一处提示
 * 把全局的 include-message 打开，会把所有异常内部信息暴露出去，得不偿失。
 */
public class ChatGuardException extends RuntimeException {

    /** 拒绝原因，用于指标标签（有限枚举，不会撑爆基数） */
    private final String reason;

    public ChatGuardException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
