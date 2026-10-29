package com.ai.workbench.knowledge.admin;

import java.util.List;
import java.util.Map;

import com.ai.workbench.core.auth.CorePrincipal;
import com.ai.workbench.knowledge.admin.KbAdminQueryService.AdminDocView;
import com.ai.workbench.knowledge.admin.KbAdminQueryService.AuditItem;
import com.ai.workbench.knowledge.admin.KbAdminQueryService.BindingItem;
import com.ai.workbench.knowledge.admin.KbAdminQueryService.LibraryItem;
import com.ai.workbench.knowledge.admin.KbAdminQueryService.Overview;
import com.ai.workbench.knowledge.admin.KbAdminQueryService.PageResult;
import com.ai.workbench.knowledge.auth.KnowledgeUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人知识库管理后台接口（/api/kb/admin）。
 *
 * ADMIN 准入不在这里做：KnowledgeAuthInterceptor 对该前缀统一校验 platformRole
 * （拦截器是第一道闸，控制器不再重复判角色）。写操作打 info 日志留操作痕迹——
 * 管理员在治理他人的数据，出了争议要能回答「谁在什么时候动了哪篇文档」。
 *
 * 异常映射集中三类，给前端的永远是 {"message": ...}：
 *   IllegalArgumentException = 资源不存在/不可达 → 404；
 *   IllegalStateException   = 状态不允许该操作 → 400；
 *   其余一律 500 兜底通用文案，不把 SQL/堆栈细节漏给客户端。
 */
@RestController
@RequestMapping("/api/kb/admin")
public class KbAdminController {

    private static final Logger log = LoggerFactory.getLogger(KbAdminController.class);

    /** PUT /documents/{docId}/enabled 请求体 */
    public record EnableRequest(Boolean enabled) {
    }

    /** POST /bindings 请求体 */
    public record BindRequest(Long spaceId) {
    }

    private final KbAdminQueryService query;

    public KbAdminController(KbAdminQueryService query) {
        this.query = query;
    }

    @GetMapping("/overview")
    public Overview overview(@RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        return query.overview();
    }

    @GetMapping("/libraries")
    public PageResult<LibraryItem> libraries(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size,
                                             @RequestParam(defaultValue = "") String keyword,
                                             @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        return query.libraries(page, Math.min(size, 100), keyword);
    }

    @GetMapping("/libraries/{spaceId}/documents")
    public List<AdminDocView> documents(@PathVariable long spaceId,
                                        @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        return query.personalDocuments(spaceId);
    }

    @PutMapping("/documents/{docId}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable long docId,
                                                          @RequestBody(required = false) EnableRequest req,
                                                          @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        if (req == null || req.enabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请提供 enabled 参数"));
        }
        query.setDocEnabled(docId, req.enabled());
        log.info("kb-admin: {} {}文档 docId={}", user.username(), req.enabled() ? "启用" : "停用", docId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Map<String, Object>> deleteDoc(@PathVariable long docId,
                                                         @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        query.deletePersonalDoc(docId);
        log.info("kb-admin: {} 删除文档 docId={}", user.username(), docId);
        return ResponseEntity.ok(Map.of("ok", true, "message", "已删除并清理检索索引"));
    }

    @GetMapping("/bindings")
    public List<BindingItem> bindings(@RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        return query.bindings();
    }

    @PostMapping("/bindings")
    public ResponseEntity<Map<String, Object>> bind(@RequestBody(required = false) BindRequest req,
                                                    @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        if (req == null || req.spaceId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请指定要绑定的知识库空间"));
        }
        query.bind(req.spaceId());
        log.info("kb-admin: {} 绑定空间 spaceId={}", user.username(), req.spaceId());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/bindings/{spaceId}")
    public ResponseEntity<Map<String, Object>> unbind(@PathVariable long spaceId,
                                                      @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        query.unbind(spaceId);
        log.info("kb-admin: {} 解绑空间 spaceId={}", user.username(), spaceId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/audit")
    public PageResult<AuditItem> audit(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestAttribute(CorePrincipal.ATTR_PRINCIPAL) KnowledgeUser user) {
        return query.audit(page, Math.min(size, 100));
    }

    // ---------- 异常 → HTTP 映射 ----------

    /** 资源不存在/不可达（空间查无、文档查无等）：404 */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(404).body(Map.of("message", msg(e)));
    }

    /** 状态不允许该操作（个人库绑定、文档不属于个人库等）：400 */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalStateException e) {
        return ResponseEntity.badRequest().body(Map.of("message", msg(e)));
    }

    /** 兜底：未预期异常只给通用文案，内部细节进日志不进响应 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("kb-admin 接口异常", e);
        return ResponseEntity.status(500).body(Map.of("message", "服务器内部错误，请稍后重试"));
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
