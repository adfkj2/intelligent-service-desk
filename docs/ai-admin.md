# AI 配置与用量管理

管理员登录后，在侧栏打开 **AI 配置与用量**（`/admin/ai`）。员工和技术支持无法访问该页面及其接口。

## 配置与切换

1. 新增配置，填写名称、供应商、完整 Base URL、模型标识和 API Key。界面仅显示 Key 末四位；编辑时留空表示沿用原 Key。
2. 保存为待启用版本。点击“测试连接”验证认证、模型及工具调用；测试只发送固定虚构文本，不查询工单或知识库，可能产生少量 Token 费用，归类为“连接测试”。
3. 测试通过后点击“设为当前”，确认切换。所有 AI 功能统一使用该版本，不必重启。编辑当前配置不会直接改变正在使用的版本；已通过测试的历史版本可重新启用，连接测试仅针对最新保存版本。
4. “停用 AI”会让后续请求使用知识库降级；归档配置需先切换或停用其当前版本。归档保留历史用量，清除配置中的密钥密文。

切换不会改变已开始的调用。每个调用固定供应商、Key、模型、价格和配置版本，直到其所有工具调用与重试结束。模型故障时沿用知识库降级，不自动把业务资料发送给其他供应商。

未使用后台管理前，系统兼容 `DEEPSEEK_API_KEY` 等原有 Spring AI 配置，页面显示“环境变量配置”。首次启用后台版本或显式停用后，不再自动回退环境变量中的 Key。

### 服务地址

| 供应商 | Base URL |
|---|---|
| DeepSeek | `https://api.deepseek.com` |
| OpenAI | `https://api.openai.com/v1` |
| 通义千问 | 从百炼控制台复制所在地域、业务空间的兼容 Base URL；旧北京地址 `https://dashscope.aliyuncs.com/compatible-mode/v1` 仍可使用 |
| 自定义兼容服务 | 部署方允许的 HTTPS 地址，协议需支持 Chat Completions 和工具调用 |

地址填写到 SDK Base URL，不包含 `/chat/completions`；系统统一追加该路径，不重复追加 `/v1`。通义千问的 Key 和地址必须属于相同地域。供应商的特定模型参数或原生 Claude/Gemini 接口不在首版范围内；应通过连接测试确认兼容性。

自定义服务需先由部署方设置 `ISD_AI_ALLOWED_ORIGINS`，例如 `https://models.example.com,https://other.example.com:8443`。白名单只包含协议、域名及可选端口；配置中的 Base URL 可带 API 路径。系统拒绝非 HTTPS、内嵌账号、查询参数、片段、路径跳转和未经允许的域名，并禁止跟随模型接口重定向。

## 统计口径

- 默认最近 30 天，可切换今日、7 天或最长 365 天范围，时间按北京时间计算。可按供应商、配置、模型、用户、用途和结果筛选。
- **业务调用**是一项实际进入模型流程的操作；未配置或已停用而直接降级的请求不产生模型消耗记录。**上游请求**包括每个工具回合及重试，一次业务调用可能对应多次上游请求。单次业务调用最多允许 16 次上游请求，避免无限工具循环。
- Token 来自每个上游原始响应；缓存命中 Token 已包括在输入中，思考 Token 已包括在输出中，不重复相加。失败未返回用量时显示“未知／不完整”，已知部分仍可查看，不能把失败认作零消耗。
- 页面超时返回降级后，上游任务可能仍在运行并产生费用；台账会继续补记。业务结果与上游最终结果分别保存，因此“页面超时、上游成功”是有效组合。进程中断留下的未完成记录会被标为不完整。
- 每项配置可填写每百万 Token 的未缓存输入、缓存输入、输出单价和币种。费用按调用时价格估算，修改单价不重算历史；缺少价格或完整用量时不显示虚假零金额。不同币种分别展示，不做汇率换算。阶梯价格、优惠、免费额度等差异以供应商账单为准。
- 连接测试单独归类；普通问答、智能建单、处理助手和知识草稿均纳入统计。用量台账不保存员工的问题和回答正文，元数据默认保留 365 天。升级前没有 Token 数据的历史记录不补估。
- 台账暂不可写时不发起新的付费模型请求，仍按原有策略降级；响应后的记录失败保留不完整标记，不假装精确账单。

## 官方余额

DeepSeek 配置可手动刷新官方**账户余额**，显示币种、赠送余额、充值余额及查询时间。它是整个供应商账户的余额，同一账户的多个 Key 查询结果不能相加，也不能与本系统消耗直接等同。查询失败保留上次成功结果并标为过期；成功结果超过 15 分钟也会提示刷新，连续刷新间隔至少 10 秒。

OpenAI、通义千问和自定义服务首版显示官方账务入口或查询能力说明，不配置额外财务凭据，不使用未公开的账务接口。

参考：[DeepSeek 余额接口](https://api-docs.deepseek.com/api/get-user-balance/)、[百炼 Base URL](https://help.aliyun.com/zh/model-studio/base-url)、[OpenAI 账务接口](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs)。

## 主密钥、备份与恢复

供应商 Key 使用 AES-256-GCM 加密后存入数据库，主密钥独立于数据库，接口不提供明文回读。所有配置更新、测试、切换和归档操作记录管理员与版本，不记录密钥。

**Windows 双击／本地 Docker：** 无需额外操作。首次打开配置页时自动生成随机主密钥，保存到 `isd-stack_isd-ai-secrets` 持久卷的 `master.key`，容器内路径 `/var/lib/isd/ai/master.key`。`stop.cmd` 会保留这个卷。原生本地运行使用 `.local/ai-master.key`，该目录被 Git 和镜像构建忽略。

**生产及多实例：** 通过 `ISD_AI_MASTER_KEY` 注入 Base64 编码的 32 字节随机主密钥，所有实例必须一致；也可通过 `ISD_AI_MASTER_KEY_FILE` 指向部署方只读挂载的主密钥文件。生产不自动生成主密钥；未提供时工单和原有环境变量模式仍可运行，后台配置录入不可用。Docker 生产编排默认传递 `ISD_AI_MASTER_KEY`；使用文件方式时需在部署覆盖文件中添加只读挂载及变量。

可在 Windows PowerShell 生成随机值并放入当前进程环境供部署读取，避免在脚本中写固定值：

```powershell
$randomBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$generator.GetBytes($randomBytes)
$env:ISD_AI_MASTER_KEY = [Convert]::ToBase64String($randomBytes)
$generator.Dispose()
```

将生成的值安全保存到部署秘密管理中，后续启动和所有实例复用同一值，不能每次部署重新生成。数据库备份必须配套备份原主密钥；本地 Docker 迁移时需同时迁移 MySQL 数据卷和 AI 主密钥卷。不要把主密钥提交仓库或写进镜像。

恢复时先提供原主密钥，再连接已恢复的数据库。已有加密配置却缺少或使用错误主密钥时，系统明确报错并停止使用这些凭据，不静默重建、覆盖或以明文存储。主密钥丢失无法解密原 Key，应恢复备份；不要直接清理数据卷解决问题。

## 管理接口

所有接口位于 `/api/admin/ai`，仅管理员会话可用，写操作执行 CSRF 校验，响应不缓存。

| 接口 | 用途 |
|---|---|
| `GET/POST /configurations`，`PUT /configurations/{id}` | 列表、新建、保存待启用版本 |
| `GET /configurations/{id}/versions` | 查看配置版本及测试、当前状态 |
| `POST /configurations/{id}/test`，`POST /configurations/{id}/archive` | 测试指定版本、归档配置 |
| `GET/PUT /runtime` | 查看来源及状态、确认切换或停用 |
| `GET /configurations/{id}/balance`，`POST /configurations/{id}/balance/refresh` | 缓存余额及刷新 |
| `GET /usage/summary`、`/usage/daily`、`/usage/users`、`/usage/calls` | 汇总、趋势、用户排名、分页明细 |

配置保存携带最新配置 `version`，切换携带当前运行设置 `version` 和目标 `configurationId/configurationVersion`；冲突返回 409，应刷新后重新确认。用量接口支持 `from/to`（ISO 日期，包含首尾）、`provider/configurationId/model/userId/purpose/outcome`；明细另支持 `page/size`。
