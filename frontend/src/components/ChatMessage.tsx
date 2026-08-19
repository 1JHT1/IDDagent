import React, { useState, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ChatMessage, ChatAttachment, PlanStatusData } from '../types';
import { isStreamingMessage } from '../types';
import { isCardClickProtocol } from '../hooks/useChat';
import RiskCheckCard from './RiskCheckCard';
import HistoricalDDQueryCard from './HistoricalDDQueryCard';
import InformationCheckCard from './InformationCheckCard';
import ReportGenerateCard from './ReportGenerateCard';
import CompanyQueryCard from './CompanyQueryCard';
import CompanyNameSelector from './CompanyNameSelector';
import ClarificationCard from './ClarificationCard';
import PlanStatusCard from './PlanStatusCard';
import PlanConfirmCard from './PlanConfirmCard';
import ResumeConfirmCard from './ResumeConfirmCard';
import FollowUpChip from './FollowUpChip';

interface ChatMessageProps {
  message: ChatMessage;
  /** 发送消息回调（用于卡片交互；silent=true 静默发送：不插入用户气泡） */
  onSendMessage?: (content: string, silent?: boolean) => void;
  /** 本地生成卡片消息回调（如模板选择后的跳转卡），插入到步骤确认卡片之前 */
  onAddMessage?: (msg: ChatMessage) => void;
  /** 穿插区域已结束时禁用功能卡片（穿插确认卡片之后的穿插对话区卡片不可再点击） */
  interleaveDisabled?: boolean;
}

/** 格式化文件大小 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 从消息 extra 中提取关键信息生成可复制文本 */
function getExtraCopyText(extra: Record<string, unknown>): string {
  const parts: string[] = [];
  const label = extra._skill_name
    ? { check_company_risk: '风险预查',
        query_due_diligence_reports: '历史尽调报告' ,
        generate_report: '报告生成' }[extra._skill_name as string]
    : undefined;
  if (label) parts.push(`【${label}】`);
  if (extra.company_name) parts.push(`企业名称：${extra.company_name}`);
  if (extra.credit_code) parts.push(`统一社会信用代码：${extra.credit_code}`);
  if (extra.risk_level) parts.push(`风险等级：${extra.risk_level}`);
  if (extra.risk_summary) parts.push(`风险摘要：${extra.risk_summary}`);
  if (extra.analysis_summary) parts.push(`分析摘要：${extra.analysis_summary}`);
  if (extra.needs_summary) parts.push(`需求摘要：${extra.needs_summary}`);
  if (extra.message) parts.push(`说明：${extra.message}`);
  if (extra.keyword) parts.push(`关键词：${extra.keyword}`);
  if (extra.total_count) parts.push(`查询结果：共 ${extra.total_count} 条记录`);
  return parts.join('\n');
}

/** 复制按钮组件 */
const CopyButton: React.FC<{ text: string; className?: string }> = ({ text, className = '' }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // fallback for older browsers
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  }, [text]);

  return (
    <button
      onClick={handleCopy}
      className={`inline-flex items-center justify-center w-7 h-7 rounded-md
        hover:bg-gray-200/60 active:scale-90 transition-all duration-150 ${className}`}
      title={copied ? '已复制' : '复制'}
    >
      {copied ? (
        <svg className="w-3.5 h-3.5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M5 13l4 4L19 7" />
        </svg>
      ) : (
        <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
        </svg>
      )}
    </button>
  );
};

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
              className="max-w-[220px] max-h-[160px] rounded-lg border border-blue-400/40 object-contain"
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

const ChatMessageComponent: React.FC<ChatMessageProps> = ({ message, onSendMessage, onAddMessage, interleaveDisabled }) => {
  const isUser = message.role === 'user';
  const streaming = isStreamingMessage(message);

  // 卡片点击协议消息（模糊匹配候选/兜底按钮发送的固定句式）：原文仅作为后端输入协议，
  // 不在用户气泡中展示企业名称与信用代码——整体隐藏，直接呈现后续的结果卡片/下一步。
  // 标记（实时路径）与 content 特征识别（历史消息重载路径）双保险。
  const hiddenUserProtocol =
    isUser &&
    ((message.extra as { action?: string } | undefined)?.action === 'card_click' ||
      isCardClickProtocol(message.content || ''));

  // 决定复制内容：纯文本直接用 content，卡片消息从 extra 提取
  const copyText = !isUser && message.extra
    ? (getExtraCopyText(message.extra) || message.content || '')
    : (message.content || '');

  // 结构化卡片渲染
  const renderContent = () => {
    if (!isUser && message.extra) {
      const extraAction = message.extra.action as string | undefined;

      // 追问消息：只渲染追问气泡
      if (extraAction === 'follow_up') {
        const text = message.extra.text as string;
        return (
          <div className="max-w-[85%]">
            <FollowUpChip text={text} onSendMessage={onSendMessage} disabled={interleaveDisabled} />
          </div>
        );
      }

      // 结构化卡片渲染（优先根据 _skill_name 路由，兜底按字段匹配）
      if (extraAction === 'result' || extraAction === 'ambiguous' || extraAction === 'not_found') {
        const skillName = message.extra._skill_name as string | undefined;

        // 根据技能名称精确路由
        if (skillName === 'check_company_risk') {
          return <RiskCheckCard data={message.extra} onSendMessage={onSendMessage} disabled={interleaveDisabled} />;
        }
        if (skillName === 'query_due_diligence_reports') {
          return <HistoricalDDQueryCard data={message.extra} onSendMessage={onSendMessage} disabled={interleaveDisabled} />;
        }
        if (skillName === 'verify_business_license') {
          return <InformationCheckCard data={message.extra} onSendMessage={onSendMessage} disabled={interleaveDisabled} />;
        }
        if (skillName === 'generate_report') {
          return <ReportGenerateCard data={message.extra} onSendMessage={onSendMessage} onAddMessage={onAddMessage} disabled={interleaveDisabled} />;
        }
        if (skillName && skillName.startsWith('query_')) {
          return <CompanyQueryCard data={message.extra} onSendMessage={onSendMessage} disabled={interleaveDisabled} />;
        }

        // 兜底：按字段特征匹配（兼容旧数据）
        return <RiskCheckCard data={message.extra} onSendMessage={onSendMessage} disabled={interleaveDisabled} />;
      }

      // 候选企业选择器（企业名匹配到多条结果时让用户选择）
      // 说明文本作为独立回答气泡显示在选项卡之前，不放在卡片底部
      if (extraAction === 'company_name_candidates') {
        const options = message.extra.options as { credit_code: string; company_name: string }[] | undefined;
        if (options && options.length > 0) {
          return (
            <>
              {typeof message.extra.message === 'string' && message.extra.message ? (
                <div className="bg-white text-gray-800 rounded-2xl rounded-bl-md px-4 py-3 shadow-sm border border-gray-100 text-sm leading-relaxed mb-3 max-w-[75%]">
                  {message.extra.message}
                </div>
              ) : null}
              <CompanyNameSelector
                options={options}
                message={typeof message.extra.message === 'string' ? message.extra.message : undefined}
                keyword={message.extra.keyword as string}
                taskLabel={message.extra.task_label as string | undefined}
                queryLabel={message.extra.query_label as string | undefined}
                skillName={message.extra._skill_name as string | undefined}
                confirmed={message.extra.confirmed === true}
                onSendMessage={onSendMessage}
                disabled={interleaveDisabled}
              />
            </>
          );
        }
      }

      // 意图澄清卡片（同技能多主体冲突时让用户确认执行对象）
      if (extraAction === 'clarification') {
        const question = message.extra.question as string;
        const options = message.extra.options as { label: string; value: string }[] | undefined;
        if (options && options.length > 0) {
          return (
            <ClarificationCard
              question={question || ''}
              options={options}
              onSendMessage={onSendMessage}
              disabled={interleaveDisabled}
            />
          );
        }
      }

      // 步骤间确认卡片（规划步骤真正结束后暂停，询问是否继续下一步）
      if (extraAction === 'plan_step_confirm') {
        const text = (message.extra.text as string) || '';
        return (
          <PlanConfirmCard
            text={text}
            currentStep={message.extra.current_step as number | string | undefined}
            totalSteps={message.extra.total_steps as number | string | undefined}
            nextStep={message.extra.next_step as string | undefined}
            onSendMessage={onSendMessage}
            disabled={interleaveDisabled}
          />
        );
      }

      // 穿插恢复确认卡片（穿插的新意图完成后，询问是否回到穿插前那一步继续旧规划）
      if (extraAction === 'resume_confirm') {
        const text = (message.extra.text as string) || '';
        return (
          <ResumeConfirmCard
            text={text}
            stepIndex={message.extra.step_index as number | string | undefined}
            totalSteps={message.extra.total_steps as number | string | undefined}
            stepDesc={message.extra.step_desc as string | undefined}
            onSendMessage={onSendMessage}
            disabled={interleaveDisabled}
          />
        );
      }

      // 任务规划状态面板（plan_status 事件：步骤状态快照，同步每一步执行进度）
      // 全部步骤 DONE 时展示"全部任务已完成"终态——普通技能与报告生成收尾渲染同一张卡片，
      // 视觉保持一致（report-complete finished 注入的终态快照与 SSE 收尾快照走同一分支）
      if (extraAction === 'plan_status') {
        const data = message.extra as unknown as PlanStatusData;
        // 快照无步骤（规划已收尾清除/被丢弃）→ 面板已从消息流移除，此处防御不渲染
        if (!data.steps || data.steps.length === 0) return null;
        return <PlanStatusCard data={data} />;
      }

      // 任务规划提示（进度/预览/汇总）：普通文本气泡渲染（复用 info_needed 同款样式）
      if (extraAction === 'plan_progress' || extraAction === 'plan_preview' || extraAction === 'plan_summary') {
        const text = (message.extra.text as string) || '';
        if (!text) return null;
        return (
          <div className="max-w-[75%] bg-white text-gray-800 rounded-2xl rounded-bl-md px-4 py-3 shadow-sm border border-gray-100">
            <div className="markdown-content text-sm">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>
            </div>
          </div>
        );
      }

      // 文本提示类消息（info_needed 缺企业标识 / need_date_range 需输入时间区间）：以普通文本气泡渲染
      if (extraAction === 'info_needed' || extraAction === 'need_date_range') {
        const text = (message.extra.message as string) || '';
        if (!text) return null;
        return (
          <div className="max-w-[75%] bg-white text-gray-800 rounded-2xl rounded-bl-md px-4 py-3 shadow-sm border border-gray-100">
            <div className="markdown-content text-sm">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>
            </div>
          </div>
        );
      }

      // 无匹配卡片时回退到 Markdown 渲染
      return null;
    }

    if (isUser) {
      // 卡片点击协议消息：整体隐藏（不渲染气泡与复制按钮）
      if (hiddenUserProtocol) return null;
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
    <div className={`message-enter mb-6 ${isUser ? 'flex flex-col items-end' : 'flex flex-col items-start'}`}>
      {/* 第一行：头像 + 消息气泡 */}
      <div className={`flex gap-3 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}>
        {/* AI 头像 */}
        {!isUser && (
          <div className="flex-shrink-0 w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-blue-700 flex items-center justify-center text-white text-sm font-bold">
            AI
          </div>
        )}

        {/* 消息内容 */}
        <div
          className={`${
            message.extra ? 'w-full max-w-full' : 'max-w-[75%]'
          } ${isUser
              ? 'bg-blue-600 text-white rounded-2xl rounded-br-md px-4 py-3 overflow-hidden'
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

      {/* 第二行：复制按钮（非流式 + 有内容 + 非隐藏协议消息时显示） */}
      {copyText && !streaming && !hiddenUserProtocol && (
        <div className={`mt-1 ${isUser ? 'mr-0' : 'ml-11'}`}>
          <CopyButton
            text={copyText}
            className="opacity-100 text-gray-400 hover:text-gray-600 hover:bg-gray-100"
          />
        </div>
      )}
    </div>
  );
};

export default ChatMessageComponent;
