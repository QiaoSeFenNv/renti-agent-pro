import { useCallback, useEffect, useRef, useState } from 'react'

import Button from '../../components/ui/Button.jsx'
import { EmptyState, LoadingBlock } from '../../components/ui/Feedback.jsx'
import Modal from '../../components/ui/Modal.jsx'
import { agentService } from '../../services/searchService.js'
import { readField } from '../workspace/utils.js'

const QUICK_QUESTIONS = ['通勤距离和交通情况如何？', '这个价格是否合理？', '数据来源可信吗？']

function formatTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

/** 单条消息气泡：user 右蓝 / assistant 左白，citations 与 toolTrace 折叠展示 */
function ChatMessage({ message }) {
  const isUser = message.role === 'user'
  const citations = Array.isArray(message.citations) ? message.citations : []
  const toolTrace = Array.isArray(message.toolTrace) ? message.toolTrace : []

  return (
    <div className={['flex', isUser ? 'justify-end' : 'justify-start'].join(' ')}>
      <div
        className={[
          'max-w-[85%] rounded-2xl px-3.5 py-2.5 text-sm leading-6',
          isUser ? 'bg-brand-600 text-white' : 'bg-white text-ink-800 shadow-card ring-1 ring-ink-100',
        ].join(' ')}
      >
        <p className="whitespace-pre-wrap break-words">{message.content}</p>
        {citations.length > 0 && (
          <details className="mt-1.5">
            <summary
              className={['cursor-pointer text-xs', isUser ? 'text-brand-100' : 'text-ink-400'].join(' ')}
            >
              引用依据（{citations.length}）
            </summary>
            <ul className={['mt-1 space-y-0.5 text-xs', isUser ? 'text-brand-50' : 'text-ink-500'].join(' ')}>
              {citations.map((citation, index) => (
                <li key={`${citation.label}-${index}`}>
                  {citation.label}：{citation.value}
                </li>
              ))}
            </ul>
          </details>
        )}
        {toolTrace.length > 0 && (
          <details className="mt-1">
            <summary className={['cursor-pointer text-xs', isUser ? 'text-brand-100' : 'text-ink-400'].join(' ')}>
              工具调用（{toolTrace.length}）
            </summary>
            <ul className={['mt-1 space-y-0.5 text-xs', isUser ? 'text-brand-50' : 'text-ink-500'].join(' ')}>
              {toolTrace.map((trace, index) => (
                <li key={`${trace.tool}-${index}`}>
                  {trace.tool} · {trace.status}
                  {trace.summary ? ` · ${trace.summary}` : ''}
                </li>
              ))}
            </ul>
          </details>
        )}
        {message.createdAt && (
          <p className={['mt-1 text-[10px]', isUser ? 'text-brand-200' : 'text-ink-300'].join(' ')}>
            {formatTime(message.createdAt)}
          </p>
        )}
      </div>
    </div>
  )
}

/**
 * 房源问答抽屉：恢复最近会话、发送消息、清空会话（二次确认）。
 */
function ChatDrawer({ listingId, open, onClose, contextTitle, contextMeta }) {
  const [loadState, setLoadState] = useState({ status: 'idle', error: '' })
  const [sessions, setSessions] = useState([])
  const [activeId, setActiveId] = useState('')
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState('')
  const [pendingUserMessage, setPendingUserMessage] = useState('')
  const [confirmClear, setConfirmClear] = useState(false)
  const [clearing, setClearing] = useState(false)
  const loadedRef = useRef(false)
  const messagesRef = useRef(null)
  const inputRef = useRef(null)

  const active = sessions.find((session) => session.id === activeId) || null
  const messages = Array.isArray(active?.messages) ? active.messages : []

  useEffect(() => {
    loadedRef.current = false
    setSessions([])
    setActiveId('')
    setInput('')
    setSendError('')
    setPendingUserMessage('')
    setLoadState({ status: 'idle', error: '' })
  }, [listingId])

  const loadSessions = useCallback(async () => {
    setLoadState({ status: 'loading', error: '' })
    try {
      const data = await agentService.listChatSessions(listingId)
      const rows = (Array.isArray(data?.sessions) ? data.sessions : [])
        .filter((session) => session?.id)
        .sort((left, right) => Date.parse(right.updatedAt || 0) - Date.parse(left.updatedAt || 0))
      setSessions(rows)
      setActiveId(rows[0]?.id || '')
      setLoadState({ status: 'ready', error: '' })
    } catch (err) {
      setLoadState({ status: 'error', error: err?.message || '会话历史加载失败' })
    }
  }, [listingId])

  useEffect(() => {
    if (open && !loadedRef.current) {
      loadedRef.current = true
      loadSessions()
    }
  }, [open, loadSessions])

  useEffect(() => {
    const element = messagesRef.current
    if (element) element.scrollTop = element.scrollHeight
  }, [messages.length, pendingUserMessage, sending, open])

  const upsertSession = (session) => {
    if (!session?.id) return
    setSessions((prev) => [session, ...prev.filter((item) => item.id !== session.id)])
    setActiveId(session.id)
  }

  const startSession = async () => {
    setSendError('')
    setLoadState((prev) => ({ ...prev, status: 'loading' }))
    try {
      const data = await agentService.createChatSession({ listingId })
      if (data?.ok === false || !data?.session) throw new Error(data?.summary || '创建会话失败')
      upsertSession(data.session)
      setLoadState({ status: 'ready', error: '' })
      inputRef.current?.focus()
    } catch (err) {
      setLoadState({ status: 'ready', error: '' })
      setSendError(err?.message || '创建会话失败，请稍后重试')
    }
  }

  const sendMessage = async () => {
    const message = input.trim()
    if (!message || sending) return

    let sessionId = activeId
    setSending(true)
    setSendError('')
    setInput('')
    setPendingUserMessage(message)
    try {
      if (!sessionId) {
        const created = await agentService.createChatSession({ listingId })
        if (created?.ok === false || !created?.session) throw new Error(created?.summary || '创建会话失败')
        upsertSession(created.session)
        sessionId = created.session.id
      }
      const data = await agentService.sendChatMessage(sessionId, { message })
      if (data?.ok === false) throw new Error(data?.summary || '发送失败')
      if (data?.session) {
        upsertSession(data.session)
      } else {
        // 兜底：接口只回单条消息时本地拼装
        const reply = readField(data || {}, 'reply', 'answer', 'content', 'message')
        const now = new Date().toISOString()
        setSessions((prev) =>
          prev.map((session) =>
            session.id === sessionId
              ? {
                  ...session,
                  updatedAt: now,
                  messages: [
                    ...(session.messages || []),
                    { id: `local-user-${Date.now()}`, role: 'user', content: message, createdAt: now },
                    ...(reply
                      ? [
                          {
                            id: `local-assistant-${Date.now()}`,
                            role: 'assistant',
                            content: typeof reply === 'string' ? reply : reply?.content || '',
                            createdAt: now,
                          },
                        ]
                      : []),
                  ],
                }
              : session,
          ),
        )
      }
      setPendingUserMessage('')
    } catch (err) {
      setPendingUserMessage('')
      setInput(message)
      setSendError(err?.message || '发送失败，请稍后重试')
    } finally {
      setSending(false)
    }
  }

  const handleClear = async () => {
    setClearing(true)
    setSendError('')
    try {
      await agentService.clearChatSessions(listingId)
      setSessions([])
      setActiveId('')
      setConfirmClear(false)
    } catch (err) {
      setSendError(err?.message || '清空会话失败')
      setConfirmClear(false)
    } finally {
      setClearing(false)
    }
  }

  const handleKeyDown = (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      sendMessage()
    }
  }

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label="房源问答">
      <div className="absolute inset-0 bg-ink-950/30 backdrop-blur-[2px] animate-fade-in" onClick={onClose} />
      <section className="relative flex h-full w-full max-w-md flex-col bg-ink-50 shadow-float animate-fade-in">
        {/* 头部 */}
        <header className="flex items-start justify-between gap-2 border-b border-ink-100 bg-white px-4 py-3.5">
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-ink-900">房源问答</h2>
            <p className="mt-0.5 truncate text-xs text-ink-400">
              {contextTitle}
              {contextMeta ? ` · ${contextMeta}` : ''}
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {sessions.length > 0 && (
              <button
                type="button"
                onClick={() => setConfirmClear(true)}
                className="rounded-full px-2.5 py-1.5 text-xs font-medium text-rose-600 transition hover:bg-rose-50"
              >
                清空会话
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              aria-label="关闭问答"
              className="rounded-full p-1.5 text-ink-400 transition hover:bg-ink-100 hover:text-ink-700"
            >
              <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
                <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22z" />
              </svg>
            </button>
          </div>
        </header>

        {/* 消息区 */}
        <div ref={messagesRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4 scrollbar-thin">
          {loadState.status === 'loading' && <LoadingBlock text="正在恢复会话…" className="py-10" />}

          {loadState.status === 'error' && (
            <div className="rounded-xl bg-rose-50 p-3 text-xs leading-5 text-rose-700 ring-1 ring-rose-100">
              <p>{loadState.error}</p>
              <Button variant="secondary" size="sm" className="mt-2" onClick={loadSessions}>
                重试
              </Button>
            </div>
          )}

          {loadState.status === 'ready' && !active && (
            <EmptyState
              icon="💬"
              title="开始向 AI 提问"
              description="AI 会结合这套房源的价格、通勤、评估和数据来源回答问题。"
              action={
                <Button size="sm" onClick={startSession}>
                  开始提问
                </Button>
              }
              className="py-10"
            />
          )}

          {active && messages.length === 0 && !pendingUserMessage && (
            <div className="space-y-2" aria-label="常用问题">
              <p className="text-xs text-ink-400">可以先从这些问题开始：</p>
              {QUICK_QUESTIONS.map((question) => (
                <button
                  key={question}
                  type="button"
                  onClick={() => {
                    setInput(question)
                    inputRef.current?.focus()
                  }}
                  className="block w-full rounded-xl bg-white px-3 py-2 text-left text-xs text-ink-600 shadow-card ring-1 ring-ink-100 transition hover:ring-brand-200"
                >
                  {question}
                </button>
              ))}
            </div>
          )}

          {messages.map((message) => (
            <ChatMessage key={message.id} message={message} />
          ))}

          {pendingUserMessage && (
            <ChatMessage
              message={{ id: 'pending', role: 'user', content: pendingUserMessage, createdAt: new Date().toISOString() }}
            />
          )}

          {sending && (
            <div className="flex justify-start" aria-label="AI 正在输入">
              <div className="flex items-center gap-1.5 rounded-2xl bg-white px-4 py-3 shadow-card ring-1 ring-ink-100">
                {[0, 1, 2].map((index) => (
                  <span
                    key={index}
                    className="h-1.5 w-1.5 animate-bounce rounded-full bg-ink-300"
                    style={{ animationDelay: `${index * 150}ms` }}
                  />
                ))}
              </div>
            </div>
          )}
        </div>

        {/* 输入区 */}
        <div className="border-t border-ink-100 bg-white px-4 py-3">
          {sendError && <p className="mb-2 text-xs text-rose-600">{sendError}</p>}
          <div className="flex items-end gap-2">
            <label htmlFor="property-chat-input" className="sr-only">
              输入问题
            </label>
            <textarea
              id="property-chat-input"
              ref={inputRef}
              rows={2}
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="询问通勤、价格、风险或数据来源…"
              className="flex-1 resize-none rounded-xl border-0 bg-ink-50 px-3 py-2 text-sm leading-6 text-ink-900 ring-1 ring-inset ring-ink-200 transition placeholder:text-ink-300 focus:bg-white focus:ring-2 focus:ring-brand-500"
            />
            <Button size="sm" onClick={sendMessage} loading={sending} disabled={!input.trim() || sending} aria-label="发送">
              发送
            </Button>
          </div>
        </div>
      </section>

      {/* 清空确认 */}
      <Modal
        open={confirmClear}
        onClose={() => setConfirmClear(false)}
        title="清空问答会话"
        size="sm"
        footer={
          <>
            <Button variant="secondary" size="sm" onClick={() => setConfirmClear(false)}>
              取消
            </Button>
            <Button variant="danger" size="sm" loading={clearing} onClick={handleClear}>
              确认清空
            </Button>
          </>
        }
      >
        <p className="text-sm leading-6 text-ink-600">将删除这套房源下的全部问答会话，删除后不可恢复。确定继续吗？</p>
      </Modal>
    </div>
  )
}

export default ChatDrawer
