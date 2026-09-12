/** @type {import('tailwindcss').Config} */
export default {
  // 用 class 策略切换暗色（对应原 script.js 里给 <html> 加 .dark）
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      // 原 tailwind.css 里存在但默认 scale 缺失的间距值（如 h-18/w-28），补齐以还原老代码尺寸
      spacing: {
        '18': '4.5rem',
      },
      // 自定义主题色：引用 style.css 里的 CSS 变量（light/dark 各自取值）
      colors: {
        primary: 'rgb(var(--color-primary) / <alpha-value>)',
        'primary-soft': 'rgb(var(--color-primary-soft) / <alpha-value>)',
        secondery: 'rgb(var(--color-secondery) / <alpha-value>)',
        bgbody: 'rgb(var(--color-bgbody) / <alpha-value>)',
        // 原 tailwind.css 中的自定义深色色板（slate 系）
        dark1: 'rgb(15 23 42)',
        dark2: 'rgb(30 41 59)',
        dark3: 'rgb(51 65 85)',
        dark4: 'rgb(71 85 105)',
        dark5: 'rgb(15 23 42)',
      },
    },
  },
  plugins: [],
}
