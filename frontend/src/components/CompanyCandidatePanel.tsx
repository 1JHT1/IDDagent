import React, { useState } from 'react'
import type { CompanyNameCandidate } from '../types'

// ============================================================
// 第1层：统一候选面板（纯 UI，无业务依赖）
// 双形态：
//   ambiguous —— 候选确认卡（琥珀色系，显示"以上都不是"）
//   not_found —— 未找到企业空态卡（灰色系，强制不显示"以上都不是"）
// 业务卡片（RiskCheckCard 等）与通用选择器（CompanyNameSelector）
// 均复用本面板，仅通过 title/confirmLabel/onSelect 注入差异。
// ============================================================
interface CompanyCandidatePanelProps {
  /** 功能名（如"风险预查""基本信息"），标题显示为 "{title} · {confirmLabel/未找到企业}" */
  title: string
  /** 形态：ambiguous=候选确认（琥珀色系），not_found=未找到空态（灰色系） */
  variant: 'ambiguous' | 'not_found'
  /** 候选企业列表（not_found 形态下为空时渲染空态区） */
  options?: CompanyNameCandidate[]
  /** 模糊匹配关键词（副标题："搜索到 N 家名称包含「{keyword}」的企业"） */
  keyword?: string
  /** not_found 空态提示文案（默认"未找到企业，请补充完整企业名称或统一社会信用代码后重新描述"） */
  message?: string
  /** ambiguous 形态的引导文案（默认"请选择要查询的企业"） */
  confirmLabel?: string
  /** 已确认态：头部蓝色提示 + 候选行"查询"标签；未确认前点击过任一候选后其余候选弱化 */
  confirmed?: boolean
  /** 已消费态（点击过一次候选/以上都不是）：整卡选项禁用，只能点击一次；由 extra.consumed 持久化恢复 */
  consumed?: boolean
  /** 穿插区域已结束时禁用，不再可点击执行 */
  disabled?: boolean
  /** 点击候选回调（上层负责按技能格式构造发送内容） */
  onSelect?: (opt: CompanyNameCandidate) => void
  /** 点击"以上都不是"回调（仅 ambiguous 形态显示） */
  onNoneOfAbove?: () => void
}

const CompanyCandidatePanel: React.FC<CompanyCandidatePanelProps> = ({
  title,
  variant,
  options,
  keyword,
  message,
  confirmLabel,
  confirmed = false,
  consumed = false,
  disabled,
  onSelect,
  onNoneOfAbove,
}) => {
  const isAmbiguous = variant === 'ambiguous'
  // 本地 clicked 标记：未确认前点击过任一候选后，其余候选弱化（防误点）；
  // 已确认态不弱化（再次点击可直接发起查询/纠错）
  const [clickedCode, setClickedCode] = useState<string | null>(null)
  // 本地已消费标记：点击任一候选或"以上都不是"后整卡禁用（只能点击一次），
  // 不等后端响应/落盘回传；刷新/切会话后由 extra.consumed 恢复
  const [localConsumed, setLocalConsumed] = useState(false)
  const candidates = options ?? []
  const blocked = disabled || consumed || localConsumed
  const handleSelect = (opt: CompanyNameCandidate) => {
    setClickedCode(opt.credit_code)
    setLocalConsumed(true)
    onSelect?.(opt)
  }
  const handleNoneOfAbove = () => {
    setLocalConsumed(true)
    onNoneOfAbove?.()
  }

  return (
    <div
      className={`rounded-xl border overflow-hidden bg-gradient-to-br ${
        isAmbiguous ? 'from-amber-50 to-yellow-50 border-amber-200' : 'from-gray-50 to-slate-50 border-gray-200'
      }`}
    >
      {/* 头部 */}
      <div className={`px-4 py-3 border-b bg-white/60 ${isAmbiguous ? 'border-amber-100' : 'border-gray-100'}`}>
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">{isAmbiguous ? '🔍' : 'ℹ️'}</span>
          {title} · {isAmbiguous ? (confirmLabel || '请选择要查询的企业') : '未找到企业'}
        </h3>
        {isAmbiguous && keyword && (
          <p className="text-xs text-gray-500 mt-1">
            搜索到 {candidates.length} 家名称包含「{keyword}」的企业
          </p>
        )}
        {/* 已确认态提示：再次点击可直接发起查询；已消费态提示：正在处理 */}
        {consumed || localConsumed ? (
          <p className="text-xs text-blue-600 mt-1">已选择企业，正在处理…</p>
        ) : confirmed ? (
          <p className="text-xs text-blue-600 mt-1">已确认企业，再次点击可直接发起查询</p>
        ) : null}
      </div>

      <div className="p-3 space-y-2">
        {candidates.length > 0 ? (
          <>
            {candidates.map((opt) => {
              // 未确认前点击过任一候选 → 其余候选弱化
              const weakened = !confirmed && clickedCode !== null && clickedCode !== opt.credit_code
              return (
                <button
                  key={opt.credit_code}
                  onClick={() => handleSelect(opt)}
                  disabled={blocked}
                  className={`w-full text-left px-4 py-3 rounded-lg border bg-white
                             transition-all flex items-center justify-between group cursor-pointer
                             disabled:opacity-50 disabled:cursor-not-allowed ${
                               isAmbiguous
                                 ? 'border-amber-200 hover:bg-amber-50 hover:border-amber-300'
                                 : 'border-gray-200 hover:bg-gray-50 hover:border-gray-300'
                             } ${weakened ? 'opacity-40' : ''}`}
                >
                  <div>
                    <div
                      className={`text-sm font-medium text-gray-800 ${
                        isAmbiguous ? 'group-hover:text-amber-700' : ''
                      }`}
                    >
                      {opt.company_name}
                    </div>
                    <div className="text-xs text-gray-400 font-mono mt-0.5">{opt.credit_code}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    {confirmed && (
                      <span className="text-[10px] text-blue-500 bg-blue-100/80 rounded-full px-2 py-0.5">
                        查询
                      </span>
                    )}
                    <svg
                      className={`w-4 h-4 ${
                        isAmbiguous
                          ? 'text-amber-400 group-hover:text-amber-600'
                          : 'text-gray-400 group-hover:text-gray-600'
                      }`}
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                    </svg>
                  </div>
                </button>
              )
            })}
            {/* 模糊匹配兜底：仅 ambiguous 形态显示；点击后后端引导用户提供准确名称/信用代码 */}
            {isAmbiguous && (
              <button
                onClick={handleNoneOfAbove}
                disabled={blocked}
                className="w-full text-center px-4 py-2.5 rounded-lg border border-dashed border-gray-300 bg-white/60
                           hover:bg-gray-100 text-sm text-gray-500 transition-all
                           disabled:opacity-50 disabled:cursor-not-allowed"
              >
                以上都不是
              </button>
            )}
          </>
        ) : (
          // 空态区（not_found 无候选）：居中 emoji + 提示文案
          <div className="flex flex-col items-center gap-2 py-6">
            <span className="text-2xl">🤷</span>
            <p className="text-sm text-gray-500 text-center">
              {message || '未找到企业，请补充完整企业名称或统一社会信用代码后重新描述'}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

export default CompanyCandidatePanel
