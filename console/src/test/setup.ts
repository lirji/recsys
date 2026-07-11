// vitest 全局 setup:挂上 @testing-library/jest-dom 的自定义匹配器(toBeInTheDocument 等),
// 供后续组件测试使用。纯函数测试不依赖它,但预置好底座。
import '@testing-library/jest-dom/vitest';
