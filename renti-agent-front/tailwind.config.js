/** @type {import('tailwindcss').Config} */

/*
 * Renti Agent 主题化设计系统。
 *
 * 关键决策：沿用全站既有的 token 名（ink/brand/shadow-card…），但把颜色
 * 取值交给 CSS 变量。这样存量页面的 bg-ink-50 / text-ink-900 等组合无需
 * 大面积改动，就可以跟随 html[data-theme] 在亮色/暗色之间切换。
 */
const withOpacity = (name) => `rgb(var(${name}) / <alpha-value>)`

export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: withOpacity('--color-brand-50'),
          100: withOpacity('--color-brand-100'),
          200: withOpacity('--color-brand-200'),
          300: withOpacity('--color-brand-300'),
          400: withOpacity('--color-brand-400'),
          500: withOpacity('--color-brand-500'),
          600: withOpacity('--color-brand-600'),
          700: withOpacity('--color-brand-700'),
          800: withOpacity('--color-brand-800'),
          900: withOpacity('--color-brand-900'),
          950: withOpacity('--color-brand-950'),
        },
        ink: {
          50: withOpacity('--color-ink-50'),
          100: withOpacity('--color-ink-100'),
          200: withOpacity('--color-ink-200'),
          300: withOpacity('--color-ink-300'),
          400: withOpacity('--color-ink-400'),
          500: withOpacity('--color-ink-500'),
          600: withOpacity('--color-ink-600'),
          700: withOpacity('--color-ink-700'),
          800: withOpacity('--color-ink-800'),
          900: withOpacity('--color-ink-900'),
          950: withOpacity('--color-ink-950'),
        },
        surface: {
          DEFAULT: withOpacity('--color-surface'),
          raised: withOpacity('--color-surface-raised'),
          deep: withOpacity('--color-surface-deep'),
        },
        emerald: {
          50: withOpacity('--color-emerald-50'),
          100: withOpacity('--color-emerald-100'),
          200: withOpacity('--color-emerald-200'),
          700: withOpacity('--color-emerald-700'),
          800: withOpacity('--color-emerald-800'),
          900: withOpacity('--color-emerald-900'),
        },
        amber: {
          50: withOpacity('--color-amber-50'),
          100: withOpacity('--color-amber-100'),
          200: withOpacity('--color-amber-200'),
          700: withOpacity('--color-amber-700'),
          800: withOpacity('--color-amber-800'),
          900: withOpacity('--color-amber-900'),
        },
        rose: {
          50: withOpacity('--color-rose-50'),
          100: withOpacity('--color-rose-100'),
          200: withOpacity('--color-rose-200'),
          700: withOpacity('--color-rose-700'),
          800: withOpacity('--color-rose-800'),
          900: withOpacity('--color-rose-900'),
        },
        sky: {
          50: withOpacity('--color-sky-50'),
          100: withOpacity('--color-sky-100'),
          200: withOpacity('--color-sky-200'),
          700: withOpacity('--color-sky-700'),
          800: withOpacity('--color-sky-800'),
          900: withOpacity('--color-sky-900'),
        },
      },
      fontFamily: {
        sans: [
          'Inter',
          'PingFang SC',
          'HarmonyOS Sans SC',
          'Microsoft YaHei',
          'system-ui',
          'sans-serif',
        ],
        display: [
          'Space Grotesk',
          'Inter',
          'PingFang SC',
          'HarmonyOS Sans SC',
          'Microsoft YaHei',
          'system-ui',
          'sans-serif',
        ],
        mono: [
          'JetBrains Mono',
          'ui-monospace',
          'SFMono-Regular',
          'Consolas',
          'monospace',
        ],
      },
      boxShadow: {
        card: 'var(--shadow-card)',
        float: 'var(--shadow-float)',
        glow: '0 0 24px -4px rgba(71,123,255,0.45)',
        'glow-lg': '0 8px 48px -8px rgba(71,123,255,0.5)',
        'glow-cyan': '0 0 24px -4px rgba(34,211,238,0.4)',
      },
      borderRadius: {
        '2xl': '1rem',
        '3xl': '1.5rem',
      },
      backgroundImage: {
        'brand-gradient': 'linear-gradient(135deg, rgb(var(--color-brand-500)) 0%, rgb(var(--color-sky-700)) 100%)',
        'brand-gradient-soft': 'linear-gradient(135deg, rgb(var(--color-brand-500) / 0.16) 0%, rgb(var(--color-sky-700) / 0.10) 100%)',
      },
    },
  },
  plugins: [],
}
