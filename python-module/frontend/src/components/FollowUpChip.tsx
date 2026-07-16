import React from 'react'

interface FollowUpChipProps {
  text: string
  onSendMessage?: (content: string) => void
}

/**
 * 将追问问题转换为用户请求指令
 * 例: "是否需要为「北京星河科技」准备拓户营销材料？" → "请为北京星河科技准备拓户营销材料"
 */
function convertToRequest(question: string): string {
  return question
    .replace('是否需要', '请')
    .replace(/「|」/g, '')
    .replace('？', '')
    .trim()
}

const FollowUpChip: React.FC<FollowUpChipProps> = ({ text, onSendMessage }) => {
  const requestText = convertToRequest(text)

  return (
    <div className="mt-3 pt-3 border-t border-gray-100">
      <div className="flex items-center gap-2">
        <span className="text-xs text-gray-400 flex-shrink-0">💭</span>
        <button
          onClick={() => onSendMessage?.(requestText)}
          className="flex-1 text-left px-3 py-2 rounded-lg border border-blue-200 bg-blue-50/80
                     hover:bg-blue-100 hover:border-blue-300 transition-all
                     text-sm text-blue-700 font-medium
                     cursor-pointer group"
        >
          <span className="group-hover:underline">{text}</span>
          <svg
            className="inline-block w-3.5 h-3.5 ml-1.5 text-blue-400 group-hover:translate-x-0.5 transition-transform"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
          </svg>
        </button>
      </div>
    </div>
  )
}

export default FollowUpChip
