import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import Sidebar from './components/Sidebar';
import ChatContainer from './components/ChatContainer';
import PlanStatusBar from './components/PlanStatusBar';
import LoginPage from './components/LoginPage';
import { useChat } from './hooks/useChat';
import {
  getConversations,
  createConversation,
  getConversation,
  deleteConversation as deleteConversationApi,
  checkHealth,
} from './api/agent';
import type { ConversationListItem, ChatMessage, ChatAttachment, PlanStatusData } from './types';

interface UserData {
  id: string;
  username: string;
  bankInstitution?: string;
}

const App: React.FC = () => {
  // ---- 认证状态 ----
  const [user, setUser] = useState<UserData | null>(() => {
    const stored = localStorage.getItem('user_info');
    return stored ? JSON.parse(stored) : null;
  });
  const [token, setToken] = useState<string | null>(() =>
    localStorage.getItem('auth_token')
  );

  // ---- 应用状态 ----
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [conversations, setConversations] = useState<ConversationListItem[]>([]);
  const [conversationsLoading, setConversationsLoading] = useState(false);
  const [backendOnline, setBackendOnline] = useState<boolean | null>(null);

  // 用 ref 追踪最新状态，避免闭包问题
  const conversationIdRef = useRef(conversationId);
  conversationIdRef.current = conversationId;

  // 会话加载竞态防护序号：handleSelectConversation 异步加载期间用户新建/切换/删除会话
  // 时递增，晚到的旧会话响应（getConversation/plan 恢复）据此丢弃，避免旧对话内容
  // （任务规划卡片/历史提问）串入新会话
  const conversationLoadSeqRef = useRef(0);

  const isAuthenticated = !!token && !!user;

  // ---- 登录回调 ----
  const handleLoginSuccess = useCallback(
    (newToken: string, newUser: UserData) => {
      setToken(newToken);
      setUser(newUser);
    },
    []
  );

  // ---- 退出登录 ----
  const handleLogout = useCallback(() => {
    localStorage.removeItem('auth_token');
    localStorage.removeItem('user_info');
    setToken(null);
    setUser(null);
    setConversationId(null);
    syncConversationId(null);
    // 使在飞的会话加载响应失效（页面已切到登录态，晚到数据不落地）
    conversationLoadSeqRef.current++;
    clearMessages();
  }, []);

  /**
   * 同步会话 id 到 ref 与 localStorage（同步执行，不依赖 useEffect）：
   * ProgressCard 报告完成收尾回调（notifyReportComplete）以 localStorage 的
   * currentConversationId 判断会话归属；若依赖 useEffect 延迟更新，新建/切换会话瞬间
   * （setState 后到 effect 执行前）旧会话收尾响应晚到时会误判归属，
   * 把"任务完成"卡注入新会话消息流。所有会话切换入口必须经此同步。
   */
  const syncConversationId = (id: string | null) => {
    conversationIdRef.current = id;
    if (id) {
      localStorage.setItem('currentConversationId', id);
    } else {
      localStorage.removeItem('currentConversationId');
    }
  };

  const handleConversationIdChange = useCallback((id: string) => {
    syncConversationId(id);
    setConversationId(id);
    loadConversations();
  }, []);

  const { messages, isSending, sendMessage, clearMessages, setMessages, stopStreaming, addMessage } =
    useChat(conversationId, handleConversationIdChange, () => {
      loadConversations();
    });

  // ================================================================
  // 报告进度消息注入（将进度卡片以智能体消息形式插入聊天流）
  // ================================================================

  /**
   * 向聊天流注入一条报告进度卡片。
   * 按对话逻辑定位（不按时间排序）：
   * - 存在同报告的结果卡（后端已持久化的 generate_report 模板/跳转卡）→ 紧随其后插入，
   *   进度卡与报告相关卡保持连续，切换会话后不会按时间漂移到对话最前；
   * - 无匹配结果卡（报告由 H5 直接发起、对话内无对应卡片）→ 追加到对话末尾；
   * - 已存在进度卡且无新时间信息 → 幂等保持，轮询期间不反复重排。
   */
  const injectProgressMessage = useCallback((reportId: string, createdAt?: string, completedAt?: string) => {
    setMessages((prev) => {
      const cardId = `report-progress-${reportId}`;
      const existingIdx = prev.findIndex((m) => m.id === cardId);
      // 已存在且本次没有新时间信息 → 幂等返回，避免轮询期间反复重排
      if (existingIdx >= 0 && !createdAt && !completedAt) return prev;
      const card = {
        id: cardId,
        role: 'assistant' as const,
        content: '',
        extra: {
          action: 'result',
          _skill_name: 'generate_report',
          stage: 'progress',
          report_id: reportId,
        },
        created_at: createdAt || new Date().toISOString(),
      };
      const rest = existingIdx >= 0 ? prev.filter((_, i) => i !== existingIdx) : prev;
      // 锚点：同报告的结果卡（模板/跳转卡无 report_id → 优先精确匹配，再取最后一张 generate_report 卡兜底）
      let anchorIdx = -1;
      for (let i = rest.length - 1; i >= 0; i--) {
        const e = (rest[i] as { extra?: Record<string, unknown> }).extra;
        if (e?._skill_name === 'generate_report' && e.report_id === reportId) {
          anchorIdx = i;
          break;
        }
      }
      if (anchorIdx < 0) {
        for (let i = rest.length - 1; i >= 0; i--) {
          const e = (rest[i] as { extra?: Record<string, unknown> }).extra;
          if (e?._skill_name === 'generate_report') {
            anchorIdx = i;
            break;
          }
        }
      }
      const next = [...rest];
      if (anchorIdx >= 0) {
        next.splice(anchorIdx + 1, 0, card);
      } else {
        next.push(card);
      }
      return next;
    });
  }, [setMessages]);

  /** 拉取指定会话的待处理报告并注入进度卡片（切换会话时立即调用，无需等待轮询）
   * seq：发起时的会话加载竞态序号，注入前校验——响应晚到时用户已新建/切换/删除会话 → 丢弃，
   * 避免旧会话（如 A 的"任务完成"进度卡）注入到新会话消息流 */
  const injectConversationReports = useCallback(async (convId: string, seq?: number) => {
    try {
      const res = await fetch(`/api/generate-report/conversation/${convId}/pending`);
      if (!res.ok) return;
      const data = await res.json();
      // 竞态防护：注入期间用户已新建/切换/删除会话 → 晚到的旧会话报告丢弃
      if (seq !== undefined && seq !== conversationLoadSeqRef.current) return;
      if (data.reports && data.reports.length > 0) {
        for (const r of data.reports) {
          // 已完成任务无 completedAt 时以当前时间兜底（完成时刻必晚于穿插消息 → 插到穿插意图之后）
          const done = r.status === 'completed';
          injectProgressMessage(
            r.reportId,
            r.createdAt as string | undefined,
            (r.completedAt as string | undefined) || (done ? new Date().toISOString() : undefined),
          );
        }
      }
    } catch { /* ignore */ }
  }, [injectProgressMessage]);

  // 定时轮询当前用户的活跃报告（捕获 H5 标签页关闭后发起的生成）
  useEffect(() => {
    if (!isAuthenticated || !user?.id) return;

    const checkActive = async () => {
      try {
        const res = await fetch(`/api/generate-report/user/${user.id}/active`);
        if (!res.ok) return;
        const data = await res.json();
        if (data.reports && data.reports.length > 0) {
          for (const r of data.reports) {
            // 只注入当前显示会话的报告：该接口返回用户所有会话的生成中报告，
            // 若不按会话过滤，新建对话/切换会话后旧会话的进度卡会串入当前消息流。
            // 校验必须用 conversationIdRef 实时值而非闭包 conversationId：旧 effect 的
            // 在途 fetch 响应晚到（会话已切换/新建）时闭包值仍是旧会话，会放行旧会话报告注入新会话
            if (r.conversationId && r.conversationId !== conversationIdRef.current) continue;
            injectProgressMessage(r.reportId, r.createdAt as string | undefined);
          }
        }
      } catch { /* ignore */ }
    };

    checkActive();
    const interval = setInterval(checkActive, 3000);
    return () => clearInterval(interval);
  }, [isAuthenticated, user?.id, conversationId, injectProgressMessage]);

  // 按对话 ID 轮询待处理报告（H5 新标签页生成报告后，原聊天页自动获取进度卡片）
  useEffect(() => {
    if (!isAuthenticated || !conversationId) return;

    const checkConversationPending = async () => {
      try {
        const res = await fetch(`/api/generate-report/conversation/${conversationId}/pending`);
        if (!res.ok) return;
        const data = await res.json();
        if (data.reports && data.reports.length > 0) {
          for (const r of data.reports) {
            // 防竞态：切会话瞬间旧会话的在飞轮询响应晚到时，其报告不属于当前会话 → 丢弃。
            // 用 conversationIdRef 实时值而非闭包 conversationId（旧 effect 响应到达时
            // 闭包仍是旧会话 id，校验会放行旧会话的完成卡注入新会话消息流）
            if (r.conversationId && r.conversationId !== conversationIdRef.current) continue;
            // 已完成任务无 completedAt 时以当前时间兜底（完成时刻必晚于穿插消息 → 插到穿插意图之后）
            const done = r.status === 'completed';
            injectProgressMessage(
              r.reportId,
              r.createdAt as string | undefined,
              (r.completedAt as string | undefined) || (done ? new Date().toISOString() : undefined),
            );
          }
        }
      } catch { /* ignore */ }
    };

    checkConversationPending();
    const interval = setInterval(checkConversationPending, 3000);
    return () => clearInterval(interval);
  }, [isAuthenticated, conversationId, injectProgressMessage]);

  // 检查后端服务状态
  useEffect(() => {
    if (!isAuthenticated) return;
    const check = async () => {
      const healthy = await checkHealth();
      setBackendOnline(healthy);
      if (healthy) {
        loadConversations();
      }
    };
    check();
    const interval = setInterval(check, 10000);
    return () => clearInterval(interval);
  }, [isAuthenticated]);

  // 加载会话列表
  const loadConversations = async () => {
    try {
      setConversationsLoading(true);
      const list = await getConversations();
      setConversations(list);
    } catch (err) {
      console.error('加载会话列表失败:', err);
    } finally {
      setConversationsLoading(false);
    }
  };

  // 新建会话
  const handleNewConversation = async () => {
    // 中止在飞 SSE（旧流的卡片事件会注入新会话）并使在飞的会话加载响应失效
    stopStreaming();
    conversationLoadSeqRef.current++;
    // 先清空消息流再发起创建：报告生成步骤（WAITING_EXTERNAL）时 SSE 已结束
    // （isSending=false，stopStreaming 直接 return），消息流中的报告进度卡轮询仍在运行，
    // 若在 createConversation 的 RTT 窗口内发现报告完成，收尾回调会把规划面板/确认卡
    // 注入新会话消息流——提前清空使进度卡立即卸载、轮询停止，
    // 从根上消除注入窗口（而非依赖 notifyReportComplete 的事后会话校验拦截）。
    clearMessages();
    try {
      const conv = await createConversation();
      // 同步 ref + localStorage：setState 到 re-render 之间旧轮询在途响应/报告完成收尾
      // 回调若到达，仍能凭实时 ref 与 localStorage 判定会话已切换而丢弃
      syncConversationId(conv.id);
      setConversationId(conv.id);
      // 再次清空：创建 RTT 窗口内其他在途轮询（会话 pending 报告/用户活跃报告）可能
      // 已把旧会话的进度卡注入消息流，统一清理，保证新会话从空白开始
      clearMessages();
      await loadConversations();
    } catch (err) {
      console.error('创建会话失败:', err);
    }
  };

  // 选择会话
  const handleSelectConversation = async (id: string) => {
    const seq = ++conversationLoadSeqRef.current;
    // 中止旧会话在飞的 SSE 流：切走后旧流的卡片/文本事件若继续注入，会落地到新会话消息流
    stopStreaming();
    try {
      // 同步 ref + localStorage：切换瞬间旧会话轮询在途响应/报告完成收尾回调晚到时，
      // 凭实时 ref 与 localStorage 判定会话已切换而丢弃
      syncConversationId(id);
      setConversationId(id);
      const conv = await getConversation(id);
      // 竞态防护：加载期间用户新建/切换/删除了会话 → 本次旧会话结果已过期，丢弃
      if (seq !== conversationLoadSeqRef.current) return;
      // 历史数据兑底去重：早期版本后端会双写同 id 消息（纯文本版 + JSON 版，差 1ms），
      // 消息列表以 id 为 key，同 id 两条会同时显示 → 加载时按 id 保留最后一条（JSON 版，
      // 经下方归一化后与实时路径一致），彻底消除"切换对话框引导提问重复"的存量脏数据
      const seen = new Map<string, typeof conv.messages[number]>();
      for (const m of conv.messages) seen.set(m.id, m);
      const msgs: ChatMessage[] = Array.from(seen.values()).map((m) => {
        const base = {
          id: m.id,
          role: m.role as 'user' | 'assistant',
          content: m.content,
          // 后端序列化为 createdAt（驼峰），兼容读取；缺失时兜底当前时间
          created_at:
            m.created_at ??
            (m as { createdAt?: string }).createdAt ??
            new Date().toISOString(),
          // 还原消息附件（用户上传的文件）
          ...(m.attachments && m.attachments.length > 0 ? { attachments: m.attachments } : {}),
        };
        // 优先使用后端持久化的 extra（任务规划面板/确认卡/进度气泡等结构化卡片消息，
        // 由 chatStream 统一拦截写入会话历史），切换会话后按原消息位置渲染为对应卡片
        if (m.role === 'assistant' && m.extra && typeof m.extra === 'object') {
          return { ...base, content: '', extra: m.extra };
        }
        // 旧数据兜底：如果是助手消息且 content 是 JSON（技能返回结果），解析为 extra 以渲染卡片
        if (m.role === 'assistant' && m.content && m.content.trim().startsWith('{')) {
          try {
            const parsed = JSON.parse(m.content);
            if (parsed && typeof parsed.action === 'string') {
              // 归一化候选选项卡 action：实时 SSE 路径前端设为 company_name_candidates，
              // 而消息持久化的是技能原始返回值 action=candidates / ambiguous，若不归一化，
              // 切换对话重载后选项卡（CompanyNameSelector）将无法恢复渲染
              if (parsed.action === 'candidates' || parsed.action === 'ambiguous') {
                parsed.action = 'company_name_candidates';
              }
              // info_needed（如"请问您要查询哪家企业"）：实时 SSE 路径是 text_delta/text_done
              // 普通文本消息，而持久化的是 {"action":"info_needed","message":"..."} JSON；
              // 若解析为 extra 则无对应渲染分支（消息消失），故归一化为普通文本，与实时路径一致
              if (parsed.action === 'info_needed') {
                const msg = typeof parsed.message === 'string' ? parsed.message : '';
                return { ...base, content: msg || base.content };
              }
              return { ...base, content: '', extra: parsed };
            }
          } catch {
            // 非合法 JSON，按普通文本处理
          }
        }
        return base;
      });
      setMessages(msgs);
      // 立即注入该会话的待处理报告进度卡片（切换后无需刷新即可显示，轮询兜底）
      injectConversationReports(id, seq);
      // 恢复该会话的任务规划面板（后端内存态规划状态，重启后为空则无操作）
      try {
        // /api/plan/** 不在 JWT 白名单，必须携带登录 token 才能通过鉴权
        const token = localStorage.getItem('auth_token') || '';
        const res = await fetch(`/api/plan/${id}/status`, {
          headers: { ...(token ? { 'Authorization': `Bearer ${token}` } : {}) },
        });
        // 恢复期间用户已切换/新建会话 → 面板数据过期，丢弃
        if (seq !== conversationLoadSeqRef.current) return;
        if (res.ok) {
          const planResp = await res.json();
          const planData = planResp?.plan as PlanStatusData | undefined;
          if (planData && planData.active && planData.steps?.length) {
            const planId = planData.planId || `local-${id}`;
            const panelId = `plan-status-${planId}`;
            setMessages((prev) => {
              if (prev.some((m) => m.id === panelId)) return prev;
              return [
                ...prev,
                {
                  id: panelId,
                  role: 'assistant' as const,
                  content: '',
                  extra: { action: 'plan_status', ...planData } as unknown as Record<string, unknown>,
                  created_at: new Date().toISOString(),
                },
              ];
            });
          }
        }
      } catch { /* ignore */ }
    } catch (err) {
      console.error('加载会话失败:', err);
    }
  };

  // 从消息流最后一条 plan_status 快照派生顶部规划状态栏数据
  // （倒序遍历：后续规划穿插/新面板会覆盖旧快照，取最后一条为准）
  const lastPlanStatus = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const extra = (messages[i] as { extra?: Record<string, unknown> }).extra;
      if (extra?.action === 'plan_status') {
        const d = extra as unknown as PlanStatusData;
        // 最后一条快照已无步骤（规划收尾清除/被丢弃）→ 状态栏隐藏
        if (!d.steps || d.steps.length === 0) return null;
        return d;
      }
    }
    return null;
  }, [messages]);

  // 删除会话
  const handleDeleteConversation = async (id: string) => {
    try {
      await deleteConversationApi(id);
      if (conversationId === id) {
        // 中止在飞 SSE 并使在飞的会话加载响应失效，避免旧会话内容回填当前视图
        stopStreaming();
        conversationLoadSeqRef.current++;
        // 同步 ref + localStorage：删除当前会话后旧轮询在途响应/报告完成收尾回调
        // 不得再注入任何消息流
        syncConversationId(null);
        setConversationId(null);
        clearMessages();
      }
      await loadConversations();
    } catch (err) {
      console.error('删除会话失败:', err);
    }
  };

  const backendOnlineRef = useRef(backendOnline);
  backendOnlineRef.current = backendOnline;

  // 将 conversationId 同步到 localStorage，供 H5 新标签页读取
  useEffect(() => {
    if (conversationId) {
      localStorage.setItem('currentConversationId', conversationId);
    }
  }, [conversationId]);

  // 检测 URL 参数中的 reportId 和 convId（H5 页面确认生成后跳转回来）
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const rid = params.get('reportId');
    const convId = params.get('convId');
    if (rid || convId) {
      window.history.replaceState({}, '', window.location.pathname);

      const selectAndInject = async () => {
        // 等待后端健康检查完成（确保 loadConversations 已启动）
        let retries = 0;
        while (backendOnlineRef.current === null && retries < 20) {
          await new Promise(r => setTimeout(r, 300));
          retries++;
        }
        // 额外等待对话列表加载完成
        await new Promise(r => setTimeout(r, 500));

        if (convId) {
          await handleSelectConversation(convId);
        }
        if (rid) {
          // 通过 /status 接口获取任务创建时间，确保跳转回来时卡片也插入其初始生成位置
          try {
            const res = await fetch(`/api/generate-report/${rid}/status`);
            if (res.ok) {
              const data = await res.json();
              // 已完成任务无 completedAt 时以当前时间兜底（完成时刻必晚于穿插消息 → 插到穿插意图之后）
              const done = data.status === 'completed';
              injectProgressMessage(
                rid,
                data.createdAt as string | undefined,
                (data.completedAt as string | undefined) || (done ? new Date().toISOString() : undefined),
              );
            } else {
              injectProgressMessage(rid);
            }
          } catch {
            injectProgressMessage(rid);
          }
        }
      };
      selectAndInject();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 发送消息
  const handleSend = useCallback(async (content: string, attachments?: ChatAttachment[], silent?: boolean) => {
    let currentConvId = conversationIdRef.current;
    if (!currentConvId) {
      try {
        const conv = await createConversation();
        currentConvId = conv.id;
        // 同步 ref + localStorage（与 handleNewConversation 一致）：避免 SSE 事件校验
        // 与报告完成收尾回调在 setState 到 re-render 之间误判会话归属
        syncConversationId(conv.id);
        setConversationId(conv.id);
        await loadConversations();
      } catch (err) {
        console.error('创建会话失败:', err);
        return;
      }
    }
    console.log('📤 App 发送消息, conversationId:', currentConvId);
    sendMessage(content, currentConvId, attachments, silent);
  }, [sendMessage]);

  // ---- 未登录：显示登录页 ----
  if (!isAuthenticated) {
    return <LoginPage onLoginSuccess={handleLoginSuccess} />;
  }

  // ---- 已登录：显示主界面 ----
  return (
    <div className="flex h-screen bg-gray-100">
      <Sidebar
        conversations={conversations}
        activeId={conversationId}
        onSelect={handleSelectConversation}
        onNew={handleNewConversation}
        onDelete={handleDeleteConversation}
        loading={conversationsLoading}
      />

      <div className="flex-1 flex flex-col min-w-0">
        {/* 顶部状态栏 */}
        <div className="h-12 bg-white border-b border-gray-200 flex items-center px-6 flex-shrink-0">
          <div className="flex items-center gap-2">
            <div
              className={`w-2 h-2 rounded-full ${
                backendOnline === null
                  ? 'bg-yellow-400 animate-pulse'
                  : backendOnline
                  ? 'bg-green-500'
                  : 'bg-red-500'
              }`}
            />
            <span className="text-sm text-gray-600">
              {backendOnline === null
                ? '正在连接服务...'
                : backendOnline
                ? '服务已连接'
                : '服务未连接'}
            </span>
          </div>
          <div className="ml-auto flex items-center gap-4">
            <span className="text-sm text-gray-500">
              {user?.username}
            </span>
            <button
              onClick={handleLogout}
              className="text-xs text-gray-400 hover:text-red-500 transition-colors"
            >
              退出
            </button>
          </div>
        </div>

        {/* 任务规划顶部状态栏（从消息流最后一条 plan_status 快照派生） */}
        <PlanStatusBar data={lastPlanStatus} />

        <ChatContainer
          messages={messages}
          isSending={isSending}
          onSend={handleSend}
          onStop={stopStreaming}
          onAddMessage={addMessage}
        />
      </div>
    </div>
  );
};

export default App;
