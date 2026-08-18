import React from 'react'

interface PlanConfirmCardProps {
  /** 卡片文案（如"第 1/2 步已完成，是否继续执行第 2 步（风险预查（小米））？"） */
  text: string
  /** 当前已完成步骤（1-based） */
  currentStep?: number | string
  /** 规划总步数 */
  totalSteps?: number | string
  /** 下一步描述 */
  nextStep?: string
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不可再点击 */
  disabled?: boolean
  onSendMessage?: (content: string) => void
}

/**
 * 步骤间确认卡片（StepConfirm）：任务规划每步真正结束后暂停，
 * 询问用户是否继续下一步。点击"继续"发送 {"action":"plan_continue"}，
 * 点击"结束"发送 {"action":"plan_stop"}，后端据此推进下一步或收尾。
 */
const PlanConfirmCard: React.FC<PlanConfirmCardProps> = ({
  text,
  currentStep,
  totalSteps,
  nextStep,
  onSendMessage,
  disabled,
}) => {
  const stepLabel = (currentStep != null && totalSteps != null)
    ? `${currentStep}/${totalSteps}`
    : '';

  return (
    <div className="bg-gradient-to-br from-emerald-50 to-teal-50 rounded-xl border border-emerald-200 overflow-hidden max-w-md">
      <div className="px-4 py-3 border-b border-emerald-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">✅</span>
          第 {stepLabel || 'x'} 步已完成
        </h3>
      </div>
      {text && (
        <div className="px-4 py-3">
          <p className="text-sm text-gray-700 leading-relaxed">{text}</p>
          {nextStep && (
            <p className="text-xs text-emerald-700 mt-2 font-medium">
              下一步：{nextStep}
            </p>
          )}
        </div>
      )}
      <div className="px-4 pb-3 flex gap-2">
        <button
          onClick={() => onSendMessage?.(JSON.stringify({ action: 'plan_continue' }))}
          disabled={disabled}
          className="flex-1 text-center px-4 py-2.5 rounded-lg border border-emerald-400 bg-emerald-600 text-white font-medium text-sm hover:bg-emerald-700 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-emerald-600"
        >
          继续执行下一步
        </button>
        <button
          onClick={() => onSendMessage?.(JSON.stringify({ action: 'plan_stop' }))}
          disabled={disabled}
          className="flex-1 text-center px-4 py-2.5 rounded-lg border border-gray-200 bg-white text-gray-600 font-medium text-sm hover:bg-gray-50 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
        >
          结束任务
        </button>
      </div>
    </div>
  )
}

export default PlanConfirmCard
