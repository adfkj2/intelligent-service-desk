# v1.1.0 发布收尾清单（Definition of Done）

本文件是 ISD **v1.1.0** 的发布门禁：每一条都能在本机或 CI 用命令复现，任何一条不通过就不发版。

数字口径以 [README「关键数字」](../README.md#关键数字) 为准；CI 会比对 `@Test` 数量，防止文档漂移。

---

## 0. 前置条件

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | 21 | 后端编译 / 测试 |
| Node | 20（≥18 即可） | 前端构建 / 单测 / Playwright |
| Docker Desktop | 近期版本 | Testcontainers、compose、镜像构建 |
| Playwright Chromium | 随 `@playwright/test` 安装 | E2E |

```bash
docker version
java -version     # 21
node -v           # v20.x
./mvnw -v         # 3.9.16（Windows 用 .\mvnw.cmd）
```

---

## 1. 后端全绿 + 覆盖率门禁

```bash
./mvnw verify
```

- [ ] 退出码 0
- [ ] surefire 汇总 **207** 个用例，失败 0、错误 0、跳过 0
- [ ] JaCoCo 行覆盖 **≥ 0.90**（实测 94.50%），低于门禁时 `verify` 直接失败

**证据**：`target/site/jacoco/index.html`；CI 产物 `jacoco-report`、`surefire-reports`。

---

## 2. 前端全绿

```bash
cd web
npm ci
npm run lint
npm test                 # 37 个单测
npm run build
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

- [ ] lint 0 warning
- [ ] 单测 **37** 个全部通过
- [ ] 构建成功，`../src/main/resources/static/index.html` 存在
- [ ] 生产依赖无 high/critical 漏洞

> registry 显式指向官方源：国内镜像（npmmirror）未实现 audit 端点，会返回 `NOT_IMPLEMENTED`。

**证据**：CI `frontend` job 日志。

---

## 3. 镜像与 JAR

```bash
# 发布构建顺序：先前端，再后端
cd web && npm ci && npm run build && cd ..
./mvnw -DskipTests package

# JAR 必须含页面，不能只有接口
jar tf target/isd-1.1.0.jar | grep 'BOOT-INF/classes/static/index.html'

# 镜像
docker build -t isd:local .
```

- [ ] `target/isd-1.1.0.jar` 存在，且包含 `static/index.html` 与 `static/assets/`
- [ ] 三阶段镜像构建成功（Node → Maven → JRE）
- [ ] 运行用户非 root

**证据**：CI `image` job；本地 `jar tf` 输出。

---

## 4. 真实浏览器主流程（E2E）

```bash
.\deploy\certs\generate.ps1        # macOS/Linux 见 docs/operations.md
docker compose -f docker-compose.prod.yml up -d --build
curl -k --fail https://localhost/api/ping

docker compose -f docker-compose.prod.yml exec -T mysql \
  mysql -uroot -p123456 isd < web/e2e/seed.sql

cd web
npx playwright install --with-deps chromium
npx playwright test
```

- [ ] 10 条浏览器用例通过（2 条真实服务流程 + 8 条模拟接口流程）
- [ ] 主流程：登录 → 建单 → 接单 → 解决 → 关闭
- [ ] CSP 违规计数为 0
- [ ] 失败时保留 trace：`web/test-results/**/trace.zip`

**证据**：CI `e2e` job 产物 `playwright-report`、`test-results`。

---

## 5. 安全基线

```bash
curl -k -D - -o NUL https://localhost/api/ping

# 无 CSRF 头的写请求必须 403
curl -k -o NUL -w "%{http_code}\n" -X POST https://localhost/api/auth/register \
  -H "Content-Type: application/json" -d '{"username":"csrf_probe","password":"probe12345"}'
```

- [ ] `Content-Security-Policy` 存在，`script-src 'self'`，不含 `unsafe-eval`
- [ ] `Strict-Transport-Security`、`X-Content-Type-Options`、`X-Frame-Options` 各出现一次
- [ ] 会话 Cookie：`Secure; HttpOnly; SameSite=Lax`
- [ ] 无 CSRF 头的写请求返回 **403**
- [ ] `http://localhost/api/ping` 返回 **301**
- [ ] 8081 不对外映射

**证据**：`SecurityHeadersTest` + 本节 curl 输出。

---

## 6. 性能有数据有门禁

```bash
docker compose -f docker-compose.stack.yml up -d --build
# 注册 perf_emp（带 CSRF 头）见 .github/workflows/ci.yml 的 perf job
docker run --rm --add-host=host.docker.internal:host-gateway \
  -v "$PWD/perf/k6:/scripts:ro" \
  -e BASE_URL=http://host.docker.internal:8081 -e VUS=5 -e DURATION=20s \
  grafana/k6:0.54.0 run /scripts/tickets.js
```

- [ ] k6 退出码 0：列表 p95 < 300ms、关键词 p95 < 500ms、详情 p95 < 200ms、失败率 < 1%
- [ ] AI 脚本：5xx == 0、至少一次 429（配额生效）
- [ ] 完整报告在 [perf-report.md](perf-report.md)

只允许调 `VUS` / `DURATION` / `SEED_TICKETS` 缩短时间，**不允许放宽阈值**。

---

## 7. 依赖风险可控

- [ ] `.github/dependabot.yml` 覆盖 maven / npm（`/web`）/ github-actions / docker
- [ ] `npm audit --omit=dev --audit-level=high` 通过（CI `frontend` job）
- [ ] 本季度复核一次 Dependabot PR

OWASP dependency-check 未接入（需 NVD API Key，首跑十分钟以上），列为后续项。

---

## 8. 数字不漂移

```bash
grep -r "@Test" --include="*.java" src/test | wc -l      # 207
grep -c '^[A-Z]' src/test/resources/api-endpoints.txt     # 69（文件含注释行，勿用 wc -l）
```

- [ ] `@Test` 数 == README 声明的 **207**
- [ ] `/api` 接口数 == **69**
- [ ] 前端单测 == **37**
- [ ] 浏览器 E2E == **10**

**证据**：CI `backend` job 的「用例数守卫」；`ApiInventoryTest`。

---

## 9. 版本可追溯

发布前先确认 CI 对最终提交全绿，再打 tag：

```bash
git tag -a v1.1.0 -m "ISD v1.1.0"
git push origin v1.1.0
gh release create v1.1.0 --title "ISD v1.1.0" \
  --notes-file docs/release-notes-v1.1.0.md \
  isd-1.1.0.jar isd-1.1.0-source.zip SHA256SUMS.txt --latest
```

- [ ] `pom.xml` / `web/package.json` / `package-lock.json` 均为 `1.1.0`
- [ ] Dockerfile 引用 `isd-1.1.0.jar`，文档无旧 JAR 名
- [ ] `CHANGELOG.md` 有 `[1.1.0]` 段
- [ ] tag `v1.1.0` 指向发布提交
- [ ] Release 附三个资产并设为 Latest
- [ ] Release Notes 链接到该版本的 CI、覆盖率、压测证据

### 生成附件

```bash
# 1) 发布 JAR（先构建前端）
cd web && npm ci && npm run build && cd ..
./mvnw -DskipTests package
cp target/isd-1.1.0.jar .

# 2) 源码包（从最终提交生成，含截图与启动脚本）
git archive --format=zip --prefix=isd-1.1.0/ -o isd-1.1.0-source.zip HEAD

# 3) 校验文件
sha256sum isd-1.1.0.jar isd-1.1.0-source.zip > SHA256SUMS.txt
```

Windows 校验：`certutil -hashfile isd-1.1.0.jar SHA256`。

---

## 10. 文档与事实一致

- [ ] README 图片相对路径可打开，桌面与窄屏下可读
- [ ] README 目录锚点、下载与文档链接有效
- [ ] README「关键数字」= CI 实测值
- [ ] 已知限制逐条复核，未解决项保留
- [ ] `docs/upgrade.md` 的迁移范围与 V7–V13 一致
- [ ] `docs/release-notes-v1.1.0.md` 的附件清单与实际一致
- [ ] 本文档所有命令在本机实际执行过一次

---

## 一键验收（除 E2E 与压测外）

```bash
./mvnw verify && \
  (cd web && npm ci && npm run lint && npm test && npm run build && \
   npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org) && \
  docker build -t isd:local . && \
  jar tf target/isd-1.1.0.jar | grep 'BOOT-INF/classes/static/index.html'
```

E2E 与压测需要先起 compose 栈，按第 4、6 节执行。

---

## 证据索引

| 门禁 | 证据位置 |
|---|---|
| 后端测试 + 覆盖率 | CI `backend` job；`target/site/jacoco/index.html` |
| 前端 lint / test / audit | CI `frontend` job |
| 镜像 + JAR 页面 | CI `image` job；`jar tf` 输出 |
| E2E + CSP 违规 | CI `e2e` job（`playwright-report` / `test-results`） |
| 性能 | CI `perf` job + [perf-report.md](perf-report.md) |
| 接口清单 | `ApiInventoryTest` + `src/test/resources/api-endpoints.txt` |
| 版本与附件 | GitHub Release v1.1.0 / `CHANGELOG.md` |
