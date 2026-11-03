# 接口一览

统一响应：`{"code": 0, "message": "OK", "data": ...}`，HTTP 状态码保留 2xx/400/401/403/404/409/429/500 语义。

共 **69** 个 `/api` 接口，完整清单由 `ApiInventoryTest` 与 `src/test/resources/api-endpoints.txt` 逐行守护。新增/删除接口未同步快照时 CI 失败。

- 交互式文档：启动后打开 <http://localhost:8081/swagger-ui.html>（先调 `POST /api/auth/login`）
- 协作、AI、分派与知识审核的新接口：[工单协作与 AI 辅助](service-desk-upgrade.md#新增接口)
- 管理员 AI 配置与用量：[AI 配置与用量](ai-admin.md#管理接口)

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 认证 | POST | `/api/auth/register` | 公开 |
| 认证 | POST | `/api/auth/login` | 公开（失败 5 次锁 15 分钟） |
| 认证 | POST | `/api/auth/logout` | 登录 |
| 认证 | GET | `/api/auth/me` | 登录 |
| 认证 | GET | `/api/auth/admin-contacts` | 公开（仅启用管理员） |
| 认证 | PUT | `/api/auth/me/contact` | 登录（仅改自己） |
| 认证 | POST | `/api/auth/change-password` | 登录 |
| 工单 | POST | `/api/tickets`（可选头 `Idempotency-Key`） | 登录 |
| 工单 | GET | `/api/tickets?page=&size=&status=&category=&keyword=` | 登录（员工仅自己） |
| 工单 | GET | `/api/tickets/{id}` | 登录（资源级校验） |
| 工单 | GET | `/api/tickets/stats/{status}` | 登录 |
| 工单 | POST | `/api/tickets/{id}/claim` | SUPPORT |
| 工单 | POST | `/api/tickets/{id}/assign` | ADMIN |
| 工单 | PATCH | `/api/tickets/{id}/status` | 处理人/创建者/ADMIN |
| 工单 | GET | `/api/tickets/{id}/operations` | 登录（资源级校验） |
| 知识库 | GET | `/api/kb/categories` | 登录 |
| 知识库 | POST | `/api/kb/categories` | ADMIN |
| 知识库 | GET | `/api/kb/items?categoryId=&page=&size=` | 登录 |
| 知识库 | POST | `/api/kb/items` | SUPPORT / ADMIN |
| 知识库 | GET | `/api/kb/items/{id}` | 登录 |
| 知识库 | PUT | `/api/kb/items/{id}` | 创建者 / ADMIN |
| 知识库 | DELETE | `/api/kb/items/{id}` | 创建者 / ADMIN |
| 知识库 | GET | `/api/kb/search?q=&page=&size=` | 登录 |
| AI | POST | `/api/ai/chat` | 登录（超过配额 429，消息带剩余秒数） |
| 站内信 | GET | `/api/notifications?limit=` | 登录（仅自己） |
| 站内信 | GET | `/api/notifications/unread-count` | 登录（仅自己） |
| 站内信 | PATCH | `/api/notifications/{id}/read` | 登录（仅自己，越权 404） |
| 站内信 | POST | `/api/notifications/read-all` | 登录（仅自己） |
| 用户管理 | GET | `/api/admin/users` | ADMIN |
| 用户管理 | PATCH | `/api/admin/users/{id}/status` | ADMIN |
| 用户管理 | PATCH | `/api/admin/users/{id}/role` | ADMIN |
| 用户管理 | POST | `/api/admin/users/{id}/password/reset` | ADMIN（不能重置自己） |

> 以上是核心接口摘要；协作回复、附件、转派、队列、知识草稿/审核、AI 会话/建议、供应商配置、用量查询等接口见上方的专题文档。
