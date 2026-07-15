import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ChatMessage } from '../types';
import { isStreamingMessage } from '../types';
import PotentialCustomerCard from './PotentialCustomerCard';
import RiskCheckCard from './RiskCheckCard';
import OutreachCard from './OutreachCard';
import ProductRecommendCard from './ProductRecommendCard';
import ProductMatchCard from './ProductMatchCard';
import AccountOpeningCard from './AccountOpeningCard';
import FollowUpChip from './FollowUpChip';

interface ChatMessageProps {
  message: ChatMessage;
  /** 发送消息回调（用于卡片交互） */
  onSendMessage?: (content: string) => void;
}

const ChatMessageComponent: React.FC<ChatMessageProps> = ({ message, onSendMessage }) => {
  const isUser = message.role === 'user';
  const streaming = isStreamingMessage(message);

  const handleRequestDetail = (sourceId: string, sourceName: string) => {
    onSendMessage?.(`查看${sourceName}的客户详情`);
  };

  // 结构化卡片渲染
  const renderContent = () => {
    if (!isUser && message.extra) {
      const extraAction = message.extra.action as string | undefined;

      // 追问消息：只渲染追问气泡
      if (extraAction === 'follow_up') {
        const text = message.extra.text as string;
        return (
          <div className="max-w-[85%]">
            <FollowUpChip text={text} onSendMessage={onSendMessage} />
          </div>
        );
      }

      // 风险预查卡片（有 risk_level 或 h5_url 字段）
      if (extraAction === 'result' || extraAction === 'ambiguous' || extraAction === 'not_found') {
        // 拓户准备卡片（有 insights_h5_url 或 script_h5_url 字段）
        if (message.extra.insights_h5_url !== undefined || message.extra.script_h5_url !== undefined) {
          return (
            <OutreachCard
              data={message.extra}
              onSendMessage={onSendMessage}
            />
          );
        }
        // 产品智荐卡片（有 detail_h5_url 或 products 字段）
        if (message.extra.detail_h5_url !== undefined || message.extra.products !== undefined) {
          return (
            <ProductRecommendCard
              data={message.extra}
              onSendMessage={onSendMessage}
            />
          );
        }
        // 产品智能匹配卡片（有 needs_summary 或 matches 字段）
        if (message.extra.needs_summary !== undefined || message.extra.matches !== undefined) {
          return (
            <ProductMatchCard
              data={message.extra}
              onSendMessage={onSendMessage}
            />
          );
        }
        // 对公账户开户卡片（有 upload_url 或 app_id 字段）
        if (message.extra.upload_url !== undefined || message.extra.app_id !== undefined) {
          return (
            <AccountOpeningCard
              data={message.extra}
              onSendMessage={onSendMessage}
            />
          );
        }
        return (
          <RiskCheckCard
            data={message.extra}
            onSendMessage={onSendMessage}
          />
        );
      }

      // 潜客推荐卡片
      return (
        <PotentialCustomerCard
          data={message.extra}
          onRequestDetail={handleRequestDetail}
          onSendMessage={onSendMessage}
        />
      );
    }

    if (isUser) {
      return (
        <p className="text-sm leading-relaxed whitespace-pre-wrap">
          {message.content}
        </p>
      );
    }

    return (
      <div className={`markdown-content text-sm ${streaming ? 'typing-cursor' : ''}`}>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
          {message.content || (streaming ? '' : '...')}
        </ReactMarkdown>
      </div>
    );
  };

  return (
    <div
      className={`message-enter flex gap-3 mb-6 ${
        isUser ? 'justify-end' : 'justify-start'
      }`}
    >
      {/* AI 头像 */}
      {!isUser && (
        <div className="flex-shrink-0 w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-blue-700 flex items-center justify-center text-white text-sm font-bold">
          AI
        </div>
      )}

      {/* 消息内容 */}
      <div
        className={`${
          message.extra ? 'max-w-[85%]' : 'max-w-[75%]'
        } ${isUser
            ? 'bg-blue-600 text-white rounded-2xl rounded-br-md px-4 py-3'
            : message.extra
              ? ''
              : 'bg-white text-gray-800 rounded-2xl rounded-bl-md px-4 py-3 shadow-sm border border-gray-100'
        }`}
      >
        {renderContent()}
      </div>

      {/* 用户头像 */}
      {isUser && (
        <div className="flex-shrink-0 w-8 h-8 rounded-full bg-gradient-to-br from-gray-400 to-gray-600 flex items-center justify-center text-white text-sm font-bold">
          U
        </div>
      )}
    </div>
  );
};

export default ChatMessageComponent;
