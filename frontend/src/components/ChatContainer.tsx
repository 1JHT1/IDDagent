import React, { useRef, useEffect, useCallback } from 'react';
import type { ChatMessage, ChatAttachment } from '../types';
import { isResumeConfirmConsumed, isStepConfirmConsumed } from '../hooks/useChat';
import ChatMessageComponent from './ChatMessage';
import ChatInput from './ChatInput';

interface ChatContainerProps {
  messages: ChatMessage[];
  isSending: boolean;
  onSend: (message: string, attachments?: ChatAttachment[]) => void;
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
  const contentRef = useRef<HTMLDivElement>(null);
  // 用户是否位于消息区底部附近（距底部 <100px）：
  // 位于底部时消息更新（AI 流式回复/卡片注入/轮询进度卡）自动跟随滚动到底部，
  // 对话时 AI 返回的结果立即可见、无需手动下翻；
  // 用户手动上滚查看历史后不再强制拉回底部——不打断阅读前面的内容，
  // 仅当用户重新回到底部附近或主动发送消息时才恢复自动跟随
  const atBottomRef = useRef(true);

  // 监听滚动位置：离开底部区域视为用户主动浏览历史，停止自动跟随
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onScroll = () => {
      atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, []);

  // 自动滚动到底部（仅当用户位于底部附近时跟随；上滚查看历史时不打扰）。
  // 直接设置 scrollTop 而非 scrollIntoView：后者依赖浏览器对 behavior:'instant' 的支持，
  // 不受支持时会退化为动画滚动，连续注入（如任务规划完成的多张卡片）时视口停在半路；
  // scrollTop 赋值无动画直达底部
  useEffect(() => {
    if (!atBottomRef.current) return;
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // 内容高度变化（如卡片内图片异步加载完成）时跟随滚动：
  // 仅靠 messages 变化触发滚动，图片加载导致的容器高度增长会把底部新内容挤出视口，
  // 用户在底部附近时自动补齐滚动；上滚查看历史（atBottom=false）时忽略，不打扰阅读
  useEffect(() => {
    const el = scrollRef.current;
    const content = contentRef.current;
    if (!el || !content) return;
    const observer = new ResizeObserver(() => {
      if (!atBottomRef.current) return;
      el.scrollTop = el.scrollHeight;
    });
    observer.observe(content);
    return () => observer.disconnect();
  }, []);

  // 用户主动发送消息 → 回到底部跟随（发送后查看自己的消息与回复）
  const handleSend = useCallback((message: string, attachments?: ChatAttachment[]) => {
    atBottomRef.current = true;
    onSend(message, attachments);
  }, [onSend]);

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* 消息区域 */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto px-4 py-6"
      >
        <div ref={contentRef} className="max-w-3xl mx-auto">
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
                    onClick={() => handleSend(q)}
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
                onSendMessage={handleSend}
                onAddMessage={onAddMessage}
                interleaveDisabled={computeInterleaveDisabled(messages, index)}
              />
            ))
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      {/* 输入区域 */}
      <ChatInput onSend={handleSend} disabled={isSending} onStop={onStop} />
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
 * 自身是已消费的 plan_step_confirm 卡片（用户已点继续/结束）→ 禁用，只能点击一次；
 * 切换对话框/刷新后按原消息流恢复显示时同样保持禁用（与 resume_confirm 行为一致）。
 * 嵌套多轮穿插-恢复天然支持：每张 resume_confirm 标记一个穿插区域，各自独立判定。
 */
function computeInterleaveDisabled(msgs: ChatMessage[], index: number): boolean {
  const selfExtra = msgs[index].extra as { action?: string } | undefined;
  if (selfExtra?.action === 'resume_confirm') {
    return isResumeConfirmConsumed(msgs, index);
  }
  // 步骤间确认卡：其后存在确认动作/恢复文本回复即已消费，卡片仅保留为历史记录，
  // 实时点击后立即禁用，切换会话恢复显示后也保持禁用（只能点击一次）
  if (selfExtra?.action === 'plan_step_confirm' && isStepConfirmConsumed(msgs, index)) {
    return true;
  }
  for (let i = index + 1; i < msgs.length; i++) {
    const extra = msgs[i].extra as { action?: string } | undefined;
    if (extra?.action === 'resume_confirm') {
      return isResumeConfirmConsumed(msgs, i);
    }
  }
  return false;
}
