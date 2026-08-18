import React from 'react'
import type { ClarificationOption } from '../types'

interface ClarificationCardProps {
  question: string
  options: ClarificationOption[]
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean
  onSendMessage?: (content: string) => void
}

/**
 * 意图澄清卡片（Phase 4）：同技能多主体冲突时让用户确认执行对象。
 * 点击选项原样发送 option.value（JSON 字符串），后端解析后直接执行对应技能；
 * “全部执行”选项（label 为“全部执行”）点击后发送 {"action":"execute_all"}，后端放行全部意图到任务规划。
 */
const ClarificationCard: React.FC<ClarificationCardProps> = ({ question, options, onSendMessage, disabled }) => {
  return (
    <div className="bg-gradient-to-br from-amber-50 to-orange-50 rounded-xl border border-amber-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-amber-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🤔</span>
          请确认执行对象
        </h3>
      </div>
      {question && (
        <div className="px-4 py-3">
          <p className="text-sm text-gray-700 leading-relaxed">{question}</p>
        </div>
      )}
      <div className="px-4 pb-3 space-y-2">
        {options.map((opt, idx) => {
          const isAll = opt.label === '全部执行'
          return (
            <button
              key={`${opt.label}-${idx}`}
              onClick={() => onSendMessage?.(opt.value)}
              disabled={disabled}
              className={
                isAll
                  ? 'w-full text-center px-4 py-2.5 rounded-lg border border-blue-400 bg-blue-600 text-white font-medium text-sm hover:bg-blue-700 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-blue-600'
                  : 'w-full text-left px-4 py-2.5 rounded-lg border border-amber-200 bg-white hover:bg-amber-50 hover:border-amber-300 transition-all flex items-center justify-between group cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white disabled:hover:border-amber-200'
              }
            >
              {isAll ? (
                <span className="inline-flex items-center gap-1.5">
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                  </svg>
                  {opt.label}
                </span>
              ) : (
                <>
                  <span className="text-sm font-medium text-gray-800 group-hover:text-amber-700">
                    {opt.label}
                  </span>
                  <svg
                    className="w-4 h-4 text-amber-400 group-hover:text-amber-600"
                    fill="none" stroke="currentColor" viewBox="0 0 24 24"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                  </svg>
                </>
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}

export default ClarificationCard
