import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ChatMessage, ChatAttachment } from '../types';
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

/** 格式化文件大小 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 附件列表渲染（用户消息气泡内） */
const AttachmentList: React.FC<{ attachments: ChatAttachment[] }> = ({ attachments }) => (
  <div className="flex flex-col gap-2 mt-2">
    {attachments.map((att, idx) => {
      const isImage = att.type?.startsWith('image/');
      if (isImage) {
        return (
          <a
            key={`${att.url}-${idx}`}
            href={att.url}
            target="_blank"
            rel="noopener noreferrer"
            className="block"
            title={att.name}
          >
            <img
              src={att.url}
              alt={att.name}
              className="max-w-[220px] max-h-[160px] rounded-lg border border-blue-400/40 object-cover"
            />
          </a>
        );
      }
      return (
        <a
          key={`${att.url}-${idx}`}
          href={att.url}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-2 px-3 py-2 rounded-lg bg-blue-500/40 hover:bg-blue-500/60
                     border border-blue-400/40 transition-colors group"
          title={`点击查看 ${att.name}`}
        >
          {/* 文件图标 */}
          <svg className="w-5 h-5 text-blue-100 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
              d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
          </svg>
          <div className="min-w-0">
            <div className="text-xs font-medium text-white truncate max-w-[180px]">{att.name}</div>
            <div className="text-[10px] text-blue-100">{formatSize(att.size)}</div>
          </div>
        </a>
      );
    })}
  </div>
);

const ChatMessageComponent: React.FC<ChatMessageProps> = ({ message, onSendMessage }) => {
  const isUser = message.role === 'user';
  const streaming = isStreamingMessage(message);

  const handleRequestDetail = (_sourceId: string, sourceName: string) => {
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

      // 结构化卡片渲染（优先根据 _skill_name 路由，兜底按字段匹配）
      if (extraAction === 'result' || extraAction === 'ambiguous' || extraAction === 'not_found') {
        const skillName = message.extra._skill_name as string | undefined;

        // 根据技能名称精确路由
        if (skillName === 'prepare_customer_outreach') {
          return <OutreachCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        if (skillName === 'recommend_products') {
          return <ProductRecommendCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        if (skillName === 'match_products_intelligently') {
          return <ProductMatchCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        if (skillName === 'open_corporate_account') {
          return <AccountOpeningCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        if (skillName === 'check_company_risk') {
          return <RiskCheckCard data={message.extra} onSendMessage={onSendMessage} />;
        }

        // 兜底：按字段特征匹配（兼容旧数据）
        if (message.extra.insights_h5_url !== undefined || message.extra.script_h5_url !== undefined) {
          return <OutreachCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        if (message.extra.detail_h5_url !== undefined || message.extra.products !== undefined) {
          return <ProductRecommendCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        if (message.extra.needs_summary !== undefined || message.extra.matches !== undefined) {
          return <ProductMatchCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        if (message.extra.upload_url !== undefined || message.extra.app_id !== undefined) {
          return <AccountOpeningCard data={message.extra} onSendMessage={onSendMessage} />;
        }
        return <RiskCheckCard data={message.extra} onSendMessage={onSendMessage} />;
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
        <>
          {message.content && (
            <p className="text-sm leading-relaxed whitespace-pre-wrap">
              {message.content}
            </p>
          )}
          {message.attachments && message.attachments.length > 0 && (
            <AttachmentList attachments={message.attachments} />
          )}
        </>
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
