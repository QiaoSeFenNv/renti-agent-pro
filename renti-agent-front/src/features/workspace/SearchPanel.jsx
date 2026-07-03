import Button from '../../components/ui/Button.jsx'

/** 搜索面板：自然语言输入 + 普通搜索 / Agent 深度搜索；导入模式下退化为本地筛选输入 */
function SearchPanel({
  value,
  onChange,
  onSearch,
  onAgentSearch,
  loading = false,
  engine = 'intent',
  isImportMode = false,
  city = '',
}) {
  const handleKeyDown = (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      if (!isImportMode && !loading) onSearch()
    }
  }

  return (
    <div>
      <label htmlFor="workspace-query" className="sr-only">
        {isImportMode ? '筛选导入房源' : '输入租房需求'}
      </label>
      <textarea
        id="workspace-query"
        rows={3}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={
          isImportMode
            ? '输入关键词筛选已导入的房源（标题 / 位置）'
            : `例如：${city || '上海'}人民广场附近预算 6000 以内的一居室`
        }
        className="w-full resize-none rounded-xl border-0 bg-ink-50 px-3.5 py-2.5 text-sm leading-6 text-ink-900 ring-1 ring-inset ring-ink-200 transition placeholder:text-ink-300 focus:bg-white focus:ring-2 focus:ring-brand-500"
      />
      {isImportMode ? (
        <p className="mt-1.5 text-xs leading-5 text-ink-400">
          自有房源模式下不查询平台房源库，输入即筛选本地导入列表。
        </p>
      ) : (
        <div className="mt-2.5 flex gap-2">
          <Button
            size="sm"
            variant="secondary"
            className="flex-1"
            loading={loading && engine === 'intent'}
            disabled={loading}
            onClick={onSearch}
          >
            <svg viewBox="0 0 20 20" fill="currentColor" className="h-3.5 w-3.5" aria-hidden="true">
              <path
                fillRule="evenodd"
                d="M9 3.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM2 9a7 7 0 1 1 12.45 4.4l3.07 3.08a.75.75 0 1 1-1.06 1.06l-3.07-3.07A7 7 0 0 1 2 9Z"
                clipRule="evenodd"
              />
            </svg>
            搜索
          </Button>
          <Button
            size="sm"
            className="flex-1"
            loading={loading && engine === 'agent'}
            disabled={loading}
            onClick={onAgentSearch}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-3.5 w-3.5" aria-hidden="true">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456Z"
              />
            </svg>
            Agent 深度搜索
          </Button>
        </div>
      )}
    </div>
  )
}

export default SearchPanel
