import { Table } from 'antd';
import type { ReportTable } from '../../api/types';

// 通用 CSV → 表格:直接按表头渲染所有列(全部当文本)。
export default function CsvTable({ table }: { table: ReportTable }) {
  const columns = table.columns.map((c, i) => ({
    title: c,
    dataIndex: String(i),
    key: String(i),
    render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
  }));
  const dataSource = table.rows.map((r, idx) => {
    const o: Record<string, string> = { key: String(idx) };
    r.forEach((cell, i) => (o[String(i)] = cell));
    return o;
  });
  return (
    <Table
      size="small"
      columns={columns}
      dataSource={dataSource}
      pagination={dataSource.length > 30 ? { pageSize: 30 } : false}
      scroll={{ x: 'max-content' }}
    />
  );
}
