import { Collapse, Typography } from 'antd';

// 调试信息面板:折叠展示 traceId/requestId + 原始 JSON 响应,便于对账/复制。
export default function TracePanel({
  traceId,
  requestId,
  raw,
}: {
  traceId?: string;
  requestId?: string;
  raw: unknown;
}) {
  const label = (
    <Typography.Text type="secondary">
      调试信息
      {traceId ? <span className="mono"> · trace={traceId}</span> : null}
      {requestId ? <span className="mono"> · req={requestId}</span> : null}
    </Typography.Text>
  );
  return (
    <Collapse
      ghost
      items={[
        {
          key: 'trace',
          label,
          children: <pre className="json-block">{JSON.stringify(raw, null, 2)}</pre>,
        },
      ]}
    />
  );
}
