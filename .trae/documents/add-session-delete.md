# 会话删除功能实现计划

## Context

银行助手侧栏的每条会话需要支持删除（参考 DeepSeek 交互）：hover 会话项出现 "…" 按钮，点开菜单选择"删除"，删除前二次确认。

数据现状：一个会话 = `chat_memory` 表一行（memory_id 主键，content 存消息 JSON）。[MysqlChatMemoryStore.deleteMessages](file:///d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/memory/MysqlChatMemoryStore.java#L46-L48) 已实现按 memoryId 删除，但未暴露 HTTP 接口。[SessionController](file:///d:/download/project/ai-workbench/platform-core/src/main/java/com/ai/workbench/core/api/SessionController.java) 目前只有 GET 列表接口。

用户已确认：DeepSeek 式 "…" 菜单 + 二次确认弹窗。菜单里只放"删除"一项（重命名/置顶等不在本次需求内）。

## 改动

### 1. 后端：SessionController 加删除接口（platform-core，所有应用共用）

文件：`platform-core/src/main/java/com/ai/workbench/core/api/SessionController.java`

新增（复用已有的 JdbcTemplate 字段）：

```java
/** 删除指定会话（memoryId 必须以 {agent}: 开头，防止跨应用误删） */
@DeleteMapping("/{agent}/{memoryId}")
public DeleteResult deleteSession(@PathVariable String agent, @PathVariable String memoryId) {
    if (!memoryId.startsWith(agent + ":")) {
        return new DeleteResult(false, "无效的会话 ID");
    }
    jdbc.update("DELETE FROM chat_memory WHERE memory_id = ?", memoryId);
    return new DeleteResult(true, "已删除");
}

public record DeleteResult(boolean ok, String message) {}
```

需要补 import：`org.springframework.web.bind.annotation.DeleteMapping`。

### 2. 前端：chat.ts 加删除 API

文件：`frontend/src/api/chat.ts`（放在 listSessions 附近）

```ts
/** 删除指定会话（DB 正本 + 前端需自行清理本地缓存） */
export async function deleteSession(basePath: string, agent: string, memoryId: string): Promise<void> {
  const resp = await fetch(`${basePath}/api/sessions/${agent}/${encodeURIComponent(memoryId)}`, { method: 'DELETE' })
  if (!resp.ok) {
    throw new Error(`删除会话失败: HTTP ${resp.status}`)
  }
}
```

### 3. 前端：ChatWindow.vue 菜单 + 确认弹窗 + 删除逻辑

文件：`frontend/src/components/ChatWindow.vue`

**状态**（script setup）：
- `menuFor = ref<string | null>(null)` — 当前展开 "…" 菜单的会话 memoryId
- `pendingDelete = ref<SessionItem | null>(null)` — 确认弹窗目标

**模板**（session-item 内，约 L300-L311）：
- 会话项内加 `<button class="session-more" @click.stop="menuFor = ...">…</button>`（hover 时显示，始终占位避免布局跳动）
- `menuFor === session.memoryId` 时渲染下拉菜单：一项红色"删除"（`@click.stop="pendingDelete = session; menuFor = null"`）
- 菜单展开时渲染透明全屏遮罩层（点击关闭菜单，z-index 低于菜单）
- 页面底部加确认模态框：文案"删除后聊天记录将不可恢复"，按钮"取消" / "删除"（红色）

**删除逻辑**：

```ts
async function removeSession(sessionId: string) {
  pendingDelete.value = null
  try {
    await deleteSession(props.basePath, props.agent, sessionId)
  } catch { /* 后端不可用也继续清本地 */ }
  // 本地清理：列表项、消息缓存、会话名
  sessions.value = sessions.value.filter(s => s.memoryId !== sessionId)
  localStorage.removeItem(`aiwb-msgs-${props.agent}-${sessionId}`)
  const labels = loadLabelMap()
  delete labels[sessionId]
  localStorage.setItem(`aiwb-labels-${props.agent}`, JSON.stringify(labels))
  cacheSessions(props.agent, sessions.value)
  // 删除的是当前会话：切到列表第一条，没有则新开
  if (memoryId === sessionId) {
    const next = sessions.value[0]
    if (next) await switchSession(next.memoryId)
    else newSession()
  }
}
```

**CSS**（style 区，沿用现有配色变量）：
- `.session-item` 改 flex 布局容纳 "…" 按钮；`.session-more` 默认半透明，hover 时显现
- `.session-menu` 下拉菜单（白底、阴影、圆角，与现有 .new-chat 风格一致），删除项红色
- `.confirm-mask` + `.confirm-dialog` 简易模态

## 验证

1. 类型检查：`npx.cmd vue-tsc --noEmit`（frontend 目录，沙箱外）
2. 重启服务：MySQL 容器已在跑；后台启动 `mvn -pl app-bank -am install -DskipTests` 后 `mvn -pl app-bank spring-boot:run`（注意需先 install platform-core 让新接口生效）+ `npm run dev`（Vite）
3. 浏览器（TRAE-browseruse）验证：
   - hover 会话出现 "…"，点开菜单显示"删除"
   - 删除一条非当前会话 → 列表移除，`GET /bank/api/sessions/bank` 确认 DB 已删
   - 删除当前会话 → 自动切到下一条（或空列表时新建）
   - 点其他区域菜单关闭；取消弹窗不删除
4. 完成后询问用户：服务保留还是关闭

## 提交

验证通过后经用户确认提交（提交信息风格沿用 `feat(frontend): ...`，本次涉及前后端，用 `feat: 会话删除功能（DeepSeek 式菜单 + 二次确认）`）
