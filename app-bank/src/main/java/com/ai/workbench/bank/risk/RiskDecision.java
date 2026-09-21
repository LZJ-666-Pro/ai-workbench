package com.ai.workbench.bank.risk;

/**
 * 风控结论。allowed=false 时 reason 必须是用户能读懂的原因，
 * LLM 只负责如实转述，不允许自行“想办法绕过”。
 */
public record RiskDecision(boolean allowed, String reason) {

    public static RiskDecision allow() {
        return new RiskDecision(true, "通过");
    }

    public static RiskDecision deny(String reason) {
        return new RiskDecision(false, reason);
    }
}
