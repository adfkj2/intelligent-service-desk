# Intelligent Service Desk (ISD)

[![CI](https://github.com/adfkj2/intelligent-service-desk/actions/workflows/ci.yml/badge.svg)](https://github.com/adfkj2/intelligent-service-desk/actions/workflows/ci.yml)

企业 IT 智能服务台：工单全流程管理 + 知识库 + 基于真实数据的只读 AI 助手。

面向"企业内网 IT 支持"场景：员工提交报障工单，技术支持接单处理，管理员指派与管人，AI 助手基于**当前登录用户自己的数据**回答问题，不执行任何写操作。

---

## 功能

| 模块 | 能力 |
|---|---|
| 认证与权限 | 注册 / 登录 / 注销（Session Cookie）、BCrypt 密码哈希、三角色 RBAC（EMPLOYEE / SUPPORT / ADMIN）、资源级访问控制、**会话外置 Redis（多实例踢人一致）** |
| 工单 | 创建（**Idempotency-Key 幂等**）、分页多条件查询、详情、接单、指派、状态流转、操作历史（审计）、**并发接单保护（乐观锁）** |
| 知识库 | 分类管理、条目 CRUD（创建者本人或管理员才能改删）、关键词检索、**FULLTEXT ngram 全文检索**、分页列表 |
| AI 助手 | DeepSeek（OpenAI 兼容协议）+ Function Calling，两个只读工具：查当前用户工单、检索知识库；**RAG：先全文召回再拼提示词**；超时/异常自动降级为知识库检索；**每次问答落审计表** |
| 管理台 | Vue 3 + Vite 桌面前端：工作台统计、工单列表/详情/新建、知识库、用户管理、全局 AI 抽屉；按角色显示菜单，构建产物打进同一个 jar |
| 工程 | 统一响应与全局异常、参数校验、Springdoc 在线接口文档、Flyway 版本化迁移、Testcontainers 测试库、GitHub Actions、Micrometer 指标与 traceId 日志、**登录失败限流（Redis）**、82 个自动化测试（含并发冲突与幂等用例） |

---

## 技术栈

| 组件 | 版本 / 选择 | 说明 |
|---|---|---|
| Java | 21 | LTS |
| Spring Boot | 3.5.16 | Web / Validation / Security / Test |
| Spring AI | 1.1.0 | BOM 管理，OpenAI starter 接 DeepSeek |
| MyBatis | 3.0.5 | 注解 SQL + 动态 `<script>`，复杂 SQL 走 XML |
| MySQL | 8.0 | utf8mb4，InnoDB |
| 可观测性 | Micrometer + Prometheus | Actuator 指标端点在独立 8082，日志带 `traceId` |
| Flyway | 随 Boot | `db/migration/V*.sql` 版本化迁移，启动自动升级；老库靠 baseline 兼容 |
| Testcontainers | 随 Boot | 跑测试时起一次性 MySQL 容器，没有 Docker 自动回退本机库 |
| Spring Security | 随 Boot | Session 认证 + URL 级/资源级授权 |
| Spring Session | 随 Boot（3.5.x） | 会话存 Redis，`indexed` 仓库维护 principal 索引 |
| Redis | 7.4（`docker compose`） | 会话共享存储；本地容器名 `isd-redis` |
| springdoc-openapi | 2.8.9 | Swagger UI |
| 前端 | Vue 3.5 + Vite 5 + Element Plus 2.9 + Pinia + Tailwind | 独立 `web/` 工程，`npm run build` 产出到 `src/main/resources/static` |
| 测试 | JUnit 5 + MockMvc + AssertJ | 82 个用例 |

---

## 快速开始

### 1. 环境

- JDK 21
- MySQL 8（本地 3306，服务名 `MySQL80`）
- **Redis 7（会话存储，默认 `127.0.0.1:6379`）**：仓库根目录 `docker compose up -d` 一键起
- Maven 3.9（也可直接用 IDEA 内置 Maven）
- Node 18+（**只有改前端时才需要**；只想跑现成页面可跳过）
- Docker Desktop（跑测试时用来起 MySQL / Redis 容器；没有就自动回退本机服务）

> 会话不再放在 Tomcat 进程内存，而是 Spring Session + Redis。**Redis 不可用时应用会直接启动失败**——
> 这是刻意的：不做静默降级，否则会出现「本地能跑、线上多实例踢人失效」的假象。

### 2. 建库建表

表结构由 **Flyway** 版本化管理，脚本在 `src/main/resources/db/migration/`，应用启动时自动执行：

- 新环境：JDBC URL 带 `createDatabaseIfNotExist=true`，连上即自动建库 + 建表，不用手工跑 SQL；
- 想手工建库：执行 `src/main/resources/db/bootstrap.sql`（只有 `CREATE DATABASE`）；
- 老库：首次启动以 `baseline-version: 1` 打基线，不会重复建表，也不影响已有数据；V2 之后的增量迁移会照常执行；
- 改结构：新增 `V2__xxx.sql` 之类的迁移，**不要改已执行过的旧脚本**（校验和会变，Flyway 会拒绝启动）。

现有 4 个迁移、6 张业务表：

| 版本 | 内容 |
|---|---|
| `V1__init_schema.sql` | `sys_user`、`ticket`、`ticket_operation_log`、`kb_category`、`kb_item` |
| `V2__ticket_idempotency.sql` | `ticket.idempotency_key` + 唯一索引 `uk_creator_idempotency`（创建工单幂等） |
| `V3__kb_fulltext_index.sql` | `kb_item` 的 FULLTEXT 索引（ngram 分词，供 RAG 召回） |
| `V4__ai_chat_log.sql` | `ai_chat_log`（AI 问答审计） |

外加 Flyway 自己的 `flyway_schema_history`。

### 3. 本机配置

复制 `src/main/resources/application-local.yml.example` 为 `application-local.yml`，填入本机数据库密码与 LLM Key：

```yaml
spring:
  datasource:
    password: "你的 MySQL 密码"
  ai:
    openai:
      api-key: "sk-你的 DeepSeek Key"
```

`application-local.yml` 已被 `.gitignore` 排除，不会进仓库。也可以用环境变量 `DEEPSEEK_API_KEY` 覆盖。

> 不填 Key 也能启动：`AgentService` 检测到占位 Key 会直接走知识库降级，AI 接口返回 `source=FALLBACK`。

### 4. 前端构建（改前端才需要）

前端是 `web/` 下的独立 Vite 工程，构建产物输出到 `src/main/resources/static`，最终仍是一个 jar。`src/main/resources/static/` 已加入 `.gitignore`，**新克隆仓库必须先构建前端**，否则后端只有接口、没有页面。

```bash
cd web
npm install
npm run build            # 输出到 ../src/main/resources/static

# 开发模式（可选）：5173 端口，/api 由 Vite 代理到 8081，改代码热更新
npm run dev
```

开发期浏览器只看到 `http://localhost:5173` 一个源，`JSESSIONID` 因此是同源 Cookie：**Spring 侧不需要任何 CORS 配置，前端也不把 token 存 localStorage**。

### 5. 启动

```bash
# 0. 先起 Redis（会话存储，只需第一次）
docker compose up -d

# IDEA：直接运行 com.isd.IsdApplication
# 命令行：
mvn -DskipTests package
java -jar target/isd-0.0.1-SNAPSHOT.jar
```

端口 **8081**（8080 被本机 Apache 占用）。

```bash
curl http://localhost:8081/api/ping
# {"code":0,"message":"OK","data":{"status":"UP","service":"isd","time":"..."}}
```

启动后浏览器打开 <http://localhost:8081/> 即进入管理台登录页。

### 6. 接口文档

启动后打开 <http://localhost:8081/swagger-ui.html>。

用 Session 认证，所以在 Swagger 里先调 `POST /api/auth/login`，浏览器会自动带上 `JSESSIONID`，其余接口直接可用。

### 7. 创建技术支持 / 管理员

注册接口固定给 EMPLOYEE 角色（防提权）。新账号注册后，由**已有管理员**在「用户管理」页点角色列的「调整」，选择技术支持或管理员即可；对方重新登录后生效。

第一次从零启动时还没有管理员，先用 SQL 提权一个（之后就可以全在页面上操作）：

```sql
-- 先注册账号：POST /api/auth/register {"username":"demo_admin","password":"admin12345"}
UPDATE sys_user SET role = 'ADMIN' WHERE username = 'demo_admin';
-- 角色变更后必须重新登录（身份在登录时读入 Session）
```

管理员不能修改自己的角色，避免误操作把自己降权后失去管理入口；系统也会拒绝降级/禁用**最后一个启用状态的管理员**。两个管理员同时操作同一账号时，后到的一方会收到 409「已被其他管理员修改」，页面自动刷新后重试即可。

完整演示步骤见 [docs/demo-script.md](docs/demo-script.md)。

---

## 界面与角色

三个角色看到的是同一套页面，菜单与操作按钮按角色裁剪（**前端隐藏只是体验，数据范围始终由后端 Service 决定**）：

| 页面 | 路由 | 员工 | 技术支持 | 管理员 |
|---|---|:---:|:---:|:---:|
| 登录 / 注册（含“忘记密码”管理员联系方式） | `/login` | ✓ | ✓ | ✓ |
| 修改密码（重置后强制进入） | `/change-password` | ✓ | ✓ | ✓ |
| 工作台（4 张状态卡 + 最近工单） | `/` | ✓ 仅本人 | ✓ 全量 | ✓ 全量 |
| 工单列表（筛选 + 服务端分页） | `/tickets` | ✓ 仅本人 | ✓ 工单池 | ✓ 工单池 |
| 新建工单 | `/tickets/create` | ✓ | ✓ | ✓ |
| 工单详情（时间线 + 接单/指派/解决/关闭） | `/tickets/:id` | ✓ | ✓ | ✓ |
| 知识库（分类 + 检索 + 编辑） | `/kb` | 只读 | ✓ 维护自己的条目 | ✓ 全量 + 建分类 |
| 用户管理（启用/禁用、调整角色、重置密码） | `/admin/users` | — | — | ✓ |
| AI 助手（右下角抽屉，任意页面） | — | ✓ | ✓ | ✓ |

演示账号（首次准备见 [docs/demo-script.md](docs/demo-script.md)）：

| 账号 | 密码 | 角色 |
|---|---|---|
| `demo_emp` / `demo_emp2` | `employee123` | EMPLOYEE |
| `demo_sup` | `support123` | SUPPORT |
| `demo_admin` | `admin12345` | ADMIN |

### 忘记密码与强制改密

1. 管理员在右上角「个人资料」填写手机号/邮箱；登录页「忘记密码？」每次打开都会实时拉取**启用中管理员**的联系方式，降权或禁用后自动消失。
2. 员工联系管理员后，管理员在「用户管理 → 重置密码」生成一次性临时密码。该密码只在弹窗里展示一次，对方已有会话立即失效。
3. 员工用临时密码登录后只能进入 `/change-password`；服务端同时拦截其他 `/api/**`，改完密码才能正常使用。

---

## 接口一览

统一响应：`{"code": 0, "message": "OK", "data": ...}`，HTTP 状态码保留 2xx/400/401/403/404/409/429/500 语义。

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 认证 | POST | `/api/auth/register` | 公开 |
| 认证 | POST | `/api/auth/login` | 公开（失败 5 次锁 15 分钟） |
| 认证 | POST | `/api/auth/logout` | 登录 |
| 认证 | GET | `/api/auth/me` | 登录 |
| 认证 | GET | `/api/auth/admin-contacts` | 公开（仅启用管理员） |
| 认证 | PUT | `/api/auth/me/contact` | 登录（仅改自己） |
| 认证 | POST | `/api/auth/change-password` | 登录 |
| 工单 | POST | `/api/tickets`（可选头 `Idempotency-Key`） | 登录 |
| 工单 | GET | `/api/tickets?page=&size=&status=&category=&keyword=` | 登录（员工仅自己） |
| 工单 | GET | `/api/tickets/{id}` | 登录（资源级校验） |
| 工单 | GET | `/api/tickets/stats/{status}` | 登录 |
| 工单 | POST | `/api/tickets/{id}/claim` | SUPPORT |
| 工单 | POST | `/api/tickets/{id}/assign` | ADMIN |
| 工单 | PATCH | `/api/tickets/{id}/status` | 处理人/创建者/ADMIN |
| 工单 | GET | `/api/tickets/{id}/operations` | 登录（资源级校验） |
| 知识库 | GET | `/api/kb/categories` | 登录 |
| 知识库 | POST | `/api/kb/categories` | ADMIN |
| 知识库 | GET | `/api/kb/items?categoryId=&page=&size=` | 登录 |
| 知识库 | POST | `/api/kb/items` | SUPPORT / ADMIN |
| 知识库 | GET | `/api/kb/items/{id}` | 登录 |
| 知识库 | PUT | `/api/kb/items/{id}` | 创建者 / ADMIN |
| 知识库 | DELETE | `/api/kb/items/{id}` | 创建者 / ADMIN |
| 知识库 | GET | `/api/kb/search?q=&page=&size=` | 登录 |
| AI | POST | `/api/ai/chat` | 登录 |
| 用户管理 | GET | `/api/admin/users` | ADMIN |
| 用户管理 | PATCH | `/api/admin/users/{id}/status` | ADMIN |
| 用户管理 | PATCH | `/api/admin/users/{id}/role` | ADMIN |
| 用户管理 | POST | `/api/admin/users/{id}/password/reset` | ADMIN（不能重置自己） |

---

## 可观测性

指标端点单独开在 **8082**（只绑 `127.0.0.1`），8081 完全不暴露 actuator，业务接口的鉴权规则与 SPA 白名单都不用改：

```bash
curl http://127.0.0.1:8082/actuator/health
curl http://127.0.0.1:8082/actuator/prometheus | findstr isd_
```

| 指标 | 类型 | 标签 | 回答什么 |
|---|---|---|---|
| `http_server_requests_seconds` | Timer（Starter 自带） | uri/method/status/outcome | 全站 P95、5xx 率；uri 是模板路径，基数安全 |
| `isd_ai_chat_seconds` | Timer | result = success/timeout/error/empty/not_configured | AI 慢不慢、降级多不多 |
| `isd_ai_tool_calls_seconds` | Timer | tool × result = success/empty/error | 工具层失败率——框架会吞掉工具异常，端到端指标看不出来 |
| `isd_ai_retrieval_total` | Counter | result = hit/miss/error | RAG 召回命中率：长期偏低说明缺语料或分词不对，不是模型不会答 |
| `isd_ticket_transitions_total` | Counter | action/from/to | 状态机流量画像 |
| `isd_ticket_conflicts_total` | Counter | action = CLAIM/ASSIGN/STATUS | 乐观锁冲突，证明并发保护真的在工作 |
| `isd_ticket_idempotent_replays_total` | Counter | action = CREATE_TICKET | 幂等重放次数：不为 0 说明前端重试/重复点击真实发生 |
| `isd_auth_login_total` | Counter | result = success/bad_credentials/disabled/locked | 登录失败率、撞库与被锁定的信号 |
| `isd_auth_session_revoked_total` | Counter | reason = password_reset/password_changed | 踢人是否生效 |
| `isd_auth_forbidden_total` | Counter | layer = url/method/resource | 越权探测 |
| `isd_sessions_started_total` | Counter | 无 | 新建会话数（SessionCreatedEvent） |
| `isd_sessions_ended_total` | Counter | reason = deleted/expired | 结束的会话数；在线数在查询侧用 started - ended 推导 |
| `isd_ai_executor_*` | Gauge | 无 | AI 线程池队列长度与活跃线程 |

两条口径约定：**比率一律在查询侧算**（冲突率 = `conflicts / (transitions + conflicts)`），不存比率；**标签只放有限枚举**，用户名、工单号只进日志。

> 为什么没有 `isd_sessions_active` 这个 Gauge：会话存在 Redis，进程内没有可枚举的 SessionRegistry
> （Spring Session 的 `getAllPrincipals()` 直接抛 `UnsupportedOperationException`），要拿精确值只能
> `SCAN` 全键空间，那是在指标抓取路径上做 O(N) 阻塞。改成 started/ended 两个累计量后，
> 在线数在 Prometheus 侧算差值即可。**已知误差**：`reason=expired` 依赖 Redis keyspace 事件通知
> （`notify-keyspace-events Egx`，compose 文件已开），没开时过期不会产生事件，差值会偏高。

日志全部带 `traceId`：请求带 `X-Request-Id` 就沿用，没带就生成 8 位并回写响应头。AI 调用跑在 `ai-call-*` 线程池里，`TaskDecorator` 会把 MDC 与 SecurityContext 一并带过去，所以一次对话的 Controller → Service → Mapper → 模型调用可以按同一个 id 串起来。

---

## 项目结构

```
src/main/java/com/isd/
├── IsdApplication.java
├── common/
│   ├── config/        SecurityConfig（过滤器链）、OpenApiConfig、AiConfig（线程池 + ChatClient）
│   ├── metrics/       IsdMetrics（业务指标唯一出口）、MetricsConfig（活跃会话 Gauge）
│   ├── exception/     BusinessException、ErrorCode、GlobalExceptionHandler
│   ├── result/        ApiResponse、PageResponse
│   └── web/           PingController、SpaForwardController（history 路由回退）
├── auth/              模块 A：entity / mapper / dto / security / service / controller
├── ticket/            模块 B：entity / domain（状态机）/ mapper / dto / service / controller
├── kb/                模块 C：entity / mapper / dto / service / controller
└── ai/                模块 D：client（LLM 适配器）/ tools（只读工具）/ service / controller / dto

src/main/resources/
├── db/migration/      Flyway 迁移脚本（V1__init_schema.sql；改结构就新增 V2）
├── db/bootstrap.sql   只建库（可选，日常用 createDatabaseIfNotExist）
└── static/            前端构建产物（npm run build 生成，已 git 忽略）

.github/workflows/ci.yml   后端 mvn verify（Testcontainers）+ 前端 npm build

web/                   前端工程（独立 npm 包，构建产物回灌 src/main/resources/static）
├── vite.config.js     /api 代理到 8081；outDir 指向后端 static
├── src/api/           axios 实例（解包 data、统一 401/403/409）
├── src/router/        history 模式 + 登录守卫 + 角色路由
├── src/stores/        会话用户（只存身份，不存 token）
├── src/layouts/       深色侧栏 + 顶栏 + AI 入口
└── src/views/         登录、修改密码（全屏）、工作台、工单列表/详情/新建、知识库、用户管理
```

分层职责：Controller 只做路由与参数校验；Service 承担业务规则、事务边界、状态机与权限；Mapper 只写 SQL；前端只做展示与交互，权限判断一律以后端为准。

---

## 关键设计

细节与取舍见 [docs/architecture.md](docs/architecture.md)，这里只列结论：

| 主题 | 选择 | 一句话理由 |
|---|---|---|
| 架构 | 模块化单体 | 单人开发，微服务只会把精力耗在运维上；按业务分包保留拆分能力 |
| 认证 | Session（非 JWT） | 单体无多端诉求，注销即失效、可服务端踢人，不用自己实现吊销 |
| 会话存储 | Spring Session + Redis（`repository-type: indexed`） | 内存 SessionRegistry 只能踢本机会话；索引版仓库按 principal name 找会话，多实例下踢人才成立 |
| 持久层 | MyBatis（非 JPA） | 已学索引/事务/MVCC，手写 SQL 能直接利用并看执行计划 |
| 状态机 | 枚举 + 迁移白名单 | 4 个状态不引框架，规则集中、可穷举测试 |
| 并发控制 | 乐观锁 `version` | 接单是短事务且冲突可重试，比悲观锁划算 |
| 检索 | 两条路径：列表页 `LIKE '%kw%'`、RAG 用 FULLTEXT ngram | 全文检索查不出单字符，直接替换会让现有搜索退化；所以是增量而不是替换 |
| 创建幂等 | `Idempotency-Key` + 唯一索引 `uk_creator_idempotency` | 先查后插在并发下仍会双写，唯一索引才是最终裁判；冲突后返回已有记录 |
| 登录防爆破 | Redis 计数：账号 5 次 / IP 20 次，锁 15 分钟 | 账号维度是主防线（换 IP 也挡得住）；IP 阈值放宽，避免 NAT 后误伤同事 |
| AI | 只读工具 + 身份取自 SecurityContext | 工具签名不带 userId，从根上堵死提示注入越权 |
| RAG | 先全文召回 top-3，再拼进 user 消息 | 让模型基于内部术语作答；不塞进 system 提示词，避免动态内容污染行为约束 |
| AI 审计 | 每次问答落 `ai_chat_log` | 记录召回条数、工具调用次数、耗时、来源；写入失败只 WARN，绝不影响回答 |
| 前端形态 | Vue SPA + history 路由 | URL 可读（`/tickets/12`）；代价是后端加 7 条显式白名单转发，不用 `/**` 通配以免吞掉 `/api` |
| 前后端联调 | Vite 代理 `/api` → 8081 | 浏览器只看到一个源，Session Cookie 天然同源，Spring 侧零 CORS 配置 |
| 权限边界 | 前端隐藏按钮 ≠ 权限 | 可见性由 Service 的 `requireVisible` / `requireRole` 决定，前端裁剪只是减少误点 |
| 管理端并发 | `version` 条件更新 + 管理员行锁 | 同一行覆盖用乐观锁；两个管理员互降导致 0 管理员，用按 id 排序的跨行锁兜底 |
| 密码重置 | 一次性临时密码 + 服务端强制改密 | 明文只在响应里出现一次；`SessionRevoker` 按登录名删 Redis 会话；未改密时 `/api/**` 只放行改密、me、logout、管理员联系方式 |
| 缓存 / MQ / 微服务 | 不做 | 没有真实业务收益，堆技术名词在面试里是负资产 |

---

## 测试

```bash
mvn test
```

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `TicketApiTest` | 12 | 创建/详情/分页/统计、参数校验、401/404/405/415 |
| `TicketWorkflowTest` | 12 | 状态机全流程、审计日志、越权 403/404、指派、筛选分页 |
| `TicketConcurrencyTest` | 1 | 双线程同时接单：恰好一个成功，`version` 只加 1 |
| `TicketIdempotencyTest` | 5 | 同 key 重复提交只落一条、无 key 保持旧行为、跨用户互不影响、超长 key 400、并发同 key 唯一索引兜底 |
| `AuthFlowTest` | 13 | 注册登录注销、密码错误、禁用账号、ADMIN 接口权限、调整角色、最后一个管理员保护 |
| `PasswordFlowTest` | 6 | 重置密码、旧会话失效、临时密码强制改密、强制改密态的退出/联系方式、公开联系方式、清空联系方式校验 |
| `LoginRateLimitTest` | 3 | 连错 5 次锁定（第 6 次正确密码也 429）、成功登录清零计数、IP 维度独立锁定 |
| `UserRoleConcurrencyTest` | 2 | 并发改同一用户角色（version 只加 1）、过期 version 更新被拒 |
| `SchemaMigrationTest` | 1 | Flyway 已应用 V1、5 张业务表与 sys_user 关键字段齐全 |
| `TraceIdFilterTest` | 3 | traceId 生成、合法值透传、非法/超长值丢弃 |
| `MetricsApiTest` | 1 | 登录失败与 URL 级越权各计一次数（差值断言） |
| `KbApiTest` | 7 | 分类/条目 CRUD、归属权限、检索、通配符转义、OpenAPI 可达 |
| `AiAgentTest` | 5 | 模型正常/异常/空回答降级、参数校验、未登录 |
| `AiNotConfiguredTest` | 1 | 未配置 Key 时直接降级，且不发起模型调用（`verifyNoInteractions`） |
| `AiTimeoutTest` | 1 | 模型超时触发降级 |
| `AiToolsTest` | 4 | 工具只返回本人数据、未登录即失败、状态过滤 |
| `AiRagTest` | 3 | 召回内容进入提示词、审计字段与真实分支一致、未命中不硬塞上下文 |
| `SessionRedisTest` | 2 | 会话真的落在 Redis、重置密码删光该账号全部会话、本人改密只保留当前会话 |

合计 **82** 个用例。并发用例刻意不加 `@Transactional`——事务是连接级的，两个线程各自提交才能测出真实冲突；指标断言一律用**差值**，因为 Spring 上下文复用、计数器不会在测试之间清零。

> `AiRagTest` 也不能加 `@Transactional`：InnoDB 的全文索引在行提交后才进入检索视图，
> 测试事务里的种子数据永远查不到，会得到"召回永远为空"的假阴性。改为 `itest-` 前缀 + `@AfterEach` 手工清理。

测试依赖有两种来源（见 `AbstractIntegrationTest`）：本机有 Docker 就启 `mysql:8.0.36` + `redis:7.4-alpine` 两个容器，Flyway 建表、Ryuk 回收；没有 Docker 则回退连本机 MySQL80（读 `application-local.yml`）与本机 Redis 6379。想强制走本机服务，设环境变量 `ISD_TEST_NO_DOCKER=true`。

> 会话外置后，测试里不能再强转 `MockHttpSession`（拿到的是 Spring Session 的包装类），统一走
> `TestSessions` 工具：登录后取 `SESSION` Cookie，后续请求带 Cookie——顺带覆盖了 Cookie 下发这一步。

CI 在 `.github/workflows/ci.yml`：后端 job 跑 `mvn verify`（runner 自带 Docker，固定走容器模式），前端 job 跑 `npm ci && npm run build`。

---

## 已知限制

- 列表页检索仍是 `LIKE '%kw%'`（无法走索引）；RAG 召回已换 FULLTEXT ngram，但没有语义召回（同义词、跨语言查不到）
- 知识库分类不支持改/删，条目为硬删除
- AI 单轮无会话记忆；降级只返回标题，不返回正文片段
- AI 接口无频率限制，生产必须加配额；`ai_chat_log` 没有自动清理任务，需按保留策略定期归档
- 登录限流按 IP 20 次的阈值对 NAT 环境偏严，企业出口 IP 集中时要按实际调大或改成网关注入的可信头
- 幂等键只在"同一用户 + 同一 key"下生效，key 由前端页面生成，没有做服务端过期回收（靠唯一索引长期占用存储可忽略的空间）
- CSRF 关闭（依赖 SameSite=Lax），生产可换 Cookie 双提交模式
- 工单号仍是"日期 + 4 位随机数 + 重试"，未换雪花/序列
- 前端没有单元测试，靠后端 82 个用例 + 手工验收清单兜底
- Element Plus 全量引入（主包 gzip 约 410KB），内网可接受；按需引入留作优化项
- 会话强依赖 Redis：Redis 不可用时应用启动失败（刻意不做内存降级）；Redis 侧需要开启 keyspace 事件通知才能统计 `reason=expired`
- 每次请求多一次 Redis 往返（Lettuce 连接复用，通常亚毫秒），换来多实例部署下的会话一致性；未做会话本地缓存，避免「缓存未失效导致踢人不生效」

---

## 文档

- [docs/architecture.md](docs/architecture.md) 架构与关键取舍（ADR）
- [docs/demo-script.md](docs/demo-script.md) 5 分钟演示脚本（含实测结果）
- [docs/interview-qa.md](docs/interview-qa.md) 面试问答速查 + 简历描述
- [docs/learning-notes.md](docs/learning-notes.md) 学习笔记（踩坑记录）
- [docs/phase0-架构与MVP.md](docs/phase0-架构与MVP.md) Phase 0 需求与规划
