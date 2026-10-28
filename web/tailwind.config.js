/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  corePlugins: {
    // 关闭 preflight：它会重置 Element Plus 依赖的 button/table 默认样式，导致按钮背景消失
    preflight: false
  },
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#EFF6FF',
          100: '#DBEAFE',
          500: '#3B82F6',
          600: '#2563EB',
          700: '#1D4ED8',
          900: '#111827'
        },
        ink: {
          900: '#1F2937',
          600: '#6B7280',
          400: '#9CA3AF'
        },
        canvas: '#F5F7FA',
        line: '#E5E7EB'
      },
      fontFamily: {
        sans: ['PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', 'Arial', 'sans-serif']
      }
    }
  },
  plugins: []
}
