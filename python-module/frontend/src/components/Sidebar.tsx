import React from 'react';
import type { ConversationListItem } from '../types';

interface SidebarProps {
  conversations: ConversationListItem[];
  activeId: string | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => void;
  loading: boolean;
}

const Sidebar: React.FC<SidebarProps> = ({
  conversations,
  activeId,
  onSelect,
  onNew,
  onDelete,
  loading,
}) => {
  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    if (isNaN(date.getTime())) return '';

    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const targetDay = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    const diffDays = Math.floor((today.getTime() - targetDay.getTime()) / (1000 * 60 * 60 * 24));

    const time = date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });

    if (diffDays === 0) return `今天 ${time}`;
    if (diffDays === 1) return `昨天 ${time}`;
    if (diffDays < 7) {
      const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
      return `${weekdays[date.getDay()]} ${time}`;
    }
    if (date.getFullYear() === now.getFullYear()) {
      return `${date.getMonth() + 1}月${date.getDate()}日`;
    }
    return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;
  };

  return (
    <div className="w-64 bg-gray-900 text-gray-100 flex flex-col h-full">
      {/* 头部 */}
      <div className="p-4 border-b border-gray-700">
        <h1 className="text-lg font-semibold text-white mb-3">
          对公账户开户助手
        </h1>
        <button
          onClick={onNew}
          className="w-full py-2 px-4 bg-blue-600 hover:bg-blue-700 text-white rounded-lg
                     transition-colors duration-200 flex items-center justify-center gap-2 text-sm"
        >
          <svg
            className="w-4 h-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 4v16m8-8H4"
            />
          </svg>
          新建对话
        </button>
      </div>

      {/* 会话列表 */}
      <div className="flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex justify-center py-8">
            <div className="animate-spin rounded-full h-5 w-5 border-2 border-blue-500 border-t-transparent" />
          </div>
        ) : conversations.length === 0 ? (
          <div className="text-center text-gray-500 py-8 text-sm">
            暂无对话记录
          </div>
        ) : (
          <div className="py-2">
            {conversations.map((conv) => (
              <div
                key={conv.id}
                onClick={() => onSelect(conv.id)}
                className={`group px-4 py-3 cursor-pointer transition-colors duration-150
                           flex items-start justify-between
                           ${
                             activeId === conv.id
                               ? 'bg-gray-700 border-l-2 border-blue-500'
                               : 'hover:bg-gray-800 border-l-2 border-transparent'
                           }`}
              >
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium truncate">
                    {conv.title}
                  </div>
                  <div className="text-xs text-gray-500 mt-1">
                    {formatDate(conv.created_at)}
                  </div>
                </div>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onDelete(conv.id);
                  }}
                  className="opacity-0 group-hover:opacity-100 ml-2 p-1 text-gray-500
                             hover:text-red-400 transition-all duration-150"
                  title="删除对话"
                >
                  <svg
                    className="w-4 h-4"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                    />
                  </svg>
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 底部信息 */}
      <div className="p-3 border-t border-gray-700 text-xs text-gray-500 text-center">
        基于 AgentScope 2.0 构建
      </div>
    </div>
  );
};

export default Sidebar;
