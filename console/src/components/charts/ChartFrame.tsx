import { useRef } from 'react';
import ReactECharts from 'echarts-for-react';
import { Button } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { ECHARTS_THEME } from './echartsTheme';
import { downloadDataUrl } from '../../utils/download';

// 图表通用外壳:统一走 recsys 主题 + 右上角一键导出 PNG(pixelRatio=2 高清、白底便于粘贴文档)。
// 所有可复用图表(EBar/ERadar/EGauge/EFunnel/EErrorBar/ELine)都套它,避免各自重复 export 逻辑。
export default function ChartFrame({
  option,
  height = 320,
  fileName = 'chart.png',
  onEvents,
}: {
  // echarts-for-react 的 option 本就是 any;此处保持宽松,由各图表构造具体 option。
  option: Record<string, unknown>;
  height?: number;
  fileName?: string;
  onEvents?: Record<string, (...args: unknown[]) => void>;
}) {
  const ref = useRef<ReactECharts>(null);
  const exportPng = () => {
    const inst = ref.current?.getEchartsInstance();
    if (!inst) return;
    downloadDataUrl(fileName, inst.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }));
  };
  return (
    <div style={{ position: 'relative' }}>
      <Button
        size="small"
        type="text"
        icon={<DownloadOutlined />}
        onClick={exportPng}
        title="导出 PNG"
        style={{ position: 'absolute', right: 0, top: 0, zIndex: 1, color: '#8a94a6' }}
      />
      <ReactECharts
        ref={ref}
        option={option}
        theme={ECHARTS_THEME}
        style={{ height }}
        notMerge
        lazyUpdate
        onEvents={onEvents}
      />
    </div>
  );
}
