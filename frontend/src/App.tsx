import React, { useState, useEffect, useCallback, useRef } from 'react';
import Sidebar from './components/Sidebar';
import ChatContainer from './components/ChatContainer';
import LoginPage from './components/LoginPage';
import { useChat } from './hooks/useChat';
import {
  getConversations,
  createConversation,
  getConversation,
  deleteConversation as deleteConversationApi,
  checkHealth,
} from './api/agent';
import type { ConversationListItem, ChatMessage, ChatAttachment } from './types';

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
    clearMessages();
  }, []);

  const handleConversationIdChange = useCallback((id: string) => {
    setConversationId(id);
    loadConversations();
  }, []);

  const { messages, isSending, sendMessage, clearMessages, setMessages, stopStreaming } =
    useChat(conversationId, handleConversationIdChange, () => {
      loadConversations();
    });

  // ================================================================
  // 报告进度消息注入（将进度卡片以智能体消息形式插入聊天流）
  // ================================================================

  /** 向聊天流注入一条进度卡片消息（消息列表中已存在同 reportId 则不重复注入） */
  const injectProgressMessage = useCallback((reportId: string, createdAt?: string) => {
    setMessages((prev) => {
      if (prev.some((m) => m.id === `report-progress-${reportId}`)) return prev;
      const card = {
        id: `report-progress-${reportId}`,
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
      // 1) 优先插入到会话中最后一条"报告生成流程"消息（模板选择卡/跳转卡）之后，
      //    让进度卡片固定在其初始返回位置，而非追加到消息末尾
      for (let i = prev.length - 1; i >= 0; i--) {
        const extra = (prev[i] as { extra?: Record<string, unknown> }).extra;
        if (extra && extra._skill_name === 'generate_report') {
          const next = [...prev];
          next.splice(i + 1, 0, card);
          return next;
        }
      }
      // 2) 兜底：按任务创建时间排序插入
      if (createdAt) {
        const cardTime = new Date(createdAt).getTime();
        const insertIdx = prev.findIndex((m) => {
          const t = m.created_at ? new Date(m.created_at).getTime() : 0;
          return t > cardTime;
        });
        const next = [...prev];
        if (insertIdx === -1) {
          next.push(card);
        } else {
          next.splice(insertIdx, 0, card);
        }
        return next;
      }
      return [...prev, card];
    });
  }, [setMessages]);

  /** 拉取指定会话的待处理报告并注入进度卡片（切换会话时立即调用，无需等待轮询） */
  const injectConversationReports = useCallback(async (convId: string) => {
    try {
      const res = await fetch(`/api/generate-report/conversation/${convId}/pending`);
      if (!res.ok) return;
      const data = await res.json();
      if (data.reports && data.reports.length > 0) {
        for (const r of data.reports) {
          injectProgressMessage(r.reportId, r.createdAt as string | undefined);
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
            injectProgressMessage(r.reportId, r.createdAt as string | undefined);
          }
        }
      } catch { /* ignore */ }
    };

    checkActive();
    const interval = setInterval(checkActive, 3000);
    return () => clearInterval(interval);
  }, [isAuthenticated, user?.id, injectProgressMessage]);

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
            injectProgressMessage(r.reportId, r.createdAt as string | undefined);
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
    try {
      const conv = await createConversation();
      setConversationId(conv.id);
      clearMessages();
      await loadConversations();
    } catch (err) {
      console.error('创建会话失败:', err);
    }
  };

  // 选择会话
  const handleSelectConversation = async (id: string) => {
    try {
      setConversationId(id);
      const conv = await getConversation(id);
      const msgs: ChatMessage[] = conv.messages.map((m) => {
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
        // 如果是助手消息且 content 是 JSON（技能返回结果），解析为 extra 以渲染卡片
        if (m.role === 'assistant' && m.content && m.content.trim().startsWith('{')) {
          try {
            const parsed = JSON.parse(m.content);
            if (parsed && typeof parsed.action === 'string') {
              // 归一化候选选项卡 action：实时 SSE 路径前端设为 company_name_candidates，
              // 而消息持久化的是技能原始返回值 action=candidates，若不归一化，
              // 切换对话重载后选项卡（CompanyNameSelector）将无法恢复渲染
              if (parsed.action === 'candidates') {
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
      injectConversationReports(id);
    } catch (err) {
      console.error('加载会话失败:', err);
    }
  };

  // 删除会话
  const handleDeleteConversation = async (id: string) => {
    try {
      await deleteConversationApi(id);
      if (conversationId === id) {
        setConversationId(null);
        clearMessages();
      }
      await loadConversations();
    } catch (err) {
      console.error('删除会话失败:', err);
    }
  };

  // 用 ref 追踪最新状态，避免闭包问题
  const conversationIdRef = useRef(conversationId);
  conversationIdRef.current = conversationId;
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
              injectProgressMessage(rid, data.createdAt as string | undefined);
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
  const handleSend = useCallback(async (content: string, attachments?: ChatAttachment[]) => {
    let currentConvId = conversationIdRef.current;
    if (!currentConvId) {
      try {
        const conv = await createConversation();
        currentConvId = conv.id;
        setConversationId(conv.id);
        await loadConversations();
      } catch (err) {
        console.error('创建会话失败:', err);
        return;
      }
    }
    console.log('📤 App 发送消息, conversationId:', currentConvId);
    sendMessage(content, currentConvId, attachments);
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

        <ChatContainer
          messages={messages}
          isSending={isSending}
          onSend={handleSend}
          onStop={stopStreaming}
        />
      </div>
    </div>
  );
};

export default App;
