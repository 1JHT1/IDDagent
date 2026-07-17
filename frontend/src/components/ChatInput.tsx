import React, { useState, useRef, useEffect, KeyboardEvent } from 'react';
import type { ChatAttachment } from '../types';
import { uploadChatAttachment } from '../api/agent';

interface ChatInputProps {
  onSend: (message: string, attachments?: ChatAttachment[]) => void;
  disabled: boolean;
}

/** 本地附件项（含上传状态） */
interface LocalAttachment {
  /** 本地唯一 key */
  localId: string;
  /** 文件名 */
  name: string;
  /** 大小（字节） */
  size: number;
  /** 上传状态 */
  status: 'uploading' | 'done' | 'error';
  /** 上传成功后的元信息 */
  meta?: ChatAttachment;
  /** 错误信息 */
  error?: string;
}

/** 格式化文件大小 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB
const MAX_ATTACHMENTS = 5;

const ChatInput: React.FC<ChatInputProps> = ({ onSend, disabled }) => {
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<LocalAttachment[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // 自动调整文本框高度
  useEffect(() => {
    const textarea = textareaRef.current;
    if (textarea) {
      textarea.style.height = 'auto';
      textarea.style.height = `${Math.min(textarea.scrollHeight, 200)}px`;
    }
  }, [input]);

  const uploading = attachments.some((a) => a.status === 'uploading');
  const doneAttachments = attachments.filter((a) => a.status === 'done' && a.meta);
  const canSend = !disabled && !uploading && (input.trim() !== '' || doneAttachments.length > 0);

  // 选择文件后立即上传
  const handleFilesSelected = async (files: FileList | null) => {
    if (!files || files.length === 0) return;

    const selected = Array.from(files);
    // 清空 input 的值，允许重复选择同一文件
    if (fileInputRef.current) fileInputRef.current.value = '';

    let currentCount = attachments.length;
    for (const file of selected) {
      if (currentCount >= MAX_ATTACHMENTS) {
        alert(`最多只能添加 ${MAX_ATTACHMENTS} 个附件`);
        break;
      }
      if (file.size > MAX_FILE_SIZE) {
        alert(`文件「${file.name}」超过 20MB 大小限制`);
        continue;
      }
      currentCount++;

      const localId = `att-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      // 先加入列表（uploading 状态）
      setAttachments((prev) => [
        ...prev,
        { localId, name: file.name, size: file.size, status: 'uploading' },
      ]);

      try {
        const meta = await uploadChatAttachment(file);
        setAttachments((prev) =>
          prev.map((a) =>
            a.localId === localId ? { ...a, status: 'done' as const, meta } : a
          )
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : '上传失败';
        setAttachments((prev) =>
          prev.map((a) =>
            a.localId === localId ? { ...a, status: 'error' as const, error: msg } : a
          )
        );
      }
    }
  };

  const removeAttachment = (localId: string) => {
    setAttachments((prev) => prev.filter((a) => a.localId !== localId));
  };

  const handleSend = () => {
    if (!canSend) return;
    const metas = doneAttachments.map((a) => a.meta!) as ChatAttachment[];
    // 无文字但有附件时给一个默认文案
    const text = input.trim() || '请查看我上传的附件';
    onSend(text, metas.length > 0 ? metas : undefined);
    setInput('');
    setAttachments([]);
    // 重置文本框高度
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="flex-shrink-0 border-t border-gray-200 bg-white p-4">
      <div className="max-w-3xl mx-auto">
        {/* 附件预览区 */}
        {attachments.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-2">
            {attachments.map((att) => (
              <div
                key={att.localId}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg border text-xs
                  ${att.status === 'error'
                    ? 'border-red-300 bg-red-50 text-red-600'
                    : 'border-gray-200 bg-gray-50 text-gray-700'}`}
              >
                {/* 状态图标 */}
                {att.status === 'uploading' ? (
                  <div className="animate-spin rounded-full h-3 w-3 border-2 border-blue-500 border-t-transparent flex-shrink-0" />
                ) : att.status === 'error' ? (
                  <svg className="w-3.5 h-3.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                      d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                ) : (
                  <svg className="w-3.5 h-3.5 text-blue-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                      d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
                  </svg>
                )}
                {/* 文件名 + 大小 */}
                <span className="max-w-[160px] truncate" title={att.name}>{att.name}</span>
                <span className="text-gray-400">{formatSize(att.size)}</span>
                {att.status === 'error' && (
                  <span className="text-red-500" title={att.error}>上传失败</span>
                )}
                {/* 删除按钮 */}
                <button
                  onClick={() => removeAttachment(att.localId)}
                  className="text-gray-400 hover:text-red-500 transition-colors flex-shrink-0"
                  title="移除附件"
                >
                  <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="flex gap-3 items-end">
          {/* 附件按钮 */}
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={disabled || uploading || attachments.length >= MAX_ATTACHMENTS}
            className="flex-shrink-0 w-10 h-10 rounded-xl border border-gray-300 bg-gray-50 text-gray-500
                       hover:text-blue-600 hover:border-blue-300 hover:bg-blue-50
                       disabled:opacity-40 disabled:cursor-not-allowed
                       transition-all duration-200 flex items-center justify-center"
            title="添加附件"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
            </svg>
          </button>
          {/* 隐藏的文件选择框 */}
          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            accept=".png,.jpg,.jpeg,.gif,.webp,.bmp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.zip"
            onChange={(e) => handleFilesSelected(e.target.files)}
          />

          <div className="flex-1 relative">
            <textarea
              ref={textareaRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="输入您的问题，如：请帮我查询某某某公司的风险报告？"
              disabled={disabled}
              rows={1}
              className="w-full resize-none rounded-xl border border-gray-300 bg-gray-50 px-4 py-3
                         text-sm text-gray-800 placeholder-gray-400
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                         disabled:opacity-50 disabled:cursor-not-allowed
                         transition-all duration-200"
            />
            <div className="absolute right-3 bottom-3 text-xs text-gray-400">
              Shift + Enter 换行
            </div>
          </div>
          <button
            onClick={handleSend}
            disabled={!canSend}
            className="flex-shrink-0 w-10 h-10 rounded-xl bg-blue-600 text-white
                       hover:bg-blue-700 disabled:opacity-40 disabled:cursor-not-allowed
                       transition-all duration-200 flex items-center justify-center
                       active:scale-95"
          >
            {disabled ? (
              <div className="animate-spin rounded-full h-4 w-4 border-2 border-white border-t-transparent" />
            ) : (
              <svg
                className="w-5 h-5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8"
                />
              </svg>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ChatInput;
