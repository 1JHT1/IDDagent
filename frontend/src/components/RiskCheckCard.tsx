import React from 'react'
import type { RiskCheckResult, RiskAmbiguousOption } from '../types'

// ============================================================
// Props
// ============================================================
interface RiskCheckCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string) => void
}

// ============================================================
// 组件
// ============================================================
const RiskCheckCard: React.FC<RiskCheckCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined

  // ========== 未找到 ==========
  if (action === 'not_found') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    return (
      <div className="risk-check-card bg-gradient-to-br from-gray-50 to-slate-50 rounded-xl border border-gray-200 overflow-hidden">
        <div className="p-4">
          <p className="text-gray-500 text-sm text-center">
            {(data.message as string) || '未找到相关企业信息'}
          </p>
        </div>
        {options && options.length > 0 && (
          <div className="px-3 pb-3 space-y-2">
            {options.map((opt) => (
              <button
                key={opt.credit_code}
                onClick={() =>
                  onSendMessage?.(
                    `查询统一信用代码为${opt.credit_code}的客户的开户风险`
                  )
                }
                className="w-full text-left px-4 py-3 rounded-lg border border-amber-200 bg-white
                           hover:bg-amber-50 hover:border-amber-300 transition-all
                           flex items-center justify-between group cursor-pointer"
              >
                <div>
                  <div className="text-sm font-medium text-gray-800 group-hover:text-amber-700">
                    {opt.company_name}
                  </div>
                  <div className="text-xs text-gray-400 font-mono mt-0.5">
                    {opt.credit_code}
                  </div>
                </div>
                <svg className="w-4 h-4 text-amber-400 group-hover:text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                </svg>
              </button>
            ))}
          </div>
        )}
      </div>
    )
  }

  // ========== 名称歧义 ==========
  if (action === 'ambiguous') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    const keyword = data.keyword as string || ''

    return (
      <div className="risk-check-card bg-gradient-to-br from-amber-50 to-yellow-50 rounded-xl border border-amber-200 overflow-hidden">
        <div className="px-4 py-3 border-b border-amber-100 bg-white/60">
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">🔍</span>
            请确认要查询的企业
          </h3>
          <p className="text-xs text-gray-500 mt-1">
            搜索到 {options?.length || 0} 家名称包含"{keyword}"的企业
          </p>
        </div>
        <div className="p-3 space-y-2">
          {options?.map((opt) => (
            <button
              key={opt.credit_code}
              onClick={() =>
                onSendMessage?.(
                  `查询统一信用代码为${opt.credit_code}的客户的开户风险`
                )
              }
              className="w-full text-left px-4 py-3 rounded-lg border border-amber-200 bg-white
                         hover:bg-amber-50 hover:border-amber-300 transition-all
                         flex items-center justify-between group cursor-pointer"
            >
              <div>
                <div className="text-sm font-medium text-gray-800 group-hover:text-amber-700">
                  {opt.company_name}
                </div>
                <div className="text-xs text-gray-400 font-mono mt-0.5">
                  {opt.credit_code}
                </div>
              </div>
              <svg className="w-4 h-4 text-amber-400 group-hover:text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </button>
          ))}
        </div>
      </div>
    )
  }

  // ========== 风险结果 ==========
  const companyName = data.company_name as string || ''
  const creditCode = data.credit_code as string || ''
  const riskLevel = data.risk_level as string || 'low'
  const riskSummary = data.risk_summary as string || ''
  const h5Url = data.h5_url as string || ''

  const levelConfig: Record<string, { label: string; bg: string; text: string; border: string; icon: string }> = {
    high: {
      label: '高风险',
      bg: 'from-red-50 to-rose-50',
      text: 'text-red-700',
      border: 'border-red-200',
      icon: '🔴',
    },
    medium: {
      label: '中等风险',
      bg: 'from-amber-50 to-yellow-50',
      text: 'text-amber-700',
      border: 'border-amber-200',
      icon: '🟡',
    },
    low: {
      label: '低风险',
      bg: 'from-emerald-50 to-green-50',
      text: 'text-emerald-700',
      border: 'border-emerald-200',
      icon: '🟢',
    },
  }

  const config = levelConfig[riskLevel] || levelConfig.low

  return (
    <div className={`risk-check-card bg-gradient-to-br ${config.bg} rounded-xl border ${config.border} overflow-hidden`}>
      {/* 头部 */}
      <div className="px-4 py-3 border-b border-white/40 bg-white/30">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🛡️</span>
          风险预查结果
        </h3>
      </div>

      {/* 内容 */}
      <div className="p-4 space-y-3">
        {/* 企业信息 */}
        <div>
          <div className="text-xs text-gray-500">查询企业</div>
          <div className="text-sm font-semibold text-gray-800">{companyName}</div>
          <div className="text-xs text-gray-400 font-mono">信用代码：{creditCode}</div>
        </div>

        {/* 风险等级 */}
        <div className="flex items-center gap-2">
          <span className="text-xs text-gray-500">风险等级：</span>
          <span className={`inline-flex items-center gap-1 px-3 py-1 rounded-full text-sm font-bold ${config.text} bg-white/70`}>
            {config.icon} {config.label}
          </span>
        </div>

        {/* 风险结论 */}
        <div className="bg-white/70 rounded-lg p-3 border-l-2 border-blue-400">
          <p className="text-sm text-gray-700 leading-relaxed">
            💡 {riskSummary}
          </p>
        </div>

        {/* H5 链接按钮 */}
        <a
          href={h5Url}
          target="_blank"
          rel="noopener noreferrer"
          className="block w-full text-center px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
        >
          📄 查看完整风险报告（H5）
        </a>
      </div>
    </div>
  )
}

export default RiskCheckCard
