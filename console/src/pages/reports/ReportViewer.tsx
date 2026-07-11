import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Breadcrumb, Card, Select, Space } from 'antd';
import { FileSearchOutlined, LineChartOutlined } from '@ant-design/icons';
import { ChartSkeleton } from '../../components/Skeletons';
import EmptyState from '../../components/EmptyState';
import CollapsibleCard from '../../components/CollapsibleCard';
import { ACCENTS } from '../../theme/tokens';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { getReportFile, getReportIndex, vizCategoryOf, type VizCategory } from '../../api/report';
import { toApiError } from '../../api/client';
import type { ReportTable } from '../../api/types';
import EvalViz from '../../components/reports/viz/EvalViz';
import AbViz from '../../components/reports/viz/AbViz';
import AdReportViz from '../../components/reports/viz/AdReportViz';
import DataQualityViz from '../../components/reports/viz/DataQualityViz';
import AdQualityViz from '../../components/reports/viz/AdQualityViz';
import AttributionViz from '../../components/reports/viz/AttributionViz';
import DelayViz from '../../components/reports/viz/DelayViz';
import ReportCompare from '../../components/reports/ReportCompare';
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
    case 'ad-attribution':
      return <AttributionViz table={table} />;
    case 'ad-delay':
      return <DelayViz table={table} />;
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
  const [compareFiles, setCompareFiles] = useState<string[]>([]);

  const index = useQuery({ queryKey: ['report-index'], queryFn: getReportIndex });
  // 按「文件名前缀派生的分类」过滤,而非后端 category —— 这样 ad-attribution 等隐藏类型也能各自成组、dispatch 到专属 viz。
  const filesOfCat = useMemo(
    () => (index.data ?? []).filter((f) => vizCategoryOf(f.fileName) === category),
    [index.data, category],
  );
  // 未指定 file 时默认取该分类最新一份。
  const selected = fileParam || filesOfCat[0]?.fileName || '';

  const fileQuery = useQuery({
    queryKey: ['report-file', selected],
    queryFn: () => getReportFile(selected),
    enabled: !!selected,
  });

  const compareInfos = useMemo(
    () => filesOfCat.filter((f) => compareFiles.includes(f.fileName)),
    [filesOfCat, compareFiles],
  );

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Breadcrumb items={[{ title: <Link to="/reports">报表总览</Link> }, { title: category }]} />
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

      {filesOfCat.length >= 2 ? (
        <CollapsibleCard
          title="趋势对比(跨时间戳,选 2+ 份叠加)"
          icon={<LineChartOutlined />}
          accent={ACCENTS.recall}
          defaultOpen={false}
        >
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Select
              mode="multiple"
              allowClear
              style={{ width: '100%' }}
              placeholder="选择 2 份及以上同类型报表进行趋势对比"
              value={compareFiles}
              onChange={setCompareFiles}
              maxTagCount="responsive"
              options={filesOfCat.map((f) => ({ value: f.fileName, label: f.timestamp }))}
            />
            {compareInfos.length >= 2 ? (
              <ReportCompare category={category as VizCategory} files={compareInfos} />
            ) : (
              <Alert type="info" showIcon message="至少选择 2 份报表以查看趋势折线" />
            )}
          </Space>
        </CollapsibleCard>
      ) : null}

      {!selected ? (
        <EmptyState
          icon={<FileSearchOutlined />}
          accent={ACCENTS.rank}
          title="该分类暂无报表文件"
          description="先运行对应的离线评测 / 报表作业生成 CSV,再回来查看。"
        />
      ) : fileQuery.isLoading ? (
        <ChartSkeleton height={360} />
      ) : fileQuery.isError ? (
        <Alert type="error" showIcon message={toApiError(fileQuery.error).message} />
      ) : fileQuery.data ? (
        renderViz(category, fileQuery.data)
      ) : null}
    </Space>
  );
}
