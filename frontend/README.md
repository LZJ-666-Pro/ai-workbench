# ai-workbench frontend

Vue 3 + Vite + TypeScript 单页应用，**一个前端对接三个后端应用**。

## 启动

```bash
npm install
npm run dev      # http://localhost:5173
```

后端未启动时页面可正常打开，发消息会提示"连接失败"——先 `mvn spring-boot:run -pl app-bank`。

## 代理规则（vite.config.ts）

| 页面路由 | 代理键（API 前缀） | 代理到 |
|---|---|---|
| /bank | /bank/api/* | http://localhost:8081 |
| /knowledge | /knowledge/api/* | http://localhost:8082 |
| /interview | /interview/api/* | http://localhost:8083 |

例：页面请求 `/bank/api/chat/bank/stream` → 后端收到 `/api/chat/bank/stream`。
注意代理键必须收窄到 `/xxx/api`，否则会把 SPA 页面路由 `/bank` 一并劫持去转发。
开发期靠代理转发免 CORS；Phase 4 部署时把这三条规则换成网关（Nginx / Spring Cloud Gateway）。

## 结构

```
src/
├── api/chat.ts            # SSE 流式客户端（fetch + ReadableStream 解析帧）
├── components/
│   └── ChatWindow.vue     # 通用聊天组件：流式渲染/记忆隔离(localStorage)/新建会话/中断
├── views/                 # 三个 Agent 页面 + 目录页（都是 ChatWindow 换 props）
└── router/index.ts
```

**新增一个 Agent 页面 = 新建一个 View 传不同 props，零新逻辑。**

## 常用命令

```bash
npm run dev        # 开发
npm run build      # 产物输出 dist/
npm run preview    # 本地预览产物
npm run typecheck  # vue-tsc 类型检查
```
