import type { ReactNode } from 'react';
import { SafetyCertificateOutlined, ShopOutlined, UserOutlined } from '@ant-design/icons';
import { DEMO_USERS, type Role } from '../api/auth';

export const ROLE_META: Record<Role, { icon: ReactNode; avatarBg: string; tagColor: string; label: string }> = {
  ADMIN: { icon: <SafetyCertificateOutlined />, avatarBg: '#2f54eb', tagColor: 'geekblue', label: '管理员' },
  ADVERTISER: { icon: <ShopOutlined />, avatarBg: '#fa8c16', tagColor: 'orange', label: '广告主' },
  USER: { icon: <UserOutlined />, avatarBg: '#13c2c2', tagColor: 'cyan', label: '用户' },
};

export const DEMO_USER_META: Record<(typeof DEMO_USERS)[number], { role: Role; desc: string }> = {
  admin: { role: 'ADMIN', desc: '全部权限(实验 / 广告主 / 只读)' },
  advertiser: { role: 'ADVERTISER', desc: '广告主后台 + 只读推荐 / 搜索' },
  user: { role: 'USER', desc: '仅在线只读(打管理页会 403)' },
};
