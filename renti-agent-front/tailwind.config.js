/** @type {import('tailwindcss').Config} */

/*
 * 黑色科技风设计系统。
 *
 * 关键决策：沿用全站既有的 token 名（ink/brand/shadow-card…），但把取值整体
 * 重调为暗色语义——ink 色阶反转（50 = 最深页面底色，950 = 最亮文字），
 * brand 两端压暗（50/100 变为深蓝衬底，300/400 为暗底高亮文字蓝），
 * emerald/amber/rose/sky 的 50~200 与 700~900 同步重调。
 * 这样存量页面的 bg-ink-50 / text-ink-900 / bg-emerald-50 等组合无需改动
 * 即可获得正确的暗色观感，新页面按同一语义继续取用。
 */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#0c142e',
          100: '#152352',
          200: '#1e3272',
          300: '#8fb0ff',
          400: '#6690ff',
          500: '#477bff',
          600: '#3566f0',
          700: '#2b52cc',
          800: '#1f3d9e',
          900: '#182f78',
          950: '#0a1330',
        },
        ink: {
          50: '#08090f',
          100: '#11131d',
          200: '#1e2233',
          300: '#39405c',
          400: '#6b7694',
          500: '#8b96b3',
          600: '#aab4cf',
          700: '#c6cee4',
          800: '#dde3f2',
          900: '#eef1fa',
          950: '#f7f9ff',
        },
        surface: {
          DEFAULT: '#0d0f18',
          raised: '#141828',
          deep: '#05060b',
        },
        emerald: {
          50: '#062218',
          100: '#0a3526',
          200: '#0f4d37',
          700: '#34d399',
          800: '#6ee7b7',
          900: '#a7f3d0',
        },
        amber: {
          50: '#241703',
          100: '#3a2604',
          200: '#553804',
          700: '#fbbf24',
          800: '#fcd34d',
          900: '#fde68a',
        },
        rose: {
          50: '#2a0a12',
          100: '#45101d',
          200: '#5f1627',
          700: '#fb7185',
          800: '#fda4af',
          900: '#fecdd3',
        },
        sky: {
          50: '#07172a',
          100: '#0c2440',
          200: '#123458',
          700: '#7dd3fc',
          800: '#bae6fd',
          900: '#e0f2fe',
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
        card: 'inset 0 1px 0 rgba(255,255,255,0.04), 0 10px 32px -12px rgba(0,0,0,0.65)',
        float: 'inset 0 1px 0 rgba(255,255,255,0.06), 0 24px 56px -16px rgba(0,0,0,0.8)',
        glow: '0 0 24px -4px rgba(71,123,255,0.45)',
        'glow-lg': '0 8px 48px -8px rgba(71,123,255,0.5)',
        'glow-cyan': '0 0 24px -4px rgba(34,211,238,0.4)',
      },
      borderRadius: {
        '2xl': '1rem',
        '3xl': '1.5rem',
      },
      backgroundImage: {
        'brand-gradient': 'linear-gradient(135deg, #477bff 0%, #22d3ee 100%)',
        'brand-gradient-soft': 'linear-gradient(135deg, rgba(71,123,255,0.16) 0%, rgba(34,211,238,0.10) 100%)',
      },
    },
  },
  plugins: [],
}
