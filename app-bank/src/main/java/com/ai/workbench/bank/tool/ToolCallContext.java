package com.ai.workbench.bank.tool;

import com.ai.workbench.bank.identity.BankIdentity;

/**
 * 工具调用上下文：BankToolProvider 在每次工具执行前放入 memoryId 与服务对象身份，
 * 工具方法内读取，用于审计、数据权限过滤与会话级业务（如把确认单挂到会话上）。
 *
 * 线程安全说明：供具器包装帧与工具方法在同一线程同步执行，ThreadLocal 恰好成立；
 * 不要在 Controller 层用 ThreadLocal 传它——流式回调线程与 HTTP 请求线程不是同一个。
 */
public final class ToolCallContext {

    private static final ThreadLocal<String> MEMORY_ID = new ThreadLocal<>();
    private static final ThreadLocal<BankIdentity> IDENTITY = new ThreadLocal<>();

    private ToolCallContext() {
    }

    public static void set(String memoryId, BankIdentity identity) {
        MEMORY_ID.set(memoryId);
        IDENTITY.set(identity);
    }

    /** 取当前会话 ID；取不到（如绕过供具器的直接调用）返回 "-"，调用方无需判空 */
    public static String currentMemoryId() {
        String id = MEMORY_ID.get();
        return id == null || id.isBlank() ? "-" : id;
    }

    /** 取当前服务对象身份；取不到时按零售客户处理（与旧格式 memoryId 行为一致） */
    public static BankIdentity currentIdentity() {
        BankIdentity identity = IDENTITY.get();
        return identity == null ? BankIdentity.RETAIL : identity;
    }

    public static void clear() {
        MEMORY_ID.remove();
        IDENTITY.remove();
    }
}