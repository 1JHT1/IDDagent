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
   * ownerConvId：卡片所属会话——注入前校验当前显示会话仍是该会话才落地。
   * 该函数是所有进度卡注入的汇聚点，调用方（切换加载/轮询/收尾）的归属校验一旦遗漏
   * 或校验时点过期（校验通过后用户已切换会话），无兜底则会跨会话注入——此处以实时
   * conversationIdRef 兜底，从根上杜绝"一个对话的进度卡出现在其他对话"。
   */
  const injectProgressMessage = useCallback((reportId: string, createdAt?: string, completedAt?: string, ownerConvId?: string) => {
    // 诊断日志（临时）：追踪进度卡注入
    console.log('📌 [injectProgressMessage] reportId:', reportId, 'owner:', ownerConvId, '当前会话:', conversationIdRef.current, '→', ownerConvId === conversationIdRef.current ? '注入' : '丢弃');
    // 归属兜底：无归属信息或归属会话已不是当前显示会话 → 丢弃，绝不注入其他会话消息流
    if (!ownerConvId || ownerConvId !== conversationIdRef.current) return;
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
          // 归属校验（与轮询一致）：后端按会话过滤，但返回数据异常/接口语义变化时
          // 不能把其他会话的报告注入当前会话；无会话归属（H5 发起）同样丢弃
          if (!r.conversationId || r.conversationId !== convId) continue;
          // 已完成任务无 completedAt 时以当前时间兜底（完成时刻必晚于穿插消息 → 插到穿插意图之后）
          const done = r.status === 'completed';
          injectProgressMessage(
            r.reportId,
            r.createdAt as string | undefined,
            (r.completedAt as string | undefined) || (done ? new Date().toISOString() : undefined),
            convId,
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
            // 在途 fetch 响应晚到（会话已切换/新建）时闭包值仍是旧会话，会放行旧会话报告注入新会话。
            // 无会话归属的任务（H5 旧标签页/旧链接发起，conversationId 为空）无法确定归属会话，
            // 一律不注入——空字符串为 falsy，仅判断非空会绕过校验，导致任务串入当前任意会话
            if (!r.conversationId || r.conversationId !== conversationIdRef.current) continue;
            injectProgressMessage(r.reportId, r.createdAt as string | undefined, undefined, conversationIdRef.current ?? undefined);
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
            // 闭包仍是旧会话 id，校验会放行旧会话的完成卡注入新会话消息流）。
            // 无会话归属的任务（conversationId 为空）同样丢弃，避免串入当前任意会话
            if (!r.conversationId || r.conversationId !== conversationIdRef.current) continue;
            // 已完成任务无 completedAt 时以当前时间兜底（完成时刻必晚于穿插消息 → 插到穿插意图之后）
            const done = r.status === 'completed';
            injectProgressMessage(
              r.reportId,
              r.createdAt as string | undefined,
              (r.completedAt as string | undefined) || (done ? new Date().toISOString() : undefined),
              conversationIdRef.current ?? undefined,
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
    // 切换中置空会话归属（ref + localStorage 同步置空）：createConversation 的 RTT
    // 窗口内（syncConversationId 更新前）旧会话在途收尾回调（notifyReportComplete
    // 响应后按 localStorage 校验）与轮询注入（injectProgressMessage 按 conversationIdRef
    // 校验）仍按旧会话 id 判定归属——不置空会把旧会话的规划卡/进度卡注入新会话消息流，
    // 表现为"新建对话后仍残留任务规划卡，刷新才消失"
    syncConversationId(null);
    setConversationId(null);
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
    // 同步 ref + localStorage：切换瞬间旧会话轮询在途响应/报告完成收尾回调晚到时，
    // 凭实时 ref 与 localStorage 判定会话已切换而丢弃（须在清空之前执行：stopStreaming
    // 依赖 ref 通知旧会话停止，ref 更新过早会把停止信号发往新会话）
    syncConversationId(id);
    setConversationId(id);
    // 先清空消息流再加载（与 handleNewConversation 对齐）：getConversation 的 RTT 窗口内
    // 旧会话内容（任务规划卡/历史消息）会残留在界面上，表现为切换对话框后其他对话框页面
    // 仍显示上一个会话的任务卡；若加载失败或竞态（seq 不匹配）丢弃响应，旧内容将永久残留，
    // 只有刷新才消失——提前清空从根上消除该残留窗口
    clearMessages();
    try {
      const conv = await getConversation(id);
      // 竞态防护：加载期间用户新建/切换/删除了会话 → 本次旧会话结果已过期，丢弃
      if (seq !== conversationLoadSeqRef.current) return;
      // 历史数据兑底去重：早期版本后端会双写同 id 消息（纯文本版 + JSON 版，差 1ms），
      // 消息列表以 id 为 key，同 id 两条会同时显示 → 加载时按 id 保留最后一条（JSON 版，
      // 经下方归一化后与实时路径一致），彻底消除"切换对话框引导提问重复"的存量脏数据
      const seen = new Map<string, typeof conv.messages[number]>();
      for (const m of conv.messages) seen.set(m.id, m);
      // 归一化（暂保留 silent 用户消息：confirmed 传播判定需要，稍后统一过滤）
      const normalized: ChatMessage[] = Array.from(seen.values()).map((m) => {
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
              // 而消息持久化的是技能原始返回值 action=candidates（历史尽调）或带候选列表的
              // ambiguous/not_found（企业查询/风险预查/信息核实，"未找到完全匹配，是否查询
              // 相似企业"也带候选），若不归一化，切换对话重载后选项卡片会被误路由成技能结果
              // 卡片或无法恢复渲染。核心规则：有模糊匹配项（options 非空）即候选确认卡，
              // 只有 options 为空才是未找到企业空态卡
              if (parsed.action === 'candidates'
                  || ((parsed.action === 'ambiguous' || parsed.action === 'not_found')
                      && Array.isArray(parsed.options) && (parsed.options as unknown[]).length > 0)) {
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
      // confirmed/consumed 双通道恢复：候选卡之后的用户消息若带 confirmed/consumed 标记
      // （候选确认/以上都不是的静默发送已由后端随用户消息落盘）→ 候选卡注入对应标记，
      // 刷新/切换会话后组件重建仍能恢复"已确认过"提示与"只能点击一次"禁用态。
      // 识别范围：第2层候选选择器（company_name_candidates）与第1层技能卡歧义候选
      // （ambiguous/not_found 且带 options）均适用
      for (let i = 0; i < normalized.length; i++) {
        const extra = (normalized[i] as { extra?: Record<string, unknown> }).extra;
        if (!extra || extra.action !== 'company_name_candidates') {
          if (!extra || !((extra.action === 'ambiguous' || extra.action === 'not_found')
              && Array.isArray(extra.options) && (extra.options as unknown[]).length > 0)) continue;
        }
        for (let j = i + 1; j < normalized.length; j++) {
          const m = normalized[j];
          const mExtra = (m as { extra?: Record<string, unknown> }).extra;
          if (m.role === 'user') {
            if (mExtra?.confirmed === true) extra.confirmed = true;
            if (mExtra?.consumed === true) extra.consumed = true;
            break;
          }
        }
      }
      // 过滤静默发送的用户消息（候选确认/以上都不是）：不展示为用户气泡，避免污染对话流
      const msgs: ChatMessage[] = normalized.filter(
        (m) => !(m.role === 'user' && (m as { extra?: Record<string, unknown> }).extra?.silent === true)
      );
      setMessages(msgs);
      // 立即注入该会话的待处理报告进度卡片（切换后无需刷新即可显示，轮询兜底）
      injectConversationReports(id, seq);
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

  // 发送消息（silent=true 为卡片静默发送：不展示用户气泡，直接进入结果；
  // extra 随请求透传，如候选确认的 confirmed 标记由后端随用户消息落盘）
  const handleSend = useCallback(async (content: string, attachments?: ChatAttachment[], silent?: boolean, extra?: Record<string, unknown>) => {
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
    sendMessage(content, currentConvId, attachments, silent, extra);
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
          conversationId={conversationId}
          onSend={handleSend}
          onStop={stopStreaming}
          onAddMessage={addMessage}
        />
      </div>
    </div>
  );
};

export default App;
