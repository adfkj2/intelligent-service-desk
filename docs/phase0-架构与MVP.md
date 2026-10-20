# ISD · Phase 0：需求分析、MVP 与架构规划

> 状态：**待确认稿**（2026-10-17）。你确认后，本文件成为后续所有开发阶段的基准文档。
> 项目：Intelligent Service Desk（智能工单与企业知识助手平台）

---

## 1. 环境检查结论（本机实测）

| 组件 | 结论 | 详情 |
|---|---|---|
| JDK | ✅ 可用 | Java 21.0.6 LTS，`JAVA_HOME` 已指向 `C:\Program Files\Java\jdk-21`，满足 Spring Boot 3.x / Spring AI 的 Java 17+ 硬性要求 |
| Maven | ⚠️ CLI 缺失 | `mvn` 不在 PATH。**不阻塞**：IDEA 自带 Bundled Maven，Phase 1 在 IDEA 内开发即可；后续需要命令行跑 `mvn test` 时再装（或把 IDEA 的 Bundled Maven 配到终端） |
| MySQL | ✅ 可用 | 8.0.46 Community，服务 MySQL80 运行中，3306 端口开放，root 需密码（你 DataGrip 中已保存连接） |
| Git | ✅ 可用 | 2.55.0.windows.3 |
| Docker | ✅ 可用 | 29.1.3（Compose 仅在最后有余量时用，非必需） |

**唯一待办**：Phase 1 前在 DataGrip 中创建数据库 `isd`（utf8mb4）——已安排为你的第一个独立任务。

---

## 2. 业务需求分析

### 2.1 业务场景与三类角色

企业 IT 服务台场景，角色与诉求：

| 角色 | 诉求 |
|---|---|
| 普通员工 EMPLOYEE | 提交工单（IT 故障 / 权限申请 / 设备维护），随时查看自己的工单进展，向 AI 助手提问 |
| 技术支持 SUPPORT | 从待处理池接单，处理并标记解决，编写/维护知识库 |
| 管理员 ADMIN | 管理用户（禁用/启用）、指派工单、管理知识库分类 |

AI 助手定位：**只读查询 + 建议**——基于当前登录用户的真实工单数据和知识库回答问题，不直接执行业务写操作。

### 2.2 核心业务流程

```
员工提交工单(OPEN) → 待处理池
  → SUPPORT 接单 / ADMIN 指派 (IN_PROGRESS)
  → 处理人标记解决 (RESOLVED)
  → 创建者确认关闭 (CLOSED)
每次状态变更 → 写入操作历史（审计）
```

### 2.3 MVP 范围

**纳入 v1（每项都有真实业务或面试价值驱动）：**

- 模块 A：注册/登录/注销、BCrypt 密码哈希、三角色 RBAC、未登录拦截、资源级访问控制（员工只能看自己的工单）
- 模块 B：工单创建、分页条件查询、详情、接单、指派、状态变更、操作历史、非法迁移拒绝、**并发接单保护（乐观锁 + 可复现冲突测试）**
- 模块 C（简版）：知识条目 CRUD、分类管理、关键词检索
- 模块 D（简版）：AI 对话 + 2 个只读工具（查我的工单、检索知识库）、超时/异常/无结果处理
- 工程：JUnit 5 + Mockito + Spring Boot Test、统一异常与响应封装、springdoc 接口文档、README

**明确砍掉（v1 不做，附理由）：**

| 砍掉项 | 理由 |
|---|---|
| Redis | 核心业务尚无真实缓存收益场景，"为缓存而缓存"在面试中是负资产。进度富余时只给"知识条目读多写少"加缓存并做一致性讨论 |
| 向量检索 / Embedding / RAG | D.I.V.E 项目已覆盖，不重复造轮子；v1 用 LIKE + 预留 FULLTEXT ngram 方案，简历如实写 |
| Docker Compose | 本地 MySQL 已就绪，演示不依赖容器；最后有余量再补 |
| D.I.V.E 集成、微服务、消息队列、文件上传、站内通知 | 均无 v1 业务必要性，不堆技术名词 |

---

## 3. 技术选型与版本兼容性（已逐项核实）

| 组件 | 选择 | 依据 |
|---|---|---|
| Java | 21（本地 21.0.6 LTS） | Boot 3.x 与 Spring AI 均要求 Java 17+；21 为当前 LTS，无需降级到 17 |
| Spring Boot | **3.5.x** | Spring AI 官方支持主线为 3.4.x / 3.5.x；3.5 已有充分补丁周期 |
| Spring AI | **1.1.x**（BOM 管理） | 官方对应关系：Spring AI 1.1.x ↔ Boot 3.5.x；1.0.x ↔ 3.4.x |
| MyBatis Starter | **3.0.x（≥3.0.4）** | 官方兼容表：3.0.x 支持 Boot 3.2–3.5、Java 17+；且 Boot 的 BOM 不管理它，需显式声明版本 |
| MySQL 驱动 | mysql-connector-j | 由 Boot BOM 管理版本，不手写 |
| Spring Security | 随 Boot 版本 | — |
| API 文档 | springdoc-openapi 2.x | 适配 Boot 3 的 OpenAPI 3 规范 |
| 测试 | JUnit 5 + Mockito（Boot 自带） | — |
| LLM | OpenAI 兼容协议 → DeepSeek | 国内直连、成本低、支持 Function Calling；Key 一律走环境变量，不进仓库 |

**兜底组合**：若初始化时 3.5.x + AI 1.1.x 出现依赖问题，整体降级为 Boot 3.4.x + Spring AI 1.0.x（社区资料最充分的成熟组合）。版本一旦锁定，后续不追新。

**认证方案建议：Session 起步（Spring Security 默认），JWT 作为 Phase 3 的知识点与可选升级项。**
理由：
1. 模块化单体、无跨服务/多端诉求，Session 是安全默认值：注销即失效、服务端可随时踢人、无签名泄露/续期问题；
2. 在单体里引入 JWT 反而要自己解决注销与续期，是自找复杂度；
3. 面试考察点是"能否讲清两者取舍"，而不是"是否用过 JWT"。

**其他关键取舍记录（面试可用）：**
- 模块化单体 vs 微服务：单人 24 天，微服务只会让精力耗散在运维上；按业务模块分包保留未来拆分能力。
- MyBatis vs JPA：你已系统学过 MySQL 索引/事务/MVCC，手写 SQL 能直接利用这些知识并检查执行计划；国内后端岗位主流也是 MyBatis。
- 状态机不引框架：用"枚举 + 迁移白名单表"实现，可测试、可讲解，避免为 4 个状态引入重型依赖。
- AI 工具一律走业务 Service：权限校验逻辑复用，模型永远不直接触达 Mapper/数据库。

---

## 4. 架构设计

### 4.1 总体架构：模块化单体

请求自上而下经过：客户端 → Spring Security 过滤器链 → Controller（路由/参数校验/DTO）→ Service（业务规则/事务边界/状态机）→ Mapper（MyBatis/SQL）→ MySQL。
AI 模块特殊约束：**LLM 只做决策，不直连数据库**；AgentService 的工具方法只读、必须经过 TicketService / KbService，并以当前登录身份过滤数据（工具方法签名中不出现 userId 参数——身份永远取自 SecurityContext，杜绝提示注入伪造）。

### 4.2 目录结构

```
isd/
├── docs/                              # 设计文档、learning-notes.md
├── src/main/java/com/isd/
│   ├── IsdApplication.java
│   ├── common/
│   │   ├── config/                    # MyBatis、Security、OpenAPI 配置
│   │   ├── exception/                 # 业务异常 + 全局异常处理器
│   │   └── result/                    # 统一响应封装
│   ├── auth/                          # 模块 A
│   │   ├── controller/  service/  mapper/  entity/  dto/
│   ├── ticket/                        # 模块 B（同上分层）
│   ├── kb/                            # 模块 C（同上分层）
│   └── ai/
│       ├── controller/  service/  tools/
├── src/main/resources/
│   ├── application.yml
│   └── mapper/                        # MyBatis XML（复杂 SQL 用 XML，简单 SQL 用注解）
└── src/test/java/com/isd/             # 与 main 镜像的测试目录
```

分层职责（也是面试高频题）：
- **Controller**：HTTP 路由、参数校验（@Valid）、DTO⇄Entity 转换，不写业务逻辑；
- **Service**：业务规则、事务边界（@Transactional 放这层）、状态机、并发控制；
- **Mapper**：SQL 与 Java 方法的绑定，只做数据访问，不含业务判断。

---

## 5. 数据库设计（5 张核心表 + 1 张可选）

### 5.1 sys_user（`user` 是 MySQL 保留字，故加前缀）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED PK AUTO_INCREMENT | 主键 |
| username | VARCHAR(50) NOT NULL UNIQUE | 登录名 |
| password_hash | VARCHAR(72) NOT NULL | BCrypt（输出 60 字符，留余量） |
| full_name | VARCHAR(50) | 显示名 |
| role | VARCHAR(20) NOT NULL | EMPLOYEE / SUPPORT / ADMIN，应用层枚举校验 |
| status | TINYINT NOT NULL DEFAULT 1 | 1 启用 / 0 禁用 |
| created_at / updated_at | DATETIME | 审计字段 |

### 5.2 ticket（工单）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED PK AI | 主键 |
| ticket_no | VARCHAR(32) NOT NULL UNIQUE | 对外业务编号（如 T20261017-0001），避免暴露自增规律 |
| title / description | VARCHAR(128) / TEXT | 标题、描述 |
| category | VARCHAR(32) NOT NULL | IT_FAULT / PERMISSION / DEVICE / OTHER |
| priority | VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' | LOW / MEDIUM / HIGH / URGENT |
| status | VARCHAR(16) NOT NULL DEFAULT 'OPEN' | 状态机字段 |
| creator_id | BIGINT NOT NULL FK | 创建者 |
| assignee_id | BIGINT NULL FK | 处理人（可空：未接单时） |
| version | INT NOT NULL DEFAULT 0 | **乐观锁** |
| created_at / updated_at | DATETIME | 审计字段 |

索引：`UNIQUE(ticket_no)`、`idx_creator(creator_id)`、`idx_assignee(assignee_id)`、`idx_status_created(status, created_at)`——最后一个是工作台默认查询（按状态过滤 + 时间倒序）的复合索引，P2 会用 EXPLAIN 验证。

### 5.3 ticket_operation_log（操作历史/审计）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AI | |
| ticket_id | BIGINT NOT NULL FK | |
| operator_id | BIGINT NOT NULL FK | |
| action | VARCHAR(32) NOT NULL | CREATE / CLAIM / ASSIGN / RESOLVE / CLOSE / REJECT |
| from_status / to_status | VARCHAR(16) NULL | 状态迁移前后值 |
| comment | VARCHAR(500) | 操作备注 |
| created_at | DATETIME | |

索引：`idx_ticket(ticket_id, created_at)`。

### 5.4 kb_category / kb_item（知识库）

- kb_category：id、name（UNIQUE）、description、created_at
- kb_item：id、category_id（FK）、title（VARCHAR 128）、content（TEXT）、tags（VARCHAR 255）、created_by（FK）、created_at、updated_at
- 索引：`idx_category(category_id)`；v1 检索用 `LIKE '%kw%'`（**讲清楚为什么这条 SQL 用不上 B+ 树索引**，预留 FULLTEXT ngram 方案作为 v2）

### 5.5 ai_chat_log（可选，Phase 5 决定是否要）

id、user_id（FK）、question（TEXT）、answer（TEXT）、created_at——审计与复盘用。

### 5.6 状态机定义（迁移白名单）

| from | to | 动作 | 允许角色 |
|---|---|---|---|
| OPEN | IN_PROGRESS | claim（本人接单）/ assign（指派） | SUPPORT / ADMIN |
| IN_PROGRESS | RESOLVED | resolve（标记解决） | 当前处理人 / ADMIN |
| RESOLVED | CLOSED | close（确认关闭） | 创建者 / ADMIN |

- 其余任何迁移（如 OPEN→CLOSED、CLOSED→任何）一律拒绝，返回 **409 Conflict**；
- 可选扩展：RESOLVED→OPEN 的 reopen（创建者不满意重新打开）——**v1 不做**，作为状态机扩展性的讨论点；
- 每次成功变更写一条 operation_log，与状态更新在**同一事务**内。

### 5.7 并发接单保护（乐观锁）

```sql
UPDATE ticket
SET status = 'IN_PROGRESS', assignee_id = ?, version = version + 1, updated_at = NOW()
WHERE id = ? AND status = 'OPEN' AND version = ?
```
影响行数 = 1 → 接单成功；= 0 → 已被他人抢走，返回 409。P2 必须写一个**可复现的并发冲突测试**（两个线程同时 claim 同一工单，断言恰好一个成功）。

---

## 6. 接口清单（21 个，均前缀 /api）

### Auth（模块 A）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /auth/register | 公开 | 注册，默认 EMPLOYEE 角色 |
| POST | /auth/login | 公开 | 登录，建立会话 |
| POST | /auth/logout | 登录 | 注销 |
| GET | /auth/me | 登录 | 当前用户信息 |

### Ticket（模块 B）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /tickets | EMPLOYEE+ | 创建工单 |
| GET | /tickets | 登录 | 分页 + 筛选；EMPLOYEE 强制只查自己 |
| GET | /tickets/{id} | 登录 | 详情（资源级校验） |
| POST | /tickets/{id}/claim | SUPPORT | 接单（乐观锁并发保护） |
| POST | /tickets/{id}/assign | ADMIN | 指派给指定 SUPPORT |
| PATCH | /tickets/{id}/status | SUPPORT/ADMIN | 状态变更（状态机校验） |
| GET | /tickets/{id}/operations | 登录 | 操作历史 |

### KB（模块 C）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | /kb/categories | 登录 | 分类列表 |
| POST | /kb/categories | ADMIN | 新建分类 |
| POST | /kb/items | SUPPORT/ADMIN | 新建知识条目 |
| GET | /kb/items | 登录 | 分页列表（可按分类过滤） |
| GET | /kb/items/{id} | 登录 | 条目详情 |
| GET | /kb/search?q= | 登录 | 关键词检索 |

### AI（模块 D）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /ai/chat | 登录 | 对话；工具：查我的工单、检索知识库（均只读） |

### Admin

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | /admin/users | ADMIN | 用户列表 |
| PATCH | /admin/users/{id}/status | ADMIN | 启用/禁用用户 |

约定：200 查询/更新成功、201 创建成功、400 参数错误、401 未登录、403 已登录但无权、404 不存在（也用于"存在但无权看"的防探测）、409 业务冲突（并发/非法状态迁移）、500 服务器内部错误。

---

## 7. 开发顺序、里程碑与预计难点

### 7.1 里程碑（对齐 2026-11-10 投递硬截止）

| 时间 | 阶段 | 交付 | 验收标准 |
|---|---|---|---|
| 10/17–10/19 | P0 确认 + 骨架 | 本文档定稿；`isd` 库创建；Git 仓库初始化；空项目可启动 | 应用启动成功，/ping 返回统一响应 |
| 10/19–10/23 | P1 基础 | 统一返回/全局异常、工单创建/详情/分页、参数校验、MyBatis 跑通 | Swagger 调通 3 个接口；冒烟测试通过 |
| 10/23–10/26 | P2 完整工单 | 状态机、接单/指派、事务、操作历史、乐观锁 | **并发冲突测试可复现**：双线程 claim 恰好一人成功 |
| 10/26–10/29 | P3 权限 | Spring Security、登录注销、三角色、资源级权限 | 越权访问返回 403/404，集成测试全绿 |
| 10/29–11/01 | P4 知识库 + 工程 | KB 简版、springdoc 完善、测试补全 | KB CRUD + 检索可用；核心接口有测试 |
| 11/01–11/03 | P5 AI 只读 Agent | LLM 接入、2 个只读工具、异常与空结果处理 | 演示：问"我的工单进展"，返回真实数据 |
| 11/03–11/05 | P6 求职材料 | README、架构说明、复盘、简历描述、面试 QA | 材料齐备且全部基于真实实现 |
| 11/06–11/10 | 投递期 | 停止加功能，只修致命 Bug | 简历开始投递 |

说明：相比你原规划（P4 Redis / P5 AI），我把 **AI 提前、Redis 降为可选**——11/1 的 AI 里程碑更关键，且 Redis 在你的优先级序列里垫底。若任一阶段超期 1 天以上，裁剪顺序：Redis → Docker Compose → 知识库 tags → AI 的知识检索工具（保工单查询工具）。

### 7.2 预计难点 Top 5（提前打预防针）

1. **并发冲突测试的确定性构造**（P2）：怎么保证两个线程"同时"到达？→ CountDownLatch 对齐起跑线，断言一个 200 一个 409；
2. **Spring Security 后置引入的改动面**（P3）：前两阶段接口是"裸"的，引入 Security 后全部要过认证 → 用已有集成测试保护重构；
3. **@Transactional 陷阱**：自调用失效、rollbackFor 默认只认 RuntimeException、事务内发 HTTP 调用（P5 的 LLM 调用绝不能放进事务）——每个坑都会配实验验证；
4. **AI 工具的权限传递**（P5）：工具方法必须从 SecurityContext 取身份、签名不带 userId 参数，防止提示注入让模型冒充他人查询；
5. **LLM 超时/降级**（P5）：设置超时 + 降级为"直接返回知识库关键词检索结果"，保证 AI 挂了业务不受影响。

---

## 8. Phase 0 你需要掌握的知识（自检清单）

1. **一次 HTTP 请求的完整调用链**：DispatcherServlet → 拦截器/过滤器 → Controller → Service → Mapper → MySQL → 响应序列化（对应任务 2）；
2. REST 语义：GET 幂等安全 / POST 非幂等 / PATCH 部分更新；2xx/400/401/403/404/409/500 的适用场景；
3. Maven：GAV 坐标、依赖传递、BOM 统一版本、scope、生命周期；
4. IoC/DI 概念级理解：Bean 是什么、为什么注入而不 new（P1 深入）；
5. MySQL：utf8mb4 vs utf8 差别、InnoDB、为什么表名避开保留字 `user`、DATETIME vs TIMESTAMP；
6. 乐观锁 vs 悲观锁的概念区别（P2 实战验证）。

---

## 9. 第一轮独立任务（今天完成，约 30 分钟）

**任务 1（环境，10 分钟）**
1. DataGrip 连接本地 MySQL，执行：
   ```sql
   CREATE DATABASE isd CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
2. 打开 IDEA，确认任意项目的 Project SDK 为 21；
3. 回答一个问题（一句话即可）：为什么用 utf8mb4 而不是 utf8？

**任务 2（理解，20 分钟，不写代码）**
不看任何资料，**用文字画出** `POST /api/tickets` 从 HTTP 请求进入，到 MySQL 落库、再返回 JSON 的完整调用链。要求：
- 标注每一层的名字与职责（Controller / Service / Mapper 各自"做什么、为什么必须有它"）；
- 思考题：如果去掉 Service 层直接让 Controller 调 Mapper，会先在哪类需求上崩掉？

**验收标准**（我来 Review 时对照）：能说清 DispatcherServlet 的入口作用、参数校验发生在哪层、事务边界为什么在 Service、SQL 和 Java 方法是怎么绑定的。

**任务 3（选做，面试自测）**
1. 结合本项目：为什么选 MyBatis 而不是 JPA？
2. `GET /api/tickets/999`（不存在）应该返回什么状态码？`GET /api/tickets/5`（存在但不是我的）呢？
3. 既然有自增主键 id，为什么还要一个 ticket_no 业务编号？

---

## 10. 待确认决策点（3 个）

| # | 决策 | 我的建议 | 备选 |
|---|---|---|---|
| 1 | 认证方案 | **Session 起步**，JWT 作为 P3 知识点与可选升级 | 直接上 JWT（不推荐，理由见 §3） |
| 2 | LLM 供应商 | DeepSeek（OpenAI 兼容协议、国内直连、支持工具调用）；你做 D.I.V.E 用过 LLM，若有现成偏好的模型/Key 告诉我 | 智谱、Qwen（均有 Spring AI starter） |
| 3 | Redis 处理 | 按你的优先级**延后为可选项**，P4 后视余量决定 | 按原计划 P4 强制加入（不推荐） |

另有两个默认值：包名 `com.isd`、数据库名 `isd`，无异议即通过。

确认（或提出修改）后，我们进入 Phase 1 第一个开发任务：项目初始化 + 第一次启动验证。
