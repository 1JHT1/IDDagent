# 08 - 代码风险与改进建议

## 8.1 架构问题

### 8.1.1 无数据库、纯 JSON 文件存储 [严重]

**问题**: 所有业务数据使用 JSON 文件存储，无事务、无并发控制、无查询优化。

**风险等级**: ⚠️ 严重

**具体风险**:
1. **并发写入覆盖**：`account_opening.json` 使用 `json.dump()` 覆盖写，多个请求同时写入时后写覆盖先写
2. **非原子操作**：读-修改-写三步非原子，中间发生异常导致数据丢失
3. **无查询能力**：无法按条件筛选、排序、分页，全部在 Python 内存中处理
4. **性能瓶颈**：JSON 文件全量加载到内存，数据量增大后性能急剧下降

**改进建议**:
- 引入 SQLite（轻量）或 PostgreSQL（生产）数据库
- 使用 ORM（SQLAlchemy 或 Tortoise-ORM）
- 过渡方案：使用 `threading.Lock` 保护所有 JSON 写入操作

**代码位置**:
- `auth.py:132-139` — `UserStore._save()` 覆盖写
- `main.py:784-787` — `_save_account_apps()` 覆盖写
- `main.py:755-756` — `uploaded_customers.json` 覆盖写

### 8.1.2 会话数据纯内存存储 [严重]

**问题**: 会话消息（`conversations` 字典）、上下文记忆、开户提交通知全部存储在进程内存中。

**风险**: 服务重启后所有会话数据丢失，且多进程部署（如 Gunicorn 多 worker）下会话不共享。

**改进建议**:
- 使用 Redis 存储会话数据和上下文记忆
- 或将会话持久化到数据库中

**代码位置**: `main.py:120-124`

### 8.1.3 所有 API 路由在单个文件中 [中等]

**问题**: `main.py` 长达 998 行，包含所有路由、数据模型、业务逻辑，不符合单一职责原则。

**改进建议**: 使用 FastAPI 的 `APIRouter` 按功能拆分为多个模块：
- `routes/auth.py`
- `routes/conversations.py`
- `routes/chat.py`
- `routes/account_opening.py`
- `routes/data_api.py`

---

## 8.2 安全风险

### 8.2.1 CORS 配置过于宽松 [中等]

**问题**: CORS 设置为 `allow_origins=["*"]`（`main.py:67`），允许所有来源访问 API。

**风险**: 生产环境中可能被任意第三方网站跨域访问。

**改进建议**: 生产部署时限制为具体域名。

### 8.2.2 H5 和开户 API 无认证 [中等]

**问题**: 所有 `/api/account-opening/*`、`/api/risk-report/*`、`/api/outreach/*`、`/api/product-recommend/*` 接口无需认证。

**风险**: 任何人知晓信用代码即可查询企业风险和拓户数据（敏感商业信息）。

**改进建议**:
- 至少对 `account-opening` 系列接口添加 Token 认证
- H5 页面可从聊天跳转，但建议增加临时访问令牌（一次性 Token）

### 8.2.3 JWT 密钥默认随机生成 [低]

**问题**: `auth.py:24` 当 `JWT_SECRET` 环境变量未设置时，每次启动随机生成密钥。

**风险**: 服务重启后所有已有 Token 失效，多实例部署时无法互认。

**改进建议**: 生产环境务必设置固定 `JWT_SECRET` 环境变量。

### 8.2.4 JSON 文件路径可被推测 [低]

**问题**: 数据文件路径固定且暴露在错误信息中（如 "风险数据文件不存在"）。

**风险**: 攻击者可推测数据文件路径。

**改进建议**: 错误信息不要暴露文件路径细节。

### 8.2.5 不存在 SQL 注入风险 [安全]

**原因**: 项目未使用 SQL 数据库，所有查询基于 JSON 文件键值查找，无 SQL 注入可能。

### 8.2.6 不存在 XSS/CSRF 风险 [安全]

**原因**: 前端使用 React 默认转义，后端返回 JSON 不包含 HTML。但 H5 页面（`account-preview.html`、`account-submitted.html`）需要留意：

**潜在风险**: H5 页面使用 `innerHTML` 渲染表单数据（`account-preview.html:237-242`），如果 form_data 中的字段包含恶意脚本标签，可能触发 XSS。

**改进建议**: H5 页面中用户可修改的字段应使用 `textContent` 代替 `innerHTML`。

---

## 8.3 性能风险

### 8.3.1 JSON 文件全量加载 [中等]

**问题**: 每个请求都完整读取 JSON 文件到内存
- `risk_check.json`: 4000+ 行
- `customer_outreach.json`: 978 行
- `product_recommendations.json`: 1113 行

**改进建议**: 对大文件可缓存到内存中（如使用 `lru_cache`），或引入数据库。

### 8.3.2 模糊匹配复杂度 [低]

**问题**: `risk_check.py:_fuzzy_match_company()` 中的子序列匹配算法在最坏情况下需要遍历所有企业（当前 23 家，数据量小无影响）。

**改进建议**: 使用倒排索引或向量化匹配。

### 8.3.3 前端健康检查轮询 [低]

**问题**: `App.tsx:78` 每 10 秒轮询 `/api/health`。

**改进建议**: 可与 SSE 连接合并，不需要独立轮询。

---

## 8.4 数据一致性风险

### 8.4.1 无事务管理 [严重]

**问题**: 开户流程涉及多个 API 调用（上传 → 处理 → 提交），每个 API 独立读写 JSON 文件，无事务保证。

**场景举例**:
1. 用户上传图片成功（`upload` 状态）
2. `process` API 写入 `form_data` 后更新为 `preview` 状态
3. 如果第 2 步在写 `form_data` 成功后、更新状态前崩溃 → 数据不一致

**改进建议**: 
- 引入数据库事务
- 或使用临时文件 + 重命名实现原子写入

### 8.4.2 线程安全问题 [中等]

**问题**: `context_memory.py` 使用了 `threading.Lock` 保护，但 `main.py` 中的 `conversations` 字典和 `account_notifications` 字典**未使用**线程锁。

**代码位置**: `main.py:120-128`

**改进建议**: 使用 `asyncio.Lock` 或 `threading.Lock` 保护共享数据结构。

### 8.4.3 N+1 查询风险 [安全]

**原因**: 项目使用 JSON 文件且无 ORM，不存在 N+1 查询问题。

---

## 8.5 代码质量问题

### 8.5.1 导入重复代码 [中等]

**问题**: 多个技能文件（`customer_outreach.py`、`product_recommend.py`、`product_match.py`、`account_opening.py`）重复导入并调用 `risk_check.py` 中的函数：

```python
from .risk_check import _load_json, _build_base_url, _fuzzy_match_company, _resolve_company_match
```

**改进建议**: 将通用工具函数提取到独立的 `utils.py` 模块中。

**涉及文件**:
- `skills/customer_outreach.py:12`
- `skills/product_recommend.py:12`
- `skills/product_match.py:14`
- `skills/account_opening.py:15`

### 8.5.2 重复的 `_load_json()` 函数 [低]

**问题**: 每个技能文件都使用 `risk_check.py` 的 `_load_json()`，但 `main.py` 中也有独立的 JSON 加载实现。

**改进建议**: 统一使用同一个工具函数。

### 8.5.3 硬编码字符串 [中等]

**问题**: 多个技能描述、路由规则中的关键词硬编码在 Python 代码中：
- `coordinator.py:50-75` — 13 条关键词匹配规则
- `product_match.py:68-97` — 5 组关键词字典

**改进建议**: 将关键词提取到配置文件或 JSON 文件中。

### 8.5.4 错误处理不统一 [中等]

**问题**: 部分技能返回 `{"error": "..."}`，部分返回 `{"action": "not_found", "message": "..."}`，前端处理需要区��判断。

**代码位置**: `skills/__init__.py:81` vs 各技能模块

**改进建议**: 统一错误响应格式。

### 8.5.5 TypeScript 类型定义不全 [低]

**问题**: `SSEEvent.data` 类型为 `any`（`api/agent.ts`），且 `ChatMessage.extra` 大量使用 `as unknown as Record<string, unknown>`。

**改进建议**: 为每个 SSE 事件类型定义精确的类型。

---

## 8.6 可维护性问题

### 8.6.1 缺少日志系统 [中等]

**问题**: 全部使用 `print()` 输出日志，无日志级别、无日志文件、无结构化日志。

**改进建议**: 引入 Python `logging` 模块，配置日志级别和输出目标。

**代码位置**: 散布在 `main.py`、`coordinator.py`、`follow_up_agent.py` 等各处

### 8.6.2 缺少单元测试 [严重]

**问题**: 项目无任何测试文件（`tests/` 目录不存在）。

**改进建议**:
- 为每个技能 handler 编写单元测试
- 为 Fuzzy Match 算法编写测试
- 为 API 接口编写集成测试

### 8.6.3 Mock OCR 过于简单 [低]

**问题**: `account_opening.py:_mock_ocr_and_prefill()` 使用 `hashlib.md5` 哈希信用代码来模拟生成法人信息，所有企业的法人/受益人信息都是确定性的伪随机。

**改进建议**: 
- 实际部署应接入真实 OCR 服务（如百度 OCR、腾讯 OCR）
- 数据预填应基于 OCR 识别结果而非哈希模拟

---

## 8.7 前端特定问题

### 8.7.1 流式状态管理不稳定 [低]

**问题**: `useChat.ts` 中的流式消息 ID 使用 `Date.now()` 生成（`assistant-${Date.now()}`），极短时间内发送多条消息可能 ID 冲突。

**改进建议**: 使用 `crypto.randomUUID()` 或 `uuid` 库生成唯一 ID。

### 8.7.2 后端在线状态轮询 [低]

**问题**: 每 10 秒轮询健康检查接口，增加不必要的网络请求。

**改进建议**: 可通过 SSE 连接的意外断开来判断后端状态。

---

## 8.8 改进优先级建议

| 优先级 | 改进项 | 影响 |
|--------|--------|------|
| P0 | 引入数据库替代 JSON 文件 | 数据安全、并发 |
| P0 | 添加单元测试 | 代码质量 |
| P1 | 会话数据持久化 | 用户体验 |
| P1 | 开户 API 添加认证 | 安全 |
| P1 | 引入日志系统 | 可维护性 |
| P1 | 添加线程锁保护共享数据 | 数据一致�� |
| P2 | 拆分 main.py 路由 | 模块化 |
| P2 | CORS 限制来源 | 安全 |
| P2 | 抽取通用工具函数 | 代码复用 |
| P3 | H5 页面 XSS 防护 | 安全 |
| P3 | 统一错误响应格式 | 可维护性 |
