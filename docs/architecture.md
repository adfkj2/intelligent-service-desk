# ISD 架构与关键取舍

> 配套文档：需求与 MVP 见 `phase0-架构与MVP.md`，演示步骤见 `demo-script.md`，踩坑记录见 `learning-notes.md`。
> 本文回答一个问题：**每处技术选择为什么是这样，代价是什么**。

---

## 1. 系统定位与边界

企业内网 IT 服务台的"最小可用闭环"：

```
员工报障 → 技术支持接单 → 标记解决 → 员工确认关闭
              ↓ 每次变更写审计日志
        知识沉淀（知识库）→ AI 助手基于真实数据回答
```

**明确不做**（v1）：多租户、SLA 计时、附件上传、站内通知、审批流、消息队列、微服务拆分。

AI 的边界尤其重要：**只读 + 建议**。模型能查工单、查知识库，不能创建/接单/关闭——不是通过提示词"请求"它别做，而是**根本没有对应的工具**。

---

## 2. 总体架构

```
客户端(Swagger/前端)
   │  HTTP + SESSION Cookie（会话体存 Redis）
   ▼
Spring Security 过滤器链
   ├─ 认证：从 Session 还原 SecurityContext
   ├─ URL 级授权：/api/admin/** 仅 ADMIN，其余需登录
   ├─ 未登录 → 401 JSON；无权限 → 403 JSON；会话被踢 → 401 JSON
   ▼
PasswordChangeInterceptor（must_change_password=1 时只放行改密/me/logout，其余 403）
   ▼
DispatcherServlet → Controller（路由 / @Valid / 取当前身份）
   ▼
Service（业务规则 / 事务边界 / 状态机 / 资源级权限）
   ├─ TicketService / KbService / UserService
   └─ AgentService（AI 编排：超时 + 降级，不在事务中）
        └─ ChatClient → DeepSeek
              └─ 工具回调 → TicketService / KbService（同样走权限）
   ▼
Mapper（MyBatis：注解 SQL / 动态 SQL）
   ▼
MySQL 8（InnoDB, utf8mb4）
```

**一条铁律**：AI 不直连数据库，工具只能调 Service。这样权限校验只有一套，模型不可能绕过。

---

## 3. 模块划分

| 包 | 职责 | 依赖 |
|---|---|---|
| `common` | 响应封装、异常、Security/Chat 配置 | 无业务依赖 |
| `auth` | 用户、角色、认证、用户管理 | common |
| `ticket` | 工单、状态机、审计 | auth（查被指派人的角色） |
| `kb` | 分类、条目、检索 | auth（记录创建者） |
| `ai` | LLM 适配、只读工具、编排 | auth / ticket / kb |

依赖是单向的：`ai → ticket/kb → auth → common`。没有反向依赖，所以未来要把 `ai` 拆成独立服务，切口是清晰的。

---

## 3.1 前端（web/）与 SPA 回退

前端是 `web/` 下的独立 Vue 3 + Vite 工程，**构建产物回灌到 `src/main/resources/static`，最终交付仍是单个 jar**。分层如下：

| 层 | 位置 | 职责 |
|---|---|---|
| 页面 | `src/views/` | 组合页面：登录、修改密码（全屏独立）、工作台、工单列表/详情/新建、知识库、用户管理 |
| 通用组件 | `src/components/` | 状态标签、优先级色点、页头、AI 抽屉、管理员联系方式弹窗、个人资料弹窗、临时密码结果弹窗；不含业务请求 |
| 接口层 | `src/api/` | axios 实例统一解包 `{code,message,data}`，集中处理 401/403/404/409 |
| 路由 | `src/router/` | history 模式、路由级懒加载、未登录拉 `/auth/me` 后跳登录 |
| 状态 | `src/stores/user.js` | 只存"当前用户是谁"，**不存 token**（凭证是 HttpOnly Cookie） |
| 常量 | `src/constants/ticket.js` | 状态/分类/优先级/角色文案与配色的单一数据源 |

### 为什么开发期不需要 CORS

```
开发：浏览器 → localhost:5173 (Vite) --proxy /api--> localhost:8081 (Spring)
生产：浏览器 → localhost:8081 (Spring 同时提供 index.html 与 /api)
```

两种形态下浏览器看到的都是**一个源**，`SESSION`（Spring Session 下发的会话 Cookie）属于同源 Cookie，`SameSite=Lax` 正常工作。因此 Spring 侧没有一行 CORS 配置，前端也不需要把凭证放进 localStorage（XSS 拿不到 HttpOnly Cookie，这是 Session 相对"前端存 JWT"的隐性便利）。

### 为什么要 SPA 回退

Vue Router 用 history 模式，URL 是 `/tickets/12` 这种可读路径。用户直接刷新或分享链接时请求先到 Spring，此时没有 Controller 匹配。`SpaForwardController` 用**显式白名单**（`/`、`/login`、`/change-password`、`/tickets`、`/tickets/{id}`（新建页 `/tickets/create` 也由它覆盖）、`/kb`、`/admin/users`）转发到 `index.html`：

- 不用 `/**` 通配：否则 `/api/**`、`/v3/api-docs` 的 404 都会被变成"一段 HTML"，掩盖真实错链；
- 静态资源放行（`/assets/**` 等）必须写在 `anyRequest().authenticated()` 之前，否则未登录用户连登录页都拿不到。

### 前端的三条纪律

1. **隐藏按钮 ≠ 权限**。菜单和按钮按角色裁剪只是减少误点，数据可见性仍由 `TicketService#requireVisible` / `KbService#requireOwnerOrAdmin` 决定（员工越权读仍是 404）。
2. **统计不调 `/stats/{status}`**。该接口统计全库数量，会向员工泄露公司工单总量；工作台改为 `GET /api/tickets?status=X&size=1` 读 `total`，服务端已按角色收口，零后端改动。
3. **分页在服务端**。前端不缓存全量数据，筛选变化即重新请求；搜索输入 300ms 防抖，避免每敲一个字打一次库。

### 构建注意

`build.outDir` 指向 `../src/main/resources/static`（在 Vite root 之外），必须显式 `emptyOutDir: true`，否则旧 hash 文件残留，表现为"改完代码页面没变"甚至白屏。`web/node_modules/` 与 `src/main/resources/static/` 都在 `.gitignore` 中，新环境需先 `npm run build` 再 `mvn package`。

---

## 4. 关键决策（ADR）

### ADR-1 模块化单体，而不是微服务

- **背景**：单人开发，24 天周期，需要同时交付功能与求职材料。
- **决策**：单个 Spring Boot 应用，按业务包分层，包内再分 controller/service/mapper。
- **代价**：无法独立伸缩；一次发布影响全站。
- **结论**：微服务在这个规模下只会把时间花在注册中心、链路追踪、分布式事务上，收益为零。按业务分包保留了拆分可能。

### ADR-2 Session 认证，而不是 JWT

- **决策**：Spring Security + `HttpSession`，登录成功后手工 `saveContext` 到会话。
- **理由**：
  1. 单体、无多端诉求，Session 是安全默认值：注销即失效、服务端可随时踢人；
  2. JWT 需要自己解决"如何注销""如何续期""如何吊销"，本质是把 Session 的复杂度搬到了自己手里；
  3. 角色变更在 Session 下"重新登录即生效"，JWT 则要等 token 过期。
- **细节**：手工登录必须自己 `securityContextRepository.saveContext(...)`（Spring Security 6 默认 `requireExplicitSave`），并调用 `changeSessionId()` 防会话固定攻击。
- **落地：会话已外置到 Redis（P0-1）**。触发点是"重置密码后踢人"：内存 `SessionRegistryImpl` 只掌握本机会话，多实例部署时另一台上被重置的账号仍能继续用旧会话。
  - 选型：`spring-session-data-redis` + `repository-type: indexed`。**indexed 是刚需而不是优化**——只有索引版仓库会维护 `principal name -> sessionId` 的 Set；Boot 3.5 默认的 `default` 仓库没有这个索引，`findByPrincipalName` 不可用，按用户找会话只能全库 `SCAN`。
  - 失效方式：**删除会话记录**（`deleteById`），不是给 `SessionInformation` 打过期标记。标记法只对内存 registry 有效——Spring Session 的 `getAllSessions` 每次都从 Redis 现查、新建临时对象，`expireNow()` 改的那个对象转手就被丢弃。
  - 连带清理：`SessionRegistryImpl` / `HttpSessionEventPublisher` / `maximumSessions(...)` 全部移除。后者依赖 `ConcurrentSessionFilter` 检查 `SessionInformation.isExpired()`，既然改走"删记录"，会话不存在时自然落到 `authenticationEntryPoint` 返回 401 JSON，语义更简单。
  - 索引键是 `Authentication.getName()`（登录名），所以 `SessionRevoker` 从 `userId` 改成 `username`；两处调用点（重置密码、本人改密）本来就已加载完整 `User`。
  - 代价：每个请求多一次 Redis 往返（Lettuce 连接复用，亚毫秒级）；Redis 挂掉应用起不来（刻意不做内存降级，否则会重新出现"本地能跑、线上踢人失效"的隐性差异）。
  - 指标口径连带调整：`isd_sessions_active` Gauge 被删除（`getAllPrincipals()` 在 Spring Session 下直接抛 `UnsupportedOperationException`，精确值只能 `SCAN`），改为 `isd_sessions_started_total` / `isd_sessions_ended_total{reason}`，在线数在查询侧算差值。

### ADR-3 MyBatis，而不是 JPA

- **理由**：项目本身就是练"能看懂执行计划"的能力；分页、动态筛选、乐观锁 UPDATE 用 MyBatis 表达更直接；国内后端岗位主流。
- **代价**：SQL 与 Java 分离，重构时没有编译期保护。
- **约定**：条件 ≤ 4 个用注解 + `<script>`；超过或需要复用片段时迁到 `resources/mapper/*.xml`。

### ADR-4 状态机 = 枚举 + 迁移白名单

```java
CLAIM(OPEN, IN_PROGRESS), ASSIGN(OPEN, IN_PROGRESS),
RESOLVE(IN_PROGRESS, RESOLVED), CLOSE(RESOLVED, CLOSED)
```

- **理由**：迁移规则集中一处；`(status, action)` 组合可以穷举测试；不为 4 个状态引入 Spring StateMachine。
- **行为**：非法迁移返回 **409**，不是 400——请求本身合法，是当前资源状态不允许。
- **扩展点**：`reopen`（RESOLVED → OPEN）只需在枚举加一行。

### ADR-5 乐观锁，而不是悲观锁

```sql
UPDATE ticket
SET status='IN_PROGRESS', assignee_id=?, version=version+1, updated_at=NOW()
WHERE id=? AND status='OPEN' AND version=?
```

- **理由**：接单冲突概率低、事务极短、失败可重试。`SELECT ... FOR UPDATE` 会持锁等网络往返，收益抵不上开销。
- **语义**：影响行数 1 = 成功；0 = 被别人抢先 → 409。
- **测试**：`TicketConcurrencyTest` 用 `CountDownLatch` 对齐起跑线，双线程 + 独立事务（**不能加 `@Transactional`**，事务是连接级的，否则测的是假并发）。

### ADR-6 审计日志与业务变更同事务

- 每次成功变更写一条 `ticket_operation_log`（action / from_status / to_status / operator / comment）。
- 同事务保证"状态变了但没日志"不可能发生。
- 审计表只追加不修改。

### ADR-7 三层权限模型

| 层 | 例子 | 实现位置 | 拒绝码 |
|---|---|---|---|
| 角色级 | 建分类只有 ADMIN | Service `requireRole` | 403 |
| 资源级-读 | 员工只能看自己的工单 | Service `requireVisible` | **404** |
| 资源级-写 | 只能改自己写的知识条目 | Service `requireOwnerOrAdmin` | 403 |

**为什么读用 404、写用 403**：
- 工单列表对员工是隔离的，"这个 id 存在"本身就是信息，所以越权读伪装成不存在（防枚举）；
- 知识条目对所有登录用户可见，"这不是你的"不泄露任何新信息，用 403 更准确。

### ADR-8 统一响应但保留 HTTP 语义

```json
{"code": 0, "message": "OK", "data": {...}}
```

- HTTP 状态码给网关/监控/CDN/axios 拦截器看；`code` 给业务前端做细分（40400 工单不存在、40900 状态冲突）。
- **不采用**"永远返回 200 + body 里带错误码"：监控上错误率永远为 0，告警失效。
- 全局异常处理器必须**逐个白名单化框架异常**（405/415/缺参/类型不匹配），否则 `@ExceptionHandler(Exception.class)` 会把它们统统变成 500。这个坑在 P2–P4 期间踩了三次。

### ADR-9 检索用 LIKE，明确知道它用不上索引

- 列表页检索仍是 `LIKE '%kw%'`：前置通配符使 B+ 树有序性失效，必然全表扫。之所以保留：数据量小、要支持单字符查询，且列表 QPS 低。
- **RAG 召回已换成 FULLTEXT ngram**（见 ADR-18）：全文索引解决"整句查不到"的问题，但查不出单字符与纯符号，所以两条路径并存而不是互相替换。
- **面试要能讲清**：`LIKE 'kw%'` 可用索引，`LIKE '%kw%'` 不可用；`ngram` 解决中文无空格分词；再往上才是 embedding 语义召回。
- 附带处理：用户输入里的 `%` `_` 必须转义，否则"搜一个 %"等于全表（通配符注入，不是 SQL 注入）。

### ADR-10 AI 只读 Agent：三层防线

| 防线 | 手段 |
|---|---|
| 能力 | 只注册两个查询工具，没有写能力 |
| 身份 | 工具签名不含 `userId`，身份从 SecurityContext 取（防提示注入伪造） |
| 数据 | 工具调用 Service，复用资源级权限（员工只能拿到自己的工单） |

**工程细节**：

1. **LLM 调用不能进事务**。一次调用可能几十秒，事务会占住连接把连接池打空。`AgentService` 无 `@Transactional`，工具自己开只读事务。
2. **异步要传安全上下文**。为做超时把调用丢到线程池后，`SecurityContextHolder` 与 MDC（都是 ThreadLocal）在新线程里是空的：工具会"未登录"，日志会丢 traceId。用 `ThreadPoolTaskExecutor` + `TaskDecorator` 成对搬运并清理（`ContextPropagatingTaskDecorator`），顺带挂 `ExecutorServiceMetrics` 暴露队列长度。
3. **降级有内容**。超时/异常/未配 Key 时返回知识库检索结果，并在响应里标 `source=FALLBACK`，让前端能提示"AI 暂不可用"，而不是让用户以为这是模型结论。

### ADR-11 管理端并发：行级 version + 跨行不变量加锁

管理端有两个管理员同时操作同一用户的可能，冲突分两类：

| 冲突 | 手段 |
|---|---|
| 同一用户的状态/角色被并发修改（后写覆盖前写） | `sys_user.version` 乐观锁：`UPDATE ... WHERE id=? AND version=?`，影响 0 行转 409 |
| 两个管理员互相降权，最终 0 个管理员 | 事务内 `SELECT id FROM sys_user WHERE role='ADMIN' AND status=1 ORDER BY id FOR UPDATE`，再判断"至少保留一个启用管理员" |

三个容易踩的点：

1. **幂等**。两个人把角色设成同一个值时，后到的那个应成功而不是 409：条件更新失败后用**加锁读**（`FOR UPDATE`）拿最新已提交值再判断，普通 SELECT 在 REPEATABLE READ 下还是旧快照。
2. **加锁顺序**。按 `id` 排序加锁，避免 A 锁 B、B 锁 A 的循环等待；`idx_role_status` 让加锁范围可控。
3. **会话不会自动失效**。角色存在 Session 里，改完必须对方重新登录才生效。`SessionRevoker` 只用在"重置密码后踢人"（ADR-12），没有顺带在改角色时踢——避免一次误操作把对方正在填的表单全部清空。

### ADR-12 密码重置与强制改密：一次性临时密码 + 服务端拦截

- **背景**：员工忘记密码没有任何自助通道，此前只能改库；同时"管理员改别人密码"需要一个不泄露、可追责、不用新中间件的方案。
- **决策**：
  1. 管理员在用户管理页对**任意非本人**账号重置密码，服务端用 `SecureRandom` 生成 12 位临时密码（排除 `O/0/I/l/1` 等易混字符），BCrypt 入库，同时置 `must_change_password=1`；
  2. 明文只出现在这一次响应里（`Cache-Control: no-store`），日志只记 `targetId/operatorId`；
  3. 重置后按登录名失效该账号全部会话（`SessionRevoker` → Redis `deleteById`，见 ADR-2）；
  4. 登录时把 `mustChangePassword` 读进 `LoginUser`，`PasswordChangeInterceptor` 拦住 `/api/**`，只放行 `/api/auth/change-password`、`/api/auth/me`、`/api/auth/logout`，其余 403 JSON。
- **代价与取舍**：
  - 会话已外置 Redis（ADR-2），踢人是跨实例一致的；但会话删掉后对方只能重新登录，正在填的表单内容会丢；
  - 临时密码走线下渠道转告，系统不保证"送达"；
  - 管理员不能重置自己，忘记自己的密码只能找另一位管理员——这是"避免把自己锁在系统外"的同一套思路；
  - 改密成功要重建 principal、`saveContext`、`changeSessionId()` 轮换会话，并失效该账号其他会话；
  - `version` 只管角色/启用状态，密码与联系方式更新不 bump version，否则改个手机号会和别人的角色调整撞出无意义的 409。
- **可观测性**：重置日志打 `targetId/operatorId`；密码、哈希、临时密码任何形式都不进日志。

### ADR-13 数据库迁移交给 Flyway，不再手写幂等 ALTER

- **背景**：早期只有一份 `schema.sql`，`CREATE TABLE IF NOT EXISTS` 能保证"表在"，但加字段得在文件末尾拼 `information_schema` 查询 + `PREPARE` 动态 DDL，靠人记得跑，也没有"这个库迁到哪一版"的记录。
- **决策**：结构变更写成 `db/migration/V*.sql`，启动时由 Flyway 执行；老库用 `baseline-on-migrate: true` + `baseline-version: 1` 打基线跳过 V1；之后的变更一律新增 `V2__xxx.sql`。
- **理由**：迁移有版本号、校验和与 `flyway_schema_history` 可查；改动已执行过的脚本会因校验和不匹配被拒绝启动，从机制上防止"偷偷改历史脚本"。
- **代价**：如果某个库停留在很早的结构（例如还没有 `phone/email`），基线会把它当成"已是最新"，需要先手工补字段再启动。
- **配套**：`db/bootstrap.sql` 只负责建库，JDBC URL 加 `createDatabaseIfNotExist=true`，新环境零手工步骤。

### ADR-14 测试库：容器优先，本机回退

- **背景**：82 个集成测试全部依赖真实 MySQL（会话外置后还依赖 Redis），要求每台机器都装好 MySQL80 并配好 `application-local.yml`，换机器或进 CI 就跑不起来。
- **决策**：`AbstractIntegrationTest` 启动 `mysql:8.0.36` 与 `redis:7.4-alpine` 两个单例容器，用 `@DynamicPropertySource` 覆盖 `spring.datasource.*` 与 `spring.data.redis.*`；探测不到 Docker 时不注册属性，回退本机服务。`ISD_TEST_NO_DOCKER=true` 可强制回退。
- **为什么容器放静态字段**：Spring 上下文会缓存复用，容器若随测试类停止，下一个复用同一上下文的测试类就连不上库。
- **代价**：有 Docker 时每个测试 JVM 多 10–20 秒；没有 Docker 时探测会先等几秒再回退。
- **CI**：GitHub Actions 两个 job 并行——后端 `mvn verify`（runner 自带 Docker，固定走容器模式），前端 `npm ci && npm run build`。

### ADR-15 可观测性：按"边界 + 结果"建指标，比率只在查询侧算

- **背景**：AI 超时/降级、抢单冲突、登录失败、越权访问这些关键行为此前只有零散日志，回答不了"最近是不是变慢""冲突率多少"。
- **决策**：
  1. **一个边界一个指标名，结果放标签**：如 `isd.ai.chat{result=success|timeout|error|empty|not_configured}`，而不是 5 个独立计数器——否则算降级率要跨指标拼分母。
  2. **比率不落库**：冲突率 = `conflicts / (transitions + conflicts)`，降级率从 `result != success` 派生。存比率会引入"分母口径漂移"和二次聚合错误。
  3. **标签只放有限枚举**（result/action/layer/tool），用户名、工单号、IP 一律只进日志，避免时间序列爆炸。
  4. **Gauge 只表示瞬时量**（线程池队列与活跃线程），累计量一律 Counter。会话故意不做 Gauge：会话在 Redis，进程内枚举不到（`getAllPrincipals()` 在 Spring Session 下抛 `UnsupportedOperationException`），精确值只能 `SCAN`，那种"抓一次指标阻塞一次"的代价划不来，改成 started/ended 两个 Counter 在查询侧算差值。
  5. **工具层单独埋点**：Spring AI 会捕获工具异常并把错误文本交给模型，端到端仍是 success，只有 `isd.ai.tool.calls` 能看出失败；埋点时异常必须原样抛出，否则会改变模型看到的结果。
  6. **指标端点独立端口 8082 并绑 127.0.0.1**：不放进 8081，也就不需要往 Security 白名单里开口子，避免指标匿名可读。
- **代价**：多一个 Tomcat 连接器；标签枚举靠人维护，写错名字会变成"指标静默缺失"，所以用 `SimpleMeterRegistry` 的差值断言把关键指标测住。
- **不做**：OpenTelemetry Collector、Jaeger、ELK。单体没有跨服务调用链，traceId（MDC）+ Prometheus 指标已足够。

### ADR-16 创建工单幂等：唯一索引是最终裁判

- **背景**：用户双击「提交」、前端超时后重试、网关重发，都会产生两条一模一样的工单。只在 Service 里"先查再插"挡不住并发。
- **决策**：客户端可带 `Idempotency-Key` 头（可选，≤64 字符），`ticket` 上加 `(creator_id, idempotency_key)` 唯一索引（V2）。命中重复时返回已有工单：首次 `201`，重放 `200` + `Idempotent-Replay: true`。
- **为什么允许 NULL**：MySQL 唯一索引对 NULL 不去重，所以不带 key 的历史行为完全不变；幂等是增量能力，不是破坏性改造。
- **踩到的坑（值得讲）**：并发同 key 时，第二个 INSERT 会等第一个事务提交，然后抛唯一键冲突；但本事务的快照早在进入方法时就建立了（前面的普通 SELECT），REPEATABLE READ 下再普通查**看不到**那一行，会被误判成"工单号冲突"。修法是用锁定读（`FOR UPDATE`，当前读）重新查一次。
- **另一个细节**：`insertOrReplay` 要区分两个唯一键——`uk_creator_idempotency` 表示"别人已经建好了，返回它"，`uk_ticket_no` 才是"换一个单号重试"。
- **代价**：每个带 key 的请求多一次等值查询（走唯一索引）；key 不做服务端过期清理，存储成本可忽略。

### ADR-17 登录防爆破：Redis 计数，账号为主、IP 为辅

- **背景**：登录接口此前只有失败计数指标，没有锁定——撞库可以无限重试；指标只能事后告警，挡不住当下。
- **决策**：`LoginAttemptGuard` 用 Redis 两个 key 维度计数（窗口 = 锁定时长 15 分钟）：同账号 5 次失败即锁；同 IP 20 次失败即锁。锁定期间**先判锁再验证凭据**，连"密码对不对"都不返回，避免把登录接口变成密码探测器。
- **为什么账号维度是主防线**：攻击者换 IP 成本极低，但换不掉他要打的那个账号。
- **为什么 IP 阈值放宽到 20**：办公室 NAT 后所有人共用一个出口 IP，阈值太严会把正常同事一起锁死。
- **为什么计量只在 `AuthenticationException` 上做**：`DisabledException` 说明密码是对的，只是账号被禁用；计入失败会让"被禁用的用户反复试密码把自己锁住"。
- **为什么成功登录只清账号计数、不清 IP 计数**：否则攻击者只要手里有一个正常账号，就能定期"洗白"自己的 IP。
- **代价**：共享出口 IP 的规模化公司需要按实际调大 IP 阈值，或改成网关注入的可信头（现在只读 `remoteAddr`，因为 `X-Forwarded-For` 客户端可伪造）。

### ADR-18 RAG：FULLTEXT 召回 + 审计，一步到位但不一次到顶

- **背景**：原实现把用户整句当关键词做 `LIKE`，命中率极低；且 AI 问答没有任何落库记录，答错无法复盘。
- **决策（召回层）**：`kb_item(title, content, tags)` 加 FULLTEXT 索引并用 `WITH PARSER ngram`（V3）。`KbService.retrieve` 先全文检索 top-3，为空时退回 `LIKE`（单字/纯符号在 ngram 下查不出来）。
- **为什么不上向量库**：语料几百条，ngram 召回够用，且不需要 embedding 模型与向量库运维。真正的触发条件是"语料上千 + 需要同义/跨语言召回"，届时只换 `retrieve` 的实现，上层不动。
- **决策（提示词）**：召回结果拼进 **user 消息**而不是 system——system 是固定的行为约束，动态内容塞进去会污染规则；同时把"可能只有部分相关，不要照抄"写进前缀，降低模型把参考当结论的概率。单条正文截断 400 字，避免长条目吃掉上下文。
- **决策（审计）**：新增 `ai_chat_log`（V4），记录 traceId、用户、问题（截 500 字）、回答、来源、召回条数、工具调用次数、耗时、是否降级。写入走旁路，失败只 WARN——审计丢失远好过用户拿不到答案。
- **工具调用次数怎么来的**：工具跑在 `aiExecutor` 线程上，用 `ToolCallContext` 把同一个 `AtomicInteger` 的引用绑到该线程（ThreadLocal 只传引用，计数本身线程安全），请求线程在 future 完成后读结果。
- **踩到的坑**：InnoDB 的全文索引在行**提交后**才进入检索视图，所以 `AiRagTest` 不能加 `@Transactional`（否则种子数据永远查不到，得到"召回永远为空"的假阴性），改成 `itest-` 前缀 + `@AfterEach` 清理。
- **代价**：审计表存了问答原文，属于内部数据，需要保留策略；`ai_chat_log` 目前没有自动清理任务。

---

## 5. 数据模型

| 表 | 关键索引 | 说明 |
|---|---|---|
| `sys_user` | `uk_username`、`idx_role_status` | 角色存字符串（可读性优于 0/1/2）；`phone/email` 供登录页展示管理员联系方式，`must_change_password` 标记"临时密码未改" |
| `ticket` | `uk_ticket_no`、`uk_creator_idempotency`、`idx_creator`、`idx_assignee`、`idx_status_created` | 最后一个服务"工作台按状态+时间"查询；幂等键唯一索引允许 NULL，历史数据不受影响 |
| `ticket_operation_log` | `idx_ticket(ticket_id, created_at)` | 审计查询 |
| `kb_category` | `uk_name` | 分类名唯一 |
| `kb_item` | `idx_category`、`idx_created_by`、`idx_title`、`ft_kb_item(FULLTEXT, ngram)` | 标题索引对 `%kw%` 无效，保留供前缀查询；全文索引供 RAG 召回 |
| `ai_chat_log` | `idx_user_created`、`idx_trace` | AI 问答审计；`question` 截断 500 字，`answer` 存全文 |
| `flyway_schema_history` | 主键 `installed_rank` | Flyway 元数据：已应用版本、校验和、耗时；业务代码不读 |

**不建物理外键**：单表操作与测试造数更灵活，一致性由 Service 保证。代价是脏数据风险，需靠应用层校验（如指派前校验 assignee 存在且角色为 SUPPORT）。

---

## 6. 事务边界约定

| 位置 | 事务 |
|---|---|
| Controller | 无 |
| Service 写方法 | `@Transactional`（插入 + 日志 + 回查在同一事务） |
| Service 读方法 | `@Transactional(readOnly = true)` |
| LLM 调用 | **无事务** |
| 测试 | 业务用例 `@Transactional` 自动回滚；并发用例不加 |

已知陷阱与对策：
- 自调用导致注解失效 → 不在同类内部调用带事务的方法；
- 默认只回滚 `RuntimeException` → 业务异常统一继承 `RuntimeException`；
- 事务内做远程调用 → AI 调用显式移出事务。

---

## 7. 演进路线（如果要继续做）

| 优先级 | 事项 | 触发条件 |
|---|---|---|
| 高 | AI 频率限制 + 会话记忆 | 对外开放或成本敏感时 |
| 高 | 语义召回（embedding + 向量库）替代 ngram | 语料上千，或同义/跨语言召回成为主要痛点 |
| 中 | 多实例部署（会话已外置 Redis，还缺负载均衡与会话粘性验证） | 需要水平扩容 |
| 中 | 知识条目软删除 + 版本 | 有内容合规要求 |
| 低 | 工单号换雪花算法 | 单日工单破百 |
| 低 | 通知（站内信 / 邮件） | 有真实用户后 |
