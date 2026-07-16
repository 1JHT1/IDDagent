# 04 - API 接口参考

## 4.1 API 总览

| 分组 | 方法 | URL | 需认证 | 功能 |
|------|------|-----|--------|------|
| 认证 | POST | `/api/auth/register` | 否 | 用户注册 |
| 认证 | POST | `/api/auth/login` | 否 | 用户登录 |
| 认证 | GET | `/api/user/me` | 是 | 获取当前用户信息 |
| 健康 | GET | `/api/health` | 否 | 健康检查 |
| 会话 | GET | `/api/conversations` | 是 | 获取会话列表 |
| 会话 | POST | `/api/conversations` | 是 | 创建新会话 |
| 会话 | GET | `/api/conversations/{id}` | 是 | 获取会话详情 |
| 会话 | DELETE | `/api/conversations/{id}` | 是 | 删除会话 |
| 聊天 | POST | `/api/chat/stream` | 是 | 流式聊天（SSE） |
| 数据 | GET | `/api/risk-report/{code}` | 否 | 获取风险报告数据 |
| 数据 | GET | `/api/outreach/{code}` | 否 | 获取拓户材料数据 |
| 数据 | GET | `/api/product-recommend/{code}` | 否 | 获取产品推荐数据 |
| 模板 | GET | `/api/customer-template` | 是 | 下载客户清单 Excel 模板 |
| 上传 | POST | `/api/customer-upload` | 是 | 上传客户清单 Excel |
| 开户 | POST | `/api/account-opening/upload` | 否 | 上传开户资料图片 |
| 开户 | POST | `/api/account-opening/process/{id}` | 否 | 触发 Mock OCR 处理 |
| 开户 | GET | `/api/account-opening/preview/{id}` | 否 | 获取预填表单数据 |
| 开户 | PUT | `/api/account-opening/update/{id}` | 否 | 修改预填表单 |
| 开户 | POST | `/api/account-opening/submit/{id}` | 否 | 提交开户申请 |
| 开户 | POST | `/api/account-opening/notify/{id}` | 否 | H5 提交通知回调 |
| 开户 | GET | `/api/account-opening/notifications/{conv_id}` | 否 | 查询开户提交通知 |

---

## 4.2 认证接口

### POST /api/auth/register

注册新用户。

- **前端调用位置**: `LoginPage.tsx:38-44`
- **后端处理位置**: `main.py:203-213`

**请求体**:
```json
{
  "username": "string (必填)",
  "password": "string (必填)"
}
```

**返回结果** (200):
```json
{
  "access_token": "string (JWT)",
  "token_type": "bearer",
  "user": {
    "id": "string (UUID)",
    "username": "string",
    "created_at": "string (ISO datetime)"
  }
}
```

**错误码**:
| 状态码 | 说明 |
|--------|------|
| 409 | 用户名已存在 |

---

### POST /api/auth/login

用户登录。

- **前端调用位置**: `LoginPage.tsx:38-44`
- **后端处理位置**: `main.py:216-226`

**请求体**:
```json
{
  "username": "string (必填)",
  "password": "string (必填)"
}
```

**返回结果** (200): 同注册接口返回结构。

**错误码**:
| 状态码 | 说明 |
|--------|------|
| 401 | 用户名或密码错误 |

---

### GET /api/user/me

获取当前登录用户信息，验证 Token 有效性。

- **前端调用位置**: 应用启动时检查登录状态（间接使用）
- **后端处理位置**: `main.py:229-232`

**请求头**:
```
Authorization: Bearer <JWT Token>
```

**返回结果** (200):
```json
{
  "id": "string",
  "username": "string",
  "created_at": "string"
}
```

**错误码**: 401（Token 过期/无效）

---

## 4.3 健康检查

### GET /api/health

- **前端调用位置**: `api/agent.ts:33-41`（`checkHealth()`）
- **后端处理位置**: `main.py:238-246`

**返回结果** (200):
```json
{
  "status": "ok",
  "service": "对公账户开户智能体",
  "version": "1.0.0",
  "timestamp": "ISO datetime"
}
```

---

## 4.4 会话管理接口

### GET /api/conversations

获取当前用户的会话列表，按 `updated_at` 降序排列。

- **前端调用位置**: `api/agent.ts:46-53`（`getConversations()`），`App.tsx:86`
- **后端处理位置**: `main.py:252-266`

**请求头**: `Authorization: Bearer <JWT>`

**返回结果** (200):
```json
{
  "conversations": [
    {
      "id": "string (UUID)",
      "user_id": "string",
      "title": "string",
      "message_count": 0,
      "created_at": "string",
      "updated_at": "string"
    }
  ]
}
```

---

### POST /api/conversations

创建新会话。

- **前端调用位置**: `api/agent.ts:58-68`（`createConversation()`），`App.tsx:97`
- **后端处理位置**: `main.py:269-281`

**请求体**:
```json
{
  "title": "string (可选，默认 '新对话')"
}
```

**返回结果** (200):
```json
{
  "id": "string (UUID)",
  "user_id": "string",
  "title": "string",
  "created_at": "string"
}
```

---

### GET /api/conversations/{conversation_id}

获取指定会话详情（含所有消息）。

- **前端调用位置**: `api/agent.ts:73-81`（`getConversation()`），`App.tsx:111`
- **后端处理位置**: `main.py:284-293`

**返回结果** (200):
```json
{
  "id": "string",
  "user_id": "string",
  "title": "string",
  "messages": [
    {
      "id": "string",
      "role": "user | assistant",
      "content": "string",
      "created_at": "string"
    }
  ],
  "created_at": "string",
  "updated_at": "string"
}
```

**错误码**: 404（会话不存在）

---

### DELETE /api/conversations/{conversation_id}

删除指定会话。

- **前端调用位置**: `api/agent.ts:86-92`（`deleteConversation()`），`App.tsx:126`
- **后端处理位置**: `main.py:296-306`

**返回结果** (200):
```json
{
  "status": "deleted",
  "id": "string"
}
```

---

## 4.5 流式聊天接口

### POST /api/chat/stream

**核心接口**。发送用户消息，返回 SSE 流式响应。

- **前端调用位置**: `api/agent.ts:98-166`（`sendMessageStream()`）
- **后端处理位置**: `main.py:312-514`
- **涉及数据表**: conversations（内存）+ context_memory + skills 数据

**请求体**:
```json
{
  "message": "string (必填，用户消息)",
  "conversation_id": "string | null (可选，不传则创建新会话)"
}
```

**响应格式**: `text/event-stream`（SSE）

**SSE 事件类型**:

| type | data 结构 | 说明 |
|------|-----------|------|
| `meta` | `{"type":"meta","conversation_id":"..."}` | 会话元数据 |
| `text_start` | `{"type":"text_start","message_id":"..."}` | 文本开始 |
| `text_delta` | `{"type":"text_delta","content":"...","message_id":"..."}` | 文本增量 |
| `text_done` | `{"type":"text_done","content":"...","message_id":"..."}` | 文本完成 |
| `potential_customer_summary` | `{"type":"...","data":{"action":"summary","sources":[...]}}` | 潜客汇总 |
| `potential_customer_detail` | `{"type":"...","data":{"action":"detail","customers":[...]}}` | 潜客详情 |
| `risk_check_result` | `{"type":"...","data":{"action":"result","risk_level":"...","h5_url":"..."}}` | 风险结果 |
| `outreach_result` | `{"type":"...","data":{"action":"result","contact_channels":[...]}}` | 拓户结果 |
| `product_recommend_result` | `{"type":"...","data":{"action":"result","products":[...]}}` | 产品推荐 |
| `product_match_result` | `{"type":"...","data":{"action":"result","matches":[...]}}` | 产品匹配 |
| `account_opening_result` | `{"type":"...","data":{"action":"result","status":"upload",...}}` | 开户结果 |
| `follow_up_suggestion` | `{"type":"...","content":"是否需要..."}` | 追问建议 |
| `done` | `{"type":"done","conversation_id":"..."}` | 流结束 |
| `error` | `{"type":"error","content":"..."}` | 错误 |

**涉及数据**:
- 涉及的所有 JSON 数据文件（按技能不同而异）
- 会话存储于内存（`conversations` 字典）

**边界情况**:
- 不传 conversation_id → 自动创建新会话
- 技能返回 error → 作为普通文本输出
- Coordinator JSON 解析失败 → 回退为普通聊天
- 所有异常被 try-except 捕获，返回 SSE error 事件

---

## 4.6 H5 数据接口

### GET /api/risk-report/{credit_code}

- **前端调用位置**: `risk-report.html`（H5 页面直接 fetch）
- **后端处理位置**: `main.py:520-537`
- **涉及数据表**: `risk_check.json`

**返回结果** (200): 风险检查 JSON 数据。

**错误码**: 404（数据不存在）

### GET /api/outreach/{credit_code}

- **前端调用位置**: `marketing-insights.html`、`marketing-script.html`（H5 页面直接 fetch）
- **后端处理位置**: `main.py:543-560`
- **涉及数据表**: `customer_outreach.json`

**返回结果** (200): 拓户准备 JSON 数据。

### GET /api/product-recommend/{credit_code}

- **前端调用位置**: `product-recommend.html`（H5 页面直接 fetch）
- **后端处理位置**: `main.py:566-607`
- **涉及数据表**: `product_recommendations.json`

**返回结果** (200): 产品推荐 JSON 数据（按优先级排序）。

---

## 4.7 客户清单接口

### GET /api/customer-template

下载客户清单上传模板（Excel .xlsx）。

- **前端调用位置**: `PotentialCustomerCard.tsx:36`（`handleDownloadTemplate()`）
- **后端处理位置**: `main.py:614-678`

**返回**: Excel 文件（`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`）

**模板格式**:
| 企业名称 | 统一社会信用代码 | 推荐得分(0-100) |
|----------|------------------|------------------|
| 示例科技有限公司 | 91110108MA01B3XK2P | 85.5 |

---

### POST /api/customer-upload

上传客户清单 Excel 文件。

- **前端调用位置**: `PotentialCustomerCard.tsx:72`（`handleUpload()`）
- **后端处理位置**: `main.py:681-766`
- **涉及数据表**: `uploaded_customers.json`

**请求格式**: `multipart/form-data`
- `file`: Excel 文件（.xlsx / .xls）
- `mode`: `"overwrite"`（覆盖）或 `"append"`（追加）

**返回结果** (200):
```json
{
  "status": "ok",
  "mode": "overwrite | append",
  "total_count": 10,
  "message": "覆盖上传成功，当前共 10 条客户记录"
}
```

**错误码**:
| 状态码 | 说明 |
|--------|------|
| 400 | 文件格式错误 / 文件无有效数据 |

---

## 4.8 对公账户开户接口

### POST /api/account-opening/upload

上传开户资料图片。

- **前端调用位置**: `account-upload.html:187`
- **后端处理位置**: `main.py:790-824`
- **涉及数据表**: `account_opening.json`

**请求格式**: `multipart/form-data`
- `app_id`: 申请 ID
- `business_license`: 营业执照图片（可选）
- `legal_rep_id`: 法人身份证图片（可选）

**状态流转**: `upload` → `processing`

### POST /api/account-opening/process/{app_id}

触发 Mock OCR + 数据预填。

- **前端调用位置**: `account-upload.html:197`
- **后端处理位置**: `main.py:827-853`
- **涉及数据表**: `account_opening.json`, `risk_check.json`, `customer_outreach.json`, `product_recommendations.json`

**状态流转**: `processing` → `preview`

### GET /api/account-opening/preview/{app_id}

获取预填表单数据。

- **前端调用位置**: `account-preview.html:182`
- **后端处理位置**: `main.py:856-875`

### PUT /api/account-opening/update/{app_id}

保存表单修改（提交前可多次修改）。

- **前端调用位置**: `account-preview.html:283`
- **后端处理位置**: `main.py:878-899`

### POST /api/account-opening/submit/{app_id}

提交确认，状态锁定。

- **前端调用位置**: `account-preview.html:294`
- **后端处理位置**: `main.py:902-931`

**状态流转**: `preview` → `submitted`

### POST /api/account-opening/notify/{app_id}

H5 提交成功后的回调，将通知写入队列。

- **前端调用位置**: `account-preview.html:309`
- **后端处理位置**: `main.py:934-970`

### GET /api/account-opening/notifications/{conversation_id}

查询并清除指定会话的待处理开户通知（前端轮询调用）。

- **前端调用位置**: `api/agent.ts:217-235`（`checkAccountNotifications()`），`useChat.ts:300`
- **后端处理位置**: `main.py:973-981`
