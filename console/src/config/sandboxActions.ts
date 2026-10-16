/** 构建期开关只开放入口，不代表后端已安装或当前身份已授权。 */
export const SANDBOX_CLICK_ENABLED = import.meta.env.VITE_SANDBOX_CLICK_ENABLED === 'true';

export const SANDBOX_CONVERSION_ENABLED =
  import.meta.env.VITE_SANDBOX_CONVERSION_ENABLED === 'true';
