import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Breadcrumb, Card, Empty, Select, Space, Spin } from 'antd';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { getReportFile, getReportIndex } from '../../api/report';
import { toApiError } from '../../api/client';
import type { ReportTable } from '../../api/types';
import EvalViz from '../../components/reports/viz/EvalViz';
import AbViz from '../../components/reports/viz/AbViz';
import AdReportViz from '../../components/reports/viz/AdReportViz';
import DataQualityViz from '../../components/reports/viz/DataQualityViz';
import AdQualityViz from '../../components/reports/viz/AdQualityViz';
import CsvTable from '../../components/reports/CsvTable';

function renderViz(category: string, table: ReportTable) {
  switch (category) {
    case 'eval':
      return <EvalViz table={table} />;
    case 'ab-report':
      return <AbViz table={table} />;
    case 'ad-report':
      return <AdReportViz table={table} />;
    case 'data-quality':
      return <DataQualityViz table={table} />;
    case 'ad-quality':
      return <AdQualityViz table={table} />;
    default:
      return (
        <Card size="small" title="明细">
          <CsvTable table={table} />
        </Card>
      );
  }
}

export default function ReportViewer() {
  const { category = 'other' } = useParams();
  const [search, setSearch] = useSearchParams();
  const fileParam = search.get('file') ?? '';

  const index = useQuery({ queryKey: ['report-index'], queryFn: getReportIndex });
  const filesOfCat = useMemo(
    () => (index.data ?? []).filter((f) => f.category === category),
    [index.data, category],
  );
  // 未指定 file 时默认取该分类最新一份。
  const selected = fileParam || filesOfCat[0]?.fileName || '';

  const fileQuery = useQuery({
    queryKey: ['report-file', selected],
    queryFn: () => getReportFile(selected),
    enabled: !!selected,
  });

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Breadcrumb
        items={[{ title: <Link to="/reports">报表总览</Link> }, { title: category }]}
      />
      <Space wrap>
        <span>选择文件</span>
        <Select
          style={{ minWidth: 320 }}
          value={selected || undefined}
          placeholder="该分类暂无文件"
          onChange={(v) => setSearch({ file: v })}
          options={filesOfCat.map((f) => ({ value: f.fileName, label: `${f.timestamp} (${f.fileName})` }))}
        />
      </Space>

      {index.isError ? <Alert type="error" showIcon message={toApiError(index.error).message} /> : null}
      {!selected ? (
        <Empty description="该分类暂无报表文件" />
      ) : fileQuery.isLoading ? (
        <Spin />
      ) : fileQuery.isError ? (
        <Alert type="error" showIcon message={toApiError(fileQuery.error).message} />
      ) : fileQuery.data ? (
        renderViz(category, fileQuery.data)
      ) : null}
    </Space>
  );
}
