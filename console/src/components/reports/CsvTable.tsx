import { Button, Space, Table } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../api/types';
import { downloadText, toCsv } from '../../utils/download';

const BOM = String.fromCharCode(0xfeff); // Excel 打开 UTF-8 CSV 中文不乱码

// 通用 CSV → 表格:直接按表头渲染所有列(全部当文本),带「导出 CSV」。
export default function CsvTable({ table }: { table: ReportTable }) {
  const columns = table.columns.map((c, i) => ({
    title: c,
    dataIndex: String(i),
    key: String(i),
    render: (v: string) => (
      <span className="mono" style={{ fontSize: 12 }}>
        {v}
      </span>
    ),
  }));
  const dataSource = table.rows.map((r, idx) => {
    const o: Record<string, string> = { key: String(idx) };
    r.forEach((cell, i) => (o[String(i)] = cell));
    return o;
  });
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <div style={{ textAlign: 'right' }}>
        <Button
          size="small"
          icon={<DownloadOutlined />}
          onClick={() => downloadText(table.fileName || 'report.csv', BOM + toCsv(table.columns, table.rows), 'text/csv')}
        >
          导出 CSV
        </Button>
      </div>
      <Table
        size="small"
        columns={columns}
        dataSource={dataSource}
        pagination={dataSource.length > 30 ? { pageSize: 30 } : false}
        scroll={{ x: 'max-content' }}
      />
    </Space>
  );
}
