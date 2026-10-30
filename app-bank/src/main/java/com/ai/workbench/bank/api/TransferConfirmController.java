package com.ai.workbench.bank.api;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.ai.workbench.bank.auth.AuthInterceptor;
import com.ai.workbench.core.auth.JwtService.AuthPrincipal;
import com.ai.workbench.bank.service.TransferService;
import com.ai.workbench.bank.service.TransferService.TransferResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 转账确认接口：资金变动的唯一入口。前端确认卡片调用；
 * 支付密码校验（含试错锁定）、幂等、过期、规则复检都在 TransferService 的事务里。
 *
 * 资金结果的指标埋在这里而不是 Service 里：Service 的方法整体在一个事务中，
 * 在事务提交前打点，一旦提交失败就会出现「指标说成功、钱没动」的假信号。
 * 控制器在事务之外，拿到的是事务已提交后的终态。
 */
@RestController
@RequestMapping("/api/transfer")
public class TransferConfirmController {

    private final TransferService transferService;
    private final MeterRegistry meterRegistry;

    public TransferConfirmController(TransferService transferService, MeterRegistry meterRegistry) {
        this.transferService = transferService;
        this.meterRegistry = meterRegistry;
    }

    public record ConfirmRequest(String memoryId, String confirmId, String action,
                                 String payPassword, String otpCode) {

    }

    public record ConfirmResponse(boolean ok, String message) {

    }

    /**
     * 本会话交易单（前端刷新/切会话后重建卡片用）：未过期的待确认单 + 最近 5 笔已执行单。
     * 卡片原本只随 SSE 流结束推一次，页面刷新后就失联了——待确认的卡片丢了没法确认，
     * 已确认的卡片丢了连「查看电子回单」入口都没了，恢复入口必须覆盖两种状态。
     */
    @GetMapping("/orders")
    public List<TransferService.OrderView> orders(
            @RequestParam("memoryId") String memoryId, HttpServletRequest http) {
        // 与 confirm 同样的归属校验；AuthInterceptor 也会对 memoryId 查询参数做一遍，这里兜底
        AuthPrincipal principal = (AuthPrincipal) http.getAttribute(AuthInterceptor.ATTR_PRINCIPAL);
        if (principal != null && !AuthInterceptor.memoryIdBelongsTo(principal.identityId(), memoryId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看其他身份的转账确认单");
        }
        return transferService.listOrders(memoryId);
    }

    /**
     * 电子回单：本会话某张已执行确认单的回单数据（前端「查看电子回单」弹窗用）。
     * 归属校验与 /orders 相同：回单虽是只读凭证，也不能让别的会话凭确认码查到。
     */
    @GetMapping("/receipt")
    public TransferService.ReceiptView receipt(
            @RequestParam("memoryId") String memoryId,
            @RequestParam("confirmId") String confirmId,
            HttpServletRequest http) {
        AuthPrincipal principal = (AuthPrincipal) http.getAttribute(AuthInterceptor.ATTR_PRINCIPAL);
        if (principal != null && !AuthInterceptor.memoryIdBelongsTo(principal.identityId(), memoryId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看其他身份的转账回单");
        }
        return transferService.receipt(memoryId, confirmId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "回单不存在或转账未执行"));
    }

    @PostMapping("/otp/send")
    public TransferService.OtpSend sendOtp(@RequestBody ConfirmRequest request, HttpServletRequest http) {
        String memoryId = request.memoryId();
        if (memoryId == null || memoryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少会话标识 memoryId");
        }
        AuthPrincipal principal = (AuthPrincipal) http.getAttribute(AuthInterceptor.ATTR_PRINCIPAL);
        if (principal != null && !AuthInterceptor.memoryIdBelongsTo(principal.identityId(), memoryId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权为其他身份的确认单发送验证码");
        }
        return transferService.sendOtp(memoryId, request.confirmId());
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
        boolean confirming = "confirm".equals(request.action());
        // 双因子：执行（确认）需要支付密码（验证「知道」）+ 短信验证码（验证「持有」），
        // 取消不涉及资金所以都不需要。校验逻辑（含试错锁定与作废）各自在 Service 的事务里，
        // 串行校验——任一失败直接返回，不会把作废和执行混进同一个事务。
        if (confirming) {
            TransferService.PasswordVerification pwdCheck = transferService.verifyPayPassword(
                    memoryId, request.confirmId(), request.payPassword());
            if (!pwdCheck.passed()) {
                // 密码拒绝单独打点：风控拒绝率看 DENY，密码爆破尝试看这张计数
                meterRegistry.counter("bank.transfer.confirm", "result", "PWD_DENY").increment();
                return new ConfirmResponse(false, pwdCheck.message());
            }
            TransferService.OtpVerification otpCheck = transferService.verifyOtp(
                    memoryId, request.confirmId(), request.otpCode());
            if (!otpCheck.passed()) {
                meterRegistry.counter("bank.transfer.confirm", "result", "OTP_DENY").increment();
                return new ConfirmResponse(false, otpCheck.message());
            }
        }
        // 真正的越权防线在 TransferService.confirmOrder：它按 confirm_id + memory_id 双条件查单，
        // 拿到别人的确认码也只会得到「确认单不存在」。
        long startNanos = System.nanoTime();
        TransferResult result = transferService.confirmOrder(
                memoryId, request.confirmId(), confirming);
        // result 被拒绝（DENY/FAIL）是业务正常路径而非异常，因此不用错误计数器，
        // 而是按 kind 打标签：风控拒绝率是否异常，看这张按 result 拆分的计数就够了
        meterRegistry.counter("bank.transfer.confirm", "result", result.kind()).increment();
        meterRegistry.timer("bank.transfer.confirm.duration")
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        return new ConfirmResponse("SUCCESS".equals(result.kind()), result.message());
    }
}
