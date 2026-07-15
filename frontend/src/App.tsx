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
import type { ConversationListItem, ChatMessage } from './types';

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

  const { messages, isSending, sendMessage, clearMessages, setMessages } =
    useChat(conversationId, handleConversationIdChange, () => {
      loadConversations();
    });

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
      const msgs: ChatMessage[] = conv.messages.map((m) => ({
        id: m.id,
        role: m.role as 'user' | 'assistant',
        content: m.content,
        created_at: m.created_at,
      }));
      setMessages(msgs);
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

  // 用 ref 追踪最新的 conversationId，避免闭包问题
  const conversationIdRef = useRef(conversationId);
  conversationIdRef.current = conversationId;

  // 发送消息
  const handleSend = useCallback(async (content: string) => {
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
    sendMessage(content, currentConvId);
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
        />
      </div>
    </div>
  );
};

export default App;
