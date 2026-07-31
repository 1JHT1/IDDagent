import React from 'react'
import type { CompanyNameCandidate } from '../types'

interface CompanyNameSelectorProps {
  options: CompanyNameCandidate[]
  message?: string
  keyword?: string
  onSendMessage?: (content: string) => void
}

const CompanyNameSelector: React.FC<CompanyNameSelectorProps> = ({ options, message, keyword, onSendMessage }) => {
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
                opt.company_name
              )
            }
            className="w-full text-left px-4 py-3 rounded-lg border border-blue-200 bg-white
                       hover:bg-blue-50 hover:border-blue-300 transition-all
                       flex items-center justify-between group cursor-pointer"
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
