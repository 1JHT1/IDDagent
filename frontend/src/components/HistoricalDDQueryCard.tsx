import React from 'react'
import type { HistoricalDDReport } from '../types'

interface HistoricalDDQueryCardProps {
  data: Record<string, unknown>
  onSendMessage?: (content: string) => void
}

const HistoricalDDQueryCard: React.FC<HistoricalDDQueryCardProps> = ({ data, onSendMessage }) => {
  const action = data.action as string | undefined
  const companyName = (data.company_name as string) || ''
  const creditCode = (data.credit_code as string) || ''
  const totalCount = (data.total_count as number) || 0
  const records = (data.records as HistoricalDDReport[]) || []
  const message = (data.message as string) || ''
  const BACKEND_URL = 'http://localhost:8000'

  // ========== 未找到 ==========
  if (action === 'not_found') {
    return (
      <div className="bg-gradient-to-br from-gray-50 to-slate-50 rounded-xl border border-gray-200 overflow-hidden">
        <div className="p-4">
          <p className="text-gray-500 text-sm text-center">{message || '未查询到相关历史尽调报告'}</p>
        </div>
      </div>
    )
  }

  // ========== 查询结果 ==========
  return (
    <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl border border-blue-200 overflow-hidden">
      {/* 头部 */}
      <div className="px-4 py-3 border-b border-blue-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📋</span>
          历史尽调报告
        </h3>
        <div className="flex items-center gap-3 mt-1 text-xs text-gray-500">
          {companyName && <span>企业：{companyName}</span>}
          {creditCode && <span className="font-mono">{creditCode}</span>}
          <span>共 {totalCount} 条记录</span>
        </div>
      </div>

      {/* 表格 */}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-blue-100/50 border-b border-blue-200">
              <th className="px-3 py-2.5 text-left text-xs font-semibold text-gray-600 w-10">序号</th>
              <th className="px-3 py-2.5 text-left text-xs font-semibold text-gray-600">机构</th>
              <th className="px-3 py-2.5 text-left text-xs font-semibold text-gray-600">报告名称</th>
              <th className="px-3 py-2.5 text-center text-xs font-semibold text-gray-600 w-52">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-blue-100">
            {records.map((report, idx) => (
              <tr key={report.report_id} className="hover:bg-blue-50/50 transition-colors">
                <td className="px-3 py-2.5 text-xs text-gray-400">{idx + 1}</td>
                <td className="px-3 py-2.5">
                  <div className="text-sm font-medium text-gray-800">{report.institution}</div>
                </td>
                <td className="px-3 py-2.5">
                  <div className="text-sm font-medium text-gray-800">{report.name}</div>
                </td>
                <td className="px-3 py-2.5">
                  <div className="flex items-center justify-center gap-1">
                    <ActionButton
                      label="查看"
                      title="查看详情"
                      href={`${BACKEND_URL}/h5/dd-report-detail.html?report_id=${report.report_id}`}
                    />
                    <ActionButton
                      label="编辑"
                      title="编辑报告"
                      href={`${BACKEND_URL}/h5/dd-report-edit.html?report_id=${report.report_id}`}
                    />
                    <ActionButton
                      label="下载"
                      title={report.status === 'completed' ? '下载报告(PDF)' : '报告未完成，暂不可下载'}
                      href={report.status === 'completed' ? `${BACKEND_URL}/h5/dd-report-detail.html?report_id=${report.report_id}&download_pdf=1` : undefined}
                      disabled={report.status !== 'completed'}
                      />
                    <ActionButton
                      label="附件"
                      title="查看附件"
                      href={`${BACKEND_URL}/h5/dd-report-attachments.html?report_id=${report.report_id}`}
                    />
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/** 操作按钮子组件 */
const ActionButton: React.FC<{
  label: string
  title: string
  href?: string
  disabled?: boolean
}> = ({ label, title, href, disabled }) => {
  if (disabled || !href) {
    return (
      <span
        className="inline-flex items-center px-2.5 py-1.5 text-xs rounded-md border border-gray-200 bg-gray-50 text-gray-300 cursor-not-allowed"
        title={title}
      >
        {label}
      </span>
    )
  }
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center px-2.5 py-1.5 text-xs rounded-md border border-blue-200 bg-white
                 text-blue-600 hover:bg-blue-50 hover:border-blue-300 transition-colors"
      title={title}
    >
      {label}
    </a>
  )
}

export default HistoricalDDQueryCard
