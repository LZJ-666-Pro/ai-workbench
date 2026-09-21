package com.ai.workbench.bank.stream;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ai.workbench.bank.service.TransferService;
import com.ai.workbench.core.api.AgentStreamListener;
import com.ai.workbench.core.api.SseSender;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 模型回复结束后，把本会话新建的转账确认单以 confirm_request 事件推给前端，
 * 由前端渲染成确认卡片。这是「human-in-the-loop」的前端半边；
 * 后半边在 TransferConfirmController 的 CAS 状态机里。
 */
@Component
public class TransferConfirmCardListener implements AgentStreamListener {

    private final TransferService transferService;

    public TransferConfirmCardListener(TransferService transferService) {
        this.transferService = transferService;
    }

    @Override
    public void onStreamComplete(String agent, String memoryId, SseEmitter emitter) {
        if (!"bank".equals(agent)) {
            return;
        }
        for (TransferService.PendingOrder order : transferService.takeUnnotifiedPending(memoryId)) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "confirm_request");
            event.put("confirmId", order.confirmId());
            event.put("fromAccount", order.fromAccount());
            event.put("toAccount", order.toAccount());
            event.put("toOwner", order.toOwner());
            event.put("amount", order.amount());
            event.put("reason", order.reason() == null ? "" : order.reason());
            SseSender.send(emitter, event);
        }
    }
}
