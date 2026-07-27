import React, { useState, useEffect, useCallback } from 'react';
import type { ReportTemplate, ChatMessage } from '../types';

interface ReportGenerateCardProps {
  data: Record<string, unknown>;
  onSendMessage?: (content: string) => void;
  onAddMessage?: (msg: ChatMessage) => void;
}

/** 获取后端 H5 页面 URL */
function getBaseH5Url(): string {
  const port = window.location.port === '3000' ? '8000' : window.location.port;
  return `${window.location.protocol}//${window.location.hostname}:${port}/h5/report-viewer.html`;
}

// ============================================================
// 模板选择
// ============================================================
const TemplateGrid: React.FC<{
  templates: ReportTemplate[];
  onSelect: (t: ReportTemplate) => void;
}> = ({ templates, onSelect }) => (
  <div className="bg-white rounded-xl border border-blue-100 shadow-sm overflow-hidden">
    <div className="px-5 py-4 bg-gradient-to-r from-blue-50 to-indigo-50 border-b border-blue-100">
      <div className="flex items-center gap-2">
        <svg className="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8}
            d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <span className="text-base font-semibold text-gray-800">选择报告模板</span>
      </div>
      <p className="text-xs text-gray-500 mt-1 ml-7">请选择一种报告模板开始生成</p>
    </div>
    <div className="divide-y divide-gray-100">
      {templates.map((t) => (
        <button
          key={t.id}
          onClick={() => onSelect(t)}
          className="w-full flex items-center gap-3 px-5 py-3.5 text-left
                     hover:bg-blue-50 transition-colors duration-150 group"
        >
          <span className="w-2 h-2 rounded-full bg-blue-400 group-hover:bg-blue-600 transition-colors flex-shrink-0" />
          <span className="text-sm font-medium text-gray-700 group-hover:text-blue-700 transition-colors">
            {t.name}
          </span>
          <svg className="w-4 h-4 text-gray-300 group-hover:text-blue-400 ml-auto flex-shrink-0 transition-colors"
               fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
        </button>
      ))}
    </div>
  </div>
);

// ============================================================
// 跳转卡片（展示模板名称 + 跳转 H5）
// ============================================================
const RedirectCard: React.FC<{
  templateId: string;
  templateName: string;
  templateIcon: string;
  message?: string;
}> = ({ templateId, templateName, templateIcon, message }) => {
  // 从 localStorage 读取当前对话 ID，携带到 H5 以便跳转回来时定位对话
  const convId = typeof window !== 'undefined' ? localStorage.getItem('currentConversationId') || '' : '';
  const baseUrl = getBaseH5Url();
  const urlParams = new URLSearchParams();
  urlParams.set('templateId', templateId);
  urlParams.set('templateName', templateName);
  if (convId) urlParams.set('conversationId', convId);
  const h5Url = `${baseUrl}?${urlParams.toString()}`;
  return (
    <div className="bg-white rounded-xl border border-blue-100 shadow-sm overflow-hidden">
      <div className="px-5 py-4 bg-gradient-to-r from-amber-50 to-orange-50 border-b border-amber-100">
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7" />
            <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z" />
          </svg>
          <span className="text-base font-semibold text-gray-800">已选择模板</span>
        </div>
      </div>
      <div className="p-5 space-y-4">
        <div className="flex items-center gap-3">
          <span className="text-3xl">{templateIcon}</span>
          <div>
            <div className="text-sm font-semibold text-gray-800">{templateName}</div>
            <p className="text-xs text-gray-500 mt-0.5">
              {message || '请在编辑页面上传附件并生成报告'}
            </p>
          </div>
        </div>
        <a
          href={h5Url}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center justify-center gap-2 px-4 py-3 bg-blue-600 text-white text-sm font-medium
                     rounded-lg hover:bg-blue-700 transition-colors"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
          </svg>
          跳转到编辑页面上传附件
        </a>
      </div>
    </div>
  );
};

// ============================================================
// 进度卡片（实时轮询报告生成状态）
// ============================================================
interface ReportStatus {
  reportId: string;
  templateName: string;
  companyName: string;
  status: 'generating' | 'completed' | 'failed';
  progress: number;
  errorMessage: string;
}

const ProgressCard: React.FC<{ reportId: string }> = ({ reportId }) => {
  const [status, setStatus] = useState<ReportStatus | null>(null);

  const fetchStatus = useCallback(async () => {
    try {
      const res = await fetch(`/api/generate-report/${reportId}/status`);
      if (!res.ok) return false;
      const data: ReportStatus = await res.json();
      setStatus(data);
      return data.status === 'completed' || data.status === 'failed';
    } catch {
      return false;
    }
  }, [reportId]);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setInterval>;

    const poll = async () => {
      if (stopped) return;
      const done = await fetchStatus();
      if (done) clearInterval(timer);
    };

    poll();
    timer = setInterval(poll, 2000);
    return () => { stopped = true; clearInterval(timer); };
  }, [fetchStatus]);

  const handleDownload = async () => {
    try {
      const res = await fetch(`/api/generate-report/${reportId}/content`);
      if (!res.ok) return;
      const data = await res.json();
      const content = data.content || '';
      const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${status?.companyName || 'report'}_尽调报告.md`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('下载失败:', err);
    }
  };

  const isCompleted = status?.status === 'completed';
  const isFailed = status?.status === 'failed';

  return (
    <div className="bg-white rounded-xl border border-blue-100 shadow-sm overflow-hidden">
      {/* 头部 */}
      <div className="px-4 py-3 bg-gradient-to-r from-blue-50 to-indigo-50 border-b border-blue-100 flex items-center gap-2">
        <svg className="w-4 h-4 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <span className="text-sm font-semibold text-gray-700">报告生成中</span>
        {status?.templateName && (
          <span className="text-xs text-gray-400">· {status.templateName}</span>
        )}
      </div>

      {/* 主体 */}
      <div className="px-4 py-3">
        {status?.companyName && (
          <p className="text-xs text-gray-500 mb-2">企业：{status.companyName}</p>
        )}

        {/* 进度条 */}
        <div className="w-full bg-gray-100 rounded-full h-2 mb-2 overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-500 ${
              isCompleted ? 'bg-green-500' : isFailed ? 'bg-red-500' : 'bg-blue-500'
            }`}
            style={{ width: `${Math.max(status?.progress || 0, 5)}%` }}
          />
        </div>

        <div className="flex items-center justify-between">
          <span className={`text-xs ${isFailed ? 'text-red-500' : 'text-gray-500'}`}>
            {!status ? '连接中...'
              : isCompleted ? '生成完成 ✓'
              : isFailed ? (status.errorMessage || '生成失败')
              : (status.errorMessage || '正在生成报告...')}
          </span>
          <span className="text-xs text-gray-400">{status?.progress || 0}%</span>
        </div>

        {/* 下载按钮 */}
        {isCompleted && (
          <button
            onClick={handleDownload}
            className="mt-3 w-full flex items-center justify-center gap-2 px-4 py-2 bg-blue-600
                       text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            下载报告
          </button>
        )}

        {/* 失败提示 */}
        {isFailed && (
          <p className="mt-2 text-xs text-red-400">
            请返回编辑页面检查数据后重试
          </p>
        )}
      </div>
    </div>
  );
};

// ============================================================
// 主组件
// ============================================================
const ReportGenerateCard: React.FC<ReportGenerateCardProps> = ({ data, onSendMessage, onAddMessage }) => {
  const stage = data.stage as string;

  // stage=templates → 展示模板列表
  if (stage === 'templates') {
    const templates = data.templates as ReportTemplate[] | undefined;
    if (!templates || templates.length === 0) {
      return (
        <div className="bg-white rounded-xl border border-blue-100 shadow-sm p-5 text-center text-gray-500 text-sm">
          暂无可用模板
        </div>
      );
    }
    return (
      <TemplateGrid
        templates={templates}
        onSelect={(t) => {
          // 直接在前端生成跳转卡片，不走后端协调器（避免LLM提取template_id失败）
          if (onAddMessage) {
            onAddMessage({
              id: `redirect-${Date.now()}`,
              role: 'assistant',
              content: '',
              extra: {
                action: 'result',
                _skill_name: 'generate_report',
                stage: 'redirect',
                template_id: t.id,
                template_name: t.name,
                template_icon: t.icon || '📄',
                message: '请在报告编辑页面中上传附件并生成报告',
              },
              created_at: new Date().toISOString(),
            });
          } else {
            // fallback：如果没有onAddMessage，走原来的文本消息路由
            onSendMessage?.(`使用"${t.name}"模板(ID:${t.id})生成尽调报告`);
          }
        }}
      />
    );
  }

  // stage=redirect → 展示跳转卡片
  if (stage === 'redirect') {
    const tid = (data.template_id as string) || '';
    const tname = (data.template_name as string) || '';
    const ticon = (data.template_icon as string) || '📄';
    const msg = (data.message as string) || '请在编辑页面上传附件并生成报告';
    return (
      <RedirectCard
        templateId={tid}
        templateName={tname}
        templateIcon={ticon}
        message={msg}
      />
    );
  }

  // stage=progress → 实时进度卡片
  if (stage === 'progress') {
    const rid = (data.report_id as string) || '';
    if (!rid) return <div className="text-sm text-gray-500 p-3">报告 ID 缺失</div>;
    return <ProgressCard reportId={rid} />;
  }

  return null;
};

export default ReportGenerateCard;
