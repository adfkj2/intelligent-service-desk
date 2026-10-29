import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import prettier from 'eslint-config-prettier'

/**
 * ESLint 9 flat config。
 *
 * 分工：ESLint 管"会不会跑错"（未定义变量、模板结构错误、Vue 用法错误），
 * 格式类规则全部交给 Prettier（最后一项 eslint-config-prettier 负责关掉冲突规则）。
 * 这样 `npm run lint` 报出来的都是真问题，而不是空格与换行的噪音。
 */
export default [
  {
    // 构建产物与依赖不参与检查；static 是 vite 的输出目录，也不该被 lint
    ignores: ['dist/**', 'node_modules/**', '../src/main/resources/static/**']
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.es2021
      }
    },
    rules: {
      // 组件命名、属性换行、自闭合都是格式偏好，交给 Prettier / 代码评审
      'vue/multi-word-component-names': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/html-self-closing': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      // 未使用变量是真问题（多半是重构留下的死代码），但允许 _ 前缀的占位参数
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrors: 'none' }]
    }
  },
  prettier
]
