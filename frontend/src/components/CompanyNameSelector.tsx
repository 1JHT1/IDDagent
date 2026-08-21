import React, { useEffect, useRef, useState } from 'react'
import type { CompanyNameCandidate } from '../types'
import CompanyCandidatePanel from './CompanyCandidatePanel'

// ============================================================
// 第2层：通用企业候选选择器（company_name_candidates 事件专用）
// 基于第1层统一面板（CompanyCandidatePanel）渲染，附加：
//   - confirmed 双通道：localConfirmedRef 本地乐观更新 + extra.confirmed 持久化恢复
//   - 超时提醒：未确认 3 分钟未选择 → 候选区顶部琥珀色提醒条
//   - 静默发送：onSendMessage(content, true) 不展示用户气泡，直接进入结果
// 新项目复用时只需提供带 silent 参数的发送回调与同构的 CompanyNameCandidate 类型。
// ============================================================
interface CompanyNameSelectorProps {
  options: CompanyNameCandidate[]
  /** 说明文本（独立气泡展示在选项卡之前，由调用方渲染） */
  message?: string
  keyword?: string
  /** 功能名标题（task_label，缺省"企业查询"） */
  title?: string
  /** 已确认态（由消息 extra.confirmed 恢复，组件重建后仍能显示"已确认过"） */
  confirmed?: boolean
  /** 已消费态（由消息 extra.consumed 恢复，点击过一次后整卡禁用） */
  consumed?: boolean
  /** 穿插区域已结束时禁用，不再可点击执行 */
  disabled?: boolean
  /** 发送回调：silent=true 时不展示用户气泡；extra 随请求透传（如 confirmed 落盘） */
  onSendMessage?: (content: string, silent?: boolean, extra?: Record<string, unknown>) => void
}

/** 超时阈值：未确认 3 分钟未选择候选 → 显示提醒条 */
const TIMEOUT_MS = 3 * 60 * 1000
/** 超时提醒文案 */
const TIMEOUT_TEXT =
  '候选选择已超时：原查询仍挂起等待确认。请选择上方候选企业继续查询，或点击「以上都不是」重新描述。'

const CompanyNameSelector: React.FC<CompanyNameSelectorProps> = ({
  options,
  message,
  keyword,
  title,
  confirmed = false,
  consumed = false,
  disabled,
  onSendMessage,
}) => {
  // 本地乐观更新：首次点击立即生效，无需等后端响应（ref 不触发重渲染，
  // 发送后父组件消息流更新会带动本组件重渲染，读取 ref 显示已确认态）
  const localConfirmedRef = useRef<boolean>(confirmed)
  const [timedOut, setTimedOut] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 未确认状态下启动 3 分钟超时计时：超时后候选区顶部出现琥珀色提醒条
  useEffect(() => {
    if (confirmed) return
    timerRef.current = setTimeout(() => setTimedOut(true), TIMEOUT_MS)
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [confirmed])

  const clearTimer = () => {
    if (timerRef.current) clearTimeout(timerRef.current)
  }

  // 点击候选：按字段名协议静默发送（后端识别企业身份跳过二次选项卡），本地置已确认
  const handleSelect = (opt: CompanyNameCandidate) => {
    clearTimer()
    localConfirmedRef.current = true
    onSendMessage?.(
      `公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`,
      true,
      { confirmed: true, consumed: true },
    )
  }

  // 点击"以上都不是"：静默发送固定短语，后端引导用户提供准确名称/信用代码
  const handleNoneOfAbove = () => {
    clearTimer()
    onSendMessage?.('以上都不是', true, { consumed: true })
  }

  return (
    <div className="space-y-2">
      {message && (
        <div className="px-4 py-2 bg-blue-50/80 border border-blue-100 rounded-lg">
          <p className="text-xs text-gray-500">{message}</p>
        </div>
      )}
      {timedOut && !localConfirmedRef.current && (
        <div className="px-3 py-2.5 rounded-lg bg-amber-50 border border-amber-200 text-xs text-amber-700">
          {TIMEOUT_TEXT}
        </div>
      )}
      <CompanyCandidatePanel
        title={title || '企业查询'}
        variant="ambiguous"
        options={options}
        keyword={keyword}
        confirmed={localConfirmedRef.current}
        consumed={consumed}
        disabled={disabled}
        onSelect={handleSelect}
        onNoneOfAbove={handleNoneOfAbove}
      />
    </div>
  )
}

export default CompanyNameSelector
