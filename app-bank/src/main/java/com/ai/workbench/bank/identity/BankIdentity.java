package com.ai.workbench.bank.identity;

import java.util.List;

/**
 * 银行助手的服务对象（身份）。身份编码进会话 ID（memoryId = bank:{identityId}:{uuid}），
 * 因此身份随会话产生：会话隔离、提示词注入、工具权限、会话列表过滤都从 memoryId 推导。
 *
 * 旧格式 memoryId（bank:{uuid}，两段）没有身份段，按 RETAIL 处理。
 */
public enum BankIdentity {

    RETAIL("zhangsan", "零售客户·张三", "零售客户",
            "62220001",
            "你好，我是银行助手小银，张三（62220001）的专属管家。可以试试：查一下我的余额，或看看最近的交易。",
            List.of("查一下我的余额", "看我最近的交易", "给李四转 200", "我今天还能转多少")),

    STAFF("staff001", "内部员工·客服专员小陈", "内部员工（工号 E1001）",
            null,
            "你好，我是银行员工助手小陈（工号 E1001）。可以查询任意客户的账户信息与全行概况，但不能操作资金。",
            List.of("查一下王五的账户", "全行目前有多少账户、总余额多少", "查 62220002 的最近交易")),

    CORPORATE("corp001", "对公客户·星辰科技", "对公客户",
            "82280001",
            "你好，我是星辰科技（对公账号 82280001）的账户管家。可以查企业账户余额、对公流水，也能发起对公转账。",
            List.of("查一下我们的账户余额", "看看最近的对公流水", "给王五转 50000"));

    private final String id;
    private final String displayName;
    private final String role;
    private final String boundAccount;
    private final String welcome;
    private final List<String> suggestions;

    BankIdentity(String id, String displayName, String role, String boundAccount,
                 String welcome, List<String> suggestions) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
        this.boundAccount = boundAccount;
        this.welcome = welcome;
        this.suggestions = suggestions;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String role() {
        return role;
    }

    /** 该身份绑定的主账号；员工无个人账户返回 null */
    public String boundAccount() {
        return boundAccount;
    }

    public String welcome() {
        return welcome;
    }

    public List<String> suggestions() {
        return suggestions;
    }

    /** 员工身份只读，不暴露任何资金操作工具 */
    public boolean isStaff() {
        return this == STAFF;
    }

    /** 是否允许发起转账（员工不允许） */
    public boolean canTransfer() {
        return this != STAFF;
    }

    /** 从 memoryId 解析身份：三段取中段，两段旧格式或未知值回退 RETAIL */
    public static BankIdentity fromMemoryId(String memoryId) {
        if (memoryId == null) {
            return RETAIL;
        }
        String[] parts = memoryId.split(":");
        if (parts.length == 3) {
            for (BankIdentity identity : values()) {
                if (identity.id.equals(parts[1])) {
                    return identity;
                }
            }
        }
        return RETAIL;
    }

    /** 前端身份选择器需要的展示元数据 */
    public record IdentityInfo(String id, String displayName, String role,
                               String welcome, List<String> suggestions) {
    }

    public IdentityInfo info() {
        return new IdentityInfo(id, displayName, role, welcome, suggestions);
    }
}