# 压测（k6）

用 [k6](https://k6.io/) 压三条最值得量化的路径：工单读接口、AI 降级、AI 配额限流。
脚本即代码，阈值写死在 `options.thresholds` 里，跑不过就退出码非 0——报告里的数字因此可复现。

## 前置条件

1. 全栈已在跑（**不要压本机 MySQL80**，数据要能一键清掉）：

   ```bash
   docker compose -f docker-compose.stack.yml up -d
   ```

2. 准备压测账号（注册接口固定给 EMPLOYEE，够读自己的工单；造数据也用同一个账号）：

   ```powershell
   $body = @{ username = 'perf_emp'; password = 'perf12345'; fullName = '压测账号' } | ConvertTo-Json
   Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/auth/register `
       -ContentType 'application/json; charset=utf-8' `
       -Body ([Text.Encoding]::UTF8.GetBytes($body))
   ```

   重复执行会返回 409/400（用户名已存在），属于正常。

3. 本机没有 k6 也可以：`run.ps1` 用 `grafana/k6` 镜像跑，脚本目录只读挂载进容器。

## 运行

```powershell
# 工单列表 + 详情（默认 10 VU、20s，setup 里造 50 条工单）
.\perf\k6\run.ps1 tickets

# 换并发与时长
.\perf\k6\run.ps1 tickets -Env @{ VUS = 20; DURATION = '30s' }

# AI 降级 + 配额（1 VU、25 次，预期第 21 次起 429）
.\perf\k6\run.ps1 ai

# macOS / Linux
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -v "$PWD/perf/k6:/scripts:ro" -e BASE_URL=http://host.docker.internal:8081 \
  grafana/k6:0.54.0 run /scripts/tickets.js
```

## 阈值（跑不过就是失败，不是"参考值"）

| 脚本 | 指标 | 阈值 | 为什么是这个数 |
|---|---|---|---|
| tickets | `http_req_failed` | < 1% | 读接口不该出现失败 |
| tickets | `list` p95 | < 300ms | 无关键词分页，走 `idx_status_created` / `idx_creator` |
| tickets | `list_kw` p95 | < 500ms | `LIKE '%kw%'` 无法走索引，阈值放宽是承认代价，不是合格线 |
| tickets | `detail` p95 | < 200ms | 主键查询 + 权限校验 |
| ai | `ai_server_error` | == 0 | 降级路径不该 5xx |
| ai | `ai_rate_limited` | ≥ 1 | 配额确实生效（没生效说明护栏被关掉了） |

## 数据清理

压测数据全部落在 `docker-compose.stack.yml` 的独立 MySQL 卷里，跑完直接清库：

```bash
docker compose -f docker-compose.stack.yml down -v
```

造数据用的是固定 `Idempotency-Key`（`perf-seed-<n>`），重跑不会产生重复工单，也不会把库越撑越大。

实测结果与环境说明见 [docs/perf-report.md](../docs/perf-report.md)。
