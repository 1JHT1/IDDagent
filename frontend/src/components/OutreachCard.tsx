import React from 'react'
import type { ContactChannel, RiskAmbiguousOption } from '../types'

interface OutreachCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string) => void
}

const OutreachCard: React.FC<OutreachCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined

  // ========== 未找到 ==========
  if (action === 'not_found') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    return (
      <div className="bg-gradient-to-br from-gray-50 to-slate-50 rounded-xl border border-gray-200 overflow-hidden">
        <div className="p-4">
          <p className="text-gray-500 text-sm text-center">
            {(data.message as string) || '未找到相关企业拓户准备资料'}
          </p>
        </div>
        {options && options.length > 0 && (
          <div className="px-3 pb-3 space-y-2">
            {options.map((opt) => (
              <button
                key={opt.credit_code}
                onClick={() =>
                  onSendMessage?.(
                    `请帮我准备统一信用代码为${opt.credit_code}的客户的拓户营销材料`
                  )
                }
                className="w-full text-left px-4 py-3 rounded-lg border border-violet-200 bg-white
                           hover:bg-violet-50 hover:border-violet-300 transition-all
                           flex items-center justify-between group cursor-pointer"
              >
                <div>
                  <div className="text-sm font-medium text-gray-800 group-hover:text-violet-700">
                    {opt.company_name}
                  </div>
                  <div className="text-xs text-gray-400 font-mono mt-0.5">
                    {opt.credit_code}
                  </div>
                </div>
                <svg className="w-4 h-4 text-violet-400 group-hover:text-violet-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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
      <div className="bg-gradient-to-br from-violet-50 to-purple-50 rounded-xl border border-violet-200 overflow-hidden">
        <div className="px-4 py-3 border-b border-violet-100 bg-white/60">
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
                  `请帮我准备统一信用代码为${opt.credit_code}的客户的拓户营销材料`
                )
              }
              className="w-full text-left px-4 py-3 rounded-lg border border-violet-200 bg-white
                         hover:bg-violet-50 hover:border-violet-300 transition-all
                         flex items-center justify-between group cursor-pointer"
            >
              <div>
                <div className="text-sm font-medium text-gray-800 group-hover:text-violet-700">
                  {opt.company_name}
                </div>
                <div className="text-xs text-gray-400 font-mono mt-0.5">
                  {opt.credit_code}
                </div>
              </div>
              <svg className="w-4 h-4 text-violet-400 group-hover:text-violet-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </button>
          ))}
        </div>
      </div>
    )
  }

  // ========== 拓户准备结果 ==========
  const companyName = (data.company_name as string) || ''
  const creditCode = (data.credit_code as string) || ''
  const businessAddress = (data.business_address as string) || ''
  const registeredAddress = (data.registered_address as string) || ''
  const channels = (data.contact_channels as ContactChannel[]) || []
  const insightsUrl = (data.insights_h5_url as string) || ''
  const scriptUrl = (data.script_h5_url as string) || ''

  return (
    <div className="bg-gradient-to-br from-violet-50 to-indigo-50 rounded-xl border border-violet-100 overflow-hidden">
      {/* 头部 */}
      <div className="px-4 py-3 border-b border-violet-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📋</span>
          拓户准备材料
        </h3>
      </div>

      <div className="p-4 space-y-4">
        {/* 企业信息 */}
        <div>
          <div className="text-xs text-gray-500 mb-1">目标客户</div>
          <div className="text-sm font-semibold text-gray-800">{companyName}</div>
          <div className="text-xs text-gray-400 font-mono mt-0.5">信用代码：{creditCode}</div>
        </div>

        {/* 地址信息 */}
        {(businessAddress || registeredAddress) && (
          <div className="grid grid-cols-2 gap-3">
            {businessAddress && (
              <div className="bg-white/70 rounded-lg p-3">
                <div className="text-xs text-gray-400 mb-1">📍 经营地址</div>
                <div className="text-xs text-gray-700 leading-relaxed">{businessAddress}</div>
              </div>
            )}
            {registeredAddress && (
              <div className="bg-white/70 rounded-lg p-3">
                <div className="text-xs text-gray-400 mb-1">🏛️ 注册地址</div>
                <div className="text-xs text-gray-700 leading-relaxed">{registeredAddress}</div>
              </div>
            )}
          </div>
        )}

        {/* 触达渠道 */}
        {channels.length > 0 && (
          <div>
            <div className="text-xs text-gray-500 font-medium mb-2">🔗 触达方式</div>
            <div className="space-y-2">
              {channels.map((ch, idx) => (
                <div
                  key={idx}
                  className={`rounded-lg p-3 border ${
                    ch.priority === 'high'
                      ? 'bg-red-50/60 border-red-100'
                      : 'bg-amber-50/60 border-amber-100'
                  }`}
                >
                  <div className="flex items-center gap-2 mb-1">
                    <span
                      className={`text-xs font-bold px-2 py-0.5 rounded-full ${
                        ch.priority === 'high'
                          ? 'bg-red-100 text-red-700'
                          : 'bg-amber-100 text-amber-700'
                      }`}
                    >
                      {ch.type}
                      {ch.priority === 'high' ? ' 🔥' : ''}
                    </span>
                  </div>
                  <div className="text-xs text-gray-600 leading-relaxed mb-1">{ch.relation}</div>
                  <div className="text-xs text-gray-500">💡 {ch.contact_method}</div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* H5 链接 */}
        <div className="grid grid-cols-2 gap-3">
          <a
            href={insightsUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="block text-center px-4 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium rounded-lg transition-colors"
          >
            📊 营销谈资
          </a>
          <a
            href={scriptUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="block text-center px-4 py-2.5 bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium rounded-lg transition-colors"
          >
            💬 营销话术
          </a>
        </div>
      </div>
    </div>
  )
}

export default OutreachCard
