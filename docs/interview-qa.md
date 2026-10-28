# 面试问答速查（ISD 项目）

> 用法：面试前 20 分钟过一遍。每题都给"一句话版本"（先说结论）+ "展开点"（被追问时用）。
> 所有数字都来自真实实现，不要编。

---

## 0. 30 秒项目介绍

> 我做了一个企业 IT 智能服务台，员工提交报障工单，技术支持接单处理，管理员指派和管人，
> 第四块是 AI 助手——它只能查当前用户自己的工单和知识库，不能执行任何写操作。
> 技术栈是 Spring Boot 3.5 + MyBatis + MySQL + Spring Security，AI 部分用 Spring AI 接 DeepSeek 做 Function Calling。
> 重点做了两件事：**工单状态机的并发正确性**（乐观锁 + 双线程冲突测试）和 **AI 工具的权限隔离**（工具签名不带 userId，身份只从 SecurityContext 取）。
> 一共 28 个接口、5 张业务表、68 个自动化测试（Flyway 迁移 + Testcontainers + GitHub Actions + Micrometer 指标）。

---

## 1. 架构与选型

**Q1：为什么用单体不是微服务？**
一句话：单人开发、24 天周期，微服务只会把时间花在注册中心、链路追踪和分布式事务上。
展开：按业务分包（auth/ticket/kb/ai），依赖单向 `ai → ticket/kb → auth → common`，真要拆的时候切口是清晰的。

**Q2：为什么用 Session 不用 JWT？**
一句话：单体、无多端诉求，Session 是安全默认值——注销即失效、服务端可随时踢人。
展开：JWT 要自己解决注销、续期、吊销列表，等于把 Session 的复杂度搬到自己手里；角色变更在 Session 下"重新登录即生效"，JWT 要等过期。
代价：多实例要共享会话（Spring Session + Redis），这是扩展时的第一改造点。

**Q3：为什么 MyBatis 不是 JPA？**
一句话：项目本身要练"看懂执行计划"，分页、动态筛选、乐观锁 UPDATE 用 MyBatis 表达更直接。
展开：国内岗位主流；代价是没有编译期保护，靠测试兜底。条件 ≤4 个用注解 `<script>`，再复杂迁 XML。

**Q4：为什么不用 Redis / MQ？**
一句话：没有真实收益场景，"为缓存而缓存"在面试里是负资产。
展开：唯一有缓存价值的"知识条目读多写少"，数据量还没到需要缓存的量级；引入后还要处理一致性，收益为负。

**Q5：分层职责怎么划的？**
一句话：Controller 只做路由和 `@Valid`；Service 管业务规则、事务、状态机、权限；Mapper 只写 SQL。
展开：举例——"员工只能看自己的工单"这个判断放在 Service，因为它属于数据范围约束；如果放 Controller，新增一个查询接口就要再抄一遍，漏一次就是数据泄露。

**Q6：统一响应为什么还保留 HTTP 状态码？**
一句话：HTTP 状态给网关、监控、CDN、拦截器看；body 里的 `code` 给业务前端做细分。
展开：全部返回 200 的坏处是监控错误率永远是 0、告警失效、错误响应可能被缓存。

**Q7：全局异常处理器有什么坑？**
一句话：`@ExceptionHandler(Exception.class)` 会把框架异常也吞掉，必须逐个白名单化。
展开：我踩了三次——405（方法不支持）、415（Content-Type 不支持）、缺少必填参数，全被兜底成 500。现在每种都单独映射，并且有回归用例。

**Q8：模块之间怎么解耦？**
一句话：`ai` 依赖 `ticket/kb`，反向没有依赖；AI 不碰 Mapper，只调 Service。
展开：这样权限校验只有一套实现，模型不可能绕过。

---

## 2. 工单与并发

**Q9：状态机怎么实现的？**
一句话：枚举就是迁移白名单——`CLAIM(OPEN, IN_PROGRESS)`、`RESOLVE(IN_PROGRESS, RESOLVED)`、`CLOSE(RESOLVED, CLOSED)`。
展开：不引 Spring StateMachine（4 个状态引框架是过度设计）；枚举集中规则、可穷举测试；加 `reopen` 只需加一行。

**Q10：非法状态迁移返回什么？为什么不是 400？**
一句话：409。请求本身合法，是当前资源状态不允许。
展开：400 表示"你的请求格式/参数不对"，409 表示"和资源当前状态冲突"，前端处理逻辑完全不同。

**Q11：并发接单怎么防重复？**
一句话：乐观锁，把"状态还是 OPEN"和"version 没变过"都写进 WHERE。

```sql
UPDATE ticket SET status='IN_PROGRESS', assignee_id=?, version=version+1
WHERE id=? AND status='OPEN' AND version=?
```

影响行数 1 = 抢到；0 = 被别人抢先 → 409。

**Q12：为什么不用悲观锁 `SELECT ... FOR UPDATE`？**
一句话：接单冲突概率低、事务极短、失败可重试，乐观锁没有持锁开销。
展开：悲观锁适合冲突频繁、重试代价高的场景（如库存扣减 + 长事务）。

**Q13：并发测试怎么保证"同时"？**
一句话：`CountDownLatch` 当起跑线，两个线程 await 到 0 才一起冲。
展开：不用 latch 的话线程池调度会让一个线程先跑完，测出来永远是"一个成功一个 409"，但根本没验证到冲突窗口。

**Q14：这个测试为什么不能加 `@Transactional`？**
一句话：事务是连接级的，数据锁在未提交事务里，另一个线程根本读不到，测的是假并发。
展开：所以这个类不加事务，用真实提交，`@AfterEach` 手工清理数据。

**Q15：事务边界在哪？自调用会怎样？**
一句话：边界在 Service；同类内部 `this.method()` 不走代理，`@Transactional` 失效。
展开：验证方式——打印 `TransactionSynchronizationManager.getCurrentTransactionName()`，外部调用能看到方法名，自调用看到的仍是外层方法名。

**Q16：审计日志为什么和状态更新放同一事务？**
一句话：否则会出现"状态改了但没日志"，而审计的价值就在于没有缺口。

---

## 3. 权限与安全

**Q17：权限分几层？**
一句话：三层——URL 级（`/api/admin/**` 仅 ADMIN）、角色级（Service 里判断）、资源级（这条数据是不是你的）。

**Q18：为什么越权读返回 404 而不是 403？**
一句话：403 等于告诉对方"这个 id 存在"，攻击者能靠状态码差异枚举出全公司工单量。
展开：例外是知识条目——它对所有登录用户可见，"这不是你的"不泄露新信息，所以写操作用 403。

**Q19：密码怎么存的？**
一句话：BCrypt（自动加盐，慢哈希）。存库字段留 72 位余量（实际输出 60）。
展开：不自己写 MD5+盐；BCrypt 的 cost 让暴力破解成本指数上升；登录失败不区分"用户不存在"和"密码错误"，防账号枚举。

**Q20：Session 方案要注意什么攻击？**
一句话：会话固定（登录后 `changeSessionId()`）、CSRF（SameSite=Lax + 关闭 CSRF 的取舍）、XSS（Cookie `http-only`）。
展开：生产如需更强，用 `CookieCsrfTokenRepository` 双提交。

**Q21：手工登录为什么必须 `saveContext`？**
一句话：Spring Security 6 默认 `requireExplicitSave`，不保存的话下一个请求还是匿名的。
展开：这也是"我的登录接口返回 200 但后续 401"的典型原因。

**Q22：越权怎么测的？**
一句话：用 `@WithMockUser` 的等价物 `.with(user(...))` 注入不同身份，断言 403/404。
展开：`TicketWorkflowTest` 里 12 个用例有一半是失败路径——只测成功路径等于没测。

**Q23：注册接口为什么不让传 role？**
一句话：一旦允许，任何人注册时写 `role=ADMIN` 就直接提权；提权只能由管理员在后台完成。
展开：`PATCH /api/admin/users/{id}/role` 仅 ADMIN 可调；Service 禁止修改自己的角色（409），避免误降权后失去管理入口；角色存在 Session 里，改完必须对方重新登录才生效。

---

## 4. AI 部分

**Q24：Function Calling 的流程？**
一句话：模型读工具描述 → 返回工具调用 → 我们执行方法 → 结果回传模型 → 模型生成最终回答。
展开：本项目两个工具 `query_my_tickets`、`search_knowledge_base`，都是只读。

**Q25：工具为什么不能带 userId 参数？**
一句话：带了就能被伪造——模型或用户通过提示注入让它查 `userId=1` 的数据。
展开：身份只能从 `SecurityContext` 取（`CurrentUserProvider`），工具再调 Service，复用同一套资源级权限。

**Q26：为什么 LLM 调用不能放在事务里？**
一句话：一次调用可能几十秒，事务会一直占着数据库连接，连接池很快被打空。
展开：`AgentService` 没有 `@Transactional`，数据查询由工具自己开只读事务，边界很短。

**Q27：异步调用 LLM 时 SecurityContext 会怎样？**
一句话：`SecurityContextHolder` 默认是 ThreadLocal，换线程就丢了，工具一进去就是"未登录"。
展开：用 `DelegatingSecurityContextExecutorService` 包装线程池，提交任务时捕获上下文、执行时恢复。

**Q28：AI 挂了怎么办？**
一句话：硬超时（默认 30 秒）+ 降级为知识库关键词检索，响应里 `source=FALLBACK` 明确告知。
展开：接口仍返回 200，前端据 `source` 提示"AI 暂不可用"，不会让用户误以为这是模型结论。

**Q29：为什么不做 RAG / 向量库？**
一句话：知识库只有几百条，`LIKE` 够用；再引 Embedding 是重复造轮子。
展开：真要升级，先 `FULLTEXT ngram`，还不行再向量召回；降级路径本身就留了"关键词检索打底"的位置。

**Q30：AI 的成本怎么控？**
一句话：只给摘要字段（工具返回不含正文）、限制返回条数、单轮无长上下文；生产再加限流与配额。
展开：`TicketBrief` 去掉 description，`KbBrief` 不带 content，都是为省 token 和减少模型抓错重点。

**Q31：怎么保证模型不编造？**
一句话：三层——系统提示词要求"只依据工具数据"；工具返回空就如实说"没查到"；代码层只有读工具，想写也没接口。
展开：实测问"帮我创建一个工单"，模型回答"我目前只有查询权限，无法创建工单，请到工单页面提交"。

---

## 5. 数据库与性能

**Q32：索引怎么设计的？**
一句话：`uk_ticket_no`（业务唯一）、`idx_creator`/`idx_assignee`（我的工单/我的待办）、`idx_status_created`（工作台按状态+时间倒序）。
展开：复合索引顺序按"等值在前、范围/排序在后"。

**Q33：`LIKE '%kw%'` 为什么用不上索引？**
一句话：B+ 树按前缀有序，前置通配符让有序性失效，只能全表扫。
展开：`LIKE 'kw%'` 可用；v2 用 FULLTEXT ngram 或 ES。

**Q34：用户输入 `%` 会怎样？**
一句话：等于搜全库——这是通配符注入（不是 SQL 注入，占位符已挡住），要在应用层转义 `%` `_`。
展开：代码里 `likePattern()`，用例断言搜 `%` 返回 0 条。

**Q35：深分页怎么办？**
一句话：`LIMIT 100000, 20` 要先扫 10 万行；改成"记住上一页最后一条 id"的游标分页。
展开：v1 数据量小，先接受；这是被追问时的加分点。

**Q36：为什么不建物理外键？**
一句话：单表操作和测试造数更灵活，一致性交给 Service。
展开：代价是脏数据风险，所以指派前校验"用户存在 + 角色是 SUPPORT + 未禁用"。

**Q37：工单号为什么不用自增 id？**
一句话：自增 id 会暴露业务量；用 `T + 日期 + 4 位随机` 对外的同时内部仍用自增主键。
展开：4 位随机有生日悖论风险（一天几十单就可能撞），所以加了唯一索引 + 重试 3 次；长期方案是雪花/Redis INCR。

---

## 6. 测试与工程

**Q38：测试怎么分层？**
一句话：Service 层用真实数据库跑集成测试（MockMvc 走完整 DispatcherServlet 链路），AI 用桩替换模型。
展开：不 mock Mapper——SQL 写错只有真库能发现。

**Q39：为什么 AI 用桩而不是真调模型？**
一句话：模型输出不确定，断言它说什么只会得到脆弱用例；该测的是编排（鉴权、超时、降级）和工具返回的数据是否正确。
展开：真实调用只做一次性冒烟，不进测试套件。

**Q40：测试怎么保证不脏库？**
一句话：业务用例 `@Transactional` 自动回滚；并发用例必须真实提交，所以 `@AfterEach` 手工删数据。

**Q41：踩过最坑的 bug？**
一句话：并发接单测试一直"通过"，其实是假并发——测试类加了 `@Transactional`，另一个线程读不到未提交数据。
展开：去掉事务后才真正暴露出乐观锁的价值；同时理解了"事务是连接级的"。

**Q42：如果重做，会改什么？**
一句话：三件事——工单号一开始就用雪花、全局异常第一时间把框架异常白名单化、AI 的降级检索改成切词而不是整句 LIKE。

---

## 6.1 前端三问

**Q43：为什么不把 token 存 localStorage？**
一句话：本项目根本没有 token，凭证是 `HttpOnly + SameSite=Lax` 的 `JSESSIONID`，JS 读不到，XSS 也偷不走。
展开：前端 Pinia 只存"当前用户是谁"（用于菜单与按钮裁剪），刷新页面靠 `GET /api/auth/me` 恢复会话；如果换成 JWT 存 localStorage，等于把凭证暴露给任何一段注入脚本，还要自己实现续期与吊销。

**Q44：Vite 代理为什么能省掉 CORS？**
一句话：代理让浏览器始终只看到一个源，Cookie 是同源的。
展开：开发期浏览器访问 `localhost:5173`，`/api` 由 Vite 转发到 `8081`，跨源发生在 Node 侧（服务端之间没有同源策略）；生产期前端产物在同一个 jar 里，本来就是同源。所以 Spring 侧不需要 `@CrossOrigin`，也不需要 `withCredentials` 之外的任何配置。反过来说，"前端 5173 + 后端 8081 直连"才会同时遇到 CORS 和 `SameSite` 丢 Cookie 两个坑。

**Q45：前端把按钮藏起来，算权限控制吗？**
一句话：不算，只是体验优化；真正的边界在 Service。
展开：员工看不到"用户管理"菜单，但如果直接 `GET /api/admin/users`，Security 的 URL 规则仍然返回 403；员工看不到别人的工单，直接改 URL 里的 id 也是 404（`requireVisible` 防枚举）。前端裁剪的价值是减少误点与无效请求，不是安全机制。

---

## 6.2 管理端并发

**Q46：两个管理员同时改同一个用户，怎么防止互相覆盖？**
一句话：同一行用 `version` 乐观锁，跨行的"至少保留一个管理员"用管理员行锁，两个问题分开解。
展开：
1. `UPDATE sys_user SET role=?, version=version+1 WHERE id=? AND version=?`，影响 0 行说明读到的数据已过期 → 409；
2. 失败后用 `SELECT ... FOR UPDATE` 读最新值，如果已经是目标值就按幂等成功返回（普通 SELECT 在 REPEATABLE READ 下仍是旧快照，会把幂等误判成冲突）；
3. 互相降权导致 0 管理员是**跨行不变量**，单行 version 管不了：事务内按 `id` 顺序 `SELECT ... FOR UPDATE` 锁住所有启用管理员，只剩一个时拒绝降级/禁用；
4. 角色存在 Session 里，改完必须重新登录才生效——这是 Session 方案的取舍，要立即踢人得上 `SessionRegistry`。

---

## 6.3 账号安全闭环（忘记密码 → 管理员重置 → 强制改密）

**Q47：员工忘记密码怎么办？为什么不做邮箱自助找回？**
一句话：走"联系管理员 → 管理员重置 → 首次登录强制改密"的线下可信渠道，v1 不引入邮件服务。
展开：公开接口 `GET /api/auth/admin-contacts` 只返回**启用中且填了手机号或邮箱**的管理员，字段限定 `fullName/phone/email`，连用户名和角色都不给；管理员被降权或禁用后自动从列表消失。自助找回要邮件通道、一次性令牌有效期、防爆破与发信配额，在这个规模上收益不抵成本，而且企业内网本来就是"找 IT 同事"的流程。
代价：管理员必须先在「个人资料」里填联系方式（至少一项，否则保存返回 400），否则登录页只能显示空状态。

**Q48：临时密码怎么生成和保存？**
一句话：`SecureRandom` 生成 12 位、排除 `O/0/I/l/1` 等易混字符，BCrypt 入库并置 `must_change_password=1`，明文只在响应里出现一次。
展开：四点——
1. 响应加 `Cache-Control: no-store`，防浏览器/代理缓存住明文；
2. 日志只记 `targetId/operatorId`，密码与哈希一律不落日志、不进列表接口；
3. 管理员不能重置自己（409），忘记自己的密码只能由另一位管理员操作，避免把自己锁在系统外；
4. 前端结果弹窗用等宽字体展示，明确写"关闭后无法再次查看"，并提供一键复制。

**Q49：重置之后怎么让对方立刻掉线？**
一句话：`SessionRegistry` + 自定义 `SessionInformationExpiredStrategy`，按用户 id 把该账号所有会话标记过期，下一次请求直接 401 JSON。
展开：手工登录（`AuthenticationManager` + `saveContext`）不会自动注册会话，所以登录成功后必须显式 `sessionRegistry.registerNewSession(...)`，否则注册表里永远是空的、踢人无效。过期策略要返回 JSON 而不是默认的 302 重定向，否则前端 axios 收到的是登录页 HTML，拦截器判断不出 401。
代价：这份注册表在 JVM 内存里，多实例部署要换 Spring Session + Redis 才能共享。

**Q50：强制改密为什么由后端拦截器兜底，而不是只靠前端路由？**
一句话：前端跳转只是体验，真正的边界是 `HandlerInterceptor` 拦 `/api/**`，未改密时只放行 `change-password`、`me`、`logout`，其余 403。
展开：`LoginUser` 带 `mustChangePassword` 标志（登录时从库里读），改密成功后重建 principal、`saveContext`、`changeSessionId()` 轮换会话，并失效该账号的其他会话。
一个细节：**改密时原密码错误返回 400，不是 401**——axios 的 401 拦截器会把用户直接踢回登录页，正在填的表单就白填了；字段级错误应该留在字段下方。

---

## 6.4 工程化（迁移、测试库、CI）

**Q51：为什么把 schema.sql 换成 Flyway？**
一句话：手工幂等 ALTER 没有版本、没有校验和、没有执行记录；Flyway 让"这个库迁到哪一版"变成可查的事实。
展开：老库用 `baseline-on-migrate` + `baseline-version: 1` 打基线，把 V1 记为已应用，避免 "table already exists"；改动已执行过的历史脚本会因校验和不匹配被拒绝启动——这条比"靠人自觉"重要得多。新环境靠 `createDatabaseIfNotExist=true`，clone 完直接启动就有表。

**Q52：测试为什么能"clone 即跑"？**
一句话：`AbstractIntegrationTest` 探测 Docker，有就起 `mysql:8.0.36` 容器并用 `@DynamicPropertySource` 覆盖数据源，没有就回退本机 MySQL，两套环境都能跑。
展开：容器放在静态字段里只启动一次——Spring 上下文会缓存复用，容器若跟着测试类停止，下一个类就连不上库。这是我踩过的坑：一开始按类启停，第二个测试类必挂。

**Q53：CI 怎么设计的？**
一句话：两个并行 job，后端 `mvn verify`（runner 自带 Docker，固定走容器模式），前端 `npm ci && npm run build`。
展开：`verify` 一次覆盖编译、68 个用例与打 jar；前端 job 顺带拦住"新克隆忘构建前端导致白屏"这个坑。`concurrency` 让同分支的旧流水线自动取消，省 runner 时间。

---

## 6.5 可观测性

**Q54：指标怎么定？为什么不直接做一个 `ai_fallback_total`？**
一句话：一个边界一个指标名，结果放标签；比率在查询侧算，不落库。
展开：`isd.ai.chat{result=success|timeout|error|empty|not_configured}` 一条就能出 QPS、P95 和降级率。如果拆成 `ai_success_total` / `ai_timeout_total` / `ai_error_total`，算降级率要跨三个指标拼分母，告警也要写三条，而且新增分支时容易漏。冲突率同理：只存分子 `isd.ticket.conflicts`，分母用 `isd.ticket.transitions`。
另一个原则是标签基数：只放 result/action/layer/tool 这类有限枚举，用户名与工单号只进日志——否则时间序列会被"每个用户一条"打爆。

**Q55：为什么工具调用要单独一个指标？**
一句话：Spring AI 会捕获工具异常并把错误文本交给模型，端到端仍然是 success，不单独埋点就完全看不见。
展开：用户看到的是"没查到相关工单"，与"真的没有数据"在指标上无法区分；只有 `isd.ai.tool.calls{tool,result}` 能看出失败率。埋点有个硬约束：异常必须原样抛出，一旦为了指标好看而吞掉，模型收到的语义就从"未登录"变成了"空结果"，等于埋点改变了业务行为。

**Q56：traceId 怎么跨到 AI 线程池？**
一句话：`ThreadPoolTaskExecutor` + 自定义 `TaskDecorator`，把 SecurityContext 与 MDC 一起带过去，并在 finally 里成对还原。
展开：原来用 `DelegatingSecurityContextExecutorService` 只搬了身份，日志里 AI 那段没有 traceId；换成 TaskDecorator 后两样都搬。必须 restore 而不是简单 clear，否则线程复用会把上一个请求的身份或 traceId 泄漏给下一个任务。指标端点单独开在 8082 并绑 127.0.0.1，就不必往 Security 白名单里开口子。

---

## 7. 反问面试官（选 2 个）

1. 团队现在工单/审批类系统的并发冲突是怎么处理的，乐观锁还是排队？
2. 团队对 AI 能力的接入是"只读辅助"还是有写操作？权限边界怎么划？
3. 后端现在的发布节奏和测试策略是怎样的，接口契约靠文档还是靠测试？

---

## 8. 简历描述（3 条，可直接用）

- 基于 **Spring Boot 3.5 + MyBatis + MySQL** 独立开发企业 IT 服务台，覆盖工单全生命周期（创建/接单/指派/解决/关闭）与知识库，共 28 个 REST 接口、5 张表。
- 工单状态流转用**枚举迁移白名单 + 乐观锁**保证并发正确性，编写 `CountDownLatch` 双线程冲突测试，验证同一工单并发接单**恰好一人成功**（另一人 409），并配套 4 表审计日志；用户管理同样用 `version` 条件更新 + 管理员行锁，避免两个管理员互相降权后系统失去管理员。
- 基于 **Spring AI（DeepSeek）** 实现只读 AI 助手：工具签名不透传 userId、身份取自 SecurityContext，杜绝提示注入越权；加入 30s 硬超时与知识库降级，AI 故障不影响主业务。累计 **68 个自动化测试**全部通过。

---

## 9. 关键数字速记

| 项 | 数字 |
|---|---|
| 接口数 | 28 |
| 数据表 | 5（sys_user / ticket / ticket_operation_log / kb_category / kb_item） |
| 工单状态 | 4（OPEN / IN_PROGRESS / RESOLVED / CLOSED） |
| 状态迁移 | 4（claim / assign / resolve / close） |
| 角色 | 3（EMPLOYEE / SUPPORT / ADMIN） |
| AI 工具 | 2（查工单 / 查知识库） |
| AI 超时 | 30 秒（可配） |
| 测试用例 | 68 |
| 前端页面 | 8（登录 / 修改密码 / 工作台 / 工单列表 / 详情 / 新建 / 知识库 / 用户管理）+ AI 抽屉 |
| 前端主包 | gzip 约 410KB（Element Plus 全量引入） |
| 端口 | 8081（开发期前端 5173 代理到 8081） |
