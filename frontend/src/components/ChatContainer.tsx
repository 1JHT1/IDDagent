import React, { useRef, useEffect } from 'react';
import type { ChatMessage } from '../types';
import ChatMessageComponent from './ChatMessage';
import ChatInput from './ChatInput';

interface ChatContainerProps {
  messages: ChatMessage[];
  isSending: boolean;
  onSend: (message: string) => void;
}

const ChatContainer: React.FC<ChatContainerProps> = ({
  messages,
  isSending,
  onSend,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="flex flex-col h-full">
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
              <p className="text-gray-500 mb-8 max-w-md">
                我是您的银行对公账户开户顾问，可以帮您了解开户流程、准备材料、解答疑问。
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
            messages.map((msg) => (
              <ChatMessageComponent key={msg.id} message={msg} onSendMessage={onSend} />
            ))
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      {/* 输入区域 */}
      <ChatInput onSend={onSend} disabled={isSending} />
    </div>
  );
};

/** 快捷提问 */
const QUICK_QUESTIONS = [
  '这里用来放一些快捷提问？',
  '什么是智能尽调？',
  '智能尽调有什么业务',
];

export default ChatContainer;
