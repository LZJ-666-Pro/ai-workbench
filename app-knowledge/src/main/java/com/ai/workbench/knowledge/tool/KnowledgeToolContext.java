package com.ai.workbench.knowledge.tool;

/**
 * 工具调用上下文：KnowledgeToolProvider 在每次工具执行前放入当前会话的登录名，
 * 工具方法内读取，用于定位「这是谁的」个人知识库。
 *
 * 线程安全说明：供具器包装帧与工具方法在同一线程同步执行，ThreadLocal 恰好成立；
 * 不要在 Controller 层用 ThreadLocal 传它——流式回调线程与 HTTP 请求线程不是同一个。
 */
public final class KnowledgeToolContext {

    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private KnowledgeToolContext() {
    }

    public static void set(String username) {
        USERNAME.set(username);
    }

    /** 取当前登录名；取不到（如绕过供具器的直接调用）返回 null，调用方按无个人库处理 */
    public static String currentUsername() {
        return USERNAME.get();
    }

    public static void clear() {
        USERNAME.remove();
    }
}
