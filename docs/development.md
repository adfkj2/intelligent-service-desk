# 开发与本地运行

面向改代码的人。只想把系统跑起来演示，请直接看 [README「三步启动」](../README.md#三步启动)。

## 环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 后端编译与测试 |
| MySQL | 8.0 | 本地默认 3306；也可全部走 Docker |
| Redis | 7.x | 会话与限流，默认 `127.0.0.1:6379` |
| Node | 18+ / 20 LTS | 只有改前端或跑 E2E 时需要 |
| Docker Desktop | 近期版本 | Testcontainers、compose、镜像构建 |
| Maven | 不需要安装 | 仓库自带 wrapper，锁定 3.9.16 |

```bash
docker compose up -d          # 只起 Redis（本地开发用）
docker version                # 没有 Docker 时集成测试会回退本机 MySQL/Redis
```

> 会话不在 Tomcat 内存里，而是 Spring Session + Redis。**Redis 不可用时应用直接启动失败**——刻意不做静默降级，否则会出现“本地能跑、多实例踢人失效”的假象。

## 数据库与迁移

Flyway 在应用启动时自动执行 `src/main/resources/db/migration/V*.sql`：

- 新环境：JDBC URL 带 `createDatabaseIfNotExist=true`，自动建库建表；
- 想手工建库：执行 `src/main/resources/db/bootstrap.sql`；
- 老库：首次启动以 `baseline-version: 1` 打基线，不会重复建表，V2 之后的增量迁移照常执行；
- 改结构：新增 `V14__xxx.sql`，**不要改已执行过的旧脚本**（校验和会变，Flyway 拒绝启动）。

当前 13 个迁移、24 张业务表：

| 版本 | 内容 |
|---|---|
| `V1__init_schema.sql` | `sys_user`、`ticket`、`ticket_operation_log`、`kb_category`、`kb_item` |
| `V2__ticket_idempotency.sql` | 创建工单幂等键与唯一索引 |
| `V3__kb_fulltext_index.sql` | `kb_item` FULLTEXT ngram 索引（RAG 召回） |
| `V4__ai_chat_log.sql` | AI 问答审计 |
| `V5__outbox_and_notification.sql` | Transactional Outbox + 站内信 |
| `V6__retention_indexes.sql` | 数据保留清理索引 |
| `V7__ticket_collaboration.sql` | 回复、附件、技能、时限提醒去重、协作字段 |
| `V8__ai_conversations.sql` | AI 会话、消息与回答反馈 |
| `V9__knowledge_review_workflow.sql` | 知识草稿、适用环境、审核与反馈 |
| `V10__ai_conversation_retention.sql` | AI 会话保留清理 |
| `V11__ai_suggestion_metrics.sql` | 建议采纳率口径 |
| `V12__ai_provider_configuration.sql` | 加密供应商 Key、配置版本、当前服务、余额缓存 |
| `V13__ai_usage_ledger.sql` | AI 用量与上游请求台账、价格快照、费用估算 |

## 本机配置

复制 `src/main/resources/application-local.yml.example` 为 `application-local.yml`：

```yaml
spring:
  datasource:
    password: "你的 MySQL 密码"
  ai:
    openai:
      api-key: "sk-你的 Key"
```

`application-local.yml` 已被 `.gitignore` 排除。也可用环境变量 `DEEPSEEK_API_KEY` 覆盖。不填 Key 也能启动，AI 接口会走知识库降级（`source=FALLBACK`）。

## 前端构建

前端是 `web/` 下的独立 Vite 工程，产物输出到 `src/main/resources/static`，最终仍是一个 jar。`static/` 已 git 忽略，**新克隆仓库必须先构建前端**，否则后端只有接口没有页面。

```bash
cd web
npm ci
npm run build          # 输出到 ../src/main/resources/static
npm run dev            # 可选：5173，/api 代理到 8081

npm run lint
npm test
npm run test:coverage
```

开发期浏览器只看到 `http://localhost:5173` 一个源，Session Cookie 天然同源，Spring 侧不需要 CORS，前端也不把 token 存 localStorage。

## 启动

```bash
docker compose up -d                 # Redis
.\mvnw.cmd -DskipTests package       # Windows；macOS/Linux 用 ./mvnw
java -jar target/isd-1.1.0.jar
```

端口 **8081**（8080 常被本机 Apache 占用）。启动后打开 <http://localhost:8081/>。

```bash
curl http://localhost:8081/api/ping
# {"code":0,"message":"OK","data":{"status":"UP","service":"isd","time":"..."}}
```

国内网络下载 Maven 依赖慢时，用环境变量换源，不必改仓库文件：

```powershell
$env:MVNW_REPOURL="https://maven.aliyun.com/repository/public"
.\mvnw.cmd verify
```

## 测试

```bash
./mvnw test      # 只跑测试
./mvnw verify    # 测试 + JaCoCo 门禁 + 打包
```

覆盖率门禁为行覆盖 **≥ 90%**，不达标 `verify` 直接失败；报告在 `target/site/jacoco/index.html`。

测试依赖有两种来源（`AbstractIntegrationTest`）：有 Docker 时启 `mysql:8.0.36` + `redis:7.4-alpine` 容器；没有则回退本机 MySQL80 与 Redis 6379。强制走本机服务：`ISD_TEST_NO_DOCKER=true`。

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `TicketApiTest` | 12 | 创建/详情/分页/统计、参数校验、401/404/405/415 |
| `TicketWorkflowTest` | 12 | 状态机全流程、审计日志、越权 403/404、指派、筛选分页 |
| `TicketConcurrencyTest` | 1 | 双线程同时接单：恰好一个成功，`version` 只加 1 |
| `TicketIdempotencyTest` | 5 | 同 key 重复提交、无 key 旧行为、跨用户隔离、并发兜底 |
| `AuthFlowTest` | 13 | 注册登录注销、禁用账号、ADMIN 权限、最后一个管理员保护 |
| `PasswordFlowTest` | 6 | 重置密码、临时密码强制改密、公开联系方式 |
| `LoginRateLimitTest` | 3 | 账号/IP 双维度锁定与清零 |
| `UserRoleConcurrencyTest` | 2 | 并发改角色、过期 version 被拒 |
| `SchemaMigrationTest` | 1 | Flyway 已应用 V1~V13、表与索引齐全 |
| `ApiInventoryTest` | 1 | 69 个接口与快照逐行比对 |
| `NotificationApiTest` / `NotificationRelayTest` | 9 | 通知权限、幂等、坏 payload 重试上限 |
| `TraceIdFilterTest` / `MetricsApiTest` | 4 | traceId 透传与指标差值 |
| `AiRateLimitTest` | 3 | 用户/IP 双维度配额 |
| `DataRetentionTest` | 4 | 过期审计、会话、事件、已读通知清理 |
| `KbApiTest` | 7 | 分类/条目 CRUD、归属权限、检索、通配符转义 |
| `AiAgentTest` / `AiNotConfiguredTest` / `AiTimeoutTest` | 7 | 正常/异常/空回答/超时/未配置降级 |
| `AiToolsTest` / `AiRagTest` / `AgentSnippetTest` | 13 | 只读工具、RAG 元数据、复核提示、片段提取 |
| `SessionRedisTest` / `CsrfProtectionTest` / `SecurityHeadersTest` | 11 | Redis 会话、CSRF 双提交、CSP 开关 |
| `TicketCollaborationServiceTest` / `TicketCollaborationApiTest` | 26 | 协作权限、时限暂停、附件、转派、队列 |
| `AiWorkspaceApiTest` / `AiSuggestionAuditTest` | 14 | 多轮与角色隔离、上下文权限、采纳率口径 |
| `KnowledgeWorkflowTest` | 7 | 草稿隔离、并发发布、版本冲突、反馈 |
| `AccessChangeSessionTest` / `UserAccessChangeTransactionTest` | 6 | 权限失效、会话隔离、事务回滚 |
| `BusinessTimeTest` | 1 | 业务时间不随宿主时区变化 |

> 并发用例刻意不加 `@Transactional`：事务是连接级的，两个线程各自提交才能测出真实冲突。
> `AiRagTest` 也不能加 `@Transactional`：InnoDB 全文索引只在提交后可见，否则永远是“召回为空”的假阴性。

浏览器 E2E 打 prod 栈（https + Secure Cookie），见 [README「可靠性与边界」](../README.md#可靠性与边界) 或 `web/e2e/`。

## 创建技术支持 / 管理员

注册接口固定给 EMPLOYEE（防提权）。新账号注册后由已有管理员在「用户管理」页调整角色；角色变更或禁用会立即失效旧会话。

第一次从零启动时还没有管理员，先注册再 SQL 提权：

```sql
-- POST /api/auth/register {"username":"demo_admin","password":"admin12345"}
UPDATE sys_user SET role = 'ADMIN' WHERE username = 'demo_admin';
```

系统拒绝降级/禁用最后一个启用状态的管理员；两个管理员并发改同一账号时，后到的一方收到 409。

## 项目结构

```
src/main/java/com/isd/
├── common/        SecurityConfig、OpenApiConfig、指标、异常、统一响应
├── auth/          认证、RBAC、密码重置、会话
├── ticket/        工单状态机、协作、SLA、附件
├── kb/            知识库、草稿审核、全文检索
└── ai/            LLM 适配、只读工具、会话、供应商配置、用量台账

src/main/resources/
├── db/migration/  Flyway V1–V13
├── db/bootstrap.sql
└── static/        前端构建产物（git 忽略）

web/
├── src/api/       axios 实例（解包 data、统一 401/403/409）
├── src/router/    history 路由 + 登录守卫 + 角色路由
├── src/views/     登录、工作台、工单、知识库、用户管理、AI 配置
├── e2e/           Playwright 用例 + 截图/演示脚本
└── tests/         Vitest 单测
```

分层职责：Controller 只做路由与参数校验；Service 承担业务规则、事务边界、状态机与权限；Mapper 只写 SQL；前端只做展示与交互，权限判断一律以后端为准。

## 常见问题

| 现象 | 处理 |
|---|---|
| 页面 404 或只有接口 | 先 `cd web && npm run build`，再重启 |
| 应用启动失败，日志提示 Redis | 起 Redis：`docker compose up -d`；不提供内存降级 |
| Maven 依赖下载慢 | 设置 `MVNW_REPOURL` 或用 compose 的 `MAVEN_MIRROR_URL` |
| 8081 被占用 | `demo.cmd -Port 18081` 或改 `ISD_HTTP_PORT` |
| 演示库想重置 | `docker compose -f docker-compose.stack.yml down -v`（会删数据） |
| 测试连错库 | 设 `ISD_TEST_NO_DOCKER=true` 走本机，或检查 Docker 是否运行 |

接口调试：启动后打开 <http://localhost:8081/swagger-ui.html>，先调 `POST /api/auth/login`，浏览器自动带 `SESSION` Cookie。
