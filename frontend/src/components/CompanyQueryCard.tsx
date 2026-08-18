import React from 'react'
import type { RiskAmbiguousOption } from '../types'

// ============================================================
// Props
// ============================================================
interface CompanyQueryCardProps {
  data: Record<string, unknown>
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean
  onSendMessage?: (content: string) => void
}

// ============================================================
// 通用小组件
// ============================================================

/** 键值行 */
const KVRow: React.FC<{ label: string; value: React.ReactNode; mono?: boolean }> = ({ label, value, mono }) => (
  <div className="flex items-start justify-between gap-3 py-1.5 border-b border-gray-100 last:border-0">
    <span className="text-xs text-gray-500 shrink-0">{label}</span>
    <span className={`text-sm text-gray-800 text-right ${mono ? 'font-mono' : ''}`}>{value || '-'}</span>
  </div>
)

/** 列表项（股东/受益人/记录等） */
const ListCard: React.FC<{ items: Record<string, unknown>[]; fields: { key: string; label: string }[] }> = ({
  items,
  fields,
}) => (
  <div className="space-y-2">
    {items.map((item, idx) => (
      <div key={idx} className="bg-white/70 rounded-lg p-3 border border-gray-100">
        {fields.map((f) => (
          <div key={f.key} className="flex items-start justify-between gap-3 py-0.5">
            <span className="text-xs text-gray-500 shrink-0">{f.label}</span>
            <span className="text-sm text-gray-800 text-right">{String(item[f.key] ?? '-')}</span>
          </div>
        ))}
      </div>
    ))}
  </div>
)

/** 标签列表（tags / accounts / children） */
const ChipList: React.FC<{ label: string; values: unknown[] }> = ({ label, values }) =>
  values.length > 0 ? (
    <div className="flex items-center gap-2 flex-wrap">
      <span className="text-xs text-gray-500">{label}</span>
      {values.map((v, i) => (
        <span key={i} className="text-xs px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-100">
          {String(v)}
        </span>
      ))}
    </div>
  ) : null

// ============================================================
// 主组件
// ============================================================
const CompanyQueryCard: React.FC<CompanyQueryCardProps> = ({ data, onSendMessage, disabled }) => {
  const action = data.action as string | undefined
  const queryLabel = (data.query_label as string) || '企业信息'
  const queryType = (data.query_type as string) || ''
  // 所属任务标识（useChat 在候选事件处理时推断并持久化，重载后仍可恢复；旧消息降级）
  const taskLabel = data.task_label as string | undefined

  // ========== 未找到 / 名称歧义（候选选择） ==========
  if (action === 'not_found' || action === 'ambiguous') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    const keyword = data.keyword as string || ''
    const isAmbiguous = action === 'ambiguous'
    return (
      <div
        className={`company-query-card rounded-xl border overflow-hidden bg-gradient-to-br ${
          isAmbiguous ? 'from-amber-50 to-yellow-50 border-amber-200' : 'from-gray-50 to-slate-50 border-gray-200'
        }`}
      >
        <div className={`px-4 py-3 border-b bg-white/60 ${isAmbiguous ? 'border-amber-100' : 'border-gray-100'}`}>
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">{isAmbiguous ? '🔍' : 'ℹ️'}</span>
            {isAmbiguous
              ? taskLabel
                ? `${taskLabel} · 请确认要查询${queryLabel}的企业`
                : `请确认要查询${queryLabel}的企业`
              : '未找到匹配企业'}
          </h3>
          {keyword && (
            <p className="text-xs text-gray-500 mt-1">
              搜索到 {options?.length || 0} 家名称包含"{keyword}"的企业
            </p>
          )}
        </div>
        <div className="p-3 space-y-2">
          {options?.map((opt) => (
            <button
              key={opt.credit_code}
              onClick={() => onSendMessage?.(`帮我查一下${opt.company_name}${queryLabel}`)}
              disabled={disabled}
              className="w-full text-left px-4 py-3 rounded-lg border border-amber-200 bg-white
                         hover:bg-amber-50 hover:border-amber-300 transition-all
                         flex items-center justify-between group cursor-pointer
                         disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white disabled:hover:border-amber-200"
            >
              <div>
                <div className="text-sm font-medium text-gray-800 group-hover:text-amber-700">
                  {opt.company_name}
                </div>
                <div className="text-xs text-gray-400 font-mono mt-0.5">{opt.credit_code}</div>
              </div>
              <svg className="w-4 h-4 text-amber-400 group-hover:text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </button>
          ))}
          {/* 模糊匹配兜底：候选均不是目标企业时点击，后端引导用户提供准确名称/信用代码 */}
          <button
            onClick={() => onSendMessage?.('以上选项均不是')}
            disabled={disabled}
            className="w-full text-center px-4 py-2.5 rounded-lg border border-dashed border-gray-300 bg-gray-50
                       hover:bg-gray-100 text-sm text-gray-500 transition-all
                       disabled:opacity-50 disabled:cursor-not-allowed"
          >
            以上选项均不是
          </button>
        </div>
      </div>
    )
  }

  // ========== 查询结果 ==========
  const companyName = (data.company_name as string) || ''
  const creditCode = (data.credit_code as string) || ''
  const result = data.data as Record<string, unknown> | undefined

  const titleMap: Record<string, string> = {
    basic_info: '企业基本信息',
    shareholders: '股东信息',
    beneficiaries: '受益人信息',
    genealogy: '企业族谱',
    customs_auth: '海关认证信息',
    customs_blacklist: '海关失信名单',
    freeze_tags: '账户冻结标签',
    credit_granting: '授信信息',
    pboc_account_control: '人行账户管控信息',
  }
  const title = titleMap[queryType] || queryLabel

  const renderBody = () => {
    if (!result) return <p className="text-sm text-gray-500">暂无数据</p>
    switch (queryType) {
      case 'basic_info':
        return (
          <div className="bg-white/70 rounded-lg p-3">
            <KVRow label="法定代表人" value={String(result.legal_rep ?? '')} />
            <KVRow label="注册资本" value={String(result.registered_capital ?? '')} />
            <KVRow label="成立日期" value={String(result.establish_date ?? '')} />
            <KVRow label="注册地址" value={String(result.address ?? '')} />
            <KVRow label="所属行业" value={String(result.industry ?? '')} />
            <KVRow label="经营状态" value={String(result.status ?? '')} />
            <KVRow label="经营范围" value={String(result.business_scope ?? '')} />
          </div>
        )
      case 'shareholders':
      case 'beneficiaries': {
        const items = Array.isArray(result) ? (result as Record<string, unknown>[]) : []
        const fields =
          queryType === 'shareholders'
            ? [
                { key: 'name', label: '股东名称' },
                { key: 'type', label: '类型' },
                { key: 'ratio', label: '持股比例' },
              ]
            : [
                { key: 'name', label: '受益人' },
                { key: 'type', label: '类型' },
                { key: 'ratio', label: '受益比例' },
                { key: 'note', label: '认定说明' },
              ]
        return items.length > 0 ? (
          <ListCard items={items} fields={fields} />
        ) : (
          <p className="text-sm text-gray-500">未查询到相关记录</p>
        )
      }
      case 'genealogy':
        return (
          <div className="bg-white/70 rounded-lg p-3">
            <KVRow label="所属集团" value={String(result.group ?? '')} />
            <KVRow label="母公司" value={String(result.parent ?? '')} />
            <div className="py-1.5 border-b border-gray-100 last:border-0">
              <div className="text-xs text-gray-500 mb-1.5">子公司</div>
              {Array.isArray(result.children) && (result.children as unknown[]).length > 0 ? (
                <ChipList label="" values={result.children as unknown[]} />
              ) : (
                <span className="text-sm text-gray-800">-</span>
              )}
            </div>
          </div>
        )
      case 'customs_auth':
        return (
          <div className="bg-white/70 rounded-lg p-3">
            <KVRow label="认证状态" value={String(result.status ?? '')} />
            <KVRow label="认证编号" value={String(result.auth_code ?? '')} mono />
            <KVRow label="有效期至" value={String(result.valid_until ?? '')} />
          </div>
        )
      case 'customs_blacklist':
        return (
          <div className="space-y-2">
            <div className="bg-white/70 rounded-lg p-3">
              <KVRow
                label="名单状态"
                value={
                  result.in_list === true ? (
                    <span className="text-red-600 font-semibold">在失信名单中</span>
                  ) : (
                    <span className="text-emerald-600 font-semibold">未在失信名单</span>
                  )
                }
              />
            </div>
            {result.in_list === true && Array.isArray(result.records) && (result.records as unknown[]).length > 0 && (
              <div>
                <div className="text-xs text-gray-500 mb-1.5">失信记录</div>
                <ListCard
                  items={result.records as Record<string, unknown>[]}
                  fields={[
                    { key: 'date', label: '日期' },
                    { key: 'type', label: '名单类型' },
                    { key: 'reason', label: '原因' },
                  ]}
                />
              </div>
            )}
          </div>
        )
      case 'freeze_tags': {
        const tags = Array.isArray(result.tags) ? (result.tags as unknown[]) : []
        const accounts = Array.isArray(result.accounts) ? (result.accounts as unknown[]) : []
        return (
          <div className="bg-white/70 rounded-lg p-3 space-y-2">
            <KVRow
              label="冻结状态"
              value={
                result.has_freeze === true ? (
                  <span className="text-red-600 font-semibold">存在冻结</span>
                ) : (
                  <span className="text-emerald-600 font-semibold">无冻结记录</span>
                )
              }
            />
            {result.has_freeze === true && <ChipList label="冻结标签" values={tags} />}
            {result.has_freeze === true && <ChipList label="冻结账户" values={accounts} />}
          </div>
        )
      }
      case 'credit_granting':
        return (
          <div className="bg-white/70 rounded-lg p-3">
            <KVRow label="授信总额" value={String(result.total_limit ?? '')} />
            <KVRow label="已用额度" value={String(result.used ?? '')} />
            <KVRow label="剩余额度" value={String(result.balance ?? '')} />
            <KVRow
              label="评级"
              value={<span className="px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-100 text-xs font-medium">{String(result.rating ?? '-')}</span>}
            />
          </div>
        )
      case 'pboc_account_control': {
        const controls = Array.isArray(result.controls) ? (result.controls as Record<string, unknown>[]) : []
        const isControlled = String(result.control_type ?? '') !== '正常'
        return (
          <div className="space-y-2">
            <div className="bg-white/70 rounded-lg p-3">
              <KVRow
                label="管控状态"
                value={
                  isControlled ? (
                    <span className="text-red-600 font-semibold">{String(result.control_type ?? '-')}</span>
                  ) : (
                    <span className="text-emerald-600 font-semibold">正常（无管控）</span>
                  )
                }
              />
              <KVRow label="最近核查日期" value={String(result.last_check ?? '')} />
            </div>
            {isControlled && controls.length > 0 && (
              <div>
                <div className="text-xs text-gray-500 mb-1.5">管控记录</div>
                <ListCard
                  items={controls}
                  fields={[
                    { key: 'date', label: '日期' },
                    { key: 'type', label: '管控类型' },
                    { key: 'reason', label: '原因' },
                  ]}
                />
              </div>
            )}
          </div>
        )
      }
      default:
        return <p className="text-sm text-gray-500">暂无数据</p>
    }
  }

  return (
    <div className="company-query-card bg-gradient-to-br from-sky-50 to-blue-50 rounded-xl border border-sky-200 overflow-hidden">
      {/* 头部 */}
      <div className="px-4 py-3 border-b border-sky-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📊</span>
          {title}
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

        {renderBody()}
      </div>
    </div>
  )
}

export default CompanyQueryCard
