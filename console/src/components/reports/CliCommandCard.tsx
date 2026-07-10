import { App, Button, Space, Typography } from 'antd';
import { CopyOutlined } from '@ant-design/icons';

// 展示「如何重新生成该报表」的离线 CLI 命令,一键复制。
export default function CliCommandCard({ job, note }: { job: string; note?: string }) {
  const { message } = App.useApp();
  const cmd = `mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=${job}`;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(cmd);
      message.success('已复制命令');
    } catch {
      message.warning('复制失败,请手动选择');
    }
  };
  return (
    <div
      style={{
        background: '#f6f8fa',
        border: '1px solid #eaecef',
        borderRadius: 8,
        padding: '10px 12px',
      }}
    >
      <Space style={{ width: '100%', justifyContent: 'space-between' }} align="start">
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            重新生成此报表(离线作业 <b>{job}</b>){note ? ` · ${note}` : ''}
          </Typography.Text>
          <pre className="mono" style={{ margin: '6px 0 0', fontSize: 12, whiteSpace: 'pre-wrap' }}>
            {cmd}
          </pre>
        </div>
        <Button size="small" icon={<CopyOutlined />} onClick={copy}>
          复制
        </Button>
      </Space>
    </div>
  );
}
