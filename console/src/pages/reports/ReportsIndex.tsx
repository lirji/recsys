import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Col, Empty, List, Row, Spin, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { getReportIndex } from '../../api/report';
import { toApiError } from '../../api/client';
import type { ReportFileInfo } from '../../api/types';

const CATS: { key: string; label: string; job: string }[] = [
  { key: 'eval', label: '离线推荐质量', job: 'eval' },
  { key: 'ab-report', label: 'A/B 分桶报表', job: 'ab-report' },
  { key: 'ad-report', label: '广告变现报表', job: 'ad-report' },
  { key: 'data-quality', label: '数据质量巡检', job: 'data-quality' },
  { key: 'ad-quality', label: '广告质量度', job: 'ad-quality' },
  { key: 'other', label: '其它', job: '' },
];

const kb = (n: number) => (n < 1024 ? `${n}B` : `${(n / 1024).toFixed(1)}KB`);

export default function ReportsIndex() {
  const query = useQuery({ queryKey: ['report-index'], queryFn: getReportIndex });
  const files = query.data ?? [];
  const byCat = (c: string) => files.filter((f) => f.category === c);

  if (query.isLoading) return <Spin />;
  if (query.isError) return <Alert type="error" showIcon message={toApiError(query.error).message} />;

  return (
    <>
      <Typography.Paragraph type="secondary">
        读取 <span className="mono">recsys-offline/eval/*.csv</span>(离线评测/报表作业产物)。若为空,先运行对应离线作业生成 CSV。
      </Typography.Paragraph>
      {files.length === 0 ? (
        <Empty description="eval 目录暂无报表 CSV(先跑 eval / ab-report / ad-report / data-quality 等作业)" />
      ) : (
        <Row gutter={[16, 16]}>
          {CATS.filter((c) => byCat(c.key).length > 0).map((c) => (
            <Col key={c.key} xs={24} md={12} xl={8}>
              <Card size="small" title={<span>{c.label} <Tag>{c.key}</Tag></span>}>
                <List<ReportFileInfo>
                  size="small"
                  dataSource={byCat(c.key)}
                  renderItem={(f) => (
                    <List.Item>
                      <Link to={`/reports/${c.key}?file=${encodeURIComponent(f.fileName)}`}>{f.timestamp}</Link>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {kb(f.sizeBytes)}
                      </Typography.Text>
                    </List.Item>
                  )}
                />
              </Card>
            </Col>
          ))}
        </Row>
      )}
    </>
  );
}
