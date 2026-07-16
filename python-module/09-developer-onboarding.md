# 09 - 新开发人员上手指南

## 9.1 环境准备

### 必需工具

| 工具 | 版本要求 | 用途 |
|------|----------|------|
| Python | ≥3.10 | 后端运行环境 |
| Node.js | ≥18 | 前端运行环境 |
| npm | ≥9 | 前端依赖管理 |
| Git | — | 版本控制 |

### 第一步：获取代码

```bash
git clone <repository-url>
cd cropAgentV2
```

### 第二步：启动后端

```bash
cd backend

# 创建虚拟环境（推荐）
python -m venv venv
# Windows: venv\Scripts\activate
# Linux/Mac: source venv/bin/activate

# 安装依赖
pip install -r requirements.txt

# 配置环境变量
cp .env.example .env
# 编辑 .env 文件，填写 DEEPSEEK_API_KEY

# 生成测试数据
cd scripts
python generate_product_data.py
python generate_outreach_data.py
python generate_risk_data.py
python expand_product_shelf.py
python add_xiaomi_food_data.py
python generate_uploaded_customer_data.py
cd ..

# 启动服务
python main.py
```

访问 `http://localhost:8000/docs` 查看 API 文档。

### 第三步：启动前端

```bash
# 新开终端
cd frontend
npm install
npm run dev
```

访问 `http://localhost:3000` 使用应用。

### 第四步：验证

1. 打开浏览器访问 `http://localhost:3000`
2. 注册一个新账户（随意输入用户名和密码）
3. 登录后输入"推荐拓户客户清单"测试
4. 输入"查询统一信用代码为91110108MA01B3XK2P的客户的开户风险"测试风险功能

---

## 9.2 项目结构速览

```
cropAgentV2/
├── backend/
│   ├── main.py          # 所有 API 路由（998 行）
│   ├── coordinator.py   # LLM 意图识别（核心）
│   ├── auth.py          # JWT 认证
│   ├── context_memory.py # 会话上下文
│   ├── follow_up_agent.py # 追问预测
│   ├── skills/          # 技能插件（6 个）
│   │   ├── __init__.py  # Skill 注册中心
│   │   ├── risk_check.py # 风险预查 + 模糊匹配工具
│   │   └── ...           # 其他技能
│   ├── data/            # JSON 数据文件
│   ├── static/h5/       # 7 个 H5 页面
│   └── scripts/         # 数据生成脚本
├── frontend/
│   ├── src/
│   │   ├── api/agent.ts       # API 封装（6 个函数）
│   │   ├── types/index.ts     # 类型定义（~30 个类型）
│   │   ├── hooks/useChat.ts   # SSE 流式聊天 Hook
│   │   ├── components/        # 8 个 React 组件
│   │   └── App.tsx            # 根组件
│   └── vite.config.ts         # Vite 配置
```

---

## 9.3 常见开发任务

### 任务 1：增加一个新的业务技能

**步骤**:

1. **创建技能文件** `backend/skills/my_new_skill.py`

```python
import json
from pathlib import Path
from . import Skill, skill_registry

async def handle_my_skill(user_id: str, params: dict) -> dict:
    """技能处理函数"""
    # 读取数据
    data_file = Path(__file__).parent.parent / "data" / "my_data.json"
    with open(data_file, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    # 业务逻辑
    result = data.get(params.get("key", ""))
    
    return {
        "action": "result",
        "my_field": result,
        "h5_url": f"http://localhost:8000/h5/my-page.html",
    }

skill_registry.register(
    Skill(
        name="my_new_skill",
        description="当用户说某些特定关键词时调用此技能",
        handler=handle_my_skill,
        parameters={
            "key": {
                "type": "string",
                "description": "参数说明",
                "required": True,
                "example": "示例值",
            },
        },
    )
)
```

2. **注册到 main.py**：在 `main.py` 顶部添加 `import skills.my_new_skill  # noqa: F401`

3. **添加 Coordinator 匹配规则**：在 `coordinator.py` 的 `_build_system_prompt()` 中添加关键词匹配规则和 LLM 示例

4. **添加前端类型**：在 `types/index.ts` 中添加新的结果类型

5. **添加前端卡组件**：在 `ChatMessage.tsx` 中添加卡片渲染逻辑

6. **在 useChat.ts 中添加事件处理**

### 任务 2：增加一个新的 API 路由

1. 在 `main.py` 中添加新路由函数（使用 `@app` 装饰器）：

```python
@app.get("/api/my-resource/{id}")
async def get_my_resource(id: str, current_user: UserInfo = Depends(get_current_user)):
    """功能说明"""
    # 业务逻辑
    return {"id": id, "data": "..."}
```

2. 如果是大型功能，建议使用 `APIRouter` 拆分到独立文件：

```python
# routes/my_routes.py
from fastapi import APIRouter, Depends
router = APIRouter(prefix="/api/my")

@router.get("/resource")
async def get_resource():
    return {"data": "..."}

# main.py
from routes.my_routes import router as my_router
app.include_router(my_router)
```

3. 在前端 `api/agent.ts` 中添加调用函数

### 任务 3：增加新的 JSON 数据字段

**例：为 `risk_check.json` 增加一个新指标**

1. 修改数据文件 `backend/data/risk_check.json`：
```json
{
  "91110108MA01B3XK2P": {
    "details": {
      "business_info": {
        "items": [
          {
            "name": "新指标名称",
            "result": "正常",
            "has_risk": false,
            "detail": "指标详情说明"
          }
        ]
      }
    }
  }
}
```

2. 如果需要在 H5 页面展示，修改 `risk-report.html` 的渲染逻辑

3. 如果需要在技能中处理，修改 `risk_check.py`

### 任务 4：修改前端页面

**例：在欢迎页增加快捷问题**

修改 `ChatContainer.tsx` 中的 `QUICK_QUESTIONS` 数组：

```typescript
const QUICK_QUESTIONS = [
  '开立基本存款账户需要准备哪些材料？',
  '对公账户开户的完整流程是什么？',
  '一般存款账户和基本存款账户有什么区别？',
  '客户有一笔3000万资金闲置3个月，请推荐产品',  // 新增产品匹配
];
```

---

## 9.4 关键概念

### SSE 流式事件类型

前端通过 SSE 接收结构化数据。理解事件类型是开发的关键：

| 事件类型 | 前端处理位置 | 对应后端技能 |
|----------|-------------|-------------|
| `potential_customer_summary` | `useChat.ts:108` | recommend_corporate_customers |
| `potential_customer_detail` | `useChat.ts:125` | recommend_corporate_customers |
| `risk_check_result` | `useChat.ts:142` | check_company_risk |
| `outreach_result` | `useChat.ts:159` | prepare_customer_outreach |
| `product_recommend_result` | `useChat.ts:176` | recommend_products |
| `product_match_result` | `useChat.ts:193` | match_products_intelligently |
| `account_opening_result` | `useChat.ts:210` | open_corporate_account |

### 结构化卡片路由逻辑

`ChatMessage.tsx` 中的渲染优先级：

1. 先判断是 `follow_up` → 渲染 FollowUpChip
2. 再判断 action 为 `result/ambiguous/not_found`：
   - 有 `insights_h5_url` → OutreachCard
   - 有 `detail_h5_url` 或 `products` → ProductRecommendCard
   - 有 `needs_summary` 或 `matches` → ProductMatchCard
   - 有 `upload_url` 或 `app_id` → AccountOpeningCard
   - 否则 → RiskCheckCard
3. 否则 → PotentialCustomerCard

---

## 9.5 常见问题排查

### 问题 1：登录后无法加载会话

**原因**: 后端未启动或 Token 已过期

**排查**:
```bash
# 检查后端是否启动
curl http://localhost:8000/api/health
# 应返回 {"status": "ok"}
```

**解决**: 启动后端服务，清除浏览器 localStorage 重新登录

### 问题 2：发送消息后无响应

**排查**:
1. 检查浏览器控制台是否有 CORS 错误
2. 检查后端终端是否有错误日志
3. 检查 `.env` 文件中 `DEEPSEEK_API_KEY` 是否正确

### 问题 3：技能返回 "未找到企业"

**排查**:
1. 检查 `company_name_index.json` 是否包含该企业
2. 检查输入的企业名称是否正确
3. 尝试使用统一信用代码查询

### 问题 4：开户图片上传后 Nothing happens

**排查**:
1. 检查 `backend/static/uploads/account_opening/` 目录是否存在
2. 检查服务端是否有写权限
3. 检查 `app_id` 是否正确传递

### 问题 5：前端构建报错

```bash
cd frontend
npm run build
# 常见错误：TypeScript 类型错误
# 检查 tsconfig.app.json 的 strict 模式
```

---

## 9.6 调试技巧

### 后端调试

```python
# 在任意位置插入 print 查看变量
print(f"[Debug] skill_params: {skill_params}")

# 使用 FastAPI 的交互式文档
# 访问 http://localhost:8000/docs
```

### 前端调试

```typescript
// 在 useChat 的 SSE 回调中查看事件
console.log('[SSE Event]', event);
```

### 查看 SSE 原始数据

在浏览器开发者工具的 Network 标签中：
1. 找到 `/api/chat/stream` 请求
2. 查看 Response 面板中的 SSE 事件流

---

## 9.7 推荐的首个开发任务

1. **修复小型 bug**：给 `useChat.ts` 中的消息 ID 生成改用 `crypto.randomUUID()`
2. **增加功能**：在 `ChatMessage.tsx` 中添加新的结构化卡片类型
3. **提升稳定性**：给 `main.py` 中的共享数据结构添加 `asyncio.Lock`
4. **优化体验**：修改 `account-opening/upload` 路由，在无文件上传时返回更友好的错误信息
