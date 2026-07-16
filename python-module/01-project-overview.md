# 01 - 项目概述

## 1.1 项目介绍

**对公账户开户智能助手** 是一款面向银行对公客户经理的 AI 辅助工作台。系统基于智能体（Agent）架构，帮助客户经理高效完成以下日常工作：

- **潜客识别**：查看对公存款智能体推荐的潜客清单，支持用户自定义上传客户清单
- **风险预查**：查询目标企业的开户风险，生成详细的风险报告
- **拓户准备**：获取目标企业的营销谈资和营销话术
- **产品推荐**：基于企业画像推荐合适的金融产品
- **产品智能匹配**：根据客户的资金需求场景（如"5千万工程款闲置一个月"）智能匹配合适的产品
- **对公账户开户**：协助完成企业开户全流程（上传资料 → OCR 识别 → 预览确认 → 提交）

项目定位为**客户经理工作台**（To B 内部工具），而非面向公众的开户门户。

## 1.2 技术栈

### 前端（frontend/）

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18.3.1 | UI 框架 |
| TypeScript | 5.6.2 | 类型安全 |
| Vite | 6.0.5 | 构建工具 |
| TailwindCSS | 3.4.17 | 样式框架 |
| react-markdown | 9.0.1 | Markdown 渲染 |
| remark-gfm | 4.0.0 | GFM 扩展 |

### 后端（backend/）

| 技术 | 版本 | 用途 |
|------|------|------|
| Python | ≥3.10 | 运行环境 |
| FastAPI | ≥0.115.0 | Web 框架 |
| Uvicorn | ≥0.32.0 | ASGI 服务器 |
| AgentScope | ≥2.0.0 | 多智能体框架（阿里达摩院） |
| DeepSeek API | — | LLM 接口 |
| PyJWT | ≥2.8.0 | JWT 认证 |
| openpyxl | ≥3.1.0 | Excel 文件处理 |
| python-dotenv | ≥1.0.0 | 环境变量管理 |
| Pydantic | ≥2.0.0 | 数据验证 |
| sse-starlette | ≥2.0.0 | SSE 支持 |
| openai | — | OpenAI 兼容客户端 |

### AI 模型

| 用途 | 模型名（默认） | 说明 |
|------|----------------|------|
| Agent 对话 | deepseek-v4-flash | 普通聊天场景 |
| Coordinator 意图识别 | deepseek-chat | 意图路由（轻量即可） |
| Follow-Up 追问 | deepseek-chat | 追问预测 |
| 产品智能匹配 | deepseek-chat | LLM 匹配引擎 |

## 1.3 启动方式

### 后端启动

```bash
cd backend
pip install -r requirements.txt
# 配置 .env 文件（参考 .env.example）
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY
python main.py
```

后端默认运行在 `http://localhost:8000`，自动提供：
- API 文档：`http://localhost:8000/docs`
- H5 页面：`http://localhost:8000/h5/`

### 前端启动

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:3000`，Vite 代理 `/api` 到后端 `http://localhost:8000`。

### 生成测试数据

```bash
cd backend/scripts
python generate_product_data.py
python generate_outreach_data.py
python generate_risk_data.py
python expand_product_shelf.py
python add_xiaomi_food_data.py
python generate_uploaded_customer_data.py
```

## 1.4 目录结构

```
cropAgentV2/
├── backend/                        # Python 后端
│   ├── main.py                     # FastAPI 入口 + 所有 API 路由
│   ├── agent_config.py             # Agent 系统提示词和配置
│   ├── auth.py                     # JWT 认证、用户注册/登录
│   ├── coordinator.py              # LLM 意图识别 + 技能路由
│   ├── context_memory.py           # 会话上下文记忆（线程安全）
│   ├── follow_up_agent.py          # 追问预测 Agent
│   ├── skills/                     # 技能插件目录
│   │   ├── __init__.py             # Skill 注册中心
│   │   ├── potential_customer.py   # 潜客推荐技能
│   │   ├── risk_check.py           # 风险预查技能（含模糊匹配）
│   │   ├── customer_outreach.py    # 拓户准备技能
│   │   ├── product_recommend.py    # 产品智荐技能
│   │   ├── product_match.py        # 产品智能匹配技能
│   │   └── account_opening.py      # 对公账户开户技能
│   ├── data/                       # JSON 数据文件
│   ├── static/h5/                  # H5 静态页面
│   └── scripts/                    # 数据生成脚本
├── frontend/                       # React 前端
│   ├── src/
│   │   ├── api/agent.ts            # 后端 API 封装
│   │   ├── types/index.ts          # 所有 TypeScript 类型
│   │   ├── hooks/useChat.ts        # SSE 流式聊天 Hook
│   │   ├── components/             # React 组件
│   │   └── App.tsx                 # 根组件
│   └── vite.config.ts              # Vite 配置（含 proxy）
```

## 1.5 主要模块

| 模块 | 说明 |
|------|------|
| **Agent（智能体）** | 基于 AgentScope 2.0 的 LLM Agent，处理普通对话 |
| **Coordinator（协调器）** | LLM 意图识别，判断用户输入应路由到哪个 Skill 还是普通聊天 |
| **Skill Registry（技能注册中心）** | 插件式技能系统，6 个已注册技能 |
| **Context Memory（上下文记忆）** | 会话级企业主体跟踪，跨轮对话保持上下文 |
| **Follow-Up Agent（追问 Agent）** | 预测用户下一步意图，生成追问建议 |
| **H5 页面** | 独立的静态 HTML 页面，用于风险报告、营销谈资/话术、产品推荐、开户流程 |
