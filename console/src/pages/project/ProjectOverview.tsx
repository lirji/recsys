import { useMemo, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Col,
  Empty,
  Row,
  Space,
  Table,
  Tag,
  Timeline,
  Tooltip,
  Typography,
} from 'antd';
import {
  ApiOutlined,
  ApartmentOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ClusterOutlined,
  CodeOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  DollarOutlined,
  PlayCircleOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import RecFunnelHero from '../../components/RecFunnelHero';
import CollapsibleCard from '../../components/CollapsibleCard';
import StatCard from '../../components/StatCard';
import PageHeader from '../../components/PageHeader';
import { ChartSkeleton, StatCardsSkeleton } from '../../components/Skeletons';
import { getSystemHealth, getSystemMetrics, getSystemOverview } from '../../api/system';
import { toApiError } from '../../api/client';
import { ACCENTS } from '../../theme/tokens';
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
  // 真实实时指标(Prometheus 经 console BFF)。观测栈未起时接口回 available=false → 优雅降级不显示延迟。
  const metricsQuery = useQuery({
    queryKey: ['system-metrics'],
    queryFn: getSystemMetrics,
    refetchInterval: 15_000,
    staleTime: 10_000,
  });

  const overview = overviewQuery.data;
  const modules = overview?.modules ?? [];
  const health = healthQuery.data ?? [];
  const appCount = modules.filter((m) => m.type === 'app').length;
  const libCount = modules.filter((m) => m.type === 'lib').length;
  // 漏斗 hero 的真实数据:整条 recall→rank→rerank 由 recsys-rec-engine 在线编排,故其健康即漏斗活/死;
  // 其余为常驻 HTTP 服务在线比与模块/链路/接口计数(全部来自 /api/console/system,无写死数值)。
  const engineStatus = health.find((h) => h.service === 'recsys-rec-engine')?.status;
  // 只数常驻 HTTP 服务(kind=app):离线/流式作业是 JOB_ONLY 被动态,永远不会 UP,计入分母会误显"未全在线"。
  // 与漏斗 hero 的"在线服务"口径一致(避免同名指标一处 6/8 一处 6/10)。
  const liveApps = health.filter((h) => h.kind === 'app' && h.status === 'UP').length;
  const totalApps = health.filter((h) => h.kind === 'app').length;
  const metrics = metricsQuery.data;
  const metricsAvailable = metrics?.available ?? false;

  // 实时指标卡:值来自 Prometheus,不可用/无流量 → 显示灰色 "—"(不编造)。
  const metricCard = (
    title: string,
    icon: ReactNode,
    value: number | null | undefined,
    accent: string,
    opts?: { suffix?: string; precision?: number },
  ) => {
    const has = metricsAvailable && value != null;
    return (
      <Col xs={12} lg={6}>
        <StatCard
          title={title}
          icon={icon}
          accent={has ? accent : '#c0c6d0'}
          value={has ? (opts?.precision != null ? Number(value).toFixed(opts.precision) : value) : '—'}
          suffix={has ? opts?.suffix : undefined}
          valueColor={has ? undefined : '#bfbfbf'}
        />
      </Col>
    );
  };

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

  if (overviewQuery.isLoading)
    return (
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <ChartSkeleton height={132} />
        <StatCardsSkeleton />
        <ChartSkeleton height={220} />
      </Space>
    );
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
      <PageHeader
        title={`${overview.projectName} 系统总览`}
        accent={ACCENTS.recall}
        description={
          <>
            {overview.description}
            <div style={{ marginTop: 4 }}>
              技术栈:{overview.stack} · 健康检查最近 {checkedAtText(health)}
            </div>
          </>
        }
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
      />

      <RecFunnelHero
        engineStatus={engineStatus}
        healthLoading={healthQuery.isLoading}
        liveApps={liveApps}
        totalApps={totalApps}
        moduleCount={modules.length}
        linkCount={overview.links.length}
        apiCount={overview.apis.length}
        p99Ms={metricsAvailable ? metrics?.recommendP99Ms ?? null : null}
        qps={metricsAvailable ? metrics?.recommendQps ?? null : null}
        adP99Ms={metricsAvailable ? metrics?.adP99Ms ?? null : null}
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="模块总数" value={modules.length} icon={<ClusterOutlined />} accent={ACCENTS.recall} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="可运行服务" value={appCount} icon={<DeploymentUnitOutlined />} accent={ACCENTS.gsp} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="领域模块" value={libCount} icon={<ApartmentOutlined />} accent={ACCENTS.rerank} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="在线服务"
            value={totalApps === 0 ? '-' : `${liveApps}/${totalApps}`}
            icon={<CheckCircleOutlined />}
            accent={ACCENTS.rank}
          />
        </Col>
      </Row>

      <CollapsibleCard
        title="实时链路指标"
        icon={<DashboardOutlined />}
        accent={ACCENTS.rank}
        extra={
          metricsQuery.isLoading ? (
            <Typography.Text type="secondary">检测中…</Typography.Text>
          ) : metricsAvailable ? (
            <Typography.Text type="secondary">数据源 Prometheus · 15 秒自动刷新</Typography.Text>
          ) : (
            <Tooltip title={metrics?.message ?? '未接入观测栈,需 docker compose --profile obs + 正确的 PROMETHEUS_URL'}>
              <Typography.Text type="warning">观测栈未接入 · 设置 PROMETHEUS_URL</Typography.Text>
            </Tooltip>
          )
        }
      >
        <Row gutter={[16, 16]}>
          {metricCard('推荐 P99 延迟', <ClockCircleOutlined />, metrics?.recommendP99Ms, ACCENTS.rank, { suffix: 'ms' })}
          {metricCard('推荐 QPS', <DashboardOutlined />, metrics?.recommendQps, ACCENTS.recall, { precision: 2 })}
          {metricCard('推荐平均延迟', <ThunderboltOutlined />, metrics?.recommendAvgMs, ACCENTS.gsp, { suffix: 'ms' })}
          {metricCard('广告 P99 延迟', <DollarOutlined />, metrics?.adP99Ms, ACCENTS.ad, { suffix: 'ms' })}
        </Row>
      </CollapsibleCard>

      <CollapsibleCard
        title="服务健康"
        icon={<CheckCircleOutlined />}
        accent={ACCENTS.gsp}
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
          <ChartSkeleton height={200} />
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
      </CollapsibleCard>

      <CollapsibleCard title="核心链路" icon={<PlayCircleOutlined />} accent={ACCENTS.recall}>
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
      </CollapsibleCard>

      <CollapsibleCard title="模块地图" icon={<ClusterOutlined />} accent={ACCENTS.rerank}>
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
      </CollapsibleCard>

      <CollapsibleCard title="API 目录" icon={<ApiOutlined />} accent={ACCENTS.ad}>
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
      </CollapsibleCard>

      <CollapsibleCard title="启动命令" icon={<CodeOutlined />} accent={ACCENTS.gsp}>
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
      </CollapsibleCard>
    </Space>
  );
}
