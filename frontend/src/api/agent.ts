// ============================================================
// 后端 API 封装层
// ============================================================

import type { ConversationListItem, Conversation, SSEEvent } from '../types';

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
 * 流式发送消息
 * 返回一个 ReadableStream，通过回调处理 SSE 事件
 */
export async function sendMessageStream(
  message: string,
  conversationId: string | null,
  onEvent: (event: SSEEvent) => void,
  onError: (error: Error) => void,
  onDone: () => void
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
      }),
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
    onError(err instanceof Error ? err : new Error(String(err)));
  } finally {
    safeDone();
  }
}

// ============================================================
// 潜客清单 - 用户自定义上传 API
// ============================================================

/**
 * 下载客户清单上传模板（Excel .xlsx 文件）
 * 返回 Blob 供前端触发下载
 */
export async function downloadCustomerTemplate(): Promise<Blob> {
  const res = await fetch(`${API_BASE}/customer-template`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('获取上传模板失败');
  return res.blob();
}

/**
 * 上传客户清单（Excel .xlsx 文件）
 * 通过 multipart/form-data 发送文件 + mode 参数
 */
export async function uploadCustomerList(
  file: File,
  mode: 'overwrite' | 'append'
): Promise<{ status: string; total_count: number; message: string }> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('mode', mode);

  const headers: Record<string, string> = {};
  const token = getAuthToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}/customer-upload`, {
    method: 'POST',
    headers,
    body: formData,
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ detail: '上传失败' }));
    throw new Error(err.detail || '上传客户清单失败');
  }
  return res.json();
}

/**
 * 查询开户提交通知（轮询调用，返回后自动清除队列）
 */
export async function checkAccountNotifications(
  conversationId: string
): Promise<Array<{
  type: string;
  app_id: string;
  company_name: string;
  credit_code: string;
  submitted_url: string;
  submitted_at: string;
}>> {
  try {
    const res = await fetch(`${API_BASE}/account-opening/notifications/${conversationId}`);
    if (!res.ok) return [];
    const data = await res.json();
    return data.notifications || [];
  } catch {
    return [];
  }
}
