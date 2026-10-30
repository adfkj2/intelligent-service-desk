# ISD v1.0.0 发布说明

**发布日期**：2026-10-30
**完整变更**：[CHANGELOG.md](../CHANGELOG.md) · **发布门禁**：[docs/release-checklist.md](release-checklist.md)

企业 IT 智能服务台第一个可交付版本：工单全流程 + 知识库 + 基于真实数据的只读 AI 助手，
后端、前端、镜像、真实浏览器、安全基线、性能与依赖风险都有可复现的门禁。

---

## 本版亮点

- **工单闭环**：创建（幂等）、接单（乐观锁）、指派、解决、关闭，操作历史审计
- **只读 AI 助手**：Function Calling 两个只读工具，身份取自 `SecurityContext`；超时/异常降级为知识库检索；每次问答落审计表
- **生产形态**：Nginx TLS 反代，app 不暴露宿主机端口；Secure Cookie + HSTS + **CSP**
- **站内信**：Transactional Outbox，业务变更与事件同事务，异步投递且幂等
- **可观测性**：Micrometer 业务指标 + traceId（跨 AI 线程池）
- **发布工程**：105 个后端测试、25 个前端单测、1 条 Playwright E2E、k6 冒烟压测全部进 CI

---

## 门禁结果（发布时实测）

| 门禁 | 命令 / 位置 | 结果 |
|---|---|---|
| 后端测试 + 覆盖率 | `./mvnw verify` | **105 用例通过**；行覆盖 **92.01%**（≥90%）、分支 71.79% |
| 前端质量 | `npm run lint && npm test && npm run build` | lint 0 warning；**25 单测通过**；构建成功 |
| 依赖漏洞 | `npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org` | 通过（`found 0 vulnerabilities`） |
| 镜像 | `docker build -t isd:local .` | 三阶段构建成功 |
| 真实浏览器 E2E | `npx playwright test`（prod 栈） | 登录→建单→接单→解决→关闭 **通过**；CSP 违规 **0** |
| 安全基线 | `curl -k -D - https://localhost/api/ping` | CSP / HSTS / X-Content-Type-Options / X-Frame-Options 齐全；Secure Cookie；无 CSRF 头写请求 403 |
| 性能 | k6 冒烟（与完整压测同阈值） | 工单读路径与 AI 配额均通过（`ai_server_error=0`、至少一次 429） |
| 数字一致 | CI `@Test` 守卫 + `ApiInventoryTest` | 105 用例 / 33 接口 / 6 迁移 / 8 表 |

> 本地复现步骤见 [docs/release-checklist.md](release-checklist.md)；
> 覆盖率报告由 CI `backend` job 的 `jacoco-report` 产物提供；
> 压测数据与瓶颈分析见 [docs/perf-report.md](perf-report.md)（含「CI 冒烟压测」小节）。

---

## 证据链接

- CI 流水线：<https://github.com/adfkj2/intelligent-service-desk/actions/workflows/ci.yml>
- 覆盖率报告：CI run → Artifacts → `jacoco-report`（本地为 `target/site/jacoco/index.html`）
- E2E 报告与 trace：CI run → Artifacts → `playwright-report` / `test-results`
- 压测报告：[docs/perf-report.md](perf-report.md)
- 发布清单：[docs/release-checklist.md](release-checklist.md)

---

## 升级说明

- 首个正式版本，无历史版本需要迁移。
- 首次部署按 README「快速开始 → 生产形态」执行；自签证书仅用于本地演示，生产需替换可信证书。
- 会话强依赖 Redis，Redis 不可用时应用会拒绝启动（刻意不做内存降级）。
- CSP 只在 `prod` profile 生效；开发期保持关闭，避免 Vite HMR 与 Swagger UI 报违规。

## 已知限制

详见 [README「已知限制」](../README.md#已知限制)。本版明确保留的边界：

- CSP 只在生产下发，`style-src` 保留 `'unsafe-inline'`（Element Plus 弹层定位）
- E2E 只覆盖一条主流程，视觉回归与页面边界态仍靠手工验收
- 列表检索仍是 `LIKE '%kw%'`，数据量上万后需换 FULLTEXT 或 ES
- AI 限流是固定窗口，窗口边界可能瞬时放行接近两倍配额
- OWASP dependency-check 未接入，Java 传递依赖的 CVE 依赖 Dependabot 提醒
