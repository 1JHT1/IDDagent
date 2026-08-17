// ============================================================
// useChat - 聊天核心逻辑 Hook
// ============================================================

import React, { useState, useCallback, useRef } from 'react';
import type { ChatMessage, SSEEvent, ChatAttachment, PipelineExtra, PlanningData, TaskStartData, TaskDoneData, PipelineTask } from '../types';
import { isStreamingMessage } from '../types';
import { sendMessageStream, stopChatStream } from '../api/agent';

interface UseChatReturn {
  messages: ChatMessage[];
  isSending: boolean;
  sendMessage: (content: string, overrideConvId?: string, attachments?: ChatAttachment[]) => Promise<void>;
  stopStreaming: () => void;
  clearMessages: () => void;
  setMessages: React.Dispatch<React.SetStateAction<ChatMessage[]>>;
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
        return [...prev, userMsg];
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
        return [...prev, assistantMsg];
      });

      /**
       * 从消息列表最后一张任务清单卡片推断当前正在执行的任务 label。
       * 供候选选择/时间区间输入等交互类卡片关联所属任务（如"历史尽调报告查询 · 请选择企业"），
       * 让用户在多意图管道中清楚"是哪个任务在询问"；无清单卡片（单技能场景）返回 undefined 优雅降级。
       */
      const resolveCurrentTaskLabel = (prev: ChatMessage[]): string | undefined => {
        for (let i = prev.length - 1; i >= 0; i--) {
          const ex = (prev[i] as { extra?: Record<string, unknown> }).extra;
          if (ex && ex.action === 'pipeline') {
            const plan = (ex.plan as PipelineTask[] | undefined) ?? [];
            const order = (ex.currentOrder as number) ?? 0;
            return plan.find((t) => t.order === order)?.label;
          }
        }
        return undefined;
      };

      /**
       * 将结果卡片写入消息列表：
       * 1. 单技能/管道首任务：复用流式占位消息（原逻辑）
       * 2. 多意图管道后续任务：planText 已定稿、无 streaming 占位可匹配，创建唯一 id 的新消息，
       *    避免结果卡片/选择器被静默丢弃（此前用户只能看到规划文本、看不到后续任务结果）
       */
      const upsertCardMessage = (content: string, extra: Record<string, unknown>) => {
        setMessages((prev) => {
          // 交互类卡片（企业/意图候选选择、时间区间输入）自动关联当前任务标识：
          // 函数式更新读取的是最新排队状态（同一批 SSE 事件中 task_start 已先推进进度），
          // task_label 随消息 extra 持久化，切换会话重载后仍可恢复展示
          const interactive =
            extra.action === 'company_name_candidates' ||
            extra.action === 'intent_candidates' ||
            extra.action === 'need_date_range';
          const mergedExtra = interactive
            ? { ...extra, task_label: extra.task_label ?? resolveCurrentTaskLabel(prev) }
            : extra;
          const next = [...prev];
          const idx = next.findIndex((m) => isStreamingMessage(m) && m.id === assistantMsgId);
          if (idx !== -1) {
            next[idx] = {
              id: next[idx].id,
              role: 'assistant' as const,
              content,
              extra: mergedExtra,
              created_at: next[idx].created_at,
            };
            return next;
          }
          return [
            ...next,
            {
              id: `result-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
              role: 'assistant' as const,
              content,
              extra: mergedExtra,
              created_at: new Date().toISOString(),
            },
          ];
        });
      };

      await sendMessageStream(
        content.trim(),
        effectiveConvId,
        (event: SSEEvent) => {
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

            case 'meta':
              // 如果后端返回了新的 conversation_id，更新它
              if (event.conversation_id && onConversationIdChange) {
                onConversationIdChange(event.conversation_id);
              }
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
                  const hasStreaming = prev.some(m => isStreamingMessage(m) && m.id === assistantMsgId);
                  if (hasStreaming) {
                    // 正常情况：追加到流式消息
                    return prev.map((msg) =>
                      isStreamingMessage(msg) && msg.id === assistantMsgId
                        ? { ...msg, content: msg.content + event.content }
                        : msg
                    );
                  }
                  // 多意图管道场景：上一段 text_done 已定稿，新的 text_delta 需要创建新消息
                  // 注意：id 必须唯一（不能复用 assistantMsgId，否则与已定稿消息产生 React key 冲突）
                  if (isSendingRef.current) {
                    const newContent = event.content || '';
                    return [...prev, {
                      id: `text-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                      role: 'assistant' as const,
                      content: newContent,
                      isStreaming: true,
                      created_at: new Date().toISOString(),
                    }];
                  }
                  return prev;
                });
              }
              break;

            case 'text_done':
              // 文本完成 - 将流式消息转为普通消息
              setMessages((prev) => {
                const doneContent = event.content || '';
                const next = [...prev];
                // 优先定稿当前请求的流式占位消息
                const idx = next.findIndex((msg) => isStreamingMessage(msg) && msg.id === assistantMsgId);
                if (idx !== -1) {
                  next[idx] = {
                    id: next[idx].id,
                    role: 'assistant' as const,
                    content: doneContent || next[idx].content,
                    created_at: next[idx].created_at,
                  };
                  return next;
                }
                // 多意图管道：后续任务的文本消息 id 唯一、与 assistantMsgId 不匹配，
                // 定稿最近一条 streaming 消息
                for (let i = next.length - 1; i >= 0; i--) {
                  if (isStreamingMessage(next[i])) {
                    next[i] = {
                      id: next[i].id,
                      role: 'assistant' as const,
                      content: doneContent || next[i].content,
                      created_at: next[i].created_at,
                    };
                    break;
                  }
                }
                return next;
              });
              break;

            case 'planning':
              // 多意图任务清单：作为一条可见的 assistant 卡片消息插入对话流
              // （resume=true 表示暂停恢复，更新已有卡片；否则新建卡片）
              {
                const data = event.data as unknown as PlanningData | undefined;
                if (data && Array.isArray(data.plan)) {
                  const newExtra: PipelineExtra = {
                    action: 'pipeline',
                    // 初始规划卡：完整任务列表，仅在首次规划时出现（后续进度由切换卡/完成卡承载）
                    kind: 'plan',
                    plan: data.plan,
                    total: data.plan.length,
                    currentOrder: 0,
                    paused: false,
                    text: data.text,
                  };
                  setMessages((prev) => {
                    const next = [...prev];
                    if (data.resume) {
                      // 恢复路径：更新最后一张任务清单卡片，保留其形态（plan/switch）
                      // 与进度序号，随后 task_start 会刷新当前任务进度
                      // plan 取较长者：防御后端恢复路径 plan 缩水（如内存快照丢失后
                      // 从剩余任务重建），避免任务总数变小、已完成任务行丢失
                      for (let i = next.length - 1; i >= 0; i--) {
                        const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                        if (ex && ex.action === 'pipeline') {
                          const prevPlan = (ex as { plan?: PipelineTask[] }).plan ?? [];
                          next[i] = {
                            ...next[i],
                            extra: {
                              ...newExtra,
                              plan: prevPlan.length >= data.plan.length ? prevPlan : data.plan,
                              total: Math.max(data.plan.length, prevPlan.length),
                              kind: (ex as { kind?: PipelineExtra['kind'] }).kind ?? 'plan',
                              currentOrder: (ex.currentOrder as number) ?? 0,
                            },
                          };
                          return next;
                        }
                      }
                    }
                    // 首次规划：新建卡片消息
                    return [
                      ...next,
                      {
                        id: `pipeline-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: '',
                        extra: newExtra,
                        created_at: new Date().toISOString(),
                      },
                    ];
                  });
                }
                // 清空占位消息的"🤔 正在思考..."文案，避免与规划文本（text_delta）拼接
                setMessages((prev) =>
                  prev.map((msg) =>
                    isStreamingMessage(msg) && msg.id === assistantMsgId && msg.content === '🤔 正在思考...'
                      ? { ...msg, content: '' }
                      : msg
                  )
                );
              }
              break;

            case 'task_start':
              // 某任务开始执行：按对话流时间顺序推进任务卡片形态
              // - order === 1（首个任务）：更新初始规划卡（plan）的进度
              // - order > 1（进入新的一级任务）：新建轻量任务切换卡（switch），
              //   展示"已完成 x/total → 正在执行 order/total"，用户无需上翻看旧卡
              // - 与最后一张卡进度相同（暂停恢复/重试）：原地更新，不重复建卡
              {
                const data = event.data as unknown as TaskStartData | undefined;
                if (data) {
                  setMessages((prev) => {
                    const next = [...prev];
                    let lastIdx = -1;
                    for (let i = next.length - 1; i >= 0; i--) {
                      const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                      if (ex && ex.action === 'pipeline') {
                        lastIdx = i;
                        break;
                      }
                    }
                    const lastEx = lastIdx !== -1 ? (next[lastIdx].extra as unknown as PipelineExtra) : undefined;
                    if (lastEx) {
                      const order = data.order ?? data.index;
                      // total 不缩小：后端 resume 时 task_start.total 可能只含剩余任务数，
                      // 以完整清单长度（plan）与已有 total 兜底取最大值，避免任务切换卡/
                      // 完成卡按错误总数渲染（正在执行的任务被隐藏、"N 项任务已完成"数量错误）
                      const safeTotal = Math.max(data.total ?? 0, lastEx.total ?? 0, lastEx.plan.length);
                      // 暂停恢复/重试：进度与最后一张卡一致，原地刷新（不产生重复卡）
                      if (lastEx.currentOrder === order) {
                        next[lastIdx] = {
                          ...next[lastIdx],
                          extra: {
                            ...lastEx,
                            total: safeTotal,
                            currentOrder: order,
                            paused: false,
                            completed: false,
                          },
                        };
                        return next;
                      }
                      // 进入新的一级任务：新建轻量任务切换卡（复制完整清单用于渲染，展示时隐藏待办）
                      if (order > 1) {
                        // 同步更新初始执行计划卡（第一张 plan 卡）的 currentOrder，
                        // 让首卡始终反映最新执行位置（与后端 updatePipelinePlanCardOrder 一致），
                        // 新任务进度详情由下方新建的切换卡承载
                        const firstPlanIdx = next.findIndex((m) => {
                          const ex = (m as { extra?: Record<string, unknown> }).extra;
                          return ex && ex.action === 'pipeline' && ex.kind !== 'switch' && ex.kind !== 'complete';
                        });
                        if (firstPlanIdx !== -1) {
                          const firstPlanEx = next[firstPlanIdx].extra as unknown as PipelineExtra;
                          next[firstPlanIdx] = {
                            ...next[firstPlanIdx],
                            extra: { ...firstPlanEx, currentOrder: order },
                          };
                        }
                        return [
                          ...next,
                          {
                            id: `pipeline-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                            role: 'assistant' as const,
                            content: '',
                            extra: {
                              action: 'pipeline',
                              kind: 'switch',
                              plan: lastEx.plan,
                              total: safeTotal,
                              currentOrder: order,
                              paused: false,
                            } as PipelineExtra,
                            created_at: new Date().toISOString(),
                          },
                        ];
                      }
                      // 首个任务：更新初始规划卡
                      next[lastIdx] = {
                        ...next[lastIdx],
                        extra: {
                          ...lastEx,
                          // 兜底：若未收到 planning（异常路径），用当前任务构造最小清单
                          plan: lastEx.plan.length > 0
                            ? lastEx.plan
                            : [{ skill: data.skill, label: data.label, order: data.order }],
                          total: data.total,
                          currentOrder: order,
                          paused: false,
                          completed: false,
                        },
                      };
                      return next;
                    }
                    // 异常路径：无清单卡片但收到 task_start（如历史会话恢复），新建最小卡片
                    return [
                      ...next,
                      {
                        id: `pipeline-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: '',
                        extra: {
                          action: 'pipeline',
                          kind: 'plan',
                          plan: [{ skill: data.skill, label: data.label, order: data.order }],
                          total: data.total,
                          currentOrder: data.order ?? data.index,
                          paused: false,
                        } as PipelineExtra,
                        created_at: new Date().toISOString(),
                      },
                    ];
                  });
                }
              }
              break;

            case 'pipeline_paused':
              // 管道暂停（当前任务等待用户补充信息）：仅将卡片标记为暂停状态，
              // 具体补充提示（如"请上传营业执照图片"）由后端以文本气泡返回，卡片内不重复展示
              setMessages((prev) => {
                const next = [...prev];
                for (let i = next.length - 1; i >= 0; i--) {
                  const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                  if (ex && ex.action === 'pipeline') {
                    next[i] = {
                      ...next[i],
                      extra: { ...ex, paused: true },
                    };
                    break;
                  }
                }
                return next;
              });
              break;

            case 'task_done': {
              // 管道中某个任务执行完成：
              // - 最后一个任务完成（order >= total）→ 仅将最后一张任务清单卡标记为
              //   完成态（completed=true，保留原 kind=plan/switch），不再原地转为
              //   kind='complete'，避免绿色完成卡顶到该任务结果卡之前（"中间"）且
              //   任务进度卡（switch）被转绿而"消失"；最终绿色完成卡由随后的 done
              //   事件在流末尾追加，保证位置正确
              // - 中间任务完成 → 仅解除暂停（该任务可能因等待补充信息/企业选择而暂停），
              //   剩余任务由随后的 task_start 创建 switch 卡继续推进、done 事件收尾。
              // 注意：不得推进 currentOrder——否则后续 task_start 的
              // "currentOrder === order 原地更新"判定失效，switch 卡不再创建
              const taskData = event.data as unknown as TaskDoneData | undefined;
              const doneOrder = taskData?.order ?? 0;
              setMessages((prev) => {
                const next = [...prev];
                for (let i = next.length - 1; i >= 0; i--) {
                  const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                  if (ex && ex.action === 'pipeline') {
                    const pipelineEx = ex as unknown as PipelineExtra;
                    const safeTotal = Math.max(pipelineEx.total ?? 0, pipelineEx.plan.length);
                    if (doneOrder > 0 && doneOrder >= safeTotal) {
                      // 最后任务完成：最后一张卡标记完成态（completed=true，保留原 kind），
                      // 其余管道卡（执行计划卡等）同步标记 completed=true 完成态，
                      // 与 report-completed 路径（advancePipelineAfterReport）展示一致；
                      // 绿色完成卡由 done 事件在流末尾追加（与轮询路径一致）
                      next[i] = {
                        ...next[i],
                        extra: {
                          ...pipelineEx,
                          paused: false,
                          completed: true,
                          currentOrder: safeTotal,
                        } as PipelineExtra,
                      };
                      for (let j = 0; j < next.length; j++) {
                        if (j === i) continue;
                        const ex2 = (next[j] as { extra?: Record<string, unknown> }).extra;
                        if (ex2 && ex2.action === 'pipeline' && (ex2 as unknown as PipelineExtra).kind !== 'complete') {
                          const p2 = ex2 as unknown as PipelineExtra;
                          next[j] = {
                            ...next[j],
                            extra: {
                              ...p2,
                              currentOrder: safeTotal,
                              paused: false,
                              completed: true,
                            } as PipelineExtra,
                          };
                        }
                      }
                    } else {
                      next[i] = { ...next[i], extra: { ...pipelineEx, paused: false } };
                    }
                    break;
                  }
                }
                return next;
              });
              break;
            }

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
              upsertCardMessage('', {
                ...(event.data as unknown as Record<string, unknown>),
                action: 'company_name_candidates',
              });
              break;
            
            case 'intent_candidates':
              // 意图澄清选择器：复用流式消息
              upsertCardMessage('', {
                ...(event.data as unknown as Record<string, unknown>),
                action: 'intent_candidates',
              });
              break;
            
            case 'need_date_range':
              // 时间区间输入提示：更新流式消息，展示提示文本
              upsertCardMessage('', {
                action: 'need_date_range',
                text: (event.data as unknown as Record<string, unknown>).message || event.content || '',
              });
              break;

            case 'follow_up_suggestion':
              // 追问建议：创建独立追问消息，追加在消息列表末尾
              if (event.content) {
                const followUpId = `followup-${Date.now()}`;
                setMessages((prev) => [
                  ...prev,
                  {
                    id: followUpId,
                    role: 'assistant' as const,
                    content: '',
                    extra: { action: 'follow_up', text: event.content } as unknown as Record<string, unknown>,
                    created_at: new Date().toISOString(),
                  },
                ]);
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
              // 任务清单卡片收尾（卡片本身保留在对话流中，不消失）：
              // - 所有任务执行完（currentOrder >= total）→ 新建最终完成卡（complete），
              //   在当前对话位置汇总"N 项任务已完成"，与初始规划卡形成闭环
              // - 中途暂停（等待用户补充信息）→ 标记最后一张卡 paused（保留清单让用户看到剩余任务）
              setMessages((prev) => {
                const next = [...prev];
                let lastIdx = -1;
                for (let i = next.length - 1; i >= 0; i--) {
                  const ex = (next[i] as { extra?: Record<string, unknown> }).extra;
                  if (ex && ex.action === 'pipeline') {
                    lastIdx = i;
                    break;
                  }
                }
                if (lastIdx !== -1) {
                  const lastEx = next[lastIdx].extra as unknown as PipelineExtra;
                  // task_done 已将最后一张卡转为完成卡（kind='complete'）时，done 事件
                  // 不得再新建完成卡或标记 paused（否则出现重复完成卡、完成卡被标回暂停），
                  // 直接跳过收尾
                  if (lastEx.kind === 'complete') return next;
                  // 暂停中（等待补充信息/企业选择）不得判为全部完成：currentOrder 已推进
                  //（如 switch 卡 2>=2）但任务并未结束，此时只标记 paused，不做完成收尾
                  if (!lastEx.paused && lastEx.currentOrder >= lastEx.total) {
                    // 全部完成：新建最终完成卡，汇总全部任务
                    // total 以完整清单长度兜底（防御后端 resume 场景 total 缩水），
                    // 确保"N 项任务已完成"的数量与完整计划一致
                    const completeTotal = Math.max(lastEx.total ?? 0, lastEx.plan.length);
                    return [
                      ...next,
                      {
                        id: `pipeline-complete-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        role: 'assistant' as const,
                        content: '',
                        extra: {
                          action: 'pipeline',
                          kind: 'complete',
                          plan: lastEx.plan,
                          total: completeTotal,
                          currentOrder: completeTotal,
                          completed: true,
                        } as PipelineExtra,
                        created_at: new Date().toISOString(),
                      },
                    ];
                  }
                  // 中途暂停：标记最后一张卡 paused
                  next[lastIdx] = {
                    ...next[lastIdx],
                    extra: { ...lastEx, paused: true },
                  };
                }
                return next;
              });
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

  return {
    messages,
    isSending,
    sendMessage,
    stopStreaming,
    clearMessages,
    setMessages,
  };
}
