# README 截图说明

六张页面截图来自 **真实运行的 v1.1.0 应用**，不是设计稿或拼接图。

| 文件 | 页面 | 展示重点 |
|---|---|---|
| `workbench.png` | 工作台 | 待办队列、最近工单、服务效率、人员负荷 |
| `ticket-create.png` | 智能报障 | VPN 描述、缺失信息追问、可编辑草稿、提交确认 |
| `ticket-detail.png` | 工单协作 | 公开回复、内部备注、附件、状态与处理时限 |
| `knowledge-review.png` | 知识审核 | 适用环境、处理步骤、复核日期、人工发布 |
| `ai-config.png` | AI 配置 | 掩码 Key、配置版本、待测试状态、测试与切换入口 |
| `ai-usage.png` | AI 用量 | 趋势、用户分布、分币种估算、不完整用量提示 |

## 数据来源与边界

- 演示环境：独立 compose 项目 `isd-demo`，全新数据卷，只绑定 `127.0.0.1:18081`；
- 账号与工单：虚构员工（陈晓、李然）、技术支持（周宁、赵敏）、管理员（王珂）；案例为 VPN、打印机、权限申请等常见场景；
- 工单、回复、附件、知识草稿、AI 配置均通过**真实接口**生成；知识草稿保留“待审核”，发布条目走完整审核流程；
- AI 配置使用明显的占位 Key（形如 `sk-demo-...-9f3a`），保存后保持“待测试”，不发起真实模型调用；
- **AI 用量图的 32 条台账为模拟数据**，页面与 README 图注均已标注；不代表任何真实费用；
- 截图中不含真实密钥、内部地址、个人信息。

## 复现

```bash
# 1) 重建隔离演示库 + 写入演示数据（会清空 isd-demo 数据卷）
docker compose -f docker-compose.demo.yml down -v
docker compose -f docker-compose.demo.yml up -d --build
node web/e2e/demo/seed.mjs

# 2) 采集截图（1440×1000）
node web/e2e/demo/screenshots.mjs
```

两个脚本都内置保护：只允许 `http://localhost:18081`，不会连接日常开发或生产环境。`seed.mjs` 默认重建数据卷，保证每次截图数据一致。

依赖：Node 20+、`web/node_modules`（`npm ci`）、Chromium（`npx playwright install chromium`）。
