import React from 'react'
import type { RiskAmbiguousOption } from '../types'
import CompanyCandidatePanel from './CompanyCandidatePanel'

// ============================================================
// Props
// ============================================================
interface RiskCheckCardProps {
  data: Record<string, unknown>
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean
  /** 发送回调：silent=true 时不展示用户气泡（候选点击直接进入查询） */
  onSendMessage?: (content: string, silent?: boolean) => void
}

// ============================================================
// 组件
// ============================================================
const RiskCheckCard: React.FC<RiskCheckCardProps> = ({ data, onSendMessage, disabled }) => {
  const action = data.action as string | undefined

  // ========== 未找到 / 名称歧义（复用第1层统一候选面板） ==========
  if (action === 'not_found' || action === 'ambiguous') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    const keyword = data.keyword as string || ''
    // 形态由候选数量决定（与 action 解耦）：有候选 → 候选确认卡（琥珀色 + "以上都不是"）；
    // 无候选 → 未找到企业空态卡（灰色）。后端 not_found 也可能携带相似企业候选（模糊匹配）
    const hasOptions = Array.isArray(options) && options.length > 0
    return (
      <CompanyCandidatePanel
        title="风险预查"
        variant={hasOptions ? 'ambiguous' : 'not_found'}
        options={options}
        keyword={keyword}
        message={(data.message as string) || '未找到相关企业信息'}
        disabled={disabled}
        // 点击候选：按风险预查协议文本静默发送（后端以 18 位码为主体直接查询）
        onSelect={(opt) =>
          onSendMessage?.(
            `查询统一信用代码为${opt.credit_code}的客户的风险`,
            true
          )
        }
        // 候选均不是目标企业：静默发送固定短语，后端引导用户提供准确名称/信用代码
        onNoneOfAbove={() => onSendMessage?.('以上都不是', true)}
      />
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
          className={`block w-full text-center px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors ${
            disabled ? 'pointer-events-none opacity-50' : ''
          }`}
        >
          📄 查看完整风险报告（H5）
        </a>
      </div>
    </div>
  )
}

export default RiskCheckCard
