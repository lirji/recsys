import { Card, Col, Row } from 'antd';

// 与最终布局同形的骨架屏(shimmer 见 index.css 的 .skl,reduced-motion 已在那关掉动画)。
// 让页面「先长出结构再填数据」,替换全站裸 <Spin/>。

// 结果行:仿 .itc-row —— 灰 rank 方块 + 两条灰线 + 灰分数条。
export function ResultRowsSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, width: '100%' }}>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="itc-row" style={{ borderLeft: '3px solid #eef1f7' }}>
          <div className="skl" style={{ width: 30, height: 30, borderRadius: 9, flex: '0 0 auto' }} />
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 7 }}>
            <div className="skl" style={{ width: '32%', height: 13 }} />
            <div className="skl" style={{ width: '58%', height: 11 }} />
          </div>
          <div className="skl" style={{ width: 150, height: 6, borderRadius: 3, flex: '0 0 auto' }} />
        </div>
      ))}
    </div>
  );
}

// 图表块:占位到目标高度。
export function ChartSkeleton({ height = 320 }: { height?: number }) {
  return <div className="skl" style={{ width: '100%', height, borderRadius: 10 }} />;
}

// KPI 卡组:仿 StatCard —— 图标砖 + 标题线 + 大号数值线。
export function StatCardsSkeleton({ count = 4 }: { count?: number }) {
  return (
    <Row gutter={[16, 16]}>
      {Array.from({ length: count }).map((_, i) => (
        <Col key={i} xs={24} sm={12} lg={6}>
          <Card size="small">
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <div className="skl" style={{ width: 40, height: 40, borderRadius: 11, flex: '0 0 auto' }} />
              <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8 }}>
                <div className="skl" style={{ width: '55%', height: 11 }} />
                <div className="skl" style={{ width: '40%', height: 20 }} />
              </div>
            </div>
          </Card>
        </Col>
      ))}
    </Row>
  );
}
