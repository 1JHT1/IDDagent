import React, { useState } from 'react'
import type { RiskAmbiguousOption } from '../types'

// ============================================================
// 第 1 层：统一面板组件 CompanyCandidatePanel（唯一样式出口）
// 承载"企业名称模糊匹配候选确认"（ambiguous）与"未找到企业"（not_found）
// 两类形态，所有卡片（CompanyNameSelector / RiskCheckCard /
// InformationCheckCard / CompanyQueryCard / HistoricalDDQueryCard）复用：
// - ambiguous：琥珀色系，候选列表 + "以上都不是"兑底按钮（必显示）
// - not_found 无候选：灰色系，🤷 未找到提示，不显示"以上都不是"
// - not_found 带候选：候选行与 ambiguous 同款琥珀样式，顶部附未找到说明，
//   同样显示"以上都不是"（用户可确认相似候选或声明均非目标企业）
// ============================================================

interface CompanyCandidatePanelProps {
  /** 功能名称（如"风险预查""基本信息"），显示在卡片头部 */
  title: string
  /** 卡片语义：ambiguous=候选确认（琥珀色系）；not_found=未找到企业（灰色系） */
  variant: 'ambiguous' | 'not_found'
  /** 候选企业列表（候选数量上限 3） */
  options?: RiskAmbiguousOption[]
  /** 搜索关键词：头部显示"搜索到 N 家名称包含「XX」的企业" */
  keyword?: string
  /** 候选区动作文案，默认"请选择要查询的企业" */
  confirmLabel?: string
  /** 已确认过候选：显示提示 + 候选"查询"标签 */
  confirmed?: boolean
  /** 候选区顶部额外提示（如超时提醒） */
  notice?: React.ReactNode
  /** 未找到企业提示文案 */
  notFoundMessage?: string
  /** 是否显示"以上都不是"（not_found 无候选时强制不显示；有候选时与 ambiguous 一致，默认显示） */
  showNoneOfAbove?: boolean
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean
  /** 点击候选回调（静默发送协议文本由调用方决定） */
  onSelect?: (opt: RiskAmbiguousOption) => void
  /** 点击"以上都不是"回调 */
  onNoneOfAbove?: () => void
}

/** 技能名 → 功能名映射（CompanyNameSelector 标题兜底等场景使用） */
const SKILL_NAME_MAP: Record<string, string> = {
  check_company_risk: '风险预查',
  verify_business_license: '信息核实',
  query_due_diligence_reports: '历史尽调报告',
}

/** 根据技能名解析功能名（query_* 前缀归为企业信息查询，未知技能兜底为"企业查询"） */
export function resolveFunctionName(skillName?: string): string {
  if (!skillName) return '企业查询'
  if (SKILL_NAME_MAP[skillName]) return SKILL_NAME_MAP[skillName]
  if (skillName.startsWith('query_')) return '企业信息查询'
  return '企业查询'
}

const CompanyCandidatePanel: React.FC<CompanyCandidatePanelProps> = ({
  title,
  variant,
  options,
  keyword,
  confirmLabel,
  confirmed,
  notice,
  notFoundMessage,
  showNoneOfAbove,
  disabled,
  onSelect,
  onNoneOfAbove,
}) => {
  const isAmbiguous = variant === 'ambiguous'
  const candidateCount = options?.length || 0
  // 本地点击标记：未确认前点击过任一候选后，其余候选弱化（提示已作出选择）
  const [selectedCode, setSelectedCode] = useState<string | null>(null)
  // "以上都不是"：not_found 无候选时强制不显示；ambiguous 与 not_found 带候选时默认显示
  // （调用方可通过开关关闭）
  const showNone = (isAmbiguous || candidateCount > 0) && showNoneOfAbove !== false

  const handleSelect = (opt: RiskAmbiguousOption) => {
    setSelectedCode(opt.credit_code)
    onSelect?.(opt)
  }

  return (
    <div
      className={`company-candidate-panel rounded-xl border overflow-hidden bg-gradient-to-br ${
        isAmbiguous ? 'from-amber-50 to-yellow-50 border-amber-200' : 'from-gray-50 to-slate-50 border-gray-200'
      }`}
    >
      {/* 头部：功能名 + 动作文案 + 关键词命中数量说明 */}
      <div className={`px-4 py-3 border-b bg-white/60 ${isAmbiguous ? 'border-amber-100' : 'border-gray-100'}`}>
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-1.5">
          <span className="text-lg">{isAmbiguous ? '🔍' : 'ℹ️'}</span>
          <span>{title}</span>
          <span className="text-xs font-normal text-gray-500">
            · {isAmbiguous ? (confirmed ? '已确认候选，点击可直接查询' : confirmLabel || '请选择要查询的企业') : '未找到企业'}
          </span>
        </h3>
        {candidateCount > 0 && keyword && (
          <p className="text-xs text-gray-500 mt-1">
            搜索到 {candidateCount} 家名称包含「{keyword}」的企业
          </p>
        )}
      </div>

      {/* 未找到：🤷 空态提示（仅无候选时；不带"以上都不是"） */}
      {!isAmbiguous && candidateCount === 0 && (
        <div className="px-6 py-5 flex flex-col items-center gap-2 text-center">
          <span className="text-2xl">🤷</span>
          <p className="text-sm font-medium text-gray-600">{notFoundMessage || '未找到企业'}</p>
        </div>
      )}

      {/* 候选列表（有候选才渲染；not_found 带候选时头部附未找到说明，候选行与 ambiguous 同款样式） */}
      {(isAmbiguous || candidateCount > 0) && (
        <div className="p-3 space-y-2">
          {!isAmbiguous && notFoundMessage && (
            <p className="px-3 py-2 rounded-lg bg-gray-100/70 border border-gray-200 text-xs text-gray-600">
              {notFoundMessage}
            </p>
          )}
          {notice && (
            <div className="px-3 py-2 rounded-lg bg-amber-100/70 border border-amber-200 text-xs text-amber-800">
              {notice}
            </div>
          )}
          {options?.map((opt) => {
            // 本地点击标记：已作出选择后，其余候选弱化提示
            const dimmed = selectedCode !== null && opt.credit_code !== selectedCode
            return (
              <button
                key={opt.credit_code}
                onClick={() => handleSelect(opt)}
                disabled={disabled}
                className={`w-full text-left px-4 py-3 rounded-lg border bg-white transition-all
                           flex items-center justify-between group cursor-pointer
                           disabled:opacity-50 disabled:cursor-not-allowed
                           ${dimmed ? 'opacity-40' : ''}
                           border-amber-200 hover:bg-amber-50 hover:border-amber-300
                           disabled:hover:bg-white disabled:hover:border-amber-200`}
              >
                <div>
                  <div className="text-sm font-medium text-gray-800 group-hover:text-amber-700">
                    {opt.company_name}
                  </div>
                  <div className="text-xs text-gray-400 font-mono mt-0.5">{opt.credit_code}</div>
                </div>
                {confirmed ? (
                  // 已确认：候选带"查询"标签，点击直接发起对应功能查询
                  <span className="text-xs px-2 py-0.5 rounded-full border font-medium bg-amber-100 text-amber-700 border-amber-200">
                    查询
                  </span>
                ) : (
                  <svg
                    className="w-4 h-4 text-amber-400 group-hover:text-amber-600"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                  </svg>
                )}
              </button>
            )
          })}
          {/* 以上都不是：有候选时显示（not_found 无候选时强制不显示）；候选均不是目标企业时点击，后端引导用户提供准确名称/信用代码 */}
          {showNone && (
            <button
              onClick={() => onNoneOfAbove?.()}
              disabled={disabled}
              className="w-full text-center px-4 py-2.5 rounded-lg border border-dashed border-gray-300 bg-gray-50
                         hover:bg-gray-100 text-sm text-gray-500 transition-all
                         disabled:opacity-50 disabled:cursor-not-allowed"
            >
              以上都不是
            </button>
          )}
        </div>
      )}
    </div>
  )
}

export default CompanyCandidatePanel
