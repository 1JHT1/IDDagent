import React, { useRef, useEffect } from 'react';
import type { ChatMessage, ChatAttachment } from '../types';
import { isResumeConfirmConsumed } from '../hooks/useChat';
import ChatMessageComponent from './ChatMessage';
import ChatInput from './ChatInput';

interface ChatContainerProps {
  messages: ChatMessage[];
  isSending: boolean;
  onSend: (message: string, attachments?: ChatAttachment[], silent?: boolean) => void;
  onStop?: () => void;
  /** 本地生成卡片消息（如模板选择后的跳转卡），插入到步骤确认卡片之前 */
  onAddMessage?: (msg: ChatMessage) => void;
}

const ChatContainer: React.FC<ChatContainerProps> = ({
  messages,
  isSending,
  onSend,
  onStop,
  onAddMessage,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* 消息区域 */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto px-4 py-6"
      >
        <div className="max-w-3xl mx-auto">
          {messages.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full text-center py-20">
              {/* 欢迎图标 */}
              <div className="w-20 h-20 rounded-full bg-gradient-to-br from-blue-100 to-blue-200 flex items-center justify-center mb-6">
                <svg
                  className="w-10 h-10 text-blue-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={1.5}
                    d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"
                  />
                </svg>
              </div>
              <h2 className="text-xl font-semibold text-gray-700 mb-2">
                智能尽调智能体
              </h2>
              <p className="text-gray-500 mb-8 ">
                我是您的智能尽调智能体小助手
                <br />
                请随时向我提问！
              </p>
              {/* 快捷问题 */}
              <div className="grid grid-cols-1 gap-3 w-full max-w-md">
                {QUICK_QUESTIONS.map((q) => (
                  <button
                    key={q}
                    onClick={() => onSend(q)}
                    disabled={isSending}
                    className="text-left px-4 py-3 rounded-xl border border-gray-200 bg-white
                               text-sm text-gray-600 hover:border-blue-300 hover:text-blue-600
                               hover:bg-blue-50 transition-all duration-200
                               disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            messages.map((msg, index) => (
              <ChatMessageComponent
                key={msg.id}
                message={msg}
                onSendMessage={(content, silent) => onSend(content, undefined, silent)}
                onAddMessage={onAddMessage}
                interleaveDisabled={computeInterleaveDisabled(messages, index)}
              />
            ))
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      {/* 输入区域 */}
      <ChatInput onSend={onSend} disabled={isSending} onStop={onStop} />
    </div>
  );
};

/** 快捷提问 */
const QUICK_QUESTIONS = [
  '智能尽调的基本流程是什么？',
  '智能尽调有什么业务',
];

export default ChatContainer;

/**
 * 计算消息的穿插禁用状态：该消息之后存在 resume_confirm 卡片且该卡片已被消费
 * （用户点击“回到之前的任务”/“不需要”，穿插区域已结束）→ 穿插区域内的功能卡片
 * （追问 chip、结果操作按钮、歧义选项等）不应再可点击执行，任务规划已结束。
 * 自身是已消费的 resume_confirm 卡片 → 同样禁用（避免恢复后旧卡误操作）。
 * 嵌套多轮穿插-恢复天然支持：每张 resume_confirm 标记一个穿插区域，各自独立判定。
 */
function computeInterleaveDisabled(msgs: ChatMessage[], index: number): boolean {
  const selfExtra = msgs[index].extra as { action?: string } | undefined;
  if (selfExtra?.action === 'resume_confirm') {
    return isResumeConfirmConsumed(msgs, index);
  }
  for (let i = index + 1; i < msgs.length; i++) {
    const extra = msgs[i].extra as { action?: string } | undefined;
    if (extra?.action === 'resume_confirm') {
      return isResumeConfirmConsumed(msgs, i);
    }
  }
  return false;
}
