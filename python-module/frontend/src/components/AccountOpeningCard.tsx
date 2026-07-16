import React from 'react'
import type { RiskAmbiguousOption } from '../types'

interface AccountOpeningCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string) => void
}

const AccountOpeningCard: React.FC<AccountOpeningCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined
  const companyName = (data.company_name as string) || ''
  const creditCode = (data.credit_code as string) || ''
  const status = (data.status as string) || ''
  const uploadUrl = (data.upload_url as string) || ''
  const previewUrl = (data.preview_url as string) || ''
  const submittedUrl = (data.submitted_url as string) || ''
  const requiredDocs = (data.required_documents as string[]) || []
  const message = (data.message as string) || ''

  // ========== 名称歧义 ==========
  if (action === 'ambiguous') {
    const options = (data.options as RiskAmbiguousOption[]) || []
    const keyword = (data.keyword as string) || ''
    return (
      <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl border border-blue-200 overflow-hidden">
        <div className="px-4 py-3 border-b border-blue-100 bg-white/60">
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">🔍</span>
            请确认要开户的企业
          </h3>
          <p className="text-xs text-gray-500 mt-1">
            搜索到 {options.length} 家与「{keyword}」匹配的企业
          </p>
        </div>
        <div className="p-3 space-y-2">
          {options.map((opt) => (
            <button
              key={opt.credit_code}
              onClick={() =>
                onSendMessage?.(
                  `客户已同意办理开户，企业名称为${opt.company_name}，统一信用代码${opt.credit_code}，请协助办理`
                )
              }
              className="w-full text-left px-4 py-3 rounded-lg border border-blue-200 bg-white
                         hover:bg-blue-50 hover:border-blue-300 transition-all
                         flex items-center justify-between group cursor-pointer"
            >
              <div>
                <div className="text-sm font-medium text-gray-800 group-hover:text-blue-700">
                  {opt.company_name}
                </div>
                <div className="text-xs text-gray-400 font-mono mt-0.5">
                  {opt.credit_code}
                </div>
              </div>
              <svg className="w-4 h-4 text-blue-400 group-hover:text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </button>
          ))}
        </div>
      </div>
    )
  }

  // ========== 未找到 ==========
  if (action === 'not_found') {
    return (
      <div className="bg-gradient-to-br from-gray-50 to-slate-50 rounded-xl border border-gray-200 overflow-hidden">
        <div className="p-4">
          <p className="text-gray-500 text-sm text-center">
            {message || '请先指定要开户的企业'}
          </p>
        </div>
      </div>
    )
  }

  // ========== 上传状态 ==========
  if (status === 'upload') {
    return (
      <div className="bg-gradient-to-br from-emerald-50 to-teal-50 rounded-xl border border-emerald-100 overflow-hidden">
        <div className="px-4 py-3 border-b border-emerald-100 bg-white/60">
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">🏦</span>
            对公账户开户
          </h3>
        </div>
        <div className="p-4 space-y-4">
          {/* 企业信息 */}
          <div>
            <div className="text-xs text-gray-500 mb-1">开户企业</div>
            <div className="text-sm font-semibold text-gray-800">{companyName}</div>
            {creditCode && (
              <div className="text-xs text-gray-400 font-mono mt-0.5">{creditCode}</div>
            )}
          </div>

          {/* 所需资料清单 */}
          <div className="bg-white/70 rounded-lg p-3 border border-emerald-100">
            <div className="text-xs font-semibold text-gray-600 mb-2">📋 所需资料</div>
            <ul className="space-y-1.5">
              {requiredDocs.map((doc, idx) => (
                <li key={idx} className="text-xs text-gray-500 flex items-start gap-2">
                  <span className="text-emerald-500 mt-0.5">•</span>
                  {doc}
                </li>
              ))}
            </ul>
          </div>

          {/* 上传链接 */}
          {uploadUrl && (
            <a
              href={uploadUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="block text-center px-4 py-3 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-medium rounded-lg transition-colors"
            >
              📤 上传资料并开始开户
            </a>
          )}
        </div>
      </div>
    )
  }

  // ========== 预览状态 ==========
  if (status === 'preview') {
    return (
      <div className="bg-gradient-to-br from-indigo-50 to-violet-50 rounded-xl border border-indigo-100 overflow-hidden">
        <div className="px-4 py-3 border-b border-indigo-100 bg-white/60">
          <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <span className="text-lg">🏦</span>
            对公账户开户 - 信息已预填
          </h3>
        </div>
        <div className="p-4 space-y-3">
          <div className="text-sm text-gray-700">
            <strong>{companyName}</strong> 的开户信息已由系统自动预填完成。
          </div>
          <div className="text-xs text-gray-500">
            包含：企业信息、账户信息、尽职调查、产品签约四大模块。请仔细核对后提交。
          </div>
          {previewUrl && (
            <a
              href={previewUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="block text-center px-4 py-3 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium rounded-lg transition-colors"
            >
              📝 预览并确认开户信息
            </a>
          )}
        </div>
      </div>
    )
  }

  // ========== 已提交状态 ==========
  if (status === 'submitted') {
    return (
      <div className="bg-gradient-to-br from-green-50 to-emerald-50 rounded-xl border border-green-200 overflow-hidden">
        <div className="px-4 py-3 border-b border-green-100 bg-white/60">
          <h3 className="text-sm font-semibold text-green-800 flex items-center gap-2">
            <span className="text-lg">✅</span>
            对公账户开户 - 申请已提交
          </h3>
        </div>
        <div className="p-4 space-y-3">
          <div className="bg-green-50 rounded-lg p-3 border border-green-200">
            <div className="text-sm font-semibold text-green-800">{companyName}</div>
            <div className="text-xs text-green-600 mt-1">开户申请已成功提交，信息已锁定，不可修改。</div>
          </div>
          {submittedUrl && (
            <a
              href={submittedUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="block text-center px-4 py-3 bg-green-600 hover:bg-green-700 text-white text-sm font-medium rounded-lg transition-colors"
            >
              📋 查看已提交的开户申请
            </a>
          )}
        </div>
      </div>
    )
  }

  // ========== 默认 ==========
  return (
    <div className="bg-gradient-to-br from-gray-50 to-slate-50 rounded-xl border border-gray-200 overflow-hidden">
      <div className="p-4">
        <p className="text-gray-500 text-sm text-center">
          {message || '对公账户开户处理中...'}
        </p>
      </div>
    </div>
  )
}

export default AccountOpeningCard
