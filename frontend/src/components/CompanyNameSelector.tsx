import React from 'react'
import type { CompanyNameCandidate } from '../types'

interface CompanyNameSelectorProps {
  options: CompanyNameCandidate[]
  keyword?: string
  /** 所属任务标识（多意图管道中如"历史尽调报告查询"），候选选择与任务关联，用户清楚是谁在询问 */
  taskLabel?: string
  onSendMessage?: (content: string) => void
}

const CompanyNameSelector: React.FC<CompanyNameSelectorProps> = ({ options, keyword, taskLabel, onSendMessage }) => {
  return (
    <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl border border-blue-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-blue-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🔍</span>
          {taskLabel ? `${taskLabel} · 请选择要查询的企业` : '请选择要查询的企业'}
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
        <button
          onClick={() => onSendMessage?.('以上都不是')}
          className="w-full px-4 py-3 rounded-lg border border-dashed border-gray-300 bg-white/60
                     hover:bg-gray-100 hover:border-gray-400 transition-all
                     flex items-center justify-center gap-1.5 group cursor-pointer"
        >
          <span className="text-sm text-gray-500 group-hover:text-gray-700">以上都不是</span>
        </button>
      </div>
    </div>
  )
}

export default CompanyNameSelector
