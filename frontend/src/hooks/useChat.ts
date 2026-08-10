// ============================================================
// useChat - 聊天核心逻辑 Hook
// ============================================================

import React, { useState, useCallback, useRef } from 'react';
import type { ChatMessage, SSEEvent, ChatAttachment } from '../types';
import { isStreamingMessage } from '../types';
import { sendMessageStream, stopChatStream } from '../api/agent';

interface UseChatReturn {
  messages: ChatMessage[];
  isSending: boolean;
  sendMessage: (content: string, overrideConvId?: string, attachments?: ChatAttachment[]) => Promise<void>;
  stopStreaming: () => void;
  clearMessages: () => void;
  setMessages: React.Dispatch<React.SetStateAction<ChatMessage[]>>;
}

export function useChat(
  conversationId: string | null,
  onConversationIdChange?: (id: string) => void,
  onMessageComplete?: () => void
): UseChatReturn {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isSending, setIsSending] = useState(false);
  const isSendingRef = useRef(false);
  // 当前进行中的 SSE 请求控制器，用于强制终止
  const abortControllerRef = useRef<AbortController | null>(null);

  // 用 ref 追踪最新值，避免闭包陈旧引用
  const conversationIdRef = useRef(conversationId);
  conversationIdRef.current = conversationId;

  const onMessageCompleteRef = useRef(onMessageComplete);
  onMessageCompleteRef.current = onMessageComplete;

  const sendMessage = useCallback(
    async (content: string, overrideConvId?: string, attachments?: ChatAttachment[]) => {
      const effectiveConvId = overrideConvId ?? conversationIdRef.current;
      const hasAttachments = !!attachments && attachments.length > 0;
      if ((!content.trim() && !hasAttachments) || isSendingRef.current) return;

      console.log('🚀 useChat.sendMessage 开始, content:', content, 'conversationId:', effectiveConvId, '附件数:', attachments?.length ?? 0);

      isSendingRef.current = true;
      setIsSending(true);

      // 创建本次请求的终止控制器（点击"停止"按钮时 abort）
      const controller = new AbortController();
      abortControllerRef.current = controller;

      // 添加用户消息
      const userMsg: ChatMessage = {
        id: `user-${Date.now()}`,
        role: 'user',
        content: content.trim(),
        created_at: new Date().toISOString(),
        ...(hasAttachments ? { attachments } : {}),
      };

      setMessages((prev) => {
        console.log('📋 添加用户消息, 之前消息数:', prev.length);
        return [...prev, userMsg];
      });

      // 添加流式助手消息占位
      const assistantMsgId = `assistant-${Date.now()}`;
      const assistantMsg: ChatMessage = {
        id: assistantMsgId,
        role: 'assistant',
        content: '',
        isStreaming: true,
        created_at: new Date().toISOString(),
      };

      setMessages((prev) => {
        console.log('📋 添加助手占位消息, assistantMsgId:', assistantMsgId, '之前消息数:', prev.length);
        return [...prev, assistantMsg];
      });

      await sendMessageStream(
        content.trim(),
        effectiveConvId,
        (event: SSEEvent) => {
          console.log('📨 useChat SSE 回调收到事件:', event.type, event.content?.substring(0, 40) || '(无内容)', '消息总数:', messages.length);
          switch (event.type) {
            case 'thinking':
              // 后端正在分析意图，更新占位消息为思考状态
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? { ...msg, content: '🤔 正在思考...' }
                    : msg
                )
              );
              break;

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
                setMessages((prev) => {
                  const targetMsg = prev.find(m => isStreamingMessage(m) && m.id === assistantMsgId);
                  console.log('📝 text_delta 更新, content:', event.content, 'assistantMsgId:', assistantMsgId, '找到目标消息:', !!targetMsg);
                  return prev.map((msg) =>
                    isStreamingMessage(msg) && msg.id === assistantMsgId
                      ? { ...msg, content: msg.content + event.content }
                      : msg
                  );
                });
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

            case 'report_generate_result':
              // 报告生成结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '智能尽调报告生成',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'information_check_result':
              // 信息核实结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '信息核实',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'historical_dd_query_result':
              // 历史尽调查询结果
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '历史尽调报告',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'company_query_result':
              // 企业信息查询结果（基本信息/股东/受益人/族谱/海关/冻结/授信/人行账管）
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '企业信息查询',
                        extra: event.data as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'company_name_candidates':
              // 候选企业选择器：复用流式消息（替代"正在思考"占位）
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '',
                        extra: { ...(event.data as unknown as Record<string, unknown>), action: 'company_name_candidates' } as unknown as Record<string, unknown>,
                        created_at: msg.created_at,
                      }
                    : msg
                )
              );
              break;

            case 'need_date_range':
              // 时间区间输入提示：更新流式消息，展示提示文本
              setMessages((prev) =>
                prev.map((msg) =>
                  isStreamingMessage(msg) && msg.id === assistantMsgId
                    ? {
                        id: msg.id,
                        role: 'assistant' as const,
                        content: '',
                        extra: { action: 'need_date_range', text: (event.data as unknown as Record<string, unknown>).message || event.content || '' } as unknown as Record<string, unknown>,
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
              console.log('✅ SSE 流完成');
              // 流结束，通知外部刷新会话列表
              onMessageCompleteRef.current?.();
              break;

            default:
              console.warn('⚠️ 未识别的 SSE 事件类型:', event.type, event);
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
          if (abortControllerRef.current === controller) {
            abortControllerRef.current = null;
          }
        },
        attachments,
        controller.signal
      );
    },
    [onConversationIdChange]
  );

  /**
   * 强制终止当前流式对话
   * 1. 前端：abort 断开 SSE 连接（fetch 抛 AbortError，走完成回调而非错误回调）
   * 2. 后端：通知 /api/chat/stop 设置取消标记，截断剩余事件流（双保险）
   * 3. 将进行中的流式占位消息转为普通消息（标记已停止），避免永久处于加载状态
   */
  const stopStreaming = useCallback(() => {
    if (!isSendingRef.current) return;
    console.log('⏹️ 用户点击停止，终止当前对话');
    const controller = abortControllerRef.current;
    const convId = conversationIdRef.current;
    if (controller) {
      controller.abort();
      abortControllerRef.current = null;
    }
    if (convId) {
      stopChatStream(convId);
    }
    isSendingRef.current = false;
    setIsSending(false);
    // 将流式占位消息转为普通消息，保留已生成的内容（如有）
    setMessages((prev) =>
      prev.map((msg) =>
        isStreamingMessage(msg)
          ? {
              id: msg.id,
              role: 'assistant' as const,
              content: msg.content && msg.content !== '🤔 正在思考...'
                ? msg.content + '\n\n> ⏹️ 已停止生成'
                : '⏹️ 已停止生成',
              ...(msg.extra ? { extra: msg.extra } : {}),
              created_at: msg.created_at,
            }
          : msg
      )
    );
  }, [setMessages]);

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  return {
    messages,
    isSending,
    sendMessage,
    stopStreaming,
    clearMessages,
    setMessages,
  };
}
