import React, { useState, useRef } from 'react'
import type { CustomerSource, CustomerDetail } from '../types'
import { downloadCustomerTemplate, uploadCustomerList } from '../api/agent'

// ============================================================
// Props
// ============================================================
interface PotentialCustomerCardProps {
  /** 数据（summary 或 detail） */
  data: Record<string, unknown>
  /** 点击客户数量后触发详情请求 */
  onRequestDetail?: (sourceId: string, sourceName: string) => void
  /** 发送消息回调（上传成功后自动刷新） */
  onSendMessage?: (content: string) => void
}

// ============================================================
// 上传弹窗组件
// ============================================================
interface UploadModalProps {
  onClose: () => void
  onSuccess: () => void
}

const UploadModal: React.FC<UploadModalProps> = ({ onClose, onSuccess }) => {
  const [mode, setMode] = useState<'overwrite' | 'append'>('overwrite')
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleDownloadTemplate = async () => {
    setDownloading(true)
    try {
      const blob = await downloadCustomerTemplate()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = '客户清单模板.xlsx'
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      setMessage({ type: 'error', text: '下载模板失败，请重试' })
    } finally {
      setDownloading(false)
    }
  }

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    // 验证文件类型
    if (!file.name.endsWith('.xlsx') && !file.name.endsWith('.xls')) {
      setMessage({ type: 'error', text: '请上传 .xlsx 或 .xls 格式的 Excel 文件' })
      setSelectedFile(null)
      return
    }

    setSelectedFile(file)
    setMessage(null)
  }

  const handleUpload = async () => {
    if (!selectedFile) {
      setMessage({ type: 'error', text: '请先选择 Excel 文件' })
      return
    }
    setUploading(true)
    try {
      const res = await uploadCustomerList(selectedFile, mode)
      setMessage({ type: 'success', text: res.message })
      setTimeout(() => {
        onSuccess()
        onClose()
      }, 1500)
    } catch (err) {
      setMessage({ type: 'error', text: err instanceof Error ? err.message : '上传失败，请重试' })
    } finally {
      setUploading(false)
    }
  }

  return (
    <div
      className="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-xl shadow-xl w-full max-w-md p-6 mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 标题 */}
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-base font-semibold text-gray-800">上传客户清单</h3>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-lg leading-none"
          >
            ✕
          </button>
        </div>

        {/* 模板下载 */}
        <div className="mb-4">
          <button
            onClick={handleDownloadTemplate}
            disabled={downloading}
            className="text-blue-600 hover:text-blue-800 text-sm underline font-medium disabled:opacity-50"
          >
            {downloading ? '下载中...' : '📥 下载 Excel 模板'}
          </button>
          <p className="text-xs text-gray-400 mt-1">
            Excel 表格格式，包含企业名称、统一社会信用代码、推荐得分三列
          </p>
        </div>

        {/* 文件选择 */}
        <div className="mb-4">
          <input
            ref={fileInputRef}
            type="file"
            accept=".xlsx,.xls"
            onChange={handleFileSelect}
            className="hidden"
          />
          <button
            onClick={() => fileInputRef.current?.click()}
            className="w-full border-2 border-dashed border-gray-300 rounded-lg py-6 text-center hover:border-blue-400 transition-colors"
          >
            {selectedFile ? (
              <div>
                <span className="text-sm text-gray-700">📊 {selectedFile.name}</span>
                <span className="text-xs text-gray-400 ml-2">
                  ({(selectedFile.size / 1024).toFixed(1)} KB)
                </span>
              </div>
            ) : (
              <span className="text-sm text-gray-400">点击选择 Excel 文件 (.xlsx / .xls)</span>
            )}
          </button>
        </div>

        {/* 上传模式 */}
        <div className="mb-4">
          <label className="text-sm text-gray-600 mb-2 block">上传模式</label>
          <div className="flex gap-4">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="upload-mode"
                value="overwrite"
                checked={mode === 'overwrite'}
                onChange={() => setMode('overwrite')}
              />
              <span className="text-sm text-gray-700">覆盖存量数据</span>
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="upload-mode"
                value="append"
                checked={mode === 'append'}
                onChange={() => setMode('append')}
              />
              <span className="text-sm text-gray-700">追加更新</span>
            </label>
          </div>
          <p className="text-xs text-gray-400 mt-1">
            {mode === 'overwrite'
              ? '清空已有上传数据，用本次上传内容完全替换'
              : '保留已有数据，按信用代码去重合并（已存在则更新）'}
          </p>
        </div>

        {/* 消息提示 */}
        {message && (
          <div
            className={`text-sm mb-4 px-3 py-2 rounded-lg ${
              message.type === 'success'
                ? 'bg-green-50 text-green-700'
                : 'bg-red-50 text-red-700'
            }`}
          >
            {message.text}
          </div>
        )}

        {/* 操作按钮 */}
        <div className="flex gap-3 justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 rounded-lg border border-gray-200 transition-colors"
          >
            取消
          </button>
          <button
            onClick={handleUpload}
            disabled={!selectedFile || uploading}
            className="px-4 py-2 text-sm text-white bg-blue-600 hover:bg-blue-700 rounded-lg disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {uploading ? '上传中...' : '上传'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ============================================================
// 主组件
// ============================================================
const PotentialCustomerCard: React.FC<PotentialCustomerCardProps> = ({
  data,
  onRequestDetail,
  onSendMessage,
}) => {
  const [detailCollapsed, setDetailCollapsed] = useState(false)
  const [showUpload, setShowUpload] = useState(false)

  // 判断是 summary 还是 detail
  const isDetail = data.action === 'detail'

  // 上传成功后自动刷新客户清单
  const handleUploadSuccess = () => {
    onSendMessage?.('推荐拓户客户清单')
  }

  if (!isDetail) {
    // ========== 汇总视图 ==========
    const sources = data.sources as CustomerSource[] | undefined
    const msg = data.message as string | undefined

    return (
      <>
        <div className="potential-customer-card bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl border border-blue-100 overflow-hidden">
          {/* 头部 */}
          <div className="px-4 py-3 border-b border-blue-100 bg-white/60 flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
              <span className="text-lg">📋</span>
              拓户客户清单
            </h3>
            {onSendMessage && (
              <button
                onClick={() => setShowUpload(true)}
                className="text-xs text-blue-600 hover:text-blue-800 font-medium flex items-center gap-1 transition-colors"
              >
                <span>📤</span>
                上传客户清单
              </button>
            )}
          </div>

          {/* 空数据提示 */}
          {(!sources || sources.length === 0) ? (
            <div className="text-gray-500 text-sm py-6 text-center">
              {msg || '暂无拓户客户清单数据'}
            </div>
          ) : (
            /* 表格 */
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-blue-100 bg-blue-50/50">
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 w-12">
                    序号
                  </th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500">
                    客户清单来源
                  </th>
                  <th className="px-4 py-2 text-center text-xs font-medium text-gray-500 w-20">
                    客户数量
                  </th>
                  <th className="px-4 py-2 text-center text-xs font-medium text-gray-500 w-16">
                    操作
                  </th>
                </tr>
              </thead>
              <tbody>
                {sources.map((source, idx) => (
                  <tr
                    key={source.source_id}
                    className="border-b border-blue-50 hover:bg-blue-50/50 transition-colors"
                  >
                    <td className="px-4 py-3 text-gray-600">{idx + 1}</td>
                    <td className="px-4 py-3 text-gray-800 font-medium">
                      {source.source_name}
                    </td>
                    <td className="px-4 py-3 text-center text-gray-700 font-semibold">
                      {source.customer_count}户
                    </td>
                    <td className="px-4 py-3 text-center">
                      <button
                        onClick={() =>
                          onRequestDetail?.(source.source_id, source.source_name)
                        }
                        className="text-blue-600 hover:text-blue-800 underline font-medium cursor-pointer transition-colors"
                      >
                        查看
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* 上传弹窗 */}
        {showUpload && (
          <UploadModal
            onClose={() => setShowUpload(false)}
            onSuccess={handleUploadSuccess}
          />
        )}
      </>
    )
  }

  // ========== 详情视图 ==========
  const customers = data.customers as CustomerDetail[] | undefined

  if (!customers || customers.length === 0) {
    return (
      <div className="text-gray-500 text-sm py-3 text-center">暂无客户详情数据</div>
    )
  }

  return (
    <div className="potential-customer-card bg-gradient-to-br from-emerald-50 to-teal-50 rounded-xl border border-emerald-100 overflow-hidden">
      {/* 头部 */}
      <div
        className="px-4 py-3 border-b border-emerald-100 bg-white/60 flex items-center justify-between cursor-pointer select-none"
        onClick={() => setDetailCollapsed(!detailCollapsed)}
      >
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">📊</span>
          客户详情
        </h3>
        <span className="text-xs text-gray-400">
          {detailCollapsed ? '展开 ▼' : '收起 ▲'}
        </span>
      </div>

      {/* 表格 */}
      {!detailCollapsed && (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-emerald-100 bg-emerald-50/50">
              <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 w-12">
                序号
              </th>
              <th className="px-4 py-2 text-left text-xs font-medium text-gray-500">
                客户名称
              </th>
              <th className="px-4 py-2 text-left text-xs font-medium text-gray-500">
                统一信用代码
              </th>
              <th className="px-4 py-2 text-right text-xs font-medium text-gray-500 w-20">
                推荐得分
              </th>
            </tr>
          </thead>
          <tbody>
            {customers.map((customer, idx) => (
              <tr
                key={idx}
                className="border-b border-emerald-50 hover:bg-emerald-50/50 transition-colors"
              >
                <td className="px-4 py-2.5 text-gray-500">{idx + 1}</td>
                <td className="px-4 py-2.5 text-gray-800 font-medium">
                  {customer.name}
                </td>
                <td className="px-4 py-2.5 text-gray-500 font-mono text-xs">
                  {customer.credit_code}
                </td>
                <td className="px-4 py-2.5 text-right">
                  <span
                    className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${
                      customer.score >= 90
                        ? 'bg-emerald-100 text-emerald-700'
                        : customer.score >= 75
                          ? 'bg-blue-100 text-blue-700'
                          : 'bg-gray-100 text-gray-600'
                    }`}
                  >
                    {customer.score.toFixed(2)}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

export default PotentialCustomerCard
