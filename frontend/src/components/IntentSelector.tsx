import type { IntentCandidate } from '../types'

interface IntentSelectorProps {
  candidates: IntentCandidate[]
  message?: string
  onSendMessage?: (content: string) => void
}

const IntentSelector: React.FC<IntentSelectorProps> = ({ candidates, message, onSendMessage }) => {
  return (
    <div className="bg-gradient-to-br from-purple-50 to-indigo-50 rounded-xl border border-purple-200 overflow-hidden">
      <div className="px-4 py-3 border-b border-purple-100 bg-white/60">
        <h3 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
          <span className="text-lg">🎯</span>
          请选择您想要的操作
        </h3>
        {message && (
          <p className="text-xs text-gray-500 mt-1">{message}</p>
        )}
      </div>
      <div className="p-3 space-y-2">
        {candidates.map((c) => (
          <button
            key={c.skill}
            onClick={() =>
              onSendMessage?.(`【意图选择】${c.skill}`)
            }
            className="w-full text-left px-4 py-3 rounded-lg border border-purple-200 bg-white
                       hover:bg-purple-50 hover:border-purple-300 transition-all
                       flex items-center justify-between group cursor-pointer"
          >
            <div>
              <div className="text-sm font-medium text-gray-800 group-hover:text-purple-700">
                {c.label}
              </div>
              {c.description && (
                <div className="text-xs text-gray-400 mt-0.5">{c.description}</div>
              )}
            </div>
            <svg className="w-4 h-4 text-purple-400 group-hover:text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        ))}
      </div>
    </div>
  )
}

export default IntentSelector
