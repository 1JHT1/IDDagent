import React from 'react'
import type { RiskAmbiguousOption } from '../types'
import CompanyCandidatePanel from './CompanyCandidatePanel'

// ============================================================
// Props
// ============================================================
interface CompanyQueryCardProps {
  data: Record<string, unknown>
  /** 穿插区域已结束（穿插确认卡片已消费）时禁用，不再可点击执行 */
  disabled?: boolean
  /** 发送回调：silent=true 时不展示用户气泡（候选点击直接进入查询）；extra 随请求透传（如 consumed 落盘） */
  onSendMessage?: (content: string, silent?: boolean, extra?: Record<string, unknown>) => void
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

  // ========== 未找到 / 名称歧义（复用第1层统一候选面板） ==========
  if (action === 'not_found' || action === 'ambiguous') {
    const options = data.options as RiskAmbiguousOption[] | undefined
    const keyword = data.keyword as string || ''
    // 形态由候选数量决定（与 action 解耦）：有候选 → 候选确认卡（琥珀色 + "以上都不是"）；
    // 无候选 → 未找到企业空态卡（灰色）。后端 not_found 也可能携带相似企业候选（模糊匹配）
    const hasOptions = Array.isArray(options) && options.length > 0
    return (
      <CompanyCandidatePanel
        title={queryLabel}
        variant={hasOptions ? 'ambiguous' : 'not_found'}
        options={options}
        keyword={keyword}
        message={(data.message as string) || '未找到匹配企业'}
        disabled={disabled}
        consumed={data.consumed === true}
        // 点击候选：按企业查询协议文本静默发送（功能名来自后端 query_label）
        onSelect={(opt) =>
          onSendMessage?.(
            `帮我查一下${opt.company_name}${queryLabel}`,
            true,
            { consumed: true },
          )
        }
        // 候选均不是目标企业：静默发送固定短语，后端引导用户提供准确名称/信用代码
        onNoneOfAbove={() => onSendMessage?.('以上都不是', true, { consumed: true })}
      />
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
