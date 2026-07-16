import React from 'react'
import type { RecommendedProduct, RiskAmbiguousOption } from '../types'

interface ProductRecommendCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string) => void
}

const priorityBadge: Record<string, { bg: string; text: string; label: string }> = {
  high: { bg: 'bg-red-50 border-red-200', text: 'text-red-700', label: '高优先级' },
  medium: { bg: 'bg-amber-50 border-amber-200', text: 'text-amber-700', label: '中优先级' },
  low: { bg: 'bg-green-50 border-green-200', text: 'text-green-700', label: '低优先级' },
}

const priorityDot: Record<string, string> = {
  high: 'bg-red-500',
  medium: 'bg-amber-500',
  low: 'bg-green-500',
}

const ProductRecommendCard: React.FC<ProductRecommendCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined

  // ========== 未找到 ==========
  if (action === 'not_found') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    return (
      <div className="bg-gradient-to-br from-gray-50 to-slate-50 rounded-xl border border-gray-200 overflow-hidden">
        <div className="p-4">
          <p className="text-gray-500 text-sm text-center">
            {(data.message as string) || '未找到相关企业产品推荐信息'}
          </p>
        </div>
        {options && options.length > 0 && (
          <div className="px-3 pb-3 space-y-2">
            {options.map((opt) => (
              <button
                key={opt.credit_code}
                onClick={() =>
                  onSendMessage?.(
                    `为统一信用代码${opt.credit_code}的企业推荐适合的金融产品`
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
        )}
      </div>
    )
  }

  // ========== 名称歧义 ==========
  if (action === 'ambiguous') {
    const options = (data.options as RiskAmbiguousOption[]) || []
    const keyword = (data.keyword as string) || ''

    return (
      <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl border border-blue-200 overflow-hidden">
        <div className="px-4 py-3 border-b border-blue-100 bg-white/60">
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">🔍</span>
            请确认要查询的企业
          </h3>
          <p className="text-xs text-gray-500 mt-1">
            搜索到 {options.length} 家与「{keyword}」匹配的企业
          </p>
        </div>
        <div className="p-3 space-y-2">
          {options.map((opt) => (
            <button
              key={opt.credit_code}
              onClick={() =>
                onSendMessage?.(
                  `为统一信用代码${opt.credit_code}的企业推荐适合的金融产品`
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
      </div>
    )
  }

  // ========== 产品推荐结果 ==========
  const companyName = (data.company_name as string) || ''
  const creditCode = (data.credit_code as string) || ''
  const analysisSummary = (data.analysis_summary as string) || ''
  const h5Url = (data.detail_h5_url as string) || ''
  const products = (data.products as RecommendedProduct[]) || []

  // 按优先级分组
  const priorityOrder = ['high', 'medium', 'low'] as const
  const grouped: Record<string, RecommendedProduct[]> = { high: [], medium: [], low: [] }
  products.forEach((p) => grouped[p.priority]?.push(p))

  return (
    <div className="bg-gradient-to-br from-blue-50 to-cyan-50 rounded-xl border border-blue-100 overflow-hidden">
      {/* 头部 */}
      <div className="px-4 py-3 border-b border-blue-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🎯</span>
          产品智能推荐
        </h3>
      </div>

      <div className="p-4 space-y-4">
        {/* 企业信息 */}
        <div>
          <div className="text-xs text-gray-500 mb-1">目标客户</div>
          <div className="text-sm font-semibold text-gray-800">{companyName}</div>
          <div className="text-xs text-gray-400 font-mono mt-0.5">信用代码：{creditCode}</div>
        </div>

        {/* 分析摘要 */}
        {analysisSummary && (
          <div className="bg-white/70 rounded-lg p-3 border border-blue-100">
            <div className="text-xs text-gray-400 mb-1">📊 诊断摘要</div>
            <div className="text-xs text-gray-600 leading-relaxed">{analysisSummary}</div>
          </div>
        )}

        {/* 按优先级分组展示产品 */}
        {priorityOrder.map((priority) => {
          const group = grouped[priority]
          if (!group || group.length === 0) return null

          return (
            <div key={priority}>
              <div className="flex items-center gap-2 mb-2">
                <span className={`w-2 h-2 rounded-full ${priorityDot[priority]}`} />
                <span className="text-xs font-semibold text-gray-600">
                  {priorityBadge[priority].label}（{group.length}）
                </span>
              </div>
              <div className="space-y-2">
                {group.map((prod, idx) => (
                  <div
                    key={idx}
                    className={`rounded-lg border p-3 bg-white/80 ${priorityBadge[priority].bg}`}
                  >
                    <div className="flex items-start justify-between mb-1.5">
                      <div>
                        <div className="text-sm font-semibold text-gray-800">
                          {prod.product_name}
                        </div>
                        <div className="text-xs text-gray-400">{prod.category}</div>
                      </div>
                      <span
                        className={`text-xs font-bold px-2 py-0.5 rounded-full border ${priorityBadge[priority].bg} ${priorityBadge[priority].text}`}
                      >
                        {prod.priority_label}
                      </span>
                    </div>
                    <div className="text-xs text-gray-500 leading-relaxed bg-gray-50 rounded-md p-2 mb-2">
                      💡 {prod.reason}
                    </div>
                    <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-400">
                      {prod.expected_amount && prod.expected_amount !== '—' && (
                        <span>💰 预估额度 <strong className="text-gray-600">{prod.expected_amount}</strong></span>
                      )}
                      {prod.application_period && prod.application_period !== '—' && (
                        <span>⏱ 周期 <strong className="text-gray-600">{prod.application_period}</strong></span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )
        })}

        {/* H5 链接 */}
        {h5Url && (
          <a
            href={h5Url}
            target="_blank"
            rel="noopener noreferrer"
            className="block text-center px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
          >
            📋 查看完整产品推荐分析报告
          </a>
        )}
      </div>
    </div>
  )
}

export default ProductRecommendCard
