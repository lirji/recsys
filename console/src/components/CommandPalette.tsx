import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Empty, Input, Modal, Typography, type InputRef } from 'antd';
import { EnterOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { NAV_DESTINATIONS } from '../api/nav';
import { canAccess } from '../api/access';
import { useAuth } from '../hooks/useAuth';
import { useGlobalUser } from '../hooks/useGlobalUser';
import { useCommandPalette } from '../hooks/useCommandPalette';
import { BRAND, rgba } from '../theme/tokens';

const LISTBOX_ID = 'cmdk-listbox';
const optionId = (i: number) => `cmdk-option-${i}`;

interface PaletteItem {
  path: string;
  label: string;
  group: string;
  keywords?: string;
}

export default function CommandPalette() {
  const { open, setOpen } = useCommandPalette();
  const { user } = useAuth();
  const { userId } = useGlobalUser();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<InputRef>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const destinations = useMemo<PaletteItem[]>(() => {
    const roles = user?.roles ?? [];
    const nav = NAV_DESTINATIONS.filter((d) => canAccess(d, roles));
    const extras: PaletteItem[] = [
      {
        path: `/user360?userId=${userId}`,
        label: `用户 360 · 当前 userId=${userId}`,
        group: '深链',
        keywords: 'user360 current yonghu',
      },
    ];
    if (canAccess({ roles: ['ADMIN'] }, roles)) {
      extras.push({
        path: '/experiment',
        label: '打开实验管理',
        group: '深链',
        keywords: 'experiment ab',
      });
    }
    if (canAccess({ roles: ['ADMIN', 'ADVERTISER'] }, roles)) {
      extras.push({
        path: '/reports/ab-report',
        label: '最新 A/B 报表',
        group: '深链',
        keywords: 'ab-report latest baobiao',
      });
    }
    return [...nav, ...extras];
  }, [user?.roles, userId]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return destinations;
    return destinations.filter((d) =>
      [d.label, d.path, d.group, d.keywords ?? ''].some((f) => f.toLowerCase().includes(q)),
    );
  }, [destinations, query]);

  useEffect(() => {
    if (!open) return;
    setQuery('');
    setActive(0);
    const t = window.setTimeout(() => inputRef.current?.focus(), 60);
    return () => window.clearTimeout(t);
  }, [open]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  useEffect(() => {
    listRef.current?.querySelector(`[data-idx="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  const go = (path: string) => {
    setOpen(false);
    navigate(path);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => (results.length ? Math.min(i + 1, results.length - 1) : 0));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const d = results[active];
      if (d) go(d.path);
    }
  };

  return (
    <Modal
      open={open}
      onCancel={() => setOpen(false)}
      footer={null}
      closable
      centered
      destroyOnClose
      width={560}
      styles={{ body: { padding: 0 }, header: { padding: '14px 20px 0', marginBottom: 0 } }}
      title="命令面板"
    >
      <div style={{ padding: '12px 20px 8px' }}>
        <Input
          ref={inputRef}
          size="large"
          allowClear
          variant="borderless"
          prefix={<SearchOutlined style={{ color: '#8c8c8c' }} />}
          placeholder="跳转页面 / 深链…(↑↓ 选择,Enter 打开)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={onKeyDown}
          aria-label="搜索页面或命令"
          role="combobox"
          aria-expanded
          aria-controls={LISTBOX_ID}
          aria-autocomplete="list"
          aria-activedescendant={results[active] ? optionId(active) : undefined}
          style={{ fontSize: 16 }}
        />
      </div>

      <div
        ref={listRef}
        id={LISTBOX_ID}
        role="listbox"
        aria-label="页面列表"
        style={{ maxHeight: 360, overflowY: 'auto', padding: '4px 8px 12px' }}
      >
        {results.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的页面" style={{ margin: '24px 0' }} />
        ) : (
          results.map((d, i) => {
            const isActive = i === active;
            return (
              <div
                key={`${d.group}-${d.path}-${d.label}`}
                id={optionId(i)}
                data-idx={i}
                role="option"
                aria-selected={isActive}
                onClick={() => go(d.path)}
                onMouseMove={() => setActive(i)}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 12,
                  padding: '10px 12px',
                  borderRadius: 8,
                  cursor: 'pointer',
                  background: isActive ? rgba(BRAND, 0.1) : 'transparent',
                  transition: 'background 0.12s',
                }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <Typography.Text strong style={{ color: isActive ? BRAND : undefined }}>
                    {d.label}
                  </Typography.Text>
                  <Typography.Text type="secondary" className="mono" style={{ fontSize: 12, marginInlineStart: 8 }}>
                    {d.path}
                  </Typography.Text>
                </div>
                <Typography.Text type="secondary" style={{ fontSize: 12, flex: '0 0 auto' }}>
                  {d.group}
                </Typography.Text>
                {isActive && <EnterOutlined style={{ color: BRAND, flex: '0 0 auto' }} aria-hidden />}
              </div>
            );
          })
        )}
      </div>
    </Modal>
  );
}
