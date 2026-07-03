/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#eef6ff',
          100: '#d9eaff',
          200: '#bcdbff',
          300: '#8ec4ff',
          400: '#59a3ff',
          500: '#3380fc',
          600: '#1d60f1',
          700: '#154bde',
          800: '#173eb4',
          900: '#18398d',
          950: '#142456',
        },
        ink: {
          50: '#f6f7f9',
          100: '#eceef2',
          200: '#d5dae2',
          300: '#b0bac9',
          400: '#8594ab',
          500: '#667791',
          600: '#515f78',
          700: '#424d62',
          800: '#3a4253',
          900: '#333a47',
          950: '#22262f',
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
      },
      boxShadow: {
        card: '0 1px 2px rgba(16, 24, 40, 0.04), 0 8px 24px -8px rgba(16, 24, 40, 0.10)',
        float: '0 12px 40px -12px rgba(16, 24, 40, 0.18)',
      },
      borderRadius: {
        '2xl': '1rem',
        '3xl': '1.5rem',
      },
    },
  },
  plugins: [],
}
