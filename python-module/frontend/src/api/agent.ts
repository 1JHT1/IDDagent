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
    const res = await fetch(`${API_BASE}/chat/stream`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        message,
        conversation_id: conversationId,
      }),
    });

    if (!res.ok) {
      throw new Error(`请求失败: ${res.status}`);
    }

    const reader = res.body?.getReader();
    if (!reader) {
      throw new Error('无法读取响应流');
    }

    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // 按行分割，处理完整的 SSE 事件
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const jsonStr = line.slice(6);
          if (jsonStr === '[DONE]') {
            safeDone();
            return;
          }
          try {
            const event: SSEEvent = JSON.parse(jsonStr);
            onEvent(event);
            if (event.type === 'done') {
              safeDone();
              return;
            }
          } catch {
            // 忽略解析错误
          }
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
