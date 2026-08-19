// ============================================================
// useChat - 聊天核心逻辑 Hook
// ============================================================

import React, { useState, useCallback, useRef } from 'react';
import type { ChatMessage, SSEEvent, ChatAttachment, PlanStatusData } from '../types';
import { isStreamingMessage } from '../types';
import { sendMessageStream, stopChatStream } from '../api/agent';

interface UseChatReturn {
  messages: ChatMessage[];
  isSending: boolean;
  sendMessage: (content: string, overrideConvId?: string, attachments?: ChatAttachment[]) => Promise<void>;
  stopStreaming: () => void;
  clearMessages: () => void;
  setMessages: React.Dispatch<React.SetStateAction<ChatMessage[]>>;
  /** 本地生成卡片消息（如模板选择后的跳转卡），插入到穿插边界之前（穿插确认卡片/步骤确认卡片之前） */
  addMessage: (msg: ChatMessage) => void;
}

/**
 * 判断 plan_step_confirm 确认卡片是否已被用户消费：
 * 其后存在确认动作（plan_continue/plan_stop/plan_resume_yes/no，即 isConfirmAction）
 * 或 resume 文本回复（isResumeReplyText）即视为已消费——卡片不再是待确认状态。
 * 已消费的确认卡仅是历史记录（用户已回应），新步骤的卡片应显示在它之后（当前步骤区域）；
 * 未消费的确认卡（穿插挂起时等待确认）才是穿插/暂停边界。
 * 同时用于卡片禁用判定：已消费的确认卡切换会话/刷新后恢复显示时保持禁用，只能点击一次。
 */
export function isStepConfirmConsumed(msgs: ChatMessage[], idx: number): boolean {
  for (let i = idx + 1; i < msgs.length; i++) {
    const m = msgs[i];
    if (m.role !== 'user') continue;
    const t = m.content.trim();
    if (isConfirmAction(t) || isResumeReplyText(t)) return true;
  }
  return false;
}

/**
 * 查找消息列表中最后一张“未消费”的确认卡片（plan_step_confirm）的索引，无则返回 -1。
 * 未消费的确认卡片是当前暂停/穿插的边界：其之前属于已完成步骤的交互记录，之后属于下一步；
 * 已消费的确认卡片（用户已点继续/结束/恢复）不再作为边界，避免后续步骤的卡片
 * （如 generate_report 的模板选择/跳转/进度卡）插入到它之前而跑到前一步骤区域。
 */
function lastConfirmCardIndex(msgs: ChatMessage[]): number {
  for (let i = msgs.length - 1; i >= 0; i--) {
    const extra = msgs[i].extra as { action?: string } | undefined;
    if (extra?.action === 'plan_step_confirm' && !isStepConfirmConsumed(msgs, i)) return i;
  }
  return -1;
}

/**
 * 移除最后一张“未消费”的确认卡片（plan_step_confirm）：
 * 用户在任务规划中穿插新意图时，后端会挂起当前规划断点（suspendPlan）并重新路由新意图，
 * 该卡片已失效——保留在界面上会误导用户以为仍可继续旧规划，应在穿插后直接消失。
 * 已消费的确认卡片（用户已点继续/结束）是历史交互记录，保留不删。
 */
function removeLastConfirmCard(msgs: ChatMessage[]): ChatMessage[] {
  const idx = lastConfirmCardIndex(msgs);
  if (idx < 0) return msgs;
  const copy = [...msgs];
  copy.splice(idx, 1);
  return copy;
}

/**
 * 将 items 插入到最后一张“未消费”确认卡片之前（保持确认卡片位于穿插/暂停边界，
 * 穿插对话保持在卡片上方）；无未消费确认卡片时追加到末尾——已消费的确认卡之后
 * 即当前步骤区域，新步骤的卡片（结果卡/跳转卡/进度卡）显示在当前位置而非前一步骤。
 */
function insertBeforeConfirmCard(prev: ChatMessage[], items: ChatMessage[]): ChatMessage[] {
  const idx = lastConfirmCardIndex(prev);
  if (idx < 0) return [...prev, ...items];
  const copy = [...prev];
  copy.splice(idx, 0, ...items);
  return copy;
}

/**
 * 判断消息是否为步骤确认动作（确认卡片的“继续/结束”按钮发送的 JSON）。
 * 确认动作属于确认卡片本身，应追加在确认卡片之后（消费该卡片）；
 * 其余消息（点击流程卡片、新请求等）应插入到确认卡片之前。
 * 兼容中英文 action 值（新协议中文发送，历史英文消息仍能正确判定消费状态）。
 */
function isConfirmAction(content: string): boolean {
  const t = content.trim();
  if (!t.startsWith('{') || !t.endsWith('}')) return false;
  try {
    const obj = JSON.parse(t);
    return obj?.action === 'plan_continue' || obj?.action === 'plan_stop'
      || obj?.action === 'plan_resume_yes' || obj?.action === 'plan_resume_no'
      || obj?.action === '继续执行下一步' || obj?.action === '结束任务'
      || obj?.action === '回到之前的任务' || obj?.action === '不需要';
  } catch {
    return false;
  }
}

/**
 * 判断文本是否为对穿插恢复确认卡片（resume_confirm）的文本回复
 * （与后端 ChatController.handleResumeConfirmReply 的关键词判定同源）。
 * 命中时该用户消息追加到卡片之后（消费卡片），而非作为穿插消息插入卡片之前。
 */
function isResumeReplyText(content: string): boolean {
  const t = content.trim();
  if (t.startsWith('{') && t.endsWith('}')) return false;
  return t.includes('不用') || t.includes('不需要') || t.includes('算了')
    || t.includes('不了') || t.includes('不必') || t.includes('不要')
    || t.includes('回到') || t.includes('回去') || t.includes('恢复')
    || t.includes('继续之前的') || t.includes('接着之前')
    || t === '要' || t === '是';
}

/**
 * 判断 resume_confirm 卡片是否已被用户消费：其后存在 plan_resume_yes/no 确认动作
 * 或文本回复（isResumeReplyText）即视为已消费。穿插进行中未消费的卡片始终位于
 * 对话最底部，是"穿插区域"与"正常对话"的分界点。
 */
export function isResumeConfirmConsumed(msgs: ChatMessage[], idx: number): boolean {
  for (let i = idx + 1; i < msgs.length; i++) {
    const m = msgs[i];
    if (m.role !== 'user') continue;
    const t = m.content.trim();
    if (isConfirmAction(t)) {
      try {
        const obj = JSON.parse(t);
        if (obj?.action === 'plan_resume_yes' || obj?.action === 'plan_resume_no'
            || obj?.action === '回到之前的任务' || obj?.action === '不需要') return true;
      } catch { /* ignore */ }
    }
    if (isResumeReplyText(t)) return true;
  }
  return false;
}

/**
 * 查找最后一张未消费 resume_confirm 的索引，无则返回 -1。
 * 穿插期间新卡片到达时会先移除旧卡（removeLastResumeConfirmCard），
 * 因此未消费的卡片一定是消息列表中最后一张 resume_confirm。
 */
function lastActiveResumeConfirmIndex(msgs: ChatMessage[]): number {
  for (let i = msgs.length - 1; i >= 0; i--) {
    const extra = msgs[i].extra as { action?: string } | undefined;
    if (extra?.action === 'resume_confirm' && !isResumeConfirmConsumed(msgs, i)) return i;
  }
  return -1;
}

/**
 * 移除最后一张未消费 resume_confirm（新卡片到达时移除旧卡，保证底部始终只有一张）。
 */
function removeLastResumeConfirmCard(msgs: ChatMessage[]): ChatMessage[] {
  const idx = lastActiveResumeConfirmIndex(msgs);
  if (idx < 0) return msgs;
  const copy = [...msgs];
  copy.splice(idx, 1);
  return copy;
}

/**
 * 插入到穿插边界之前：存在未消费 resume_confirm 时插到它之前（穿插区域的对话
 * 保持在卡片上方）；否则退化为插入到步骤确认卡片之前；都无则追加末尾。
 */
function insertBeforeBoundaryCard(prev: ChatMessage[], items: ChatMessage[]): ChatMessage[] {
  const resumeIdx = lastActiveResumeConfirmIndex(prev);
  if (resumeIdx >= 0) {
    const copy = [...prev];
    copy.splice(resumeIdx, 0, ...items);
    return copy;
  }
  return insertBeforeConfirmCard(prev, items);
}

/**
 * 用户/占位消息插入策略：穿插进行中（存在未消费 resume_confirm）插入到卡片之前
 * （穿插对话保持在卡片上方）；否则移除失效的步骤确认卡片后追加末尾。
 */
function insertInterleavingAware(prev: ChatMessage[], items: ChatMessage[]): ChatMessage[] {
  const resumeIdx = lastActiveResumeConfirmIndex(prev);
  if (resumeIdx >= 0) {
    const copy = [...prev];
    copy.splice(resumeIdx, 0, ...items);
    return copy;
  }
  return [...removeLastConfirmCard(prev), ...items];
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
      // 本次流所属会话：SSE 事件回调据此校验是否仍属于当前显示会话。
      // 切走会话后旧流在飞事件全部丢弃，避免旧会话的卡片/文本落地新会话消息流；
      // meta 事件（后端确认/分配会话 id）先于校验处理并同步流归属，
      // 防止首次发消息（无会话 id）时后端分配新 id 后后续事件被误判为跨会话丢弃
      let streamConvId = effectiveConvId;
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
        const t = content.trim();
        // 确认动作（继续/结束/恢复）追加到确认卡片之后（消费该卡片）；
        // 穿插进行中对 resume_confirm 的文本回复同样追加末尾（消费该卡片，卡片保持在最底部）；
        // 其余消息（流程卡片点击、新请求/意图穿插）：穿插中插入到 resume_confirm 之前（穿插对话保持在卡片上方），
        // 否则移除失效的“下一步”确认卡片再追加
        if (isConfirmAction(t)) return [...prev, userMsg];
        if (lastActiveResumeConfirmIndex(prev) >= 0 && isResumeReplyText(t)) return [...prev, userMsg];
        return insertInterleavingAware(prev, [userMsg]);
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
        // 占位消息跟随用户消息位置：确认动作/穿插恢复文本回复追加在确认卡片之后（消费卡片）；
        // 其余消息（含意图穿插）按穿插边界策略插入（用户消息处已处理，此处幂等）
        const t = content.trim();
        if (isConfirmAction(t)) return [...prev, assistantMsg];
        if (lastActiveResumeConfirmIndex(prev) >= 0 && isResumeReplyText(t)) return [...prev, assistantMsg];
        return insertInterleavingAware(prev, [assistantMsg]);
      });

      // 卡片类事件统一处理：
      // 1. 优先把流式占位消息转为普通消息并挂 extra（单意图/首个 result 事件）
      // 2. 多意图串行时占位消息已被首个 result 事件消费，找不到则追加独立消息，避免卡片丢失
      const upsertCardMessage = (content: string, extra: Record<string, unknown>) => {
        setMessages((prev) => {
          const placeholder = prev.find(
            (m) => isStreamingMessage(m) && m.id === assistantMsgId
          );
          if (placeholder) {
            return prev.map((msg) =>
              isStreamingMessage(msg) && msg.id === assistantMsgId
                ? {
                    id: msg.id,
                    role: 'assistant' as const,
                    content,
                    extra,
                    created_at: msg.created_at,
                  }
                : msg
            );
          }
          // 占位已被消费：结果卡片插入到穿插边界之前（穿插确认卡片/步骤确认卡片之前，若存在）
          return insertBeforeBoundaryCard(prev, [
            {
              id: `card-${Date.now()}`,
              role: 'assistant' as const,
              content,
              extra,
              created_at: new Date().toISOString(),
            },
          ]);
        });
      };

      await sendMessageStream(
        content.trim(),
        effectiveConvId,
        (event: SSEEvent) => {
          // 本流已被主动终止（点击停止/切换会话）：缓冲事件一律丢弃。
          // 会话校验依赖 conversationIdRef，而它要等 React re-render 才更新——
          // abort 后的竞态窗口内旧流事件会误判为仍属当前会话而注入消息流，
          // 故在 meta/会话校验之前先以 abort 状态硬拦截
          if (controller.signal.aborted) return;
          // meta 事件（后端确认/分配会话 id）先于会话校验处理：流归属与当前显示会话一致时
          // 同步流会话（首次发消息后端下发新 id 的场景）并更新组件会话 id；
          // 不一致（用户已切走会话，旧流 meta 晚到）→ 整段丢弃，不得把会话改回旧会话
          if (event.type === 'meta') {
            if (event.conversation_id && streamConvId === conversationIdRef.current) {
              streamConvId = event.conversation_id;
              if (onConversationIdChange) {
                onConversationIdChange(event.conversation_id);
              }
            }
            return;
          }
          // 会话校验：本次流已不属于当前显示会话（用户切换/新建会话后旧流仍在飞）
          // → 丢弃事件，旧流的卡片/文本/进度事件不得注入新会话消息流
          if (streamConvId !== conversationIdRef.current) return;
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
              upsertCardMessage('风险预查', event.data as unknown as Record<string, unknown>);
              break;

            case 'report_generate_result':
              // 报告生成结果
              upsertCardMessage('智能尽调报告生成', event.data as unknown as Record<string, unknown>);
              break;

            case 'information_check_result':
              // 信息核实结果
              upsertCardMessage('信息核实', event.data as unknown as Record<string, unknown>);
              break;

            case 'historical_dd_query_result':
              // 历史尽调查询结果
              upsertCardMessage('历史尽调报告', event.data as unknown as Record<string, unknown>);
              break;

            case 'company_query_result':
              // 企业信息查询结果（基本信息/股东/受益人/族谱/海关/冻结/授信/人行账管）
              upsertCardMessage('企业信息查询', event.data as unknown as Record<string, unknown>);
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
              // 追问建议：创建独立追问消息，插入到穿插边界之前（穿插确认卡片/步骤确认卡片之前）
              if (event.content) {
                const followUpId = `followup-${Date.now()}`;
                setMessages((prev) => insertBeforeBoundaryCard(prev, [
                  {
                    id: followUpId,
                    role: 'assistant' as const,
                    content: '',
                    extra: { action: 'follow_up', text: event.content } as unknown as Record<string, unknown>,
                    created_at: new Date().toISOString(),
                  },
                ]));
              }
              break;

            case 'clarification':
              // 意图澄清（同技能多主体冲突）：优先复用流式占位消息，找不到则追加独立消息（复用卡片挂载逻辑）
              upsertCardMessage(
                '',
                { ...(event.data as unknown as Record<string, unknown>), action: 'clarification' } as unknown as Record<string, unknown>
              );
              break;

            case 'plan_progress':
            case 'plan_preview':
            case 'plan_summary':
              // 任务规划提示（进度/预览/汇总）
              // 插入到流式占位消息之前而非追加末尾：结果卡片会复用占位消息的位置，
              // 若提示语追加到末尾，卡片将先于"第 x/N 步"提示渲染，顺序颠倒
              if (event.content) {
                const planId = `plan-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
                const planMsg: ChatMessage = {
                  id: planId,
                  role: 'assistant' as const,
                  content: '',
                  extra: { action: event.type, text: event.content } as unknown as Record<string, unknown>,
                  created_at: new Date().toISOString(),
                };
                setMessages((prev) => {
                  const placeholderIdx = prev.findIndex(
                    (m) => isStreamingMessage(m) && m.id === assistantMsgId
                  );
                  if (placeholderIdx >= 0) {
                    const copy = [...prev];
                    copy.splice(placeholderIdx, 0, planMsg);
                    return copy;
                  }
                  // 占位已被消费（如后续步骤）→ 插入到穿插边界之前（穿插中保持在 resume_confirm 上方）
                  return insertBeforeBoundaryCard(prev, [planMsg]);
                });
              }
              break;

            case 'plan_status':
              // 任务规划状态快照：按 planId upsert 规划面板消息（固定 id 原地更新，不重复插入）。
              // 面板已存在 → 覆盖快照（steps 为空表示规划已收尾清除 → 移除面板）；
              // 面板不存在 → 插到流式占位消息之前（结果卡片会复用占位位置，面板须在卡片之前渲染）；
              // 占位已消费 → 插入到穿插边界之前（穿插中保持在 resume_confirm 上方）。
              {
                const planData = (event.data as unknown as PlanStatusData) || {};
                const planId = planData.planId || `local-${Date.now()}`;
                const panelId = `plan-status-${planId}`;
                const planMsg: ChatMessage = {
                  id: panelId,
                  role: 'assistant' as const,
                  content: '',
                  extra: { action: 'plan_status', ...planData } as unknown as Record<string, unknown>,
                  created_at: new Date().toISOString(),
                };
                // 全部步骤已完成且最后一步非报告生成 → 收尾"任务完成"卡片：
                // 移除旧规划面板（执行中状态）并把完成卡片插入当前消息区域（占位/穿插边界之前），
                // 对齐报告生成完成卡片（ProgressCard）在结果卡片之后的展示体验；
                // 报告生成收尾由 ProgressCard 完成态 + 注入终态面板承担，不走此分支。
                const steps = planData.steps || [];
                const allDone = steps.length > 0 && steps.every((s) => s.status === 'DONE');
                const lastStepIsReport = steps.length > 0 && steps[steps.length - 1]?.skill === 'generate_report';
                setMessages((prev) => {
                  if (allDone && !lastStepIsReport) {
                    const withoutPanel = prev.filter((m) => m.id !== panelId);
                    const placeholderIdx = withoutPanel.findIndex(
                      (m) => isStreamingMessage(m) && m.id === assistantMsgId
                    );
                    if (placeholderIdx >= 0) {
                      const copy = [...withoutPanel];
                      copy.splice(placeholderIdx, 0, planMsg);
                      return copy;
                    }
                    return insertBeforeBoundaryCard(withoutPanel, [planMsg]);
                  }
                  const existingIdx = prev.findIndex((m) => m.id === panelId);
                  if (existingIdx >= 0) {
                    // 面板已存在且快照无步骤（如 rejectResume 丢弃挂起规划）→ 移除面板
                    if (!planData.steps || planData.steps.length === 0) {
                      const copy = [...prev];
                      copy.splice(existingIdx, 1);
                      return copy;
                    }
                    const copy = [...prev];
                    copy[existingIdx] = { ...copy[existingIdx], extra: planMsg.extra };
                    return copy;
                  }
                  // 面板不存在且快照无步骤（防御）→ 无事可做
                  if (!planData.steps || planData.steps.length === 0) return prev;
                  const placeholderIdx = prev.findIndex(
                    (m) => isStreamingMessage(m) && m.id === assistantMsgId
                  );
                  if (placeholderIdx >= 0) {
                    const copy = [...prev];
                    copy.splice(placeholderIdx, 0, planMsg);
                    return copy;
                  }
                  // 占位已被消费（如后续步骤）→ 插入到穿插边界之前（穿插中保持在 resume_confirm 上方）
                  return insertBeforeBoundaryCard(prev, [planMsg]);
                });
              }
              break;

            case 'plan_step_confirm':
              // 步骤间确认卡片（步骤完成后询问是否继续下一步）
              // 此时占位消息已被结果卡片消费，追加独立确认卡片消息；
              // 若占位仍存在（如最后一步收尾前），则插入到占位之前保持顺序
              {
                const confirmData = event.data as Record<string, unknown> | undefined;
                const confirmMsg: ChatMessage = {
                  id: `plan-confirm-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
                  role: 'assistant' as const,
                  content: '',
                  extra: {
                    action: 'plan_step_confirm',
                    text: event.content || '是否继续下一步？',
                    current_step: confirmData?.current_step,
                    total_steps: confirmData?.total_steps,
                    next_step: confirmData?.next_step,
                  } as unknown as Record<string, unknown>,
                  created_at: new Date().toISOString(),
                };
                setMessages((prev) => {
                  const placeholderIdx = prev.findIndex(
                    (m) => isStreamingMessage(m) && m.id === assistantMsgId
                  );
                  if (placeholderIdx >= 0) {
                    const copy = [...prev];
                    copy.splice(placeholderIdx, 0, confirmMsg);
                    return copy;
                  }
                  // 占位已被消费 → 插入到穿插边界之前（穿插中保持在 resume_confirm 上方）
                  return insertBeforeBoundaryCard(prev, [confirmMsg]);
                });
              }
              break;

            case 'resume_confirm':
              // 穿插恢复确认卡片（穿插的新意图完成后，询问是否回到穿插前那一步）
              // 始终保持在对话最底部：先移除旧未消费卡片（连续穿插时每张新卡替换旧卡），
              // 占位仍存在时插入到占位之前（穿插任务的结果卡片复用占位位置），否则追加末尾
              {
                const resumeData = event.data as Record<string, unknown> | undefined;
                const resumeMsg: ChatMessage = {
                  id: `resume-confirm-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
                  role: 'assistant' as const,
                  content: '',
                  extra: {
                    action: 'resume_confirm',
                    text: event.content || '是否需要回到穿插进来前的那一步？',
                    step_index: resumeData?.step_index,
                    total_steps: resumeData?.total_steps,
                    step_desc: resumeData?.step_desc,
                  } as unknown as Record<string, unknown>,
                  created_at: new Date().toISOString(),
                };
                setMessages((prev) => {
                  const placeholderIdx = prev.findIndex(
                    (m) => isStreamingMessage(m) && m.id === assistantMsgId
                  );
                  if (placeholderIdx >= 0) {
                    // 占位仍存在：移除旧未消费卡片后插入到占位之前
                    const copy = removeLastResumeConfirmCard(prev);
                    const idx = copy.findIndex(
                      (m) => isStreamingMessage(m) && m.id === assistantMsgId
                    );
                    copy.splice(idx < 0 ? copy.length : idx, 0, resumeMsg);
                    return copy;
                  }
                  // 占位已消费：移除旧未消费卡片后追加末尾（穿插期间卡片始终在对话最底部）
                  return [...removeLastResumeConfirmCard(prev), resumeMsg];
                });
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
              // 清理未被消费的流式占位消息：正常文本/卡片事件会复用占位（text_done/upsertCardMessage），
              // 残留的占位（如穿插恢复 confirmResume 只发 plan_status/plan_progress/plan_step_confirm
              // 事件、无 text/结果卡片）会以 "🤔 正在思考..." 幽灵消息留在列表底部 → 流结束统一移除
              setMessages((prev) =>
                prev.filter((m) => !(isStreamingMessage(m) && m.id === assistantMsgId))
              );
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

  /** 本地生成卡片消息（不走后端）：按固定 id 原地 upsert（如 plan-status-{planId} 面板），
   * 避免同一面板重复插入（SSE 事件与收尾轮询注入同 id 消息时出现双面板/渲染错位）；
   * 新 id 消息插入到穿插边界之前（穿插确认卡片/步骤确认卡片之前），保持"确认卡片为当前步骤分界点" */
  const addMessage = useCallback((msg: ChatMessage) => {
    // 诊断日志（临时）：追踪跨会话卡片注入来源
    console.log('🎯 [addMessage] 注入:', msg.id, msg.extra?.action, '| 当前会话:', conversationIdRef.current, '|', new Error().stack?.split('\n').slice(1, 4).join(' <- '));
    setMessages((prev) => {
      const existingIdx = prev.findIndex((m) => m.id === msg.id);
      if (existingIdx >= 0) {
        // 已存在同 id 消息 → 原地替换（保留原位置，避免面板漂移/重复）
        return prev.map((m) => (m.id === msg.id ? { ...m, ...msg } : m));
      }
      return insertBeforeBoundaryCard(prev, [msg]);
    });
  }, [setMessages]);

  return {
    messages,
    isSending,
    sendMessage,
    stopStreaming,
    clearMessages,
    setMessages,
    addMessage,
  };
}
