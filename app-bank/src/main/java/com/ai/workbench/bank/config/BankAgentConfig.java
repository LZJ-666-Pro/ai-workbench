package com.ai.workbench.bank.config;

import java.util.List;

import com.ai.workbench.bank.tool.AccountTools;
import com.ai.workbench.core.agent.AgentSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册银行 Agent：名字即路由（/api/chat/bank/stream），记忆也按此隔离。
 */
@Configuration
public class BankAgentConfig {

    @Bean
    public AgentSpec bankAgent(AccountTools accountTools) {
        return new AgentSpec("bank", """
                你是「小银」，一家模拟银行的智能客服助手。
                你可以帮用户查询账户余额和最近交易记录。
                要求：
                - 用简体中文，语气专业、简洁。
                - 金额保留两位小数，单位"元"。
                - 只依据工具返回的数据回答，不要编造。
                - 用户没有说清账户时，先调用 listAccounts 工具查询可用账户再追问。
                """, List.of(accountTools));
    }
}
