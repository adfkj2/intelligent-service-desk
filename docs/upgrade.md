# 升级到 v1.1.0

适用对象：已经在运行 **v1.0.0**，要升级到 **v1.1.0** 的环境。首次安装直接看 [README「三步启动」](../README.md#三步启动)，不需要本文。

## 这次升级做了什么

v1.1.0 把 v1.0.0 之后的能力正式收进版本：工单协作（公开回复/内部备注/附件/转派/重开）、处理时限与分派建议、智能报障与处理助手、知识草稿审核、管理员多供应商 AI 配置与用量台账、一键启动脚本，以及 README 与实际页面截图。

数据库会执行 **V7–V13** 共 7 个增量迁移（V1–V6 已在 v1.0.0 执行过）。迁移只新增表/列/索引，不删除既有数据，但**不可回退**，所以必须先备份。

> 本次版本整理不新增业务 API、不新增数据库迁移；V7–V13 是 v1.0.0 之后已经开发完成、随本版本一并发布的迁移。

## 升级前检查

| 项 | 要求 |
|---|---|
| 当前版本 | v1.0.0（`/api/ping` 或镜像 tag 可确认） |
| 数据库 | MySQL 8.0，`flyway_schema_history` 最新为 V6 |
| 磁盘 | 数据库 + 附件的 2 倍空间（迁移会建索引） |
| 主密钥 | 若已使用后台 AI 配置，先确认 `ISD_AI_MASTER_KEY` / `master.key` 可获取 |
| 维护窗口 | 迁移期间停止写入；附件在数据库中，避免边迁移边上传 |

## 1. 备份（必做）

```bash
# 数据库（含附件、知识、AI 配置密文、用量台账）
docker compose -f docker-compose.prod.yml exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines isd \
  > isd-v1.0.0-$(date +%F).sql

# AI 主密钥（后台配置过 Key 就必须备份）
docker compose -f docker-compose.prod.yml exec -T app cat /var/lib/isd/ai/master.key \
  > isd-master.key
# 或从部署系统导出 ISD_AI_MASTER_KEY
```

把 SQL 与主密钥放到**与数据库不同的机器**上。只备份数据库、不备份主密钥，恢复后后台 Key 无法解密。

## 2. 升级

### Docker Compose（推荐）

```bash
git fetch --tags
git checkout v1.1.0

# 重新构建（前端在镜像内构建，jar 内含页面）
docker compose -f docker-compose.prod.yml up -d --build

# 等待应用就绪
curl -k --fail https://localhost/api/ping
```

### 直接替换 JAR

```bash
# 1) 用发布附件里的 isd-1.1.0.jar（已包含构建后的前端）
# 2) 停旧进程，替换 jar，保持原有环境变量不变
java -jar isd-1.1.0.jar
```

启动时 Flyway 自动执行 V7–V13。观察日志出现 `Successfully applied 7 migrations` 或 `Schema is up to date`。

## 3. 升级后验证

- [ ] `/api/ping` 返回 200，登录正常
- [ ] `flyway_schema_history` 最新版本为 13
- [ ] 工单列表能打开，旧工单的回复/附件区域正常
- [ ] 知识库旧条目可读，待审核草稿为空或符合预期
- [ ] 若使用后台 AI：配置页能看到原有供应商，Key 仍为掩码；测试连接或直接问答能走通
- [ ] 生产环境 CSP / HSTS / Secure Cookie 仍在（见 [部署与运维](operations.md#安全基线)）
- [ ] 上传一个附件并下载，确认附件链路正常

## 4. 回滚

Flyway 不支持自动降级。回滚以**备份恢复**为准：

1. 停止 v1.1.0 应用；
2. 用升级前的 SQL 覆盖数据库（会丢失升级后的新数据）；
3. 恢复同一个 AI 主密钥；
4. 换回 v1.0.0 的 jar/镜像并启动；
5. 验证 `/api/ping`、登录与工单详情。

不要试图手工执行 `DROP COLUMN` / 删除 V7–V13 的表来“降级”，Flyway 校验和与数据依赖都会出问题。

## 5. 已知注意事项

- **Secure Cookie**：生产经 HTTPS 访问；用 `http://IP:8081` 直连会导致登录态不回传，看起来像“登录失败”。
- **CSP**：只在 `prod` profile 下发；开发期关闭是刻意为之（Vite HMR / Swagger 需要 eval）。
- **Redis**：升级期间 Redis 可保留，但会话会因应用重启而失效，用户需重新登录；这是预期行为。
- **AI 主密钥**：多实例必须使用同一个值；轮换主密钥需要重新录入后台 Key。
- **附件**：存数据库，升级备份请确认 SQL 体积包含附件表。
