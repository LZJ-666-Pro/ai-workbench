package com.ai.workbench.bank.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 转账风控参数（application.yml 的 bank.risk 块）。
 *
 * 限额与黑名单原先是硬编码常量，调整一次要改代码、发版、重启；
 * 挪进配置后运维可以直接在 yml 里调参。默认值与原常量保持一致，
 * yml 不写这组配置时行为不变（对老库/演示环境零影响）。
 *
 * 注意：黑名单用 CopyOnWriteArraySet——Spring 绑定 List 后转存，
 * 运行时只读访问无需加锁；每次绑定替换整个集合引用，不存在部分更新。
 */
@ConfigurationProperties(prefix = "bank.risk")
public class TransferRiskProperties {

    /** 个人客户单笔限额（元） */
    private BigDecimal retailSingleLimit = new BigDecimal("5000.00");

    /** 个人客户单日累计限额（元） */
    private BigDecimal retailDailyLimit = new BigDecimal("10000.00");

    /** 对公客户单笔限额（元） */
    private BigDecimal corporateSingleLimit = new BigDecimal("500000.00");

    /** 对公客户单日累计限额（元） */
    private BigDecimal corporateDailyLimit = new BigDecimal("2000000.00");

    /** 收款黑名单（命中直接拒绝） */
    private Set<String> blacklist = new CopyOnWriteArraySet<>(List.of("62220004"));

    public BigDecimal getRetailSingleLimit() {
        return retailSingleLimit;
    }

    public void setRetailSingleLimit(BigDecimal retailSingleLimit) {
        this.retailSingleLimit = retailSingleLimit;
    }

    public BigDecimal getRetailDailyLimit() {
        return retailDailyLimit;
    }

    public void setRetailDailyLimit(BigDecimal retailDailyLimit) {
        this.retailDailyLimit = retailDailyLimit;
    }

    public BigDecimal getCorporateSingleLimit() {
        return corporateSingleLimit;
    }

    public void setCorporateSingleLimit(BigDecimal corporateSingleLimit) {
        this.corporateSingleLimit = corporateSingleLimit;
    }

    public BigDecimal getCorporateDailyLimit() {
        return corporateDailyLimit;
    }

    public void setCorporateDailyLimit(BigDecimal corporateDailyLimit) {
        this.corporateDailyLimit = corporateDailyLimit;
    }

    public Set<String> getBlacklist() {
        return blacklist;
    }

    public void setBlacklist(List<String> blacklist) {
        this.blacklist = new CopyOnWriteArraySet<>(blacklist);
    }
}
