# Changelog

本文件记录 ISD（Intelligent Service Desk）的所有重要变更。
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-10-30

首个可交付版本：工单全流程、知识库、只读 AI 助手、站内信与生产向部署齐备，
后端 105 个测试 + Playwright 主流程 E2E + k6 冒烟压测全部纳入 CI 门禁。

### Added

- **工单**：创建（`Idempotency-Key` + 唯一索引幂等）、分页多条件查询、详情、接单、指派、状态流转、操作历史（`feat(ticket): 接入 MyBatis 与 MySQL...`、`d849392 feat(ticket): 创建工单幂等...`）
- **并发控制**：接单乐观锁 `version`，双线程冲突用例保证"恰好一人成功"（`e65ceb9 feat(ticket): 工单状态机、乐观锁接单与审计时间线`）
- **知识库**：分类与条目 CRUD、归属权限、`LIKE` 关键词检索、FULLTEXT ngram 全文检索（`c66674d feat(kb): 知识库分类、条目与关键词检索`）
- **AI 助手**：DeepSeek（OpenAI 兼容）+ Function Calling，两个只读工具，身份取自 `SecurityContext`；30s 硬超时降级；RAG 先召回再拼提示词；问答落审计表（`00c3a0c feat(ai): 只读 AI 助手...`、`1a50124 feat(ai): 知识库全文召回（RAG）与 AI 调用审计`）
- **站内信**：Transactional Outbox，事件与业务同事务落库，调度器异步投递，`(event_id, user_id)` 唯一索引幂等，重试上限转 `FAILED`（`b513b76 feat(notification): Transactional Outbox + 站内信...`）
- **认证与账号安全**：三角色 RBAC、管理员联系方式、一次性临时密码、强制改密、按登录名踢会话（`88672d2 feat(auth): 三角色 RBAC、管理员联系方式与密码重置闭环`）
- **会话与限流**：Spring Session + Redis（`indexed` 仓库），登录失败按账号/IP 双维度限流，AI 配额按用户/IP 双维度限流（`9a1e347 feat(auth): 会话外置 Redis 与登录失败限流`、`aaa8b6d feat(ai): 对话配额限流...`）
- **可观测性**：Micrometer 业务指标、traceId 日志（含 AI 线程池上下文传递）、独立管理端口 8082（`cc6bcda feat(observability): Micrometer 指标与 traceId 观测链路`）
- **数据保留**：每日 03:30 分批清理过期 AI 审计、`SENT` 事件与已读通知（`18c0bf1 feat(retention): ...按时间分批清理`）
- **前端**：Vue 3 + Vite 管理台、全屏改密页、AI 抽屉、消息铃铛、按角色裁剪菜单（`99d79e2 feat(web): Vue 3 管理台与全屏改密页`）
- **部署**：三阶段 Dockerfile、全栈 compose、prod compose + Nginx TLS 反代（`1566623 build(docker): ...`、`2e01a98 build(deploy): 新增 prod profile 与 Nginx TLS 反代编排`）
- **工程化**：Flyway V1–V6、Testcontainers 双模式、JaCoCo 覆盖率门禁、Maven Wrapper（`bc75b44 build: Flyway 版本化迁移、Testcontainers 双模式与 CI`、`725be2c build: 接入 JaCoCo 覆盖率门禁与 Maven Wrapper`）
- **测试**：105 个后端用例（含并发、幂等、CSRF、CSP 开关）与 25 个前端单测（`f9ce513 test(web): 接入 Vitest 单测（25 个）并纳入 CI`）
- **E2E**：Playwright + Chromium 打 prod 栈，覆盖登录 → 建单 → 接单 → 解决 → 关闭，并监听 CSP 违规
- **CI**：新增 e2e、k6 冒烟两个 job；前端加 `npm audit --audit-level=high`；后端加 `@Test` 数字守卫
- **依赖治理**：Dependabot 覆盖 maven / npm / actions / docker
- **发布物料**：MIT LICENSE、本 CHANGELOG、`docs/release-checklist.md`（十条发布门禁）

### Changed

- 会话从进程内存改为 Redis：多实例下"重置密码立即踢人"才成立（`9a1e347`）
- CSRF 从关闭改为 Cookie 双提交，全站写请求校验 `XSRF-TOKEN` + `X-XSRF-TOKEN`（`ceee0d4 feat(security): 开启 CSRF 双提交...`）
- CI 从 3 个 job 扩到 5 个，并把 README「关键数字」作为唯一事实源，由 CI 比对 `@Test` 数
- k6 脚本适配 CSRF：登录/建单前先取 `XSRF-TOKEN` 并回传请求头（压测报告与 CI 冒烟同步）
- 文档口径统一：用例数、接口数、覆盖率、迁移数在 README 集中维护，其余文档只引用

### Fixed

- 前端错误提示不再用兜底文案覆盖各状态码的默认提示（`3fac1ba fix(web): ...`）
- 修复强制改密死锁与登录回跳污染（`df6d6c2 fix(web): ...`）
- AI 用例不再依赖本地私钥，并覆盖未配置 Key 的降级分支（`ef6362a fix(test): ...`）
- `AiRagTest` 不再使用 `@Transactional`（InnoDB 全文索引只在提交后可见，否则永远是"召回为空"的假阴性）
- 并发接单用例去掉 `@Transactional`，避免"假并发"让乐观锁测试假通过

### Security

- 生产 profile 下发 CSP：`script-src 'self'`，不含 `unsafe-inline`/`unsafe-eval`；`style-src` 保留 `'unsafe-inline'` 兼容 Element Plus 弹层
- 生产 Secure Cookie + HSTS + `X-Content-Type-Options` + `X-Frame-Options`，Nginx 只补 `Referrer-Policy`，避免重复头
- CSRF 双提交 + `SameSite=Lax` 纵深防御；缺 token 或 Cookie/头不一致返回 403
- BCrypt 密码哈希、注册接口固定 EMPLOYEE 角色、资源级越权返回 404 防枚举
- 依赖风险：Dependabot + `npm audit --audit-level=high`；OWASP dependency-check 列为后续项

### Known limitations

见 [README「已知限制」](README.md#已知限制)。要点：CSP 只在 prod 生效；E2E 只覆盖一条主流程；
`LIKE '%kw%'` 无法走索引；AI 限流是固定窗口；会话强依赖 Redis。

[1.0.0]: https://github.com/adfkj2/intelligent-service-desk/releases/tag/v1.0.0
