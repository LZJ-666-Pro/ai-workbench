package com.ai.workbench.bank.api;

import java.util.List;

import com.ai.workbench.bank.identity.BankIdentity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份选择接口：返回银行助手可切换的服务对象，供前端渲染身份选择器数据源。
 * 前端不改写身份列表，新增身份只改 BankIdentity 枚举与提示词。
 */
@RestController
@RequestMapping("/api/bank")
public class BankIdentityController {

    @GetMapping("/identities")
    public List<BankIdentity.IdentityInfo> identities() {
        return java.util.Arrays.stream(BankIdentity.values())
                .map(BankIdentity::info)
                .toList();
    }
}