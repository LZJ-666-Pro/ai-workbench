package com.ai.workbench.bank.api;

import com.ai.workbench.bank.auth.AuthInterceptor;
import com.ai.workbench.bank.auth.JwtService.AuthPrincipal;
import com.ai.workbench.bank.service.TransferService;
import com.ai.workbench.bank.service.TransferService.TransferResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 转账确认接口：资金变动的唯一入口。前端确认卡片调用；
 * 幂等、过期、规则复检都在 TransferService.confirmOrder 的事务里。
 */
@RestController
@RequestMapping("/api/transfer")
public class TransferConfirmController {

    private final TransferService transferService;

    public TransferConfirmController(TransferService transferService) {
        this.transferService = transferService;
    }

    public record ConfirmRequest(String memoryId, String confirmId, String action) {

    }

    public record ConfirmResponse(boolean ok, String message) {

    }

    @PostMapping("/confirm")
    public ConfirmResponse confirm(@RequestBody ConfirmRequest request, HttpServletRequest http) {
        // memoryId 在请求体里，拦截器看不到，因此这里必须自己把归属校验做完整：
        // 缺失即拒绝（不能退化成用 "-" 当会话去查单），且会话必须属于登录身份。
        String memoryId = request.memoryId();
        if (memoryId == null || memoryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少会话标识 memoryId");
        }
        AuthPrincipal principal =
                (AuthPrincipal) http.getAttribute(AuthInterceptor.ATTR_PRINCIPAL);
        if (principal != null && !AuthInterceptor.memoryIdBelongsTo(principal.identityId(), memoryId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作其他身份的转账确认单");
        }
        // 真正的越权防线在 TransferService.confirmOrder：它按 confirm_id + memory_id 双条件查单，
        // 拿到别人的确认码也只会得到「确认单不存在」。
        TransferResult result = transferService.confirmOrder(
                memoryId, request.confirmId(), "confirm".equals(request.action()));
        return new ConfirmResponse("SUCCESS".equals(result.kind()), result.message());
    }
}
