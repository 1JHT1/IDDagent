import React, { useEffect, useState } from 'react'
import type { CompanyNameCandidate } from '../types'
import CompanyCandidatePanel, { resolveFunctionName } from './CompanyCandidatePanel'

// ============================================================
// 第 2 层：通用选择器 CompanyNameSelector（公司名候选事件专用）
// 两次点击协议：
// 1. 首次点击候选：静默发送候选确认格式"公司：{name}\n统一信用代码：{code}"，
//    后端按字段名解析企业身份跳过二次选项卡直接查询；本地立即置 confirmed 态
//    （乐观更新，无需等后端响应）。
// 2. 已确认后再次点击：发送"帮我查一下{公司名}{查询功能标签}"直接发起对应功能
//    查询（支持手滑选错后纠错，后端按新意图穿插挂起当前管道）。
// 超时提醒：未确认时 3 分钟未选择，候选区顶部出现琥珀色提示。
// 空 options：直接渲染 not_found 空态（无"以上都不是"）。
// ============================================================

interface CompanyNameSelectorProps {
  options: CompanyNameCandidate[]
  message?: string
  keyword?: string
  /** 所属任务标识（多意图管道中让用户清楚"是哪个任务在询问"），显示在卡片头部 */
  taskLabel?: string
  /** 查询功能标签（二次点击发起查询的协议文案：帮我查一下{公司名}{queryLabel}） */
  queryLabel?: string
  /** 技能名（标题兜底：无 task_label/query_label 时按技能解析功能名） */
  skillName?: string
  /** 已确认过候选（后端落盘 + 前端乐观更新），组件重建（刷新/切换会话）后仍保持 */
  confirmed?: boolean
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean
  onSendMessage?: (content: string, silent?: boolean) => void
}

const CompanyNameSelector: React.FC<CompanyNameSelectorProps> = ({
  options,
  message,
  keyword,
  taskLabel,
  queryLabel,
  skillName,
  confirmed,
  disabled,
  onSendMessage,
}) => {
  // 超时提醒：未确认时 3 分钟未选择，候选区顶部出现琥珀色提示，防止对话"静默挂起"观感
  const [timedOut, setTimedOut] = useState(false)
  useEffect(() => {
    if (confirmed || !options || options.length === 0) return
    const timer = setTimeout(() => setTimedOut(true), 3 * 60 * 1000)
    return () => clearTimeout(timer)
  }, [confirmed, options])

  // 查询功能标签兜底：candidates 事件后端仅注入 _skill_name（无 query_label），
  // 按技能名解析功能名（历史尽调报告/风险预查等），保证两次点击协议文案完整
  const effectiveQueryLabel = queryLabel || resolveFunctionName(skillName)

  const title = taskLabel || effectiveQueryLabel

  // 空 options：直接渲染 not_found 空态（无"以上都不是"）
  if (!options || options.length === 0) {
    return (
      <CompanyCandidatePanel
        title={title}
        variant="not_found"
        notFoundMessage={message || '未找到匹配企业'}
        disabled={disabled}
      />
    )
  }

  return (
    <CompanyCandidatePanel
      title={title}
      variant="ambiguous"
      options={options}
      keyword={keyword}
      confirmed={confirmed}
      notice={
        !confirmed && timedOut ? (
          '原查询仍挂起等待确认，请选择候选企业或点击「以上都不是」重新描述'
        ) : undefined
      }
      disabled={disabled}
      onSelect={(opt) => {
        if (confirmed) {
          // 已确认后再次点击：直接发起对应功能查询（支持手滑选错后纠错）
          onSendMessage?.(`帮我查一下${opt.company_name}${effectiveQueryLabel}`, true)
        } else {
          // 首次点击：静默发送候选确认格式，后端按字段名解析企业身份（跳过二次选项卡）
          onSendMessage?.(`公司：${opt.company_name}\n统一信用代码：${opt.credit_code}`, true)
        }
      }}
      onNoneOfAbove={() => onSendMessage?.('以上都不是', true)}
    />
  )
}

export default CompanyNameSelector
