import React from 'react'

interface ResumeConfirmCardProps {
  /** 卡片文案（如"已为您完成穿插的任务。是否需要回到穿插进来前的那一步..."） */
  text: string
  /** 回到的步骤序号（1-based） */
  stepIndex?: number | string
  /** 规划总步数 */
  totalSteps?: number | string
  /** 回到的步骤描述 */
  stepDesc?: string
  /** 该卡片已被消费（已点击恢复/不需要）时禁用，避免旧卡误操作 */
  disabled?: boolean
  onSendMessage?: (content: string) => void
}

/**
 * 穿插恢复确认卡片（ResumeConfirm）：穿插的新意图执行完成后，询问用户
 * 是否需要回到穿插进来前的那一步继续之前的任务规划。
 * 点击"回到之前的任务"发送 {"action":"回到之前的任务"}，点击"不需要"
 * 发送 {"action":"不需要"}，后端据此恢复挂起规划或将其丢弃
 * （后端兼容英文旧协议 plan_resume_yes/plan_resume_no，历史消息不影响）。
 */
const ResumeConfirmCard: React.FC<ResumeConfirmCardProps> = ({
  text,
  stepIndex,
  totalSteps,
  stepDesc,
  onSendMessage,
  disabled,
}) => {
  const stepLabel = (stepIndex != null && totalSteps != null)
    ? `${stepIndex}/${totalSteps}`
    : '';

  return (
    <div className="bg-gradient-to-br from-amber-50 to-orange-50 rounded-xl border border-amber-200 overflow-hidden max-w-md">
      <div className="px-4 py-3 border-b border-amber-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🔄</span>
          穿插任务已完成
        </h3>
      </div>
      {text && (
        <div className="px-4 py-3">
          <p className="text-sm text-gray-700 leading-relaxed">{text}</p>
          {stepLabel && (
            <p className="text-xs text-amber-700 mt-2 font-medium">
              回到第 {stepLabel} 步{stepDesc ? `：${stepDesc}` : ''}
            </p>
          )}
        </div>
      )}
      <div className="px-4 pb-3 flex gap-2">
        <button
          onClick={() => onSendMessage?.(JSON.stringify({ action: '回到之前的任务' }))}
          disabled={disabled}
          className="flex-1 text-center px-4 py-2.5 rounded-lg border border-amber-400 bg-amber-500 text-white font-medium text-sm hover:bg-amber-600 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-amber-500"
        >
          回到之前的任务
        </button>
        <button
          onClick={() => onSendMessage?.(JSON.stringify({ action: '不需要' }))}
          disabled={disabled}
          className="flex-1 text-center px-4 py-2.5 rounded-lg border border-gray-200 bg-white text-gray-600 font-medium text-sm hover:bg-gray-50 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
        >
          不需要
        </button>
      </div>
    </div>
  )
}

export default ResumeConfirmCard
