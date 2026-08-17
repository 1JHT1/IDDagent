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

/** 【新增】获取浏览器存储中的 organization */
function getStoredOrganization(): string {
  try {
    return localStorage.getItem('userOrganization') || '';
  } catch { return ''; }
}

// ============================================================
// 模板选择
// ============================================================
const TemplateGrid: React.FC<{
  templates: ReportTemplate[];
  organization?: string;
  onSelect: (t: ReportTemplate) => void;
}> = ({ templates, onSelect, organization }) => {
  const baseUrl = getBaseH5Url();
  const libUrl = `${baseUrl}?mode=browse${organization ? '&organization=' + encodeURIComponent(organization) : ''}`;
  return (
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
    {/* 查看模板库按钮 */}
    <a
      href={libUrl}
      target="_blank"
      rel="noopener noreferrer"
      className="flex items-center justify-center gap-2 px-4 py-3 bg-gray-50 text-sm text-blue-600 font-medium
                 border-t border-gray-100 hover:bg-blue-50 transition-colors"
    >
      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
          d="M4 6h16M4 10h16M4 14h16M4 18h16" />
      </svg>
      查看模板库
    </a>
  </div>
);
};

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
  const org = getStoredOrganization();
  if (org) urlParams.set('organization', org);
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
        <button
          onClick={() => window.open(h5Url, '_blank')}
          className="flex items-center justify-center gap-2 px-4 py-3 bg-blue-600 text-white text-sm font-medium
                     rounded-lg hover:bg-blue-700 transition-colors w-full cursor-pointer"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
          </svg>
          跳转到编辑页面上传附件
        </button>
      </div>
    </div>
  );
};

// ============================================================
// 进度卡片（实时轮询报告生成状态 + 动态阶段展示）
// ============================================================
interface ReportStatus {
  reportId: string;
  templateName: string;
  companyName: string;
  status: 'generating' | 'completed' | 'failed';
  progress: number;
  errorMessage: string;
}

/** 生成阶段定义（按 progress 阈值映射） */
const GENERATE_STAGES = [
  { label: '加载模板', threshold: 20 },
  { label: '解析附件', threshold: 35 },
  { label: '提取数据', threshold: 50 },
  { label: '生成内容', threshold: 80 },
];

/** 根据 progress 返回当前进行中的阶段下标（全部完成返回 length） */
function getCurrentStageIndex(progress: number): number {
  for (let i = 0; i < GENERATE_STAGES.length; i++) {
    if (progress < GENERATE_STAGES[i].threshold) return i;
  }
  return GENERATE_STAGES.length;
}

/** 动态阶段状态文字：优先使用后端下发的 errorMessage（后端实时更新阶段描述） */
function getStageText(status: ReportStatus | null): string {
  if (!status) return '正在连接生成服务...';
  if (status.status === 'failed') return status.errorMessage || '生成失败';
  if (status.status === 'completed') return '报告已生成完成';
  const stage = GENERATE_STAGES[getCurrentStageIndex(status.progress)];
  if (status.errorMessage) return status.errorMessage;
  return stage ? stage.label + '中...' : '正在生成报告...';
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

  const handleViewReport = () => {
    const baseUrl = getBaseH5Url();
    const org = getStoredOrganization();
    const url = org ? `${baseUrl}?reportId=${reportId}&organization=${encodeURIComponent(org)}` : `${baseUrl}?reportId=${reportId}`;
    window.open(url, '_blank');
  };

  const handlePrint = () => {
    const baseUrl = getBaseH5Url();
    const org = getStoredOrganization();
    const url = org ? `${baseUrl}?reportId=${reportId}&organization=${encodeURIComponent(org)}` : `${baseUrl}?reportId=${reportId}`;
    window.open(url, '_blank');
  };

  const isCompleted = status?.status === 'completed';
  const isFailed = status?.status === 'failed';
  const isGenerating = status?.status === 'generating';
  const progress = status?.progress || 0;
  const currentStage = getCurrentStageIndex(progress);
  const stageText = getStageText(status);

  return (
    <div className="bg-white rounded-xl border border-blue-100 shadow-sm overflow-hidden">
      {/* 头部：动态生成状态 */}
      <div className="px-4 py-3 bg-gradient-to-r from-blue-50 to-indigo-50 border-b border-blue-100">
        <div className="flex items-center gap-2">
          <svg
            className={`w-4 h-4 ${isGenerating ? 'text-blue-600 animate-spin' : isCompleted ? 'text-green-500' : isFailed ? 'text-red-500' : 'text-blue-600'}`}
            fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>

          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-1.5">
              <span className={`text-sm font-semibold ${isCompleted ? 'text-green-700' : isFailed ? 'text-red-600' : 'text-gray-700'}`}>
                {!status ? '正在连接'
                  : isCompleted ? '报告生成完成'
                  : isFailed ? '报告生成失败'
                  : '报告生成中'}
              </span>
              {/* 三点脉冲动画（生成中） */}
              {isGenerating && (
                <span className="flex items-center gap-0.5">
                  <span className="status-dot w-1.5 h-1.5 rounded-full bg-blue-500" />
                  <span className="status-dot w-1.5 h-1.5 rounded-full bg-blue-500" />
                  <span className="status-dot w-1.5 h-1.5 rounded-full bg-blue-500" />
                </span>
              )}
              {status?.templateName && (
                <span className="text-xs text-gray-400 truncate">· {status.templateName}</span>
              )}
            </div>
            {/* 动态阶段文字：切换时淡入滑动 */}
            <p
              key={stageText}
              className={`status-fade text-xs mt-0.5 truncate ${isFailed ? 'text-red-500' : isCompleted ? 'text-green-600' : 'text-blue-600'}`}
            >
              {stageText}
            </p>
          </div>

          {/* 百分比（生成中高亮跳动） */}
          {isGenerating && (
            <span className="text-sm font-bold text-blue-600 tabular-nums">{progress}%</span>
          )}
        </div>
      </div>

      {/* 主体 */}
      <div className="px-4 py-3">
        {status?.companyName && (
          <p className="text-xs text-gray-500 mb-2">企业：{status.companyName}</p>
        )}

        {/* 生成中：阶段胶囊指示器 */}
        {isGenerating && status && (
          <div className="flex items-center gap-1 flex-wrap mb-2">
            {GENERATE_STAGES.map((s, i) => {
              const done = progress >= s.threshold;
              const active = !done && currentStage === i;
              return (
                <React.Fragment key={s.label}>
                  <span
                    className={`px-2 py-0.5 rounded-full text-[10px] font-medium transition-all duration-300 ${
                      done
                        ? 'bg-green-100 text-green-700'
                        : active
                        ? 'bg-blue-600 text-white animate-pulse'
                        : 'bg-gray-100 text-gray-400'
                    }`}
                  >
                    {done ? '✓ ' : active ? '● ' : '○ '}{s.label}
                  </span>
                  {i < GENERATE_STAGES.length - 1 && (
                    <span className="text-[10px] text-gray-300">→</span>
                  )}
                </React.Fragment>
              );
            })}
          </div>
        )}

        {/* 进度条（生成中带条纹流动效果） */}
        <div className="w-full bg-gray-100 rounded-full h-2 mb-2 overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-500 ${
              isCompleted ? 'bg-green-500' : isFailed ? 'bg-red-500' : 'bg-blue-500 progress-stripes'
            }`}
            style={{ width: `${Math.max(progress, 5)}%` }}
          />
        </div>

        <div className="flex items-center justify-between">
          <span className={`text-xs ${isFailed ? 'text-red-500' : 'text-gray-500'}`}>
            {!status ? '连接中...'
              : isCompleted ? '生成完成 ✓'
              : isFailed ? (status.errorMessage || '生成失败')
              : (status.errorMessage || '正在生成报告...')}
          </span>
          <span className="text-xs text-gray-400">{progress}%</span>
        </div>

        {/* 完成按钮组 */}
        {isCompleted && (
          <div className="mt-3 flex gap-2">
            <button
              onClick={handleViewReport}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2 bg-blue-600
                         text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
              </svg>
              查看报告
            </button>
            <button
              onClick={handlePrint}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2 bg-emerald-600
                         text-white text-sm font-medium rounded-lg hover:bg-emerald-700 transition-colors"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M6 9V2h12v7M6 18H4a2 2 0 01-2-2v-5a2 2 0 012-2h16a2 2 0 012 2v5a2 2 0 01-2 2h-2" />
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M6 14h12v8H6z" />
              </svg>
              打印
            </button>
          </div>
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
        organization={data.organization as string || getStoredOrganization()}
        onSelect={(t) => {
          // 向后端发送固定格式模板选择消息（【模板选择】<template_id>）：
          // generate_report 展示模板列表后以 pendingSkill 等待此消息，后端解析注入
          // template_id 重入技能返回跳转信息并推进任务完成状态。此前本地生成跳转卡
          // 或走 LLM 提取模板 ID，多意图管道不感知模板选择，任务被误判为已完成
          if (onSendMessage) {
            onSendMessage(`【模板选择】${t.id}`);
          } else if (onAddMessage) {
            // fallback：无发送通道时本地生成跳转卡（仅渲染，不参与管道进度）
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
