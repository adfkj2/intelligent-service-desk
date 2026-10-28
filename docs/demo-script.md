# 演示脚本（约 5 分钟）

> 本文所有步骤在 2026-10-28 已用真实服务 + 真实 DeepSeek 逐条跑通，文末附实测输出。
> 演示目标是让人看到：**状态机、并发保护、权限隔离、AI 基于真实数据回答**这四件事。
> 时间富余时再加一轮 1.1 的「忘记密码 → 管理员重置 → 强制改密」，约 90 秒。

---

## 0. 演示前检查

| 检查项 | 命令 / 方式 | 期望 |
|---|---|---|
| MySQL 在跑 | 服务列表 `MySQL80` | 运行中 |
| 表齐全 | `USE isd; SHOW TABLES;` | 5 张业务表 + `flyway_schema_history` |
| 应用启动 | `java -jar target/isd-0.0.1-SNAPSHOT.jar` | `Tomcat started on port 8081` |
| 管理台 | 打开 `http://localhost:8081/` | 出现登录页（404/白屏说明没构建前端：`cd web && npm run build` 后重新打包） |
| LLM Key | `src/main/resources/application-local.yml` | `spring.ai.openai.api-key` 非占位符 |
| 管理员联系方式 | `SELECT username, phone, email FROM isd.sys_user WHERE role='ADMIN' AND status=1;` | 至少一个管理员填了手机号或邮箱，否则「忘记密码」只能显示空状态 |
| 健康检查 | `http://localhost:8081/api/ping` | `{"code":0,...,"status":"UP"}` |

> 表结构由 Flyway 在启动时创建/升级（`db/migration/V*.sql`），不需要手工执行 SQL；库里出现 `flyway_schema_history` 是正常的。

### 演示账号

| 账号 | 密码 | 角色 | 用途 |
|---|---|---|---|
| `demo_emp` | `employee123` | EMPLOYEE | 提交工单、关闭工单、问 AI |
| `demo_emp2` | `employee123` | EMPLOYEE | 验证越权（看不到别人的工单） |
| `demo_sup` | `support123` | SUPPORT | 接单、写知识库 |
| `demo_admin` | `admin12345` | ADMIN | 指派、建分类、管用户 |

首次准备：注册接口固定给 EMPLOYEE，所以**第一次**需要手工提权出一个管理员：

```sql
UPDATE isd.sys_user SET role = 'ADMIN' WHERE username = 'demo_admin';
```

之后（含 `demo_sup` 的技术支持角色）都可以由管理员在「用户管理 → 角色列 → 调整」完成，**改完必须重新登录**才生效。

---

## 1. 路径 A：页面点选 13 步（现场演示推荐）

打开 <http://localhost:8081/>，用三个演示账号依次操作：

| # | 角色 | 操作 | 期望 | 讲什么 |
|---|---|---|---|---|
| 1 | 员工 | `demo_emp` 登录 | 进入工作台，4 张状态卡有数字 | Session 登录；统计复用列表接口，员工只统计自己的 |
| 2 | 员工 | 新建工单：标题/分类/优先级/描述 | 提交后自动跳到详情，状态「待接单」 | DTO 校验、初始状态、时间线出现 CREATE |
| 3 | 员工 | 我的工单页，输入关键词筛选 | 只出现自己提交的工单，分页由服务端返回 | 员工的数据范围由 Service 强制加 `creator_id`，前端传参绕不过 |
| 4 | 员工 | 右下角打开 AI 助手，问「我的工单处理到哪了」 | 回答引用真实工单号，标注「模型回答」 | AI 工具从 SecurityContext 取身份，只查本人数据 |
| 5 | 技术支持 | 退出后用 `demo_sup` 登录 | 左侧菜单变成「工单池」，能看到全部工单 | 同一套页面按角色裁剪菜单；真正权限在后端 |
| 6 | 技术支持 | 工单池点「接单」并二次确认 | 状态变「处理中」，处理人变成自己 | 状态机 + 乐观锁 UPDATE |
| 7 | 技术支持 | 对同一单再点一次接单 | 右上角提示「工单已被他人接单…」而非报错页 | 409 是预期结果，用 warning 提示 |
| 8 | 技术支持 | 详情页「标记为已解决」并填处理说明 | 状态「已解决」，时间线新增 RESOLVE | 处理说明进审计日志 |
| 9 | 技术支持 | 知识库「新建条目」，填分类/标题/标签/正文 | 列表出现新条目，可按标题查看正文 | SUPPORT 可写；只能改删自己创建的条目 |
| 10 | 管理员 | 退出后用 `demo_admin` 登录 | 菜单多出「用户管理」 | `/api/admin/**` 仅 ADMIN |
| 11 | 管理员 | 用户管理把 `demo_emp2` 的角色「调整」为技术支持；再演示禁用开关 | 角色标签变「技术支持」，提示需重新登录；禁用后该账号无法登录 | 注册默认员工，提权只能管理员操作；不能改自己的角色与状态 |
| 12 | 管理员 | 工单池对另一张待接单工单「指派」给 `demo_sup` | 状态「处理中」，处理人变为被指派人 | 指派校验角色与启用状态 |
| 13 | 员工 | 换回 `demo_emp`，打开自己那张已解决工单点「确认关闭」 | 状态「已关闭」，时间线 4 条：CREATE/CLAIM/RESOLVE/CLOSE | 只有创建者（或管理员）能关闭；审计与状态同事务 |

> 演示 AI 降级：把 `--isd.ai.timeout-seconds=1` 加到启动参数，第 4 步的回答会标注「知识库降级」并列出命中的条目标题，说明 **AI 挂了业务不受影响**。

---

## 1.1 忘记密码 → 管理员重置 → 强制改密（约 90 秒，可选）

前提：`demo_admin` 已在右上角「个人资料」里填了手机号或邮箱（管理员至少一项，否则保存返回 400）。

| # | 角色 | 操作 | 期望 | 讲什么 |
|---|---|---|---|---|
| 1 | 未登录 | 登录页点「忘记密码？」 | 弹窗列出启用中管理员的姓名、手机号、邮箱，电话/邮箱可直接点，也可一键复制 | 每次打开都实时拉 `GET /api/auth/admin-contacts`，字段只有 `fullName/phone/email`，不含用户名与角色 |
| 2 | 管理员 | 「用户管理」找到 `demo_emp2` 点「重置密码」并二次确认 | 结果弹窗用等宽字体展示 12 位一次性临时密码，带复制按钮 | 明文只出现这一次（`Cache-Control: no-store`），日志只记 `targetId/operatorId` |
| 3 | 员工 | 另开无痕窗口用 `demo_emp2` + 临时密码登录 | 直接落到 `/change-password`，提示「首次登录需修改初始密码」 | 登录响应 `mustChangePassword=true`；此时直接请求 `/api/tickets` 返回 403 |
| 4 | 员工 | 填「当前密码」= 临时密码，再填两遍新密码，提交 | 提示成功后进入工作台，可正常建单 | 改密成功重建 principal、轮换 session id，并踢掉该账号其他会话 |
| 5 | 管理员 | 回到用户管理，看自己那一行的操作列 | 不出现「重置密码」按钮 | 服务端同样拒绝：管理员不能重置自己（409），忘记自己的密码只能找另一位管理员 |

演示后如需还原：用 `demo_emp2` 的新密码登录，或让管理员再重置一次。**不要**在演示中途重置 `demo_emp`——它的会话会被立刻踢掉，后面的关闭工单步骤会 401。

---

## 2. 路径 B：Swagger UI（接口视角，备用）

打开 <http://localhost:8081/swagger-ui.html>，按顺序点：

| # | 操作 | 期望 | 讲什么 |
|---|---|---|---|
| 1 | `POST /api/auth/login` 用 `demo_emp` | 200，data.role=EMPLOYEE | 登录成功后浏览器自动带 JSESSIONID，后续接口免 token |
| 2 | `POST /api/tickets` 建单 | 201，status=OPEN，有 ticketNo | DTO 校验、状态初始值、审计日志 CREATE |
| 3 | `GET /api/tickets` | total ≥ 1 | 员工只能看到自己的（Service 强制加 creator_id） |
| 4 | 换成 `demo_sup` 登录 | — | 注意要先 logout 或换个浏览器窗口 |
| 5 | `POST /api/tickets/{id}/claim` | 200，IN_PROGRESS，assigneeId=本人 | 乐观锁；再点一次 → 409 |
| 6 | `PATCH /api/tickets/{id}/status` `{"action":"RESOLVE"}` | 200 RESOLVED | 状态机白名单 |
| 7 | 换回 `demo_emp`，`CLOSE` | 200 CLOSED | 只有创建者能关闭；SUPPORT 关 → 403 |
| 8 | `GET /api/tickets/{id}/operations` | 4 条：CREATE/CLAIM/RESOLVE/CLOSE | 审计与状态同事务 |
| 9 | 用 `demo_emp2` 查这张单 | 404 | 防枚举：越权读伪装成不存在 |
| 10 | `demo_admin` 登录，`POST /api/kb/categories` | 201 | 分类只有 ADMIN 能建 |
| 11 | `demo_sup` 建条目，`GET /api/kb/search?q=打印机` | total ≥ 1 | LIKE 检索、通配符转义 |
| 12 | `POST /api/ai/chat`：`我的工单处理到哪了` | source=LLM，回答含真实 ticketNo | AI 调工具查真实数据 |
| 13 | `POST /api/ai/chat`：`帮我创建一个工单` | 拒绝并引导去页面 | 只读 Agent，工具层根本没有写能力 |

---

## 3. 路径 C：PowerShell 脚本（可复现）

> 注意两个 Windows 坑：
> 1. PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，不能用 bash 的 `-X/-H` 语法；
> 2. 脚本含中文时必须以 **UTF-8 with BOM** 保存，否则 PS 5.1 按 GBK 解析会报语法错误；
> 3. 请求体手动转 UTF-8 字节，否则中文会乱码。

```powershell
$base = 'http://localhost:8081'
function Json($o) { $o | ConvertTo-Json -Depth 6 -Compress }

function Call($method, $uri, $obj, $sess) {
    $p = @{ Method = $method; Uri = $uri; UseBasicParsing = $true; TimeoutSec = 90 }
    if ($sess) { $p.WebSession = $sess }
    if ($null -ne $obj) {
        $p.ContentType = 'application/json; charset=utf-8'
        $p.Body = [System.Text.Encoding]::UTF8.GetBytes((Json $obj))
    }
    $resp = Invoke-WebRequest @p
    # 服务端返回 application/json 不带 charset，PS 会按 ISO-8859-1 解，必须自己按 UTF-8 解码
    return ([System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json)
}

function Login($user, $pwd) {
    $body = [System.Text.Encoding]::UTF8.GetBytes((Json @{ username = $user; password = $pwd }))
    Invoke-WebRequest -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' `
        -Body $body -UseBasicParsing -SessionVariable s | Out-Null
    return $s
}

$emp = Login 'demo_emp' 'employee123'
$sup = Login 'demo_sup' 'support123'
$adm = Login 'demo_admin' 'admin12345'

# 建单
$t = Call 'Post' "$base/api/tickets" @{ title='打印机无法连接'; description='3 楼打印机离线'; category='IT_FAULT'; priority='HIGH' } $emp
$id = $t.data.id

# 接单 -> 解决 -> 关闭
Call 'Post'  "$base/api/tickets/$id/claim"  $null $sup | Out-Null
Call 'Patch' "$base/api/tickets/$id/status" @{ action='RESOLVE'; comment='已重启打印服务' } $sup | Out-Null
Call 'Patch' "$base/api/tickets/$id/status" @{ action='CLOSE';   comment='问题已解决' }     $emp | Out-Null

# 审计
(Call 'Get' "$base/api/tickets/$id/operations" $null $emp).data | Select-Object action, fromStatus, toStatus

# AI
(Call 'Post' "$base/api/ai/chat" @{ question='我的工单处理到哪了' } $emp).data
```

---

## 4. 验收清单（本次实测结果）

| 步骤 | 预期 | 实测 |
|---|---|---|
| 员工建单 | 201 / OPEN | `id=154 no=T20261028-3185 status=OPEN` |
| 员工列表 | 只看到自己的 | `total=1` |
| 支持接单 | 200 / IN_PROGRESS | `assigneeId=49`（demo_sup） |
| 重复接单 | 409 | `CLAIM_AGAIN -> 409` |
| 标记解决 | 200 / RESOLVED | 通过 |
| 创建者关闭 | 200 / CLOSED | 通过 |
| 操作历史 | 4 条且顺序正确 | `count=4 actions=CREATE,CLAIM,RESOLVE,CLOSE` |
| 他人读单 | 404 | `CROSS_USER_READ -> 404` |
| 管理员指派 | 200 / IN_PROGRESS | `assigneeId=49` |
| 建分类（ADMIN） | 201 | `id=26 name=硬件故障` |
| 建条目（SUPPORT） | 201 且带分类名 | `id=23 category=硬件故障` |
| 关键词检索 | 命中 | `total=1 first=打印机无法连接的处理步骤` |
| AI 查工单 | source=LLM，含真实单号 | 回答引用 `T20261028-3185`、状态 CLOSED、处理人 49 |
| AI 查知识库 | source=LLM，引用条目 | 引用《打印机无法连接的处理步骤》编号 23 并给出排查步骤 |
| AI 被要求建单 | 拒绝 | 「我目前只有查询权限，无法创建工单」 |
| 管理员重置密码 | 200 + 12 位临时密码，旧密码失效 | 自动用例通过（`PasswordFlowTest`） |
| 旧会话被踢 | 下一次请求 401 | 自动用例通过（`SessionRegistry` 标记过期） |
| 临时密码登录 | `mustChangePassword=true`，业务接口 403 | 自动用例通过 |
| 改密后 | 标志清除，新密码可登录、临时密码 401 | 自动用例通过 |
| 公开联系方式 | 仅启用管理员，且不含 `username` | 自动用例通过；清空联系方式保存 → 400 |

> 密码闭环这几行由 `PasswordFlowTest`（真实 MySQL、5 个用例、类级事务回滚）覆盖，没有在演示数据上手工重置，避免把演示账号的会话踢掉。

AI 实测原文（节选）：

```
您目前只有 1 个工单：
- **T20261028-3185**｜打印机无法连接
- 状态：已关闭（CLOSED）
- 优先级：高
- 处理人 ID：49
```

---

## 5. 演示 AI 降级（30 秒）

任选一种，重启后 `POST /api/ai/chat` 会返回 `source=FALLBACK`：

1. 把 `application-local.yml` 里的 Key 改错一个字符；
2. 或启动时加参数 `--isd.ai.timeout-seconds=1`（DeepSeek 正常响应 2–5 秒，必然超时）。

预期响应：

```json
{"code":0,"message":"OK","data":{
  "answer":"AI 响应超时。先给你知识库里的相关条目：...",
  "source":"FALLBACK",
  "relatedTitles":["打印机无法连接的处理步骤"]}}
```

要点：**AI 挂了业务不受影响**，接口仍 200，前端据 `source` 提示"AI 暂不可用"。

---

## 6. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `curl: 无法将 -X 识别为参数` | PowerShell 里 curl 是别名 | 用 `Invoke-RestMethod`，或在 cmd 里跑 curl |
| 请求中文乱码 / 400 | 请求体按默认编码发送 | `[Text.Encoding]::UTF8.GetBytes(...)` |
| 所有接口 401 | 未传 Cookie | 登录请求加 `-SessionVariable`，后续加 `-WebSession` |
| 分类建不了（403） | 账号还是 EMPLOYEE | 执行 SQL 提权后**重新登录** |
| 接单 409 | 单已被接/状态不是 OPEN | 这是预期行为，正好讲解乐观锁 |
| AI 返回 FALLBACK | Key 无效、超时或未配置 | 检查 Key 与网络；这是降级而非故障 |
| 「忘记密码」弹窗为空 | 没有**启用中**的管理员填联系方式 | 让管理员在「个人资料」补手机号或邮箱；降权/禁用后会自动消失 |
| 重置后对方报 401 | 预期：该账号所有会话已被失效 | 用临时密码重新登录，改完密码即可正常使用 |
| 用临时密码登录后处处 403 | 预期：没改密前只放行改密/me/logout | 进 `/change-password` 改完密码 |
| 改密提示「原密码不正确」 | 输的是重置前的旧密码 | 用管理员给的临时密码作为当前密码 |
