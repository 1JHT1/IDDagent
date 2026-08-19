import React, { useState, useEffect, useCallback, useRef } from 'react';
import type { ReportTemplate, ChatMessage, PlanStatusData } from '../types';
import { persistCardMessage } from '../api/agent';

interface ReportGenerateCardProps {
  data: Record<string, unknown>;
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean;
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
  disabled?: boolean;
}> = ({ templates, onSelect, organization, disabled }) => {
  const baseUrl = getBaseH5Url();
  // 携带当前对话 ID 到 H5：模板库入口（浏览模式）生成报告时也能关联回当前会话，
  // 否则 H5 生成任务 conversationId 为空，穿插挂起恢复时无法按会话兜底解析 report_id
  const convId = typeof window !== 'undefined' ? localStorage.getItem('currentConversationId') || '' : '';
  const libUrl = `${baseUrl}?mode=browse${convId ? '&conversationId=' + encodeURIComponent(convId) : ''}${organization ? '&organization=' + encodeURIComponent(organization) : ''}`;
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
          disabled={disabled}
          className="w-full flex items-center gap-3 px-5 py-3.5 text-left
                     hover:bg-blue-50 transition-colors duration-150 group
                     disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
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
      className={`flex items-center justify-center gap-2 px-4 py-3 bg-gray-50 text-sm text-blue-600 font-medium
                 border-t border-gray-100 hover:bg-blue-50 transition-colors ${
                   disabled ? 'pointer-events-none opacity-50' : ''
                 }`}
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
  disabled?: boolean;
}> = ({ templateId, templateName, templateIcon, message, disabled }) => {
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
          disabled={disabled}
          className="flex items-center justify-center gap-2 px-4 py-3 bg-blue-600 text-white text-sm font-medium
                     rounded-lg hover:bg-blue-700 transition-colors w-full cursor-pointer
                     disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-blue-600"
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

const ProgressCard: React.FC<{
  reportId: string;
  disabled?: boolean;
  /** 本地插入消息（addMessage → 穿插边界之前），用于轮询到报告完成后通知规划收尾 */
  onAddMessage?: (msg: ChatMessage) => void;
}> = ({ reportId, disabled, onAddMessage }) => {
  const [status, setStatus] = useState<ReportStatus | null>(null);
  // 防重复通知：轮询到 completed/failed 后只向后端报告一次规划收尾
  const notifiedRef = useRef(false);
  // 挂载时捕获所属会话 id：notifyReportComplete 是轮询异步回调，可能晚于会话切换返回，
  // 此时实时读 localStorage 已变为新会话——用错误会话调后端会推进其他会话的规划、
  // 并把本会话收尾卡片注入当前消息流，因此收尾必须绑定挂载时的会话并加卸载守卫
  const convIdRef = useRef('');
  const mountedRef = useRef(true);
  useEffect(() => {
    // StrictMode（开发模式）会 double-invoke effect（setup → cleanup → setup），
    // useRef 不随重挂载重新初始化：cleanup 置 false 后若不在此重置，mountedRef
    // 将永远为 false，notifyReportComplete 被守卫永久拦截（进度卡显示完成但规划永不收尾）
    mountedRef.current = true;
    convIdRef.current = typeof window !== 'undefined'
      ? (localStorage.getItem('currentConversationId') || '') : '';
    return () => { mountedRef.current = false; };
  }, []);

  /**
   * 报告生成完成/失败 → 通知后端标记规划步骤结束（仅一次）：
   * - 后端返回 next（还有下一步）→ 本地插入步骤确认卡片（复用 plan_step_confirm 渲染），
   *   用户点击"继续"后走 chatStream 的 planConfirming 分支推进下一步；
   * - 后端返回 finished（最后一步）→ 本地插入"全部任务已完成"汇总气泡（plan_progress 渲染）。
   * 非规划模式/重复通知后端幂等返回 ignored，无需处理。
   */
  const notifyReportComplete = useCallback(async (reportStatus: string) => {
    if (notifiedRef.current) return;
    // 组件已卸载（轮询期间用户已切换/新建会话）→ 丢弃本次收尾：不调后端（避免用错误会话推进规划）、
    // 不注入本地（避免收尾卡片串入其他会话消息流）；用户切回原会话后进度卡重新挂载轮询会再次收尾。
    // 守卫失败不置位 notifiedRef（如 StrictMode 双挂载瞬态/会话未捕获），后续轮询可重试，避免永久静默
    if (!mountedRef.current) return;
    const convId = convIdRef.current;
    if (!convId) return;
    // 发起前实时校验：轮询间隔内用户可能已新建/切换会话，但组件因消息流复用尚未卸载
    // （mountedRef 仍为 true），若不拦截会把旧会话收尾请求发出，其响应后校验虽能兜底，
    // 但旧构建/异常时序下仍存在注入窗口——发起即弃，避免任何跨会话副作用
    if (typeof window !== 'undefined'
        && (localStorage.getItem('currentConversationId') || '') !== convId) return;
    try {
      // 收尾始终绑定挂载时所属会话，而非实时读取 localStorage（切换会话后已变为新会话）
      const token = typeof window !== 'undefined'
        ? (localStorage.getItem('auth_token') || '') : '';
      const res = await fetch('/api/plan/report-complete', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ conversationId: convId, reportId, status: reportStatus }),
      });
      if (!res.ok) return;
      const resp = await res.json();
      // 响应晚到时再次校验：组件已卸载（切换/新建会话后消息流清空）或当前显示的会话
      // 已不是收尾归属会话 → 丢弃（双保险）。mountedRef 复查是关键：新建对话时
      // createConversation 的 RTT 窗口内 localStorage 仍是旧会话值，仅凭 localStorage
      // 校验会放行，把本会话的规划面板/确认卡注入新会话消息流（跨会话残留任务规划卡）
      if (!mountedRef.current
          || (typeof window !== 'undefined'
              && (localStorage.getItem('currentConversationId') || '') !== convId)) return;
      // 收尾真正落地（注入本地面板）才标记已通知：发起前/响应后校验被拦截或请求失败时不置位，
      // 避免"已尝试但未注入"被永久记录——用户切回原会话后进度卡重新挂载仍可再次收尾，不会静默悬挂
      notifiedRef.current = true;
      // 规划状态快照（report-complete 响应携带）：注入/更新规划面板消息
      // （固定 id = plan-status-{planId}，与 SSE plan_status 事件同一 id，重复通知自动覆盖不重复插入）
      if (resp?.plan && resp.plan.active && resp.plan.steps?.length && onAddMessage) {
        onAddMessage({
          id: `plan-status-${(resp.plan as PlanStatusData).planId || reportId}`,
          role: 'assistant',
          content: '',
          extra: {
            action: 'plan_status',
            ...resp.plan,
            // finished 分支的收尾汇总文案在后端 text 字段，注入面板 summary 展示终态
            ...(resp.status === 'finished' && resp.text ? { summary: resp.text } : {}),
          } as unknown as Record<string, unknown>,
          created_at: new Date().toISOString(),
        });
      }
      if (resp?.status === 'next' && onAddMessage) {
        const confirmMsg: ChatMessage = {
          id: `plan-confirm-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
          role: 'assistant',
          content: '',
          extra: {
            action: 'plan_step_confirm',
            text: resp.text,
            current_step: resp.current_step,
            total_steps: resp.total_steps,
            next_step: resp.next_step,
          },
          created_at: new Date().toISOString(),
        };
        onAddMessage(confirmMsg);
        // 同步持久化"继续下一步"确认卡：该卡由 report-complete 响应在前端本地生成、不经
        // SSE 事件流（persistPlanCardEvent 拦截不到）；不持久化则切换对话框后确认卡消失，
        // 无法继续下一步。后端按穿插边界规则插入，切换后按原位置恢复（与模板跳转卡同机制）
        persistCardMessage(convId, { id: confirmMsg.id, extra: confirmMsg.extra }).catch(() => {});
      } else if (resp?.status === 'finished' && onAddMessage) {
        const summaryMsg: ChatMessage = {
          id: `plan-summary-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
          role: 'assistant',
          content: '',
          extra: {
            action: 'plan_progress',
            text: resp.text,
          },
          created_at: new Date().toISOString(),
        };
        onAddMessage(summaryMsg);
        // 同步持久化收尾汇总气泡（同上：本地生成不经 SSE，不持久化则切换会话后消失）
        persistCardMessage(convId, { id: summaryMsg.id, extra: summaryMsg.extra }).catch(() => {});
      }
    } catch {
      // 通知失败不影响进度卡自身展示，用户仍可查看/重试报告
    }
  }, [reportId, onAddMessage]);

  const fetchStatus = useCallback(async () => {
    try {
      const res = await fetch(`/api/generate-report/${reportId}/status`);
      if (!res.ok) return false;
      const data: ReportStatus = await res.json();
      setStatus(data);
      const done = data.status === 'completed' || data.status === 'failed';
      if (done) notifyReportComplete(data.status);
      return done;
    } catch {
      return false;
    }
  }, [reportId, notifyReportComplete]);

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
              disabled={disabled}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2 bg-blue-600
                         text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors
                         disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-blue-600"
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
              disabled={disabled}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2 bg-emerald-600
                         text-white text-sm font-medium rounded-lg hover:bg-emerald-700 transition-colors
                         disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-emerald-600"
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
const ReportGenerateCard: React.FC<ReportGenerateCardProps> = ({ data, onSendMessage, onAddMessage, disabled }) => {
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
        disabled={disabled}
        onSelect={(t) => {
          // 直接在前端生成跳转卡片，不走后端协调器（避免LLM提取template_id失败）
          const cardId = `redirect-${Date.now()}`;
          const extra = {
            action: 'result',
            _skill_name: 'generate_report',
            stage: 'redirect',
            template_id: t.id,
            template_name: t.name,
            template_icon: t.icon || '📄',
            message: '请在报告编辑页面中上传附件并生成报告',
          };
          if (onAddMessage) {
            onAddMessage({
              id: cardId,
              role: 'assistant',
              content: '',
              extra,
              created_at: new Date().toISOString(),
            });
            // 同步持久化"已选择模板"记录：该卡由前端本地生成、不经后端协调器，
            // 若不持久化则穿插恢复后切换对话框"原来提供的模板记录"会丢失
            const convId = typeof window !== 'undefined' ? localStorage.getItem('currentConversationId') || '' : '';
            if (convId) {
              persistCardMessage(convId, { id: cardId, extra }).catch(() => {});
            }
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
        disabled={disabled}
      />
    );
  }

  // stage=progress → 实时进度卡片
  if (stage === 'progress') {
    const rid = (data.report_id as string) || '';
    if (!rid) return <div className="text-sm text-gray-500 p-3">报告 ID 缺失</div>;
    return <ProgressCard reportId={rid} disabled={disabled} onAddMessage={onAddMessage} />;
  }

  return null;
};

export default ReportGenerateCard;
