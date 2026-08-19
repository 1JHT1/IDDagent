// ============================================================
// 后端 API 封装层
// ============================================================

import type { ConversationListItem, Conversation, SSEEvent, ChatAttachment } from '../types';

const API_BASE = '/api';

/**
 * 获取存储的认证 Token
 */
function getAuthToken(): string | null {
  return localStorage.getItem('auth_token');
}

/**
 * 创建带认证头的请求配置
 */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  const token = getAuthToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}

/**
 * 健康检查
 */
export async function checkHealth(): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE}/health`);
    const data = await res.json();
    return data.status === 'ok';
  } catch {
    return false;
  }
}

/**
 * 获取会话列表
 */
export async function getConversations(): Promise<ConversationListItem[]> {
  const res = await fetch(`${API_BASE}/conversations`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('获取会话列表失败');
  const data = await res.json();
  return data.conversations;
}

/**
 * 创建新会话
 */
export async function createConversation(
  title?: string
): Promise<{ id: string; title: string; created_at: string }> {
  const res = await fetch(`${API_BASE}/conversations`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ title: title || '新对话' }),
  });
  if (!res.ok) throw new Error('创建会话失败');
  return res.json();
}

/**
 * 获取会话详情
 */
export async function getConversation(
  conversationId: string
): Promise<Conversation> {
  const res = await fetch(`${API_BASE}/conversations/${conversationId}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('获取会话详情失败');
  return res.json();
}

/**
 * 删除会话
 */
export async function deleteConversation(conversationId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/conversations/${conversationId}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('删除会话失败');
}

/**
 * 上传聊天附件
 * 返回附件元信息（name/url/size/type/file_id）
 */
export async function uploadChatAttachment(file: File): Promise<ChatAttachment> {
  const formData = new FormData();
  formData.append('file', file);

  const headers: Record<string, string> = {};
  const token = getAuthToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}/chat/attachments`, {
    method: 'POST',
    headers,
    body: formData,
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: '上传附件失败' }));
    throw new Error(err.message || `上传附件失败 (${res.status})`);
  }
  return res.json();
}

/**
 * 流式发送消息
 * 返回一个 ReadableStream，通过回调处理 SSE 事件
 * @param signal 可选 AbortSignal，用于中途强制终止（配合后端 /api/chat/stop 双保险）
 */
export async function sendMessageStream(
  message: string,
  conversationId: string | null,
  onEvent: (event: SSEEvent) => void,
  onError: (error: Error) => void,
  onDone: () => void,
  attachments?: ChatAttachment[],
  signal?: AbortSignal
): Promise<void> {
  let doneCalled = false;
  const safeDone = () => { if (!doneCalled) { doneCalled = true; onDone(); } };
  try {
    console.log('🌐 发起 SSE 请求, conversationId:', conversationId);
    const res = await fetch(`${API_BASE}/chat/stream`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        message,
        conversationId: conversationId,
        attachments: attachments && attachments.length > 0 ? attachments : undefined,
      }),
      signal,
    });

    console.log('📡 SSE 响应状态:', res.status, 'Content-Type:', res.headers.get('content-type'));

    if (!res.ok) {
      throw new Error(`请求失败: ${res.status}`);
    }

    const reader = res.body?.getReader();
    if (!reader) {
      throw new Error('无法读取响应流');
    }

    console.log('📖 开始读取 SSE 流...');

    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        // 已主动终止（停止生成/切换会话）：立即丢弃缓冲事件，不再分发。
        // abort 后 fetch 本身会抛错走 catch，但已 resolve 的 chunk 内的事件若继续
        // 处理，可能在会话切换的 re-render 窗口内把旧会话的任务规划卡/进度卡
        // 注入新会话消息流（表现为"每个对话都有任务卡，刷新才消失"）
        if (signal?.aborted) return;
        // 兼容 "data:" 和 "data: " 两种前缀
        let jsonStr = '';
        if (line.startsWith('data: ')) {
          jsonStr = line.slice(6);
        } else if (line.startsWith('data:')) {
          jsonStr = line.slice(5).trim(); // 去掉可能的空格
        } else {
          continue; // 不是 data 行，跳过
        }

        if (jsonStr === '[DONE]') {
          safeDone();
          return;
        }
        try {
          const raw = JSON.parse(jsonStr);

          // 后端自定义 SSE 格式：{type, content, message_id, conversation_id, ...}
          const eventType = raw.type as string;
          if (!eventType) {
            console.warn('SSE 事件缺少 type 字段:', raw);
            continue;
          }

          // 提取 data：移除 type/message_id/conversation_id 后的剩余字段
          const { type: _t, message_id: _mid, conversation_id: _cid, ...restData } = raw;

          const event: SSEEvent = {
            type: eventType as SSEEvent['type'],
            content: raw.content as string | undefined,
            message_id: raw.message_id as string | undefined,
            conversation_id: raw.conversation_id as string | undefined,
            data: Object.keys(restData).length > 0 ? restData as SSEEvent['data'] : undefined,
          };

          console.log('🔍 收到 SSE 事件:', event.type, event.content?.substring(0, 30) || '');
          onEvent(event);

          // done 事件表示流结束
          if (eventType === 'done') {
            safeDone();
            return;
          }
        } catch (parseError) {
          console.warn('解析 SSE 数据失败:', jsonStr, parseError);
        }
      }
    }

    safeDone();
  } catch (err) {
    // 主动终止（AbortController.abort）不算错误，直接按完成处理
    if (signal?.aborted) {
      console.log('⏹️ SSE 请求已被主动终止');
      safeDone();
      return;
    }
    onError(err instanceof Error ? err : new Error(String(err)));
  } finally {
    safeDone();
  }
}

/**
 * 强制终止当前对话的流式生成（通知后端截断事件流）
 * 与前端 AbortController 双保险：前端断开连接 + 后端取消标记
 */
export async function stopChatStream(conversationId: string): Promise<void> {
  try {
    await fetch(`${API_BASE}/chat/stop`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ conversationId }),
    });
  } catch (err) {
    console.warn('通知后端终止对话失败（前端连接已断开，不影响）：', err);
  }
}

/**
 * 持久化一条前端本地生成的卡片消息（如模板选择后的"已选择模板"跳转卡）：
 * 该卡由 ReportGenerateCard 点击模板时直接生成、不经后端协调器，若不持久化则
 * 穿插恢复后切换对话框"原来提供的模板记录"会丢失。后端按穿插边界规则插入消息流。
 */
export async function persistCardMessage(
  conversationId: string,
  message: { id: string; content?: string; extra?: Record<string, unknown> }
): Promise<void> {
  try {
    await fetch(`${API_BASE}/chat/card`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        conversationId,
        message: {
          id: message.id,
          content: message.content || '',
          extra: message.extra || {},
        },
      }),
    });
  } catch (err) {
    // 持久化失败不影响本地即时展示（当前会话内仍可见），仅记录告警
    console.warn('持久化卡片消息失败（不影响本地展示）：', err);
  }
}

