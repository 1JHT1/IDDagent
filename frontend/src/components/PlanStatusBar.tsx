import type { PlanStatusData } from '../types'

interface PlanStatusBarProps {
  data: PlanStatusData | null
}

/** 技能名 → 中文展示名（与 PlanStatusCard / 后端 SkillRegistry.displayName 保持一致） */
const SKILL_LABELS: Record<string, string> = {
  chat: '对话问答',
  generate_report: '生成尽调报告',
  check_company_risk: '风险预查',
  query_due_diligence_reports: '历史尽调报告查询',
  verify_business_license: '执照信息核实',
  query_company_basic_info: '基本信息',
  query_shareholder_info: '股东信息',
  query_beneficiary_info: '受益人信息',
  query_company_genealogy: '企业族谱',
  query_customs_auth: '海关认证信息',
  query_customs_blacklist: '海关失信名单信息',
  query_account_freeze_tag: '账户冻结标签',
  query_credit_granting: '授信信息',
  query_pboc_account_control: '人行账户管控信息',
}

/**
 * 对话区顶部常驻规划状态栏：
 * 从消息流最后一条 plan_status 快照派生，简洁展示当前规划进度。
 * 快照为空 / 规划未激活且未挂起时返回 null（不渲染）。
 */
const PlanStatusBar: React.FC<PlanStatusBarProps> = ({ data }) => {
  if (!data) return null
  if (!data.active && !data.suspended) return null

  const total = data.steps?.length || 0
  // 全部步骤已完成（如 report-complete finished 注入的终态快照）
  const allDone = total > 0 && data.steps.every((s) => s.status === 'DONE')
  const cur = data.steps?.[Math.min(data.index, Math.max(total - 1, 0))]
  const curName = cur ? (SKILL_LABELS[cur.skill] || cur.skill) : ''

  let text = ''
  let dotCls = 'bg-blue-500'
  if (data.suspended) {
    text = '任务规划已挂起'
    dotCls = 'bg-amber-500'
  } else if (allDone) {
    text = '任务规划已完成'
    dotCls = 'bg-green-500'
  } else if (data.summary) {
    text = data.summary
    dotCls = 'bg-green-500'
  } else if (data.confirming && total > 0) {
    text = `等待确认：第 ${data.index + 1}/${total} 步`
    dotCls = 'bg-blue-500'
  } else if (total > 0) {
    text = `第 ${data.index + 1}/${total} 步执行中${curName ? `：${curName}` : ''}`
    dotCls = 'bg-blue-500 animate-pulse'
  }

  return (
    <div className="h-10 bg-indigo-50/80 border-b border-indigo-100 flex items-center px-6 gap-2 flex-shrink-0">
      <span className={`w-2 h-2 rounded-full ${dotCls}`} />
      <span className="text-xs font-medium text-indigo-700">📋 任务规划</span>
      <span className="text-xs text-gray-500 truncate">{text}</span>
    </div>
  )
}

export default PlanStatusBar
