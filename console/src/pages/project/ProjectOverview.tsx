import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Row,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import {
  ApiOutlined,
  ApartmentOutlined,
  CheckCircleOutlined,
  ClusterOutlined,
  CodeOutlined,
  DeploymentUnitOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { getSystemHealth, getSystemOverview } from '../../api/system';
import { toApiError } from '../../api/client';
import type { ServiceHealth, SystemApiEndpoint, SystemLink, SystemModule } from '../../api/types';

const typeColor: Record<string, string> = {
  frontend: 'cyan',
  app: 'blue',
  lib: 'purple',
  job: 'orange',
};

const statusColor = (status: string) => {
  if (status === 'UP') return 'success';
  if (status === 'JOB_ONLY' || status === 'LOCAL_CLUSTER_JOB') return 'processing';
  if (status === 'UNKNOWN') return 'warning';
  return 'error';
};

const renderPath = (path?: string | null) =>
  path ? (
    <Link to={path}>
      <Button size="small" type="link" style={{ paddingInline: 0 }}>
        打开控制台
      </Button>
    </Link>
  ) : (
    <Typography.Text type="secondary">-</Typography.Text>
  );

const checkedAtText = (health: ServiceHealth[]) => {
  const latest = health.reduce((max, item) => Math.max(max, item.checkedAt), 0);
  return latest > 0 ? new Date(latest).toLocaleTimeString() : '-';
};

export default function ProjectOverview() {
  const overviewQuery = useQuery({
    queryKey: ['system-overview'],
    queryFn: getSystemOverview,
    staleTime: 5 * 60_000,
  });
  const healthQuery = useQuery({
    queryKey: ['system-health'],
    queryFn: getSystemHealth,
    refetchInterval: 15_000,
    staleTime: 10_000,
  });

  const overview = overviewQuery.data;
  const modules = overview?.modules ?? [];
  const health = healthQuery.data ?? [];
  const appCount = modules.filter((m) => m.type === 'app').length;
  const libCount = modules.filter((m) => m.type === 'lib').length;
  const liveCount = health.filter((h) => h.status === 'UP').length;

  const moduleColumns = useMemo(
    () => [
      {
        title: '模块',
        dataIndex: 'name',
        width: 180,
        render: (name: string, row: SystemModule) => (
          <Space direction="vertical" size={0}>
            <Typography.Text strong>{name}</Typography.Text>
            <Tag color={typeColor[row.type] ?? 'default'}>{row.type}</Tag>
          </Space>
        ),
      },
      { title: '职责', dataIndex: 'description' },
      {
        title: '端口',
        width: 120,
        render: (_: unknown, row: SystemModule) => (
          <Space direction="vertical" size={0}>
            <Typography.Text>{row.port ?? '-'}</Typography.Text>
            {row.grpcPort ? <Typography.Text type="secondary">gRPC {row.grpcPort}</Typography.Text> : null}
          </Space>
        ),
      },
      {
        title: '网关路径',
        dataIndex: 'gatewayPrefixes',
        width: 220,
        render: (paths: string[]) =>
          paths.length > 0 ? paths.map((p) => <Tag key={p}>{p}</Tag>) : <Typography.Text type="secondary">内部/无</Typography.Text>,
      },
      {
        title: '前端入口',
        dataIndex: 'frontendPath',
        width: 100,
        render: renderPath,
      },
    ],
    [],
  );

  const healthColumns = useMemo(
    () => [
      {
        title: '服务',
        dataIndex: 'name',
        render: (name: string, row: ServiceHealth) => (
          <Space direction="vertical" size={0}>
            <Typography.Text strong>{name}</Typography.Text>
            <Typography.Text type="secondary" className="mono">
              {row.service}
            </Typography.Text>
          </Space>
        ),
      },
      {
        title: '状态',
        dataIndex: 'status',
        width: 130,
        render: (status: string) => <Tag color={statusColor(status)}>{status}</Tag>,
      },
      {
        title: '地址',
        dataIndex: 'url',
        width: 220,
        render: (url?: string | null) => (url ? <Typography.Text className="mono">{url}</Typography.Text> : '-'),
      },
      { title: '说明', dataIndex: 'message' },
      {
        title: '检查时间',
        dataIndex: 'checkedAt',
        width: 120,
        render: (ts: number) => new Date(ts).toLocaleTimeString(),
      },
    ],
    [],
  );

  const apiColumns = useMemo(
    () => [
      {
        title: '方法',
        dataIndex: 'method',
        width: 120,
        render: (method: string) => <Tag color="geekblue">{method}</Tag>,
      },
      {
        title: '路径',
        dataIndex: 'path',
        width: 260,
        render: (path: string) => <Typography.Text className="mono">{path}</Typography.Text>,
      },
      { title: '服务', dataIndex: 'service', width: 180 },
      { title: '说明', dataIndex: 'description' },
      {
        title: '页面',
        dataIndex: 'frontendPath',
        width: 90,
        render: renderPath,
      },
    ],
    [],
  );

  if (overviewQuery.isLoading) return <Spin />;
  if (overviewQuery.isError) {
    return (
      <Alert
        type="error"
        showIcon
        message="系统总览加载失败"
        description={toApiError(overviewQuery.error).message}
        action={<Button onClick={() => overviewQuery.refetch()}>重试</Button>}
      />
    );
  }
  if (!overview) return <Empty description="系统总览暂无数据,请确认 recsys-console 已启动。" />;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        bordered={false}
        extra={
          <Button
            loading={overviewQuery.isFetching || healthQuery.isFetching}
            onClick={() => {
              overviewQuery.refetch();
              healthQuery.refetch();
            }}
          >
            刷新
          </Button>
        }
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {overview.projectName} 系统总览
          </Typography.Title>
          <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
            {overview.description}
          </Typography.Paragraph>
          <Descriptions size="small" column={1}>
            <Descriptions.Item label="技术栈">{overview.stack}</Descriptions.Item>
            <Descriptions.Item label="健康检查">最近检查 {checkedAtText(health)}</Descriptions.Item>
          </Descriptions>
        </Space>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="模块总数" value={modules.length} prefix={<ClusterOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="可运行服务" value={appCount} prefix={<DeploymentUnitOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="领域模块" value={libCount} prefix={<ApartmentOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic title="在线服务" value={health.length === 0 ? '-' : `${liveCount}/${health.length}`} prefix={<CheckCircleOutlined />} />
          </Card>
        </Col>
      </Row>

      <Card
        title="服务健康"
        extra={
          <Space>
            {healthQuery.isFetching ? <Typography.Text type="secondary">刷新中</Typography.Text> : null}
            <Typography.Text type="secondary">15 秒自动刷新</Typography.Text>
            <Button size="small" onClick={() => healthQuery.refetch()}>
              刷新健康
            </Button>
          </Space>
        }
      >
        {healthQuery.isError ? (
          <Alert
            type="error"
            showIcon
            message="健康状态加载失败"
            description={toApiError(healthQuery.error).message}
            action={<Button onClick={() => healthQuery.refetch()}>重试</Button>}
          />
        ) : healthQuery.isLoading ? (
          <Spin />
        ) : health.length === 0 ? (
          <Empty description="暂无健康检查结果,服务可能未配置 health target。" />
        ) : (
          <Table<ServiceHealth>
            rowKey="service"
            size="small"
            columns={healthColumns}
            dataSource={health}
            pagination={false}
            scroll={{ x: 860 }}
          />
        )}
      </Card>

      <Card title="核心链路" extra={<PlayCircleOutlined />}>
        <Row gutter={[16, 16]}>
          {overview.links.map((link: SystemLink) => (
            <Col key={link.name} xs={24} lg={12} xl={8}>
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Space>
                  <Typography.Text strong>{link.name}</Typography.Text>
                  {renderPath(link.frontendPath)}
                </Space>
                <Typography.Text type="secondary">{link.description}</Typography.Text>
                <Timeline
                  style={{ marginTop: 8 }}
                  items={link.steps.map((step) => ({
                    children: <Typography.Text className="mono">{step}</Typography.Text>,
                  }))}
                />
              </Space>
            </Col>
          ))}
        </Row>
      </Card>

      <Card title="模块地图">
        {modules.length === 0 ? (
          <Empty description="暂无模块元数据。" />
        ) : (
          <Table<SystemModule>
            rowKey="name"
            size="small"
            columns={moduleColumns}
            dataSource={modules}
            pagination={{ pageSize: 12, showSizeChanger: false }}
            scroll={{ x: 980 }}
          />
        )}
      </Card>

      <Card title="API 目录" extra={<ApiOutlined />}>
        {overview.apis.length === 0 ? (
          <Empty description="暂无 API 目录。" />
        ) : (
          <Table<SystemApiEndpoint>
            rowKey={(row) => `${row.method}:${row.path}`}
            size="small"
            columns={apiColumns}
            dataSource={overview.apis}
            pagination={false}
            scroll={{ x: 900 }}
          />
        )}
      </Card>

      <Card title="启动命令" extra={<CodeOutlined />}>
        {overview.commands.length === 0 ? (
          <Empty description="暂无启动命令。" />
        ) : (
          <Row gutter={[16, 16]}>
            {overview.commands.map((group) => (
              <Col key={group.name} xs={24} lg={12}>
                <Typography.Title level={5}>{group.name}</Typography.Title>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {group.items.map((item) => (
                    <div key={item.label}>
                      <Typography.Text type="secondary">{item.label}</Typography.Text>
                      <Typography.Paragraph className="json-block" copyable style={{ marginTop: 4 }}>
                        {item.command}
                      </Typography.Paragraph>
                    </div>
                  ))}
                </Space>
              </Col>
            ))}
          </Row>
        )}
      </Card>
    </Space>
  );
}
