import { useMemo, useState, type ReactNode } from 'react';
import { App, Button, Collapse, Input, Space, Typography } from 'antd';
import { CopyOutlined, DownloadOutlined } from '@ant-design/icons';
import { downloadText } from '../../utils/download';

// 调试信息面板:折叠展示 traceId/requestId + 原始 JSON。升级:关键字过滤高亮 + 匹配计数 + 复制/下载 JSON + 复制 ID。

function highlight(text: string, kw: string): ReactNode {
  if (!kw) return text;
  const out: ReactNode[] = [];
  const lower = text.toLowerCase();
  const k = kw.toLowerCase();
  let i = 0;
  let idx = lower.indexOf(k, i);
  let n = 0;
  while (idx !== -1) {
    if (idx > i) out.push(text.slice(i, idx));
    out.push(
      <mark key={n++} style={{ background: '#fde68a', color: 'inherit', borderRadius: 2 }}>
        {text.slice(idx, idx + kw.length)}
      </mark>,
    );
    i = idx + kw.length;
    idx = lower.indexOf(k, i);
  }
  out.push(text.slice(i));
  return out;
}

export default function TracePanel({
  traceId,
  requestId,
  raw,
}: {
  traceId?: string;
  requestId?: string;
  raw: unknown;
}) {
  const { message } = App.useApp();
  const [kw, setKw] = useState('');
  const json = useMemo(() => JSON.stringify(raw, null, 2), [raw]);
  const matchCount = kw ? json.toLowerCase().split(kw.toLowerCase()).length - 1 : 0;

  const copy = async (text: string, ok: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success(ok);
    } catch {
      message.error('复制失败(浏览器不支持或无剪贴板权限)');
    }
  };

  const label = (
    <Typography.Text type="secondary">
      调试信息
      {traceId ? <span className="mono"> · trace={traceId}</span> : null}
      {requestId ? <span className="mono"> · req={requestId}</span> : null}
    </Typography.Text>
  );

  const body = (
    <div>
      <Space wrap style={{ marginBottom: 8 }}>
        <Input
          size="small"
          allowClear
          placeholder="过滤 / 高亮关键字"
          value={kw}
          onChange={(e) => setKw(e.target.value)}
          style={{ width: 200 }}
        />
        {kw ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {matchCount} 处匹配
          </Typography.Text>
        ) : null}
        <Button size="small" icon={<CopyOutlined />} onClick={() => copy(json, '已复制 JSON')}>
          复制 JSON
        </Button>
        <Button
          size="small"
          icon={<DownloadOutlined />}
          onClick={() => downloadText(`trace-${traceId || requestId || 'raw'}.json`, json, 'application/json')}
        >
          下载
        </Button>
        {traceId ? (
          <Button size="small" onClick={() => copy(traceId, '已复制 traceId')}>
            复制 trace
          </Button>
        ) : null}
        {requestId ? (
          <Button size="small" onClick={() => copy(requestId, '已复制 requestId')}>
            复制 req
          </Button>
        ) : null}
      </Space>
      <pre className="json-block">{highlight(json, kw)}</pre>
    </div>
  );

  return <Collapse ghost items={[{ key: 'trace', label, children: body }]} />;
}
