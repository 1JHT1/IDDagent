import React from 'react'
import type { CompanyNameCandidate } from '../types'

interface CompanyNameSelectorProps {
  options: CompanyNameCandidate[]
  message?: string
  keyword?: string
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean
  onSendMessage?: (content: string) => void
}

const CompanyNameSelector: React.FC<CompanyNameSelectorProps> = ({ options, message, keyword, onSendMessage, disabled }) => {
  return (
    <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl border border-blue-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-blue-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🔍</span>
          请选择要查询的企业
        </h3>
        {keyword && (
          <p className="text-xs text-gray-500 mt-1">
            搜索到 {options.length} 家名称包含「{keyword}」的企业
          </p>
        )}
      </div>
      <div className="p-3 space-y-2">
        {options.map((opt) => (
          <button
            key={opt.credit_code}
            onClick={() =>
              onSendMessage?.(
                // 按字段名发送：后端解析"公司：名称\n统一信用代码：代码"识别企业身份（跳过二次选项卡）
                `公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`
              )
            }
            disabled={disabled}
            className="w-full text-left px-4 py-3 rounded-lg border border-blue-200 bg-white
                       hover:bg-blue-50 hover:border-blue-300 transition-all
                       flex items-center justify-between group cursor-pointer
                       disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white disabled:hover:border-blue-200"
          >
            <div>
              <div className="text-sm font-medium text-gray-800 group-hover:text-blue-700">
                {opt.company_name}
              </div>
              <div className="text-xs text-gray-400 font-mono mt-0.5">
                {opt.credit_code}
              </div>
            </div>
            <svg className="w-4 h-4 text-blue-400 group-hover:text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        ))}
        {/* 模糊匹配兜底：候选均不是目标企业时点击，后端引导用户提供准确名称/信用代码 */}
        <button
          onClick={() => onSendMessage?.('以上选项均不是')}
          disabled={disabled}
          className="w-full text-center px-4 py-2.5 rounded-lg border border-dashed border-gray-300 bg-gray-50
                     hover:bg-gray-100 text-sm text-gray-500 transition-all
                     disabled:opacity-50 disabled:cursor-not-allowed"
        >
          以上选项均不是
        </button>
      </div>
      {message && (
        <div className="px-4 py-2 bg-blue-50/80 border-t border-blue-100">
          <p className="text-xs text-gray-500">{message}</p>
        </div>
      )}
    </div>
  )
}

export default CompanyNameSelector
