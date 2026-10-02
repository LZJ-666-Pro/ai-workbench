package com.ai.workbench.bank.api;

import com.ai.workbench.bank.auth.AuthInterceptor;
import com.ai.workbench.bank.auth.JwtService.AuthPrincipal;
import com.ai.workbench.bank.console.ConsoleDtos.LogPage;
import com.ai.workbench.bank.console.ConsoleDtos.Workbench;
import com.ai.workbench.bank.console.PlatformConsoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台工作台接口。
 *
 *   GET /api/platform/workbench        首页数据（任何登录用户）
 *   GET /api/admin/platform-logs       平台运行日志（ADMIN）
 *
 * 两个接口的权限刻意不同：首页是每个用户都要看的门户，数字都是聚合量、不含客户信息；
 * 日志里带会话 id、traceId 与业务动作明细，属运维视图，因此放在 /api/admin/ 前缀下——
 * AuthInterceptor 对 /api/admin/** 统一要求 ADMIN，鉴权规则只写在拦截器一处，
 * 不在控制器里另建一套判断。
 */
@RestController
public class PlatformConsoleController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformConsoleService console;

    public PlatformConsoleController(PlatformConsoleService console) {
        this.console = console;
    }

    @GetMapping("/api/platform/workbench")
    public Workbench workbench(@RequestAttribute(AuthInterceptor.ATTR_PRINCIPAL) AuthPrincipal principal) {
        return console.workbench("ADMIN".equals(principal.role()));
    }

    @GetMapping("/api/admin/platform-logs")
    public LogPage platformLogs(
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 夹紧分页参数：size 不设上限时，一个 size=100000 的请求就能把整表拖出来
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return console.logs(app, category, result, keyword, safePage, safeSize);
    }
}
