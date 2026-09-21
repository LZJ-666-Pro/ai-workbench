package com.ai.workbench.bank.tool;

/**
 * 工具调用上下文：BankToolProvider 在每次工具执行前放入 memoryId，工具方法内读取，
 * 用于审计与会话级业务（如把确认单挂到会话上）。
 *
 * 线程安全说明：供具器包装帧与工具方法在同一线程同步执行，ThreadLocal 恰好成立；
 * 不要在 Controller 层用 ThreadLocal 传它——流式回调线程与 HTTP 请求线程不是同一个。
 */
public final class ToolCallContext {

    private static final ThreadLocal<String> MEMORY_ID = new ThreadLocal<>();

    private ToolCallContext() {
    }

    public static void set(String memoryId) {
        MEMORY_ID.set(memoryId);
    }

    /** 取当前会话 ID；取不到（如绕过供具器的直接调用）返回 "-"，调用方无需判空 */
    public static String currentMemoryId() {
        String id = MEMORY_ID.get();
        return id == null || id.isBlank() ? "-" : id;
    }

    public static void clear() {
        MEMORY_ID.remove();
    }
}
