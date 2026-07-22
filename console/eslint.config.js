// ESLint 9 flat config —— typescript-eslint + react-hooks + react-refresh。
// 设计原则(工程底座):对既有业务源码「零重构」。凡是 pre-existing 代码可能大面积触发的规则
// 一律降为 warn 或关闭,保证 `npm run lint` 无 error 干净退出;新代码仍能看到 warning 提示。
import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // 生成物 / 依赖 / 覆盖率报告 / 本配置无关文件:不 lint。
  {
    ignores: [
      'dist',
      '.vite',
      'node_modules',
      'coverage',
      '**/*.tsbuildinfo',
      'vite.config.js',
      'vite.config.d.ts',
    ],
  },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2021,
      sourceType: 'module',
      globals: { ...globals.browser },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      // ↓ 既有源码常见写法:降级为 warn(不阻断),避免逐个改业务文件。
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': 'warn',
      '@typescript-eslint/no-empty-object-type': 'warn',
      '@typescript-eslint/ban-ts-comment': 'warn',
      '@typescript-eslint/no-non-null-assertion': 'off',
      'no-empty': 'warn',
      'prefer-const': 'warn',
    },
  },
  // 测试文件:放开 no-explicit-any 等,专注断言逻辑。
  {
    files: ['**/*.{test,spec}.{ts,tsx}', 'src/test/**'],
    languageOptions: {
      globals: { ...globals.node },
    },
  },
);
