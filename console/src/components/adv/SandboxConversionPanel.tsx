import { useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Divider, Input, Space, Typography } from 'antd';
import { UUID_PATTERN, validLongId } from '../../api/sandboxActions';
import { actionQueue } from '../../exposure/actionReceiptQueue';
import ConversionActorScope, { useConversionActor } from './ConversionActorScope';
import SandboxConversionForm from './SandboxConversionForm';
import ConversionOutcome from './ConversionOutcome';
function Panel({ advertiserId }: { advertiserId: string }) {
  const identity = useConversionActor(),
    queue = actionQueue();
  useSyncExternalStore(queue.subscribe, queue.snapshot);
  const [id, setId] = useState(''),
    [error, setError] = useState('');
  if (!validLongId(advertiserId)) return <Alert type="error" message="广告主 ID 无效" />;
  return (
    <Card title="CPA Sandbox 业务事件">
      <SandboxConversionForm
        key={`${identity?.scope}:${identity?.epoch}:${advertiserId}`}
        target={{ advertiserId }}
      />
      <Divider />
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Text>恢复已有业务事件</Typography.Text>
        <Input
          aria-label="查询业务事件 ID"
          placeholder="原 eventId（UUID）"
          value={id}
          maxLength={36}
          onChange={(e) => setId(e.target.value)}
        />
        <Button
          disabled={!identity || !UUID_PATTERN.test(id)}
          onClick={() => {
            setError(
              queue.watchConversion(advertiserId, id) ? '' : '队列已满或身份不可用，请稍后查询。',
            );
          }}
        >
          查询业务事件
        </Button>
        {error && <Alert type="warning" message={error} />}
        {queue.outcomes(advertiserId).map((e) => (
          <Card key={e.key} size="small">
            <ConversionOutcome entry={e} />
          </Card>
        ))}
      </Space>
    </Card>
  );
}
export default function SandboxConversionPanel({ advertiserId }: { advertiserId: string }) {
  return (
    <ConversionActorScope>
      <Panel key={advertiserId} advertiserId={advertiserId} />
    </ConversionActorScope>
  );
}
