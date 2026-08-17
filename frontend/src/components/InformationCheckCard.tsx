import React from 'react'
import type { RiskAmbiguousOption } from '../types'

// ============================================================
// Props
// ============================================================
interface InformationCheckCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string) => void
}

// ============================================================
// 组件
// ============================================================
const InformationCheckCard: React.FC<InformationCheckCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined
  // 所属任务标识（useChat 在候选事件处理时推断并持久化，重载后仍可恢复；旧消息降级）
  const taskLabel = data.task_label as string | undefined

  // ========== 未找到 ==========
  if (action === 'not_found') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    return (
      <div className="info-check-card bg-gradient-to-br from-gray-50 to-slate-50 rounded-xl border border-gray-200 overflow-hidden">
        <div className="p-4">
          <p className="text-gray-500 text-sm text-center">
            {(data.message as string) || '未找到相关信息核实数据'}
          </p>
        </div>
        {options && options.length > 0 && (
          <div className="px-3 pb-3 space-y-2">
            {options.map((opt) => (
              <button
                key={opt.credit_code}
                onClick={() =>
                  onSendMessage?.(
                    // 携带信用代码（与 CompanyNameSelector 一致），技能可直接定位企业，避免二次歧义选项卡
                    `公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`
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
            <button
              onClick={() => onSendMessage?.('以上都不是')}
              className="w-full px-4 py-3 rounded-lg border border-dashed border-gray-300 bg-white/60
                         hover:bg-gray-100 hover:border-gray-400 transition-all
                         flex items-center justify-center gap-1.5 group cursor-pointer"
            >
              <span className="text-sm text-gray-500 group-hover:text-gray-700">以上都不是</span>
            </button>
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
      <div className="info-check-card bg-gradient-to-br from-amber-50 to-yellow-50 rounded-xl border border-amber-200 overflow-hidden">
        <div className="px-4 py-3 border-b border-amber-100 bg-white/60">
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">🔍</span>
            {taskLabel ? `${taskLabel} · 请确认要核实的企业` : '请确认要核实的企业'}
          </h3>
          {keyword && (
            <p className="text-xs text-gray-500 mt-1">
              搜索到 {options?.length || 0} 家名称包含"{keyword}"的企业
            </p>
          )}
        </div>
        <div className="p-3 space-y-2">
          {options?.map((opt) => (
            <button
              key={opt.credit_code}
              onClick={() =>
                onSendMessage?.(
                  // 携带信用代码（与 CompanyNameSelector 一致），技能可直接定位企业，避免二次歧义选项卡
                  `公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`
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

  // ========== 核实结果 ==========
  const companyName = data.company_name as string || ''
  const creditCode = data.credit_code as string || ''
  const passCount = data.pass_count as number || 0
  const failCount = data.fail_count as number || 0
  const noneCount = data.none_count as number || 0
  const totalCount = data.total_count as number || 0
  const h5Url = data.h5_url as string || ''

  // 根据是否有不通过项来决定卡片风格
  const hasFail = failCount > 0
  const config = hasFail
    ? {
        bg: 'from-amber-50 to-yellow-50',
        border: 'border-amber-200',
        headerBg: 'bg-white/60',
        headerBorder: 'border-amber-100',
        passColor: 'text-emerald-600',
        failColor: 'text-red-600',
      }
    : {
        bg: 'from-emerald-50 to-green-50',
        border: 'border-emerald-200',
        headerBg: 'bg-white/60',
        headerBorder: 'border-emerald-100',
        passColor: 'text-emerald-600',
        failColor: 'text-red-600',
      }

  return (
    <div className={`info-check-card bg-gradient-to-br ${config.bg} rounded-xl border ${config.border} overflow-hidden`}>
      {/* 头部 */}
      <div className={`px-4 py-3 border-b ${config.headerBorder} ${config.headerBg}`}>
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📋</span>
          信息核实结果
        </h3>
      </div>

      {/* 内容 */}
      <div className="p-4 space-y-3">
        {/* 企业信息 */}
        <div>
          <div className="text-xs text-gray-500">核实企业</div>
          <div className="text-sm font-semibold text-gray-800">{companyName}</div>
          <div className="text-xs text-gray-400 font-mono">信用代码：{creditCode}</div>
        </div>

        {/* 提取参数概览 */}
        <div className="bg-white/70 rounded-lg p-3">
          <div className="text-xs text-gray-500 mb-2">
            营业执照参数提取完成，共 <span className="font-semibold text-gray-700">{totalCount}</span> 项信息
          </div>
          <div className="flex flex-col items-start gap-2">
            <div className="flex items-center gap-1.5">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-emerald-500"></span>
              <span className="text-sm text-gray-600">
                <span className={`font-bold ${config.passColor}`}>{passCount}</span> 项核实通过
              </span>
            </div>
            <div className="flex items-center gap-1.5">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-red-500"></span>
              <span className="text-sm text-gray-600">
                <span className={`font-bold ${config.failColor}`}>{failCount}</span> 项核实不通过
              </span>
            </div>
            {noneCount > 0 && (
              <div className="flex items-center gap-1.5">
                <span className="inline-block w-2.5 h-2.5 rounded-full bg-gray-400"></span>
                <span className="text-sm text-gray-600">
                  <span className="font-bold text-gray-500">{noneCount}</span> 项无需核实
                </span>
              </div>
            )}
          </div>
        </div>

        {/* H5 链接按钮 */}
        <a
          href={h5Url}
          target="_blank"
          rel="noopener noreferrer"
          className="block w-full text-center px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
        >
          📄 查看核实结果
        </a>
      </div>
    </div>
  )
}

export default InformationCheckCard
