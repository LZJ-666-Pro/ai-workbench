package com.ai.workbench.core.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个 HTTP 请求绑定 traceId：写入 MDC（日志 pattern 会带出来）并回写到响应头，
 * 前端因此能在报错时把编号直接给到用户/客服。
 *
 * 最高优先级：要保证后续所有过滤器、拦截器、控制器乃至它们的异常日志都已经带上 traceId。
 *
 * 注意边界——本过滤器只覆盖 Servlet 请求线程。SSE 的增量回调跑在 LLM 客户端线程上，
 * 那里的 MDC 需要由 ChatStreamController 显式接续（见 {@link TraceContext#runWith}）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.adoptOrCreate(request.getHeader(TraceContext.HEADER));
        try (MDC.MDCCloseable ignored = MDC.putCloseable(TraceContext.MDC_KEY, traceId)) {
            // 必须回写而不是只在请求时读：调用方没带时，这个值是服务端生成的，
            // 不回写调用方就无从知道该拿哪个编号来定位这次请求。
            response.setHeader(TraceContext.HEADER, traceId);
            filterChain.doFilter(request, response);
        }
    }
}
