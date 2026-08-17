import React from 'react';
import type { PipelineExtra } from '../types';

interface TaskProgressCardProps {
  /** 任务清单卡片数据（消息 extra，action='pipeline'） */
  data: PipelineExtra;
}

/**
 * 多意图任务清单卡片：按对话流时间顺序出现，而非单卡原地更新（data.kind）：
 * - plan：初始规划卡（首次规划时仅出现一次，展示完整任务列表）
 * - switch：任务切换卡（每进入新的一级任务时出现，轻量展示已完成 + 当前任务，隐藏待办）
 * - complete：最终完成卡（全部任务完成后汇总"N 项任务已完成"，形成闭环）
 * 风格与结果卡片统一；卡片内提供进度描述文字（"正在执行第 x/n 项：xxx"）
 * 让用户一眼看清当前执行状态；操作提示（如"请上传营业执照图片"）由后端
 * 以文本气泡返回，卡片内不重复展示。
 */
const TaskProgressCard: React.FC<TaskProgressCardProps> = ({ data }) => {
  const { plan, total, currentOrder, paused, completed, kind } = data;
  if (!plan || plan.length === 0) return null;

  // total 以完整清单长度兜底：历史会话持久化的卡片或后端 resume 场景下
  // total 可能小于清单长度，统一取最大值避免任务行被错误隐藏、进度序号错乱
  const safeTotal = Math.max(total, plan.length);
  // 暂停/已完成时当前任务序号展示不回退（0 = 尚未开始，兜底显示第 1 项）
  const displayOrder = Math.min(Math.max(currentOrder, 1), safeTotal);

  // ========== 最终完成卡（kind=complete）：绿色汇总卡 ==========
  // 注意：仅 kind=complete 渲染绿色卡；completed=true 的非 complete 卡（执行计划卡/
  // 任务切换卡）走下方蓝色完成态分支，避免中段卡片变绿与末尾完成卡语义重复
  if (kind === 'complete') {
    return (
      <div className="rounded-xl border overflow-hidden bg-gradient-to-br from-green-50 to-emerald-50 border-green-200">
        <div className="px-4 py-3 border-b border-green-100 bg-white/60">
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">✅</span>
            {safeTotal} 项任务已完成
          </h3>
        </div>
        <div className="p-3 space-y-2">
          {plan.map((task) => (
            <div key={task.skill} className="flex items-center gap-2 text-sm text-green-700">
              <span className="w-4 h-4 rounded-full bg-green-500 text-white flex items-center justify-center text-[10px] flex-shrink-0">
                ✓
              </span>
              <span>{task.label}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // ========== 初始规划卡 / 任务切换卡（plan / switch） ==========
  const isSwitch = kind === 'switch';
  // 完成态（completed=true 的非 complete 卡，如报告生成完成后由前端同步更新的
  // 执行计划卡/任务切换卡）：蓝色卡展示"已完成"状态，与末尾绿色完成卡语义一致
  const isAllDone = completed === true;

  // 状态徽章：已完成 > 暂停等待补充 > 正常进度
  const badge = isAllDone
    ? { text: '已完成', cls: 'bg-green-100 text-green-700' }
    : paused
      ? { text: '等待补充信息', cls: 'bg-amber-100 text-amber-700' }
      : { text: `第 ${displayOrder}/${safeTotal} 项`, cls: 'bg-blue-100 text-blue-700' };

  // 进度描述文字：让用户一眼看清"当前执行到哪一项、在干什么"。
  // 注意：卡片内只放进度状态描述；"请上传营业执照图片"等操作提示仍由后端文本气泡
  // 承载、卡片内不重复（与此前"操作提示只出现在气泡一处"的决策一致）。
  let progressText = '';
  // 到达此处必为 plan/switch 形态（complete 分支已在前面提前返回）
  // 优先按 order 精确匹配；order 缺失/错乱（如恢复路径兑底重建的残缺清单）时
  // 按下标兑底，避免标签退化为"第 X 项任务"占位
  const currentTask = plan.find((t) => t.order === displayOrder) ?? plan[displayOrder - 1];
  const currentLabel = currentTask?.label || `第 ${displayOrder} 项任务`;
  if (isAllDone) {
    progressText = `已完成 ${safeTotal} 项任务`;
  } else if (paused) {
    // 任务切换卡暂停时保留"已完成 X 项"的进度反馈（如"已完成 1 项，第 2/2 项…"），
    // 让用户看到前序任务已完成，而不是只看"等待补充信息"
    progressText = isSwitch && displayOrder > 1
      ? `已完成 ${displayOrder - 1} 项，第 ${displayOrder}/${safeTotal} 项「${currentLabel}」等待补充信息`
      : `第 ${displayOrder}/${safeTotal} 项「${currentLabel}」等待补充信息`;
  } else if (isSwitch) {
    progressText =
      displayOrder > 1
        ? `已完成 ${displayOrder - 1} 项，正在执行第 ${displayOrder}/${safeTotal} 项：${currentLabel}`
        : `正在执行第 ${displayOrder}/${safeTotal} 项：${currentLabel}`;
  } else if (currentOrder === 0) {
    progressText = `共 ${safeTotal} 项任务待执行`;
  } else {
    progressText = `正在执行第 ${displayOrder}/${safeTotal} 项：${currentLabel}`;
  }

  return (
    <div className="rounded-xl border overflow-hidden bg-gradient-to-br from-blue-50 to-indigo-50 border-blue-200">
      {/* 头部：与其他结果卡片统一（标题 + 状态徽章） */}
      <div className="px-4 py-3 border-b border-blue-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📋</span>
          {isSwitch ? '任务进度' : '执行计划'}
          <span className={`ml-auto inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${badge.cls}`}>
            {badge.text}
          </span>
        </h3>
      </div>

      {/* 进度描述行：明确告知当前执行状态（未开始 / 进行中 / 等待补充） */}
      {progressText && (
        <div className="px-4 py-2 bg-blue-50/70 border-b border-blue-100 text-xs font-medium text-blue-700">
          {progressText}
        </div>
      )}

      {/* 任务列表：switch 卡只展示已完成 + 当前任务（"x/total → order/total" 已足够建立上下文） */}
      <div className="p-3 space-y-2">
        {plan.map((task) => {
          // 完成态卡片全部任务视为已完成（✓ 徽标），不保留"进行中"行
          const done = isAllDone || task.order < displayOrder;
          const active = !isAllDone && task.order === displayOrder;
          // 切换卡隐藏灰色待办项，避免重复完整列表
          if (isSwitch && !done && !active) return null;
          return (
            <div
              key={task.skill}
              className={`flex items-center gap-2 text-sm ${
                done ? 'text-green-700' : active ? 'text-blue-800' : 'text-gray-400'
              }`}
            >
              {done ? (
                <span className="w-4 h-4 rounded-full bg-green-500 text-white flex items-center justify-center text-[10px] flex-shrink-0">
                  ✓
                </span>
              ) : active ? (
                <span className="w-4 h-4 rounded-full border-2 border-blue-500 border-t-transparent animate-spin flex-shrink-0" />
              ) : (
                <span className="w-4 h-4 rounded-full border border-gray-300 flex-shrink-0" />
              )}
              <span className={`font-mono text-xs ${done ? 'text-green-500' : active ? 'text-blue-500' : 'text-gray-400'}`}>
                {task.order}/{safeTotal}
              </span>
              <span className={active ? 'font-medium' : ''}>{task.label}</span>
              {done && <span className="text-xs text-green-500">已完成</span>}
              {active && paused && <span className="text-xs text-amber-600">等待补充</span>}
              {active && !paused && <span className="text-xs text-blue-500">进行中</span>}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default TaskProgressCard;
