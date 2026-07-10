import { Steps } from 'antd';

// 流水线示意:把某场景的处理阶段画成一条步骤条,帮助理解「这次请求经过了哪些环节」。
type Mode = 'rec' | 'search' | 'ads' | 'feed';

const PRESETS: Record<Mode, { title: string; description: string }[]> = {
  rec: [
    { title: '召回', description: '多通道候选' },
    { title: '排序', description: 'ONNX / 规则打分' },
    { title: '融合', description: 'recall×rank + 通道加成' },
    { title: '重排', description: 'diversity / mmr / dpp' },
    { title: '截断', description: 'top-N + 理由' },
  ],
  search: [
    { title: 'Query 理解', description: '分词 / 意图 / 向量化' },
    { title: '混合召回', description: 'RRF(词法+向量)' },
    { title: '排序', description: 'query↔item 相关性主导' },
    { title: '重排', description: '多样性' },
  ],
  ads: [
    { title: 'Query 理解', description: '关键词 / 向量' },
    { title: '广告召回', description: '倒排 / 语义 / U2A' },
    { title: '相关性门槛', description: 'RelevanceGate' },
    { title: 'pCTR/pCVR', description: '复用排序模型' },
    { title: '校准', description: '保序回归' },
    { title: 'oCPC 出价', description: 'bid=targetCpa·pCVR·k' },
    { title: 'eCPM 竞价', description: '排序' },
    { title: 'GSP 计费', description: '次价' },
  ],
  feed: [
    { title: '自然推荐', description: '推荐漏斗' },
    { title: '广告竞价', description: 'eCPM/GSP' },
    { title: '频控', description: 'FrequencyCap' },
    { title: '混排', description: 'Ad Load 位次/密度' },
  ],
};

export default function PipelineSteps({ mode }: { mode: Mode }) {
  const items = PRESETS[mode].map((s) => ({ ...s }));
  return (
    <div style={{ overflowX: 'auto', paddingBottom: 4 }}>
      <div style={{ minWidth: mode === 'ads' ? 820 : 560 }}>
        <Steps size="small" labelPlacement="vertical" current={items.length} status="finish" items={items} />
      </div>
    </div>
  );
}
