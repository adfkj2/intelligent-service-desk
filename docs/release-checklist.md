# v1.0 发布收尾清单（Definition of Done）

本文件是 ISD v1.0 的**唯一发布门禁**：每一条都能在本机或 CI 用命令复现，任何一条不通过就不发版。
不接受「看着没问题」——门禁要么给出退出码，要么给出可下载的证据。

数字口径以 [README「关键数字」](../README.md#关键数字) 为准；CI 会比对 `@Test` 数量，防止文档漂移。

---

## 0. 前置条件

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | 21 | 后端编译 / 测试 |
| Node | 20（≥18 即可） | 前端构建 / 单测 / Playwright |
| Docker Desktop | 任意近期版本 | Testcontainers、compose、镜像构建 |
| Playwright Chromium | 随 `@playwright/test` 安装 | E2E |

```bash
# 仓库根目录；Windows PowerShell 把 ./mvnw 换成 .\mvnw.cmd
docker version          # 必须有输出，否则后端测试会回退本机 MySQL
java -version           # 21
node -v                 # v20.x
```

---

## 1. 后端全绿 + 覆盖率门禁

```bash
./mvnw verify
```

- [ ] 退出码 0
- [ ] surefire 汇总 **105** 个用例，失败 0、错误 0、跳过 0
- [ ] JaCoCo 行覆盖 **≥ 0.90**（实测基线 92.01%），低于门禁时 `verify` 直接失败

**证据**：`target/site/jacoco/index.html`；CI 产物 `jacoco-report`、`surefire-reports`。

---

## 2. 前端全绿

```bash
cd web
npm ci
npm run lint          # ESLint 9 flat config，--max-warnings=0
npm test              # Vitest，25 个单测
npm run build         # 构建产物写入 ../src/main/resources/static
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

> registry 显式指向官方源：国内镜像（npmmirror）没有实现 audit 端点，会返回 `NOT_IMPLEMENTED`。

- [ ] lint 0 warning
- [ ] 单测 **25** 个全部通过
- [ ] 构建成功，`../src/main/resources/static/index.html` 存在
- [ ] 生产依赖无 high/critical 漏洞

**证据**：CI `frontend` job 日志。

---

## 3. 镜像可构建

```bash
docker build -t isd:local .
```

- [ ] 三阶段构建成功（Node → Maven → JRE）
- [ ] 镜像内前端产物存在：`docker run --rm isd:local ls /app/static/index.html`（路径以 Dockerfile 为准）
- [ ] 运行用户非 root

**证据**：CI `image` job。

---

## 4. 真实浏览器主流程（E2E）

```bash
# 1) 证书 + prod 栈（app 不映射 8081，必须经 Nginx 的 https）
.\deploy\certs\generate.ps1                     # macOS/Linux：bash 版命令见 deploy/certs
docker compose -f docker-compose.prod.yml up -d --build
curl -k --fail https://localhost/api/ping

# 2) 灌演示账号（固定 BCrypt 哈希，跳过注册 + SQL 提权）
docker compose -f docker-compose.prod.yml exec -T mysql \
  mysql -uroot -p123456 isd < web/e2e/seed.sql

# 3) 跑主流程
cd web
npx playwright install --with-deps chromium
npx playwright test
```

- [ ] 登录 → 建单 → 接单 → 解决 → 关闭 全链路通过
- [ ] CSP 违规计数为 0（用例内监听 `securitypolicyviolation` / console）
- [ ] 失败时保留 trace：`web/test-results/**/trace.zip`

**证据**：CI `e2e` job 产物 `playwright-report`、`test-results`。

---

## 5. 安全基线

```bash
# HSTS / CSP / X-Content-Type-Options / X-Frame-Options / Referrer-Policy
curl -k -D - -o NUL https://localhost/api/ping

# Secure + HttpOnly + SameSite 会话 Cookie
curl -k -i -X POST https://localhost/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: <XSRF-TOKEN Cookie 值>" \
  -d '{"username":"demo_emp","password":"employee123"}'

# 无 CSRF 头的写请求必须 403
curl -k -o NUL -w "%{http_code}\n" -X POST https://localhost/api/auth/register \
  -H "Content-Type: application/json" -d '{"username":"csrf_probe","password":"probe12345"}'
```

- [ ] `Content-Security-Policy` 存在，且 `script-src 'self'`、不含 `unsafe-eval`
- [ ] `Strict-Transport-Security`、`X-Content-Type-Options`、`X-Frame-Options` 各出现一次
- [ ] `Set-Cookie: SESSION=...; Secure; HttpOnly; SameSite=Lax`
- [ ] 无 CSRF 头的写请求返回 **403**
- [ ] `http://localhost/api/ping` 返回 **301**

开发期不启用 CSP（Vite HMR / Swagger UI 需要内联脚本与 eval），因此以上检查必须针对 `docker-compose.prod.yml` 栈。

**证据**：`SecurityHeadersTest`（CSP 开 / 关两种断言）+ 本节 curl 输出。

---

## 6. 性能有数据有门禁

```bash
# CI 冒烟参数（VUS=5 / DURATION=5s），阈值与完整压测完全一致
docker compose -f docker-compose.stack.yml up -d --build
# 注册压测账号（带 CSRF 头，见 .github/workflows/ci.yml 的 perf job）
docker run --rm --add-host=host.docker.internal:host-gateway \
  -v "$PWD/perf/k6:/scripts:ro" \
  -e BASE_URL=http://host.docker.internal:8081 -e VUS=5 -e DURATION=5s \
  grafana/k6:0.54.0 run /scripts/tickets.js
```

- [ ] k6 退出码 0：列表 p95 < 300ms、关键词列表 p95 < 500ms、详情 p95 < 200ms、失败率 < 1%
- [ ] AI 脚本：5xx == 0、至少一次 429（配额生效）
- [ ] 完整报告与瓶颈分析在 [docs/perf-report.md](perf-report.md)

只允许调 `VUS` / `DURATION` / `SEED_TICKETS` 缩短 CI 时间，**不允许放宽阈值**；跑不过说明回归了。

---

## 7. 依赖风险可控

- [ ] `.github/dependabot.yml` 覆盖 `maven`、`npm`（`/web`）、`github-actions`、`docker`
- [ ] `npm audit --omit=dev --audit-level=high` 通过（CI `frontend` job）
- [ ] 本季度内复核一次 Dependabot PR（即使是 patch 也走 CI 验证）

OWASP dependency-check 尚未接入：需要 NVD API Key，首跑十分钟以上，留作后续项（见「已知限制」）。

---

## 8. 数字不漂移

```bash
# 后端用例数：CI 用同一命令比对 README 声明值
grep -r "@Test" --include="*.java" src/test | wc -l

# 接口数：33，由 ApiInventoryTest 与 api-endpoints.txt 逐行守护
wc -l src/test/resources/api-endpoints.txt
```

- [ ] `@Test` 数 == README 声明的 **105**
- [ ] `/api` 接口数 == **33**（`ApiInventoryTest` 保证）
- [ ] 前端单测数 == **25**

**证据**：CI `backend` job 的「用例数守卫」步骤。

---

## 9. 版本可追溯

```bash
git tag -a v1.0.0 -m "ISD v1.0.0"
git push origin v1.0.0
gh release create v1.0.0 --notes-file docs/release-notes-v1.0.0.md
```

- [ ] `CHANGELOG.md` 有 `[1.0.0]` 段（Keep a Changelog 结构）
- [ ] `LICENSE`（MIT）存在
- [ ] tag `v1.0.0` 指向发布提交
- [ ] Release Notes 附带 CI run、覆盖率报告、压测报告三处链接

---

## 10. 文档与事实一致

- [ ] README「已知限制」逐条复核：已解决项删除，未解决项保留
- [ ] README 徽章（CI / 覆盖率 / 版本 / License）指向真实链接
- [ ] `docs/architecture.md` 的 ADR 与代码一致（CSP、E2E、CI 证据链）
- [ ] `docs/perf-report.md` 含「CI 冒烟压测」小节，参数差异写清楚
- [ ] 本文档所有命令在本机实际执行过一次

---

## 一键验收（除 E2E 与压测外）

```bash
./mvnw verify && \
  (cd web && npm ci && npm run lint && npm test && npm run build && npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org) && \
  docker build -t isd:local .
```

E2E 与压测需要先起 compose 栈，按第 4、6 节分别执行。

---

## 证据索引

| 门禁 | 证据位置 |
|---|---|
| 后端测试 + 覆盖率 | CI `backend` job；`target/site/jacoco/index.html` |
| 前端 lint / test / audit | CI `frontend` job |
| 镜像 | CI `image` job |
| E2E + CSP 违规 | CI `e2e` job（`playwright-report` / `test-results`） |
| 性能 | CI `perf` job + [docs/perf-report.md](perf-report.md) |
| 接口清单 | `ApiInventoryTest` + `src/test/resources/api-endpoints.txt` |
| 版本 | GitHub Releases / `CHANGELOG.md` |
