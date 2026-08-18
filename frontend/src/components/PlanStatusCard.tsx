import type { PlanStatusData, PlanStatusStep, PlanStepStatus } from '../types'

interface PlanStatusCardProps {
  data: PlanStatusData
}

/** 技能名 → 面板展示名映射（其余保持原样） */
const SKILL_LABELS: Record<string, string> = {
  chat: '对话问答',
  generate_report: '生成尽调报告',
  check_company_risk: '风险预查',
  query_due_diligence_reports: '历史尽调报告查询',
  verify_business_license: '执照信息核实',
}

/** 步骤状态 → 徽章样式与文案映射 */
const STATUS_BADGES: Record<PlanStepStatus, { text: string; cls: string }> = {
  PENDING: { text: '待执行', cls: 'bg-gray-100 text-gray-500' },
  WAITING_INPUT: { text: '等待补充信息', cls: 'bg-blue-100 text-blue-600' },
  RUNNING: { text: '执行中', cls: 'bg-blue-600 text-white animate-pulse' },
  DONE: { text: '已完成', cls: 'bg-green-100 text-green-600' },
  FAILED: { text: '失败', cls: 'bg-red-100 text-red-600' },
  WAITING_EXTERNAL: { text: '等待外部完成', cls: 'bg-purple-100 text-purple-600' },
}

/** 判断某一步是否正处于执行/等待阶段（面板中高亮） */
function isStepActive(step: PlanStatusStep, index: number, data: PlanStatusData): boolean {
  if (step.status === 'RUNNING' || step.status === 'WAITING_INPUT' || step.status === 'WAITING_EXTERNAL') {
    return true
  }
  return data.index === index && step.status === 'PENDING'
}

/**
 * 任务规划面板卡片（plan_status 事件渲染）：
 * 头部状态行 + 步骤列表（含状态徽章与当前步骤高亮）。
 * 顶部状态栏（PlanStatusBar）与对话流内此面板均从同一份快照派生。
 */
const PlanStatusCard: React.FC<PlanStatusCardProps> = ({ data }) => {
  const { steps, index, confirming, suspended, summary } = data
  const total = steps?.length || 0
  // 全部步骤已完成（如 report-complete finished 注入的终态快照）
  const allDone = total > 0 && steps.every((s) => s.status === 'DONE')

  // 顶部状态文案：挂起 > 全部完成 > 等待确认 > 收尾汇总 > 执行中
  let statusText = ''
  let statusColor = 'text-gray-600'
  if (suspended) {
    statusText = '已挂起（可穿插其他任务）'
    statusColor = 'text-amber-600'
  } else if (allDone) {
    statusText = '全部任务已完成'
    statusColor = 'text-green-700'
  } else if (confirming && total > 0) {
    statusText = `等待确认下一步（第 ${index + 1}/${total} 步已完成）`
    statusColor = 'text-blue-600'
  } else if (summary) {
    statusText = summary
    statusColor = 'text-green-700'
  } else if (total > 0) {
    const cur = steps[Math.min(index, total - 1)]
    const curName = cur ? SKILL_LABELS[cur.skill] || cur.skill : ''
    statusText = `第 ${index + 1}/${total} 步：${curName} 执行中`
    statusColor = 'text-blue-600'
  }

  return (
    <div className="bg-gradient-to-br from-indigo-50 to-blue-50 rounded-xl border border-indigo-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-indigo-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📋</span>
          任务规划
          <span className={`ml-auto text-xs font-normal ${statusColor}`}>{statusText}</span>
        </h3>
      </div>
      <div className="px-4 py-3 space-y-2">
        {steps.map((step, idx) => {
          const badge = STATUS_BADGES[step.status] || STATUS_BADGES.PENDING
          const active = isStepActive(step, idx, data)
          const name = SKILL_LABELS[step.skill] || step.skill
          return (
            <div
              key={`${idx}-${step.skill}`}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg border transition-colors ${
                active
                  ? 'bg-white border-indigo-300 shadow-sm'
                  : 'bg-white/50 border-indigo-100'
              }`}
            >
              <span
                className={`flex-shrink-0 w-6 h-6 rounded-full flex items-center justify-center text-xs font-semibold ${
                  active ? 'bg-indigo-600 text-white' : 'bg-gray-200 text-gray-500'
                }`}
              >
                {idx + 1}
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium text-gray-800 truncate">{name}</div>
                {step.summary && (
                  <div className="text-xs text-gray-500 truncate">{step.summary}</div>
                )}
              </div>
              <span
                className={`flex-shrink-0 text-xs px-2 py-0.5 rounded-full font-medium ${badge.cls}`}
              >
                {badge.text}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default PlanStatusCard
