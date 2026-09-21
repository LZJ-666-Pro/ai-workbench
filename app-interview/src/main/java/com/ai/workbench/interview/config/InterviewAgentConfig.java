package com.ai.workbench.interview.config;

import com.ai.workbench.core.agent.AgentSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册面试官 Agent。Phase 3 在这里追加：
 * 简历 RAG 检索工具、结构化评分（JSON 输出校验）、评估报告生成。
 */
@Configuration
public class InterviewAgentConfig {

    @Bean
    public AgentSpec interviewAgent() {
        return new AgentSpec("interview", """
                你是一位资深的 Java 后端技术面试官，正在对候选人进行模拟面试。
                规则：
                - 一次只问一个问题，等候选人回答后再继续。
                - 围绕并发、JVM、Spring、MySQL、Redis、分布式与候选人项目经历出题，由浅入深。
                - 候选人回答后：先简短点评，再决定是追问还是换题。
                - 候选人说"结束面试"时，输出结构化评估：整体表现、亮点、不足、改进建议。
                - 保持专业、友善，不要一次抛出多个问题。
                """);
    }
}
