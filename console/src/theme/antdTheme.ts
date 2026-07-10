import { theme, type ThemeConfig } from 'antd';
import { BRAND, SURFACE, rgba } from './tokens';

// 全站 AntD 主题:仍走 defaultAlgorithm(浅色),只保守调 token —— 影响全站 20+ 页的
// 表格/菜单/卡片,故取值克制,只让整体更圆润、更有质感、配色更协调,不改变布局与信息密度。
export const antdTheme: ThemeConfig = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary: BRAND,
    colorLink: BRAND,
    colorInfo: BRAND,
    colorTextHeading: '#1f2a44', // 标题略带蓝调深色,比纯黑更耐看
    colorBorderSecondary: SURFACE.cardBorder, // 卡片/表格/分割线统一柔描边
    borderRadius: 10,
    borderRadiusLG: 14,
    fontFamily: "-apple-system, 'PingFang SC', 'Segoe UI', Roboto, sans-serif",
  },
  components: {
    Layout: {
      bodyBg: '#f4f6fb', // 页底渐变兜底色(渐变本体在 AppLayout 内联,不支持渐变的场景退这个)
      headerBg: '#ffffff',
      siderBg: '#ffffff',
    },
    Menu: {
      itemSelectedBg: rgba(BRAND, 0.1),
      itemSelectedColor: BRAND,
      itemBorderRadius: 8,
      itemHoverBg: rgba(BRAND, 0.05),
    },
    Card: {
      borderRadiusLG: 14,
    },
    Table: {
      headerBg: '#f7f9fc',
      rowHoverBg: rgba(BRAND, 0.035),
      borderColor: SURFACE.cardBorder,
    },
    Tag: {
      borderRadiusSM: 6,
    },
    Statistic: {
      titleFontSize: 13,
    },
  },
};
