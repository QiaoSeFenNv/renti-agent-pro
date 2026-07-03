import React from 'react'

/**
 * 全局错误边界：局部渲染错误不至于让整页白屏。
 * （React 官方要求错误边界必须是类组件）
 */
class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false }
  }

  static getDerivedStateFromError() {
    return { hasError: true }
  }

  componentDidCatch(error, info) {
    console.error('[ErrorBoundary]', error, info)
  }

  handleReset = () => {
    this.setState({ hasError: false })
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-[40vh] flex-col items-center justify-center gap-4 p-8 text-center">
          <div className="text-4xl">😵</div>
          <p className="text-lg font-semibold text-ink-800">页面出了一点小状况</p>
          <p className="text-sm text-ink-500">你可以刷新页面，或点击下方按钮重试。</p>
          <button
            type="button"
            onClick={this.handleReset}
            className="rounded-full bg-brand-600 px-5 py-2 text-sm font-medium text-white transition hover:bg-brand-700"
          >
            重新加载此区域
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

export default ErrorBoundary
