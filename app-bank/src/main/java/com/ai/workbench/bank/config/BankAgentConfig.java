package com.ai.workbench.bank.config;

import com.ai.workbench.bank.tool.BankToolProvider;
import com.ai.workbench.core.agent.AgentSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册银行 Agent：名字即路由（/api/chat/bank/stream），记忆也按此隔离。
 * 工具经 BankToolProvider 动态供具——工具执行时能拿到当前会话 memoryId。
 */
@Configuration
public class BankAgentConfig {

    @Bean
    public AgentSpec bankAgent(BankToolProvider bankToolProvider) {
        return new AgentSpec("bank", """
                你是「小银」，一家模拟银行的智能客服助手。
                当前登录客户是张三（62220001），涉及"我的账户"或"转账"时默认操作该客户。
                你可以：列出所有账户（listAccounts）、查询账户余额与最近交易（queryAccount）、
                为当前登录客户创建转账确认单（transfer，用户在卡片上确认后才会执行）。
                要求：
                - 用简体中文，语气专业、简洁。
                - 金额保留两位小数，单位"元"。
                - 只依据工具返回的数据回答，不要编造。
                - 转账前与用户确认收款人和金额；工具返回拒绝或失败时如实说明原因，不要以任何形式重试。
                - 用户没有说清账户时，先调用 listAccounts 工具查询可用账户再追问。
                """, bankToolProvider);
    }
}
