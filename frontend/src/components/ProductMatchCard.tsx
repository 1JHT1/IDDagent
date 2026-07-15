import React from 'react'
import type { MatchedProduct } from '../types'

interface ProductMatchCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string) => void
}

/** 匹配度评分对应的颜色 */
function scoreColor(score: number) {
  if (score >= 90) return { bar: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50 border-emerald-200', badge: 'bg-emerald-100 text-emerald-800' }
  if (score >= 75) return { bar: 'bg-blue-500', text: 'text-blue-700', bg: 'bg-blue-50 border-blue-200', badge: 'bg-blue-100 text-blue-800' }
  return { bar: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50 border-amber-200', badge: 'bg-amber-100 text-amber-800' }
}

function scoreLabel(score: number) {
  if (score >= 90) return '完美匹配'
  if (score >= 75) return '高度匹配'
  if (score >= 60) return '部分匹配'
  return '参考价值'
}

const ProductMatchCard: React.FC<ProductMatchCardProps> = ({ data }) => {
  const needsSummary = (data.needs_summary as string) || ''
  const companyName = (data.company_name as string) || ''
  const creditCode = (data.credit_code as string) || ''
  const matches = (data.matches as MatchedProduct[]) || []

  return (
    <div className="bg-gradient-to-br from-violet-50 to-purple-50 rounded-xl border border-violet-100 overflow-hidden">
      {/* 头部 */}
      <div className="px-4 py-3 border-b border-violet-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🧠</span>
          产品智能匹配
        </h3>
        {needsSummary && (
          <p className="text-xs text-gray-500 mt-1 leading-relaxed">{needsSummary}</p>
        )}
      </div>

      <div className="p-4 space-y-4">
        {/* 企业信息（如有） */}
        {companyName && (
          <div className="flex items-center gap-2 text-xs text-gray-500">
            <span>🏢 {companyName}</span>
            {creditCode && <span className="font-mono text-gray-400">{creditCode}</span>}
          </div>
        )}

        {/* 匹配产品列表 */}
        {matches.length > 0 ? (
          <div className="space-y-3">
            {matches.map((prod, idx) => {
              const colors = scoreColor(prod.match_score)
              return (
                <div
                  key={idx}
                  className={`rounded-lg border p-3 ${colors.bg}`}
                >
                  {/* 产品名 + 匹配度 */}
                  <div className="flex items-start justify-between mb-2">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-lg font-bold text-gray-800">
                          #{idx + 1}
                        </span>
                        <span className="text-sm font-semibold text-gray-800">
                          {prod.product_name}
                        </span>
                      </div>
                      <div className="text-xs text-gray-400 mt-0.5">{prod.category}</div>
                    </div>
                    <div className="flex flex-col items-end gap-1">
                      <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${colors.badge}`}>
                        {scoreLabel(prod.match_score)}
                      </span>
                      <div className="flex items-center gap-1.5">
                        <div className="w-16 h-1.5 bg-gray-200 rounded-full overflow-hidden">
                          <div
                            className={`h-full rounded-full ${colors.bar}`}
                            style={{ width: `${prod.match_score}%` }}
                          />
                        </div>
                        <span className={`text-xs font-bold ${colors.text}`}>
                          {prod.match_score}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* 推荐理由 */}
                  <div className="text-xs text-gray-600 leading-relaxed bg-white/70 rounded-md p-2 mb-2">
                    💡 {prod.reason}
                  </div>

                  {/* 预估收益 */}
                  {prod.estimated_return && prod.estimated_return !== '—' && (
                    <div className="text-xs font-medium text-emerald-700 bg-emerald-50 rounded-md px-2 py-1.5 mb-2 inline-block">
                      💰 预估收益：{prod.estimated_return}
                    </div>
                  )}

                  {/* 产品亮点 */}
                  {prod.highlights && prod.highlights.length > 0 && (
                    <div className="flex flex-wrap gap-1.5 mb-2">
                      {prod.highlights.map((h, i) => (
                        <span
                          key={i}
                          className="text-xs px-2 py-0.5 bg-white border border-gray-200 rounded-full text-gray-600"
                        >
                          ✨ {h}
                        </span>
                      ))}
                    </div>
                  )}

                  {/* 底部信息 */}
                  <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-400 border-t border-gray-100 pt-2">
                    {prod.application_period && prod.application_period !== '—' && (
                      <span>⏱ 办理周期 <strong className="text-gray-600">{prod.application_period}</strong></span>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          <div className="text-center text-sm text-gray-400 py-6">
            暂无匹配产品
          </div>
        )}

        {/* 底部说明 */}
        <div className="text-xs text-gray-400 text-center border-t border-violet-100 pt-3">
          以上推荐基于您描述的需求进行智能匹配，具体方案请以客户经理评估为准
        </div>
      </div>
    </div>
  )
}

export default ProductMatchCard
