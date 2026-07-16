// ============================================================
// useChat - 聊天核心逻辑 Hook
// ============================================================

import { useState, useCallback, useRef, useEffect } from 'react';
import type { ChatMessage, SSEEvent } from '../types';
import { isStreamingMessage } from '../types';
import { sendMessageStream, checkAccountNotifications } from '../api/agent';

interface UseChatReturn {
  messages: ChatMessage[];
  isSending: boolean;
  sendMessage: (content: string) => Promise<void>;
  clearMessages: () => void;
  setMessages: (messages: ChatMessage[]) => void;
}

export function useChat(
  conversationId: string | null,
  onConversationIdChange?: (id: string) => void,
  onMessageComplete?: () => void
): UseChatReturn {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isSending, setIsSending] = useState(false);
  const isSendingRef = useRef(false);

  const sendMessage = useCallback(
    async (content: string) => {
      if (!content.trim() || isSendingRef.current) return;

      isSendingRef.current = true;
      setIsSending(true);

      // 添加用户消息
      const userMsg: ChatMessage = {
        id: `user-${Date.now()}`,
        role: 'user',
        content: content.trim(),
        created_at: new Date().toISOString(),
      };

      setMessages((prev) => [...prev, userMsg]);

      // 添加流式助手消息占位
      const assistantMsgId = `assistant-${Date.now()}`;
      const assistantMsg: ChatMessage = {
        id: assistantMsgId,
        role: 'assistant',
        content: '',
        isStreaming: true,
        created_at: new Date().toISOString(),
      };

      setMessages((prev) => [...prev, assistantMsg]);

      await sendMessageStream(
        content.trim(),
        conversationId,
        (event: SSEEvent) => {
          switch (event.type) {
            case 'meta':
              // 如果后端返回了新的 conversation_id，更新它
              if (event.conversation_id && onConversationIdChange) {
                onConversationIdChange(event.conversation_id);
              }
              break;

            case 'text_start':
              // 文本开始 - 清空占位内容
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? { ...msg, content: '' }
                    : msg
                )
              );
              break;

            case 'text_delta':
              // 增量更新消息内容
              if (event.content) {
                setMessages((prev) =>
                  prev.map((msg) =>
                    isStreamingMessage(msg) && msg.id === assistantMsgId
                      ? { ...msg, content: msg.content + event.content }
                      : msg
                  )
                );
              }
              break;

            case 'text_done':
              // 文本完成 - 将流式消息转为普通消息
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: event.content || msg.content,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'potential_customer_summary':
              // 潜客汇总卡片
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '潜客清单',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'potential_customer_detail':
              // 潜客详情卡片
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '客户详情',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'risk_check_result':
              // 风险预查结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '风险预查',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'outreach_result':
              // 拓户准备结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '拓户准备',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'product_recommend_result':
              // 产品智荐结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '产品推荐',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'product_match_result':
              // 产品智能匹配结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '产品智能匹配',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'account_opening_result':
              // 对公账户开户结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '对公账户开户',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'follow_up_suggestion':
              // 追问建议：创建独立追问消息，追加在消息列表末尾
              if (event.content) {
                const followUpId = `followup-${Date.now()}`;
                setMessages((prev) => [
                  ...prev,
                  {
                    id: followUpId,
                    role: 'assistant' as const,
                    content: '',
                    extra: { action: 'follow_up', text: event.content } as unknown as Record<string, unknown>,
                    created_at: new Date().toISOString(),
                  },
                ]);
              }
              break;

            case 'error':
              // 错误处理
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: `抱歉，发生了错误：${event.content || '请稍后重试'}`,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'done':
              // 流结束，通知外部刷新会话列表
              onMessageComplete?.();
              break;
          }
        },
        (error: Error) => {
          console.error('发送消息失败:', error);
          isSendingRef.current = false;
          setIsSending(false);
          setMessages((prev) =>
            prev.map((msg) =>
              isStreamingMessage(msg) && msg.id === assistantMsgId
                ? {
                    id: msg.id,
                    role: 'assistant' as const,
                    content: `抱歉，请求失败：${error.message}`,
                    isStreaming: false,
                    created_at: msg.created_at,
                  }
                : msg
            )
          );
        },
        () => {
          // 完成回调
          isSendingRef.current = false;
          setIsSending(false);
          // 每次 SSE 完成后检查开户提交通知
          pollNotifications();
        }
      );
    },
    [conversationId, onConversationIdChange]
  );

  // ============================================================
  // 开户提交通知轮询
  // ============================================================

  const pollNotifications = useCallback(async () => {
    if (!conversationId) return;
    const notifications = await checkAccountNotifications(conversationId);
    for (const n of notifications) {
      if (n.type === 'account_submitted') {
        // 构造开户提交成功的智能体回复消息
        const submittedMsg: ChatMessage = {
          id: `msg-submitted-${n.app_id}-${Date.now()}`,
          role: 'assistant',
          content: '',
          created_at: new Date().toISOString(),
          extra: {
            action: 'result',
            app_id: n.app_id,
            company_name: n.company_name,
            credit_code: n.credit_code,
            status: 'submitted',
            submitted_url: n.submitted_url,
            submitted_at: n.submitted_at,
          },
        };
        setMessages((prev) => [...prev, submittedMsg]);
      }
    }
  }, [conversationId]);

  // 当有 conversationId 时自动启动轮询（每5秒检查一次通知）
  useEffect(() => {
    if (!conversationId) return;
    // 立即检查一次（用户从 H5 页面返回时立刻看到通知）
    pollNotifications();
    const timer = setInterval(pollNotifications, 5000);
    return () => clearInterval(timer);
  }, [conversationId, pollNotifications]);

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  return {
    messages,
    isSending,
    sendMessage,
    clearMessages,
    setMessages,
  };
}
