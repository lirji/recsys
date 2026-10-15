import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReportTable } from '../../../api/types';
import { inspectDataQuality } from '../dataQualityStatus';
import DataQualityViz from '../viz/DataQualityViz';

vi.mock('../../charts/EGauge', () => ({
  default: ({ title }: { title: string }) => <div data-testid="gauge">{title}</div>,
  higherBetterBands: () => [],
  lowerBetterBands: () => [],
}));
vi.mock('../CliCommandCard', () => ({ default: () => null }));

// 与既有Ant Design测试一致，jsdom不提供响应式媒体查询API。
beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
});

const healthy: ReportTable = {
  fileName: 'data-quality-fixture.csv',
  category: 'data-quality',
  columns: ['metric', 'value'],
  rows: [
    ['item_embedding_coverage', '1'],
    ['user_embedding_coverage', '1'],
    ['pctr_ece', '0.01'],
    ['category_psi_7d', '0.02'],
    ['breaches', '0'],
    ['check_embedding_status', 'PASSED'],
    ['check_calibration_status', 'PASSED'],
    ['check_category_status', 'PASSED'],
  ],
};
const changed = (metric: string, value?: string): ReportTable => ({
  ...healthy,
  rows: healthy.rows
    .filter((row) => row[0] !== metric)
    .concat(value === undefined ? [] : [[metric, value]]),
});

describe('巡检真实状态', () => {
  it('完整健康数据才显示成功与四个仪表', () => {
    render(<DataQualityViz table={healthy} />);
    expect(screen.getByText('三项检查已完成，无越阈值项')).toBeInTheDocument();
    expect(screen.getByText('向量覆盖率：通过')).toBeInTheDocument();
    expect(screen.getAllByTestId('gauge')).toHaveLength(4);
  });
  it('部分失败显示目录码并保留其余有效检查', () => {
    render(<DataQualityViz table={changed('check_category_status', 'FAILED')} />);
    expect(screen.getByText(/\[JOB_DATA_QUALITY_CHECK_FAILED\]/)).toBeInTheDocument();
    expect(screen.getByText('类目分布：检查失败')).toBeInTheDocument();
    expect(screen.getAllByTestId('gauge')).toHaveLength(3);
    expect(screen.queryByText('三项检查已完成，无越阈值项')).not.toBeInTheDocument();
  });
  it('分母0的约定覆盖率不当作健康仪表', () => {
    render(<DataQualityViz table={changed('check_embedding_status', 'NO_DATA')} />);
    expect(screen.getByText(/\[JOB_DATA_QUALITY_INCOMPLETE\]/)).toBeInTheDocument();
    expect(screen.getByText('向量覆盖率：缺少数据')).toBeInTheDocument();
    expect(screen.getAllByTestId('gauge')).toHaveLength(2);
  });
  it('旧报表不显示成功和未经确认的健康仪表', () => {
    render(
      <DataQualityViz
        table={{ ...healthy, rows: healthy.rows.filter((row) => !row[0].startsWith('check_')) }}
      />,
    );
    expect(screen.getByText(/\[JOB_DATA_QUALITY_INCOMPLETE\]/)).toBeInTheDocument();
    expect(screen.queryAllByTestId('gauge')).toHaveLength(0);
  });
  it('未知状态只展示受控标签，原始字段仍属于明细', () => {
    render(<DataQualityViz table={changed('check_category_status', 'private SQL password')} />);
    const statuses = screen.getByLabelText('巡检检查状态');
    expect(within(statuses).getByText('类目分布：状态未知')).toBeInTheDocument();
    expect(statuses).not.toHaveTextContent('private');
  });
  it.each([
    ['breaches', '-1'],
    ['breaches', '0.5'],
    ['breaches', ' '],
    ['pctr_ece', 'NaN'],
    ['item_embedding_coverage', '1.1'],
    ['category_psi_7d', 'Infinity'],
    ['pctr_ece', ''],
  ])('非法%s=%s不得假成功', (metric, value) => {
    expect(inspectDataQuality(changed(metric, value)).code).toBe('JOB_DATA_QUALITY_INCOMPLETE');
  });
  it('状态越阈值或旧自由文本告警不被0计数掩盖', () => {
    expect(inspectDataQuality(changed('check_category_status', 'BREACHED')).code).toBe(
      'JOB_DATA_QUALITY_BREACHED',
    );
    render(
      <DataQualityViz
        table={{ ...healthy, rows: healthy.rows.concat([['breach', 'private SQL password']]) }}
      />,
    );
    expect(screen.getByText(/\[JOB_DATA_QUALITY_BREACHED\]/)).toBeInTheDocument();
    expect(screen.getByRole('alert')).not.toHaveTextContent('private');
  });
  it('缺少或重复PSI以及重复检查状态均视作完整性未知', () => {
    expect(inspectDataQuality(changed('category_psi_7d')).code).toBe('JOB_DATA_QUALITY_INCOMPLETE');
    for (const row of [
      ['category_psi_0d', '0.02'],
      ['check_category_status', 'PASSED'],
    ]) {
      expect(inspectDataQuality({ ...healthy, rows: healthy.rows.concat([row]) }).code).toBe(
        'JOB_DATA_QUALITY_INCOMPLETE',
      );
    }
  });
});
