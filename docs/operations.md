# 部署与运维

面向把 ISD 放到内网长期运行的人。只想试一下，用 [README「三步启动」](../README.md#三步启动) 就够。

## 生产形态（Nginx + TLS）

`docker-compose.prod.yml` 在 stack 上再加一层 Nginx：TLS 终止、HSTS、gzip、静态资源长缓存，**app 不再映射宿主机端口**，绕过反代直连 8081 被堵死。

```powershell
# 自签证书仅用于本地演示；生产请替换为企业 CA 证书
.\deploy\certs\generate.ps1
docker compose -f docker-compose.prod.yml up -d --build
curl -k https://localhost/api/ping
docker compose -f docker-compose.prod.yml down
```

```bash
# macOS / Linux 等价命令
mkdir -p deploy/certs
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout deploy/certs/server.key -out deploy/certs/server.crt \
  -subj "/CN=isd.internal" -addext "subjectAltName=DNS:isd.internal"
docker compose -f docker-compose.prod.yml up -d --build
```

日志统一走 stdout/stderr：`docker compose -f docker-compose.prod.yml logs -f app nginx`。证书文件已 git 忽略，仓库只保留生成脚本。

### 关键环境变量

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接；生产不要用 root |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 会话与限流；Redis 不可用时应用拒绝启动 |
| `DEEPSEEK_API_KEY` | 保留的环境变量接入方式；后台 Key 管理启用后以管理端为准 |
| `ISD_AI_MASTER_KEY` | Base64 的 32 字节主密钥；多实例必须一致 |
| `ISD_AI_MASTER_KEY_FILE` | 或改用主密钥文件（双击启动默认放持久卷） |
| `ISD_AI_ALLOWED_ORIGINS` | 自定义模型服务的允许来源，逗号分隔，只写协议+域名+端口 |
| `SPRING_PROFILES_ACTIVE=prod` | 关闭 Swagger、启用 CSP、还原 `X-Forwarded-Proto` |

AI 配置、余额与密钥轮换的细节见 [AI 配置与用量](ai-admin.md)。

## 安全基线

| 检查 | 期望 |
|---|---|
| `http://host/api/ping` | `301` 跳 HTTPS |
| `https://host/api/ping` | `200`，且带 CSP / HSTS / `X-Content-Type-Options` / `X-Frame-Options` |
| 会话 Cookie | `Secure; HttpOnly; SameSite=Lax` |
| 无 CSRF 头的写请求 | `403` |
| CSP | `script-src 'self'`，不含 `unsafe-eval`；`style-src` 保留 `'unsafe-inline'` 兼容 Element Plus |
| 8081 | 不对外映射 |

安全头只由 Spring Security 输出，Nginx 不再重复添加（两边都加会出现重复响应头）。CSP 只在 `prod` 启用：开发期 Vite HMR / Swagger UI 需要 eval 与内联脚本。

## 备份与恢复

备份必须包含**三样**，缺一不可：

1. **数据库**：工单、知识、AI 配置密文、用量台账、会话之外的全部业务数据；
2. **附件**：存在 MySQL 的附件表中，随数据库一起备份（单文件 ≤ 5 MB，单工单 ≤ 20 个）；
3. **AI 主密钥**：`ISD_AI_MASTER_KEY`，或 Docker 卷里的 `master.key` / `ISD_AI_MASTER_KEY_FILE`。

```bash
# 数据库（含附件）
docker compose -f docker-compose.prod.yml exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines isd > isd-$(date +%F).sql

# 主密钥（二选一）
docker compose -f docker-compose.prod.yml exec -T app cat /var/lib/isd/ai/master.key > isd-master.key
# 或从部署系统读取 ISD_AI_MASTER_KEY
```

恢复顺序：停应用 → 恢复数据库 → 恢复同一个主密钥 → 启动。**主密钥换了，后台保存的供应商 Key 全部无法解密**，AI 会降级为知识库检索，但工单和知识库不受影响。

## 升级

版本升级、迁移与回滚步骤见 [升级到 v1.1.0](upgrade.md)。原则：先备份数据库与主密钥，再替换镜像/jar；Flyway 迁移只前进，回滚以配套备份恢复为准。

## 可观测性

指标端点单独开在 **8082**（只绑 `127.0.0.1`），8081 不暴露 actuator：

```bash
curl http://127.0.0.1:8082/actuator/health
curl http://127.0.0.1:8082/actuator/prometheus | findstr isd_
```

| 指标 | 类型 | 回答什么 |
|---|---|---|
| `http_server_requests_seconds` | Timer | 全站 P95、5xx 率 |
| `isd_ai_chat_seconds` | Timer | AI 慢不慢、降级多不多 |
| `isd_ai_tool_calls_seconds` | Timer | 工具层失败率 |
| `isd_ai_retrieval_total` | Counter | RAG 召回命中率 |
| `isd_ticket_transitions_total` | Counter | 状态机流量画像 |
| `isd_ticket_conflicts_total` | Counter | 乐观锁冲突 |
| `isd_ticket_idempotent_replays_total` | Counter | 重复提交/重试 |
| `isd_auth_login_total` | Counter | 登录失败、撞库、锁定 |
| `isd_auth_session_revoked_total` | Counter | 重置密码/改密踢人是否生效 |
| `isd_auth_forbidden_total` | Counter | 越权探测（URL / 方法 / 资源级） |
| `isd_sessions_started_total` / `ended_total` | Counter | 在线数在 Prometheus 侧算差值 |

日志全部带 `traceId`：请求带 `X-Request-Id` 就沿用，否则生成并回写响应头。AI 调用在线程池里执行，`TaskDecorator` 会把 MDC 与 SecurityContext 带过去。

## 数据保留

每日 03:30 分批清理：超期 AI 审计/会话、`SENT` 事件、已读通知。单批 1000 行，避免长事务锁表；积压由次日继续消化。

## 容量与限制

- 附件存数据库，备份体积会随附件增长；大文件场景建议后续换对象存储。
- 处理时限使用连续自然小时，暂不支持工作日/节假日日历。
- 列表检索仍是 `LIKE '%kw%'`，数据量上万后建议换 FULLTEXT/ES。
- AI 限流是 Redis 固定窗口（用户 20/min、IP 60/min），窗口边界可能瞬时放行接近两倍。
- Redis 是硬依赖：故障时登录会话与 AI 配额同时不可用。
