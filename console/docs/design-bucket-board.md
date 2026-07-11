# 设计规格:桶对比大盘 (Bucket Comparison Dashboard) — `/bucket-board`

**Status**: Ready for build  **Author**: UI Designer  **For**: Frontend Dev
**Domain**: 实验与增长  **Route**: `/bucket-board`  **PRD**: `console/docs/prd-experiment-growth-p1.md`
**Constraint**: 纯前端 · 零后端改动 · AntD5 + ECharts only · 全中文 · 复用既有件/主题/色板。

> 本规格的目标:前端**零设计决策**。每个尺寸/颜色/图标/文案/降级分支都已定死。
> 一切颜色、间距、字号引用既有 token(`src/theme/tokens.ts`)与既有件,不新增设计资产。

---

## 0. 复用清单(照抄,不重造)

| 用途 | 件 / token | 位置 |
|------|-----------|------|
| 页头 | `PageHeader`(`accent` = `ACCENTS.rank` = `#22d3ee`) | `components/PageHeader.tsx` |
| KPI 砖 | `StatCard` | `components/StatCard.tsx` |
| 误差棒图 | `EErrorBar`(**本规格新增 2 个可选 prop,§4**) | `components/charts/EErrorBar.tsx` |
| 分组柱(层×变体可选) | `EBar` | `components/charts/EBar.tsx` |
| 空态 | `EmptyState` | `components/EmptyState.tsx` |
| 骨架 | `StatCardsSkeleton` / `ChartSkeleton` | `components/Skeletons.tsx` |
| 可折叠卡(层×变体) | `CollapsibleCard` | `components/CollapsibleCard.tsx` |
| 数据 + 取数 + 汇总 | `useAbReport` / `AbBucketRow` / `significantBuckets` / `variantOnlineStat` | `components/experiment/abReport.ts` |
| 实验配置 | `getExperiment()` → `ExperimentSnapshot.staticLayers` | `api/experiment.ts` / `api/types.ts` |
| 色 token | `ACCENTS` / `STATUS` / `rgba()` / `hexOfPreset()` | `theme/tokens.ts` |
| 通道/变体配色 | `channelColor(variant)` | `components/explain/channelColors.ts` |
| 等宽数字 | class `mono` | `index.css:12` |

**主题事实(必须遵守)**:
- 站点当前**仅浅色**(代码内无 `darkAlgorithm` / `data-theme` / `prefers-color-scheme`)。全部取色按浅色玻璃卡背景做 AA 对比;所有颜色一律引 token,未来若接暗色算法可整体继承 —— **不要为本页自造暗色处理**。
- AntD `5.21.4` / ECharts `5.5.1` → `Segmented`、`Alert action`、`Table sorter` 均原生可用。
- 卡片外观由 `index.css` 的 `.ant-card-bordered` 玻璃样式统一接管 —— **只用 `<Card>` / `<Card size="small">`,不写自定义卡背景**。

---

## 1. 页面布局与结构

外层沿用既有页面节奏:`<Space direction="vertical" size={16} style={{ width:'100%' }}>` 包裹自上而下 6 段(与 `StrategyLab` / `ExperimentConsole` 同构)。取数**两路解耦**(参照 `ExperimentConsole` 的 `query` 与 `abQuery` 独立):`useAbReport()` 驱动 ①~⑤;`getExperiment()` 只驱动 ⑥,任一路失败不阻塞另一路。

```
┌───────────────────────────────────────────────────────────────────────────┐
│ ① PageHeader   桶对比大盘        accent=#22d3ee(rank)    extra=报表文件名(mono)│
│    「一屏读懂:哪些桶显著赢/输基线、样本够不够、是否疑似 AA 有偏。」            │
├───────────────────────────────────────────────────────────────────────────┤
│ ② 结论条 Verdict Bar   ← 全页记忆点,0 点击可读(§2)                          │
│   ┌───────────────────────────────────────────────────────────────────┐   │
│   │ (icon) 有 3 个桶显著优于基线          [ 去调流量 → ]                 │   │
│   │        胜出桶:…  最大正向 lift +8.2%。若含与基线同策略桶(AA)…       │   │
│   └───────────────────────────────────────────────────────────────────┘   │
├───────────────────────────────────────────────────────────────────────────┤
│ ③ KPI 行   Row gutter=[16,16]  · 4 砖 · xs=24 sm=12 lg=6(§3)               │
│   ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐                              │
│   │分桶数  │ │基线 CTR│ │显著桶数│ │最大 lift│                             │
│   │  7     │ │ 4.31%  │ │ 3 / 6  │ │ +8.20% │                              │
│   └────────┘ └────────┘ └────────┘ └────────┘                              │
├───────────────────────────────────────────────────────────────────────────┤
│ ④ 对比区(Card)                                                             │
│   ┌ 控制条 ────────────────────────────────────────────────────────────┐  │
│   │ [完整分桶│层×变体汇总]   排序[原始│CTR│lift│p值│曝光]   仅显著 (⚪)  │  │
│   └────────────────────────────────────────────────────────────────────┘  │
│   ┌ 视图 A:完整分桶 ───────────────────────────────────────────────────┐  │
│   │  EErrorBar:柱=CTR% · 须=Wilson 95%CI · ★显著 · 基线柱高亮 · 基线横线 │  │
│   │            height=360                                                │  │
│   └────────────────────────────────────────────────────────────────────┘  │
│   ┌ 视图 B:层×变体汇总(Segmented 切到此)——§6 ─────────────────────────┐  │
│   │  每层一张 CollapsibleCard(accent=#8b5cf6 rerank):变体边际 CTR 汇总  │  │
│   └────────────────────────────────────────────────────────────────────┘  │
├───────────────────────────────────────────────────────────────────────────┤
│ ⑤ 明细大表(Card)  Table<AbBucketRow>  scroll={{x:'max-content'}}(§5)       │
│   分桶 | 曝光 | 点击 | CTR | 95%CI | lift | p值 | 显著 | 最小样本/臂          │
│   基线行浅青底高亮 · lift 正绿负红 · 列头可排序 · ∞/样本不足 橙标            │
└───────────────────────────────────────────────────────────────────────────┘
```

**段序固定**:PageHeader → 结论条 → KPI 行 → 对比区(含视图切换) → 明细表。视图切换(完整分桶 ↔ 层×变体)在**对比区内部**,不改变外层段序。

---

## 2. 结论条 Verdict Bar(本页核心,0 点击可读)

**容器**:一个 `<Alert>`(靠 `type` 出底色/图标),按钮塞进 Alert 原生 `action` 槽 —— 不额外包 Card,让它在 KPI 之上独立成条、视觉最重。

```
<Alert
  showIcon
  type={verdict.antdType}
  icon={verdict.icon}
  message={<strong>{verdict.title}</strong>}
  description={verdict.detail}
  action={<Button size="small" onClick={() => navigate('/experiment')}>去调流量 →</Button>}
/>
```

**判定(纯 `rows` 派生,零新契约)**:

```ts
const sig      = significantBuckets(rows);                              // significant === true
const winners  = sig.filter(r => Number.isFinite(r.lift) && r.lift > 0);// 显著且正向
const maxLift   = Math.max(0, ...rows.filter(r => !r.isBaseline && Number.isFinite(r.lift)).map(r => r.lift));

// 旧格式(hasSignificance === false)→ 直接走 OLD 分支,不判胜负。
const verdict =
  !hasSignificance   ? 'OLD'
  : sig.length === 0 ? 'FLAT'
  : winners.length>0 ? 'WIN'
  :                    'REVIEW';
```

### 三态定义

| 态 | 触发 | AntD `type` | 底色语义 | 图标(`@ant-design/icons`) | 标题(中文) | 描述(中文,复用 `AbSignificancePanel` 116–135 口吻) |
|----|------|------------|---------|---------------------------|-----------|------|
| **① 胜出 WIN** | 有显著桶且 ≥1 个正向 lift | `success` | 绿(`#52c41a` 系) | `TrophyOutlined` | `有 {N} 个桶显著优于基线` | `胜出桶:{桶名、桶名…}。最大正向 lift +{maxLift%}。若其中含与基线同策略的桶(AA),说明分流/埋点有偏,应先修分流再放量;若确为不同策略,则为真实实验效果。` |
| **② 无显著差异 FLAT** | 无显著桶 | `info` | 蓝(`#1677ff` 系) | `InfoCircleOutlined` | `未发现显著差异` | `各桶 CTR 与基线的差异均不显著(可能效果为零,或样本量不足——见明细「最小样本/臂」)。暂不建议据此放量。` |
| **③ 需复核 REVIEW** | 有显著桶但**无一正向**(仅劣于基线/歧义) | `warning` | 橙(`#faad14` 系) | `WarningOutlined` | `检测到显著差异,但无桶优于基线,需复核` | `有 {N} 个桶与基线差异显著却无正向 lift({桶名…})。优先排查:①是否存在与基线同策略的桶(AA)→ 分流/埋点有偏;②该变体确实劣于基线。修分流前不要下正式结论。` |
| **(旁支)旧格式 OLD** | `hasSignificance === false` | `info` | 蓝 | `InfoCircleOutlined` | `旧格式报表` | `本份 ab-report 无 CI / 显著性列,无法给出胜负结论,仅展示逐桶 CTR。重跑 --job=ab-report 可得完整结论。` |

**色语义与 `AbSignificancePanel` 一致性**:该面板现用 `warning`(有显著桶)+ `success`(无显著桶)双态。本结论条**继承其 token 语义并细化**:把「有显著桶」按 lift 方向拆成 `success`(有正向赢家=好消息)与 `warning`(有显著却无赢家=需复核,即原 warning 语义),「无显著桶」从原 `success` 改为 `info`(结论台上「无结论」是中性信息,不是成功)。三 token 均取 AntD 默认预设,天然 AA。

**图标**:统一用 `@ant-design/icons` 线性图标,`showIcon` + 传 `icon`;不要用 emoji(与全站 `★`/无 emoji 的技术腔一致)。

**桶名渲染**:描述里的桶名用 `<span className="mono">`,多个用 `、` 连接(照抄 `AbSignificancePanel:125` 的 `.join('、')`)。

### 结论条 ASCII(WIN 态)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 🛈绿  有 3 个桶显著优于基线                              [ 去调流量 → ]    │
│      胜出桶:recall:i2i;rank:din;rerank:mmr 、 recall:vec;rank:din;…       │
│      最大正向 lift +8.20%。若其中含与基线同策略的桶(AA),说明分流/埋点   │
│      有偏,应先修分流再放量;若确为不同策略,则为真实实验效果。            │
└─────────────────────────────────────────────────────────────────────────┘
   ▲ type=success 绿底/绿左条  ▲ TrophyOutlined  ▲ action 槽:size=small link 感按钮
```

---

## 3. KPI 砖(StatCard 行)

**4 砖**(对齐 `StatCardsSkeleton count={4}` 与 `lg={6}` 网格,不做 5 砖以免破网格)。基线**桶名**(字符串)不进砖 —— 已在结论条描述里,并在明细表基线行 Tag 呈现。

`<Row gutter={[16,16]}>` + 每砖 `<Col xs={24} sm={12} lg={6}>`。

| # | 标题(title) | 取值(value) | 格式 | accent(顶条) | icon | valueColor 规则 |
|---|------|------|------|------|------|------|
| 1 | `分桶数` | `rows.length` | 整数 | `ACCENTS.recall` `#2d6cdf` | `BranchesOutlined` | 默认 `#1f2a44` |
| 2 | `基线 CTR` | `baseline?.ctr` | `(n*100).toFixed(2)`,`suffix="%"` | `ACCENTS.rank` `#22d3ee` | `AimOutlined` | 默认 |
| 3 | `显著桶数` | `sig.length` | 整数,`suffix={`/ ${非基线桶数}`}` | `ACCENTS.rerank` `#8b5cf6` | `StarOutlined` | `sig.length>0` → `#389e0d`(绿),否则默认 |
| 4 | `最大正向 lift` | `maxLift>0 ? '+'+(maxLift*100).toFixed(2) : '—'` | `suffix={maxLift>0?'%':undefined}` | `ACCENTS.gsp` `#2ee6a6` | `RiseOutlined` | `maxLift>0` → `#389e0d`(绿),否则 `#8a94a6`(灰) |

**取值细则**:
- `baseline = rows.find(r => r.isBaseline)`;缺基线 → 砖 2 value 显示 `—`、灰。
- `非基线桶数 = rows.filter(r => !r.isBaseline).length`。
- 砖里大号数字**必带 `className="mono"`**(StatCard 内部已加,直接用即可)。
- 旧格式(`hasSignificance===false`):砖 3、砖 4 的 value 显示 `—`(灰),不误报 0;砖 1、砖 2 照常。

---

## 4. 对比图(视图 A:完整分桶)

**基座**:直接复用 `EErrorBar`,输入映射照抄 `AbSignificancePanel:71–76`:

```ts
const pct = (n:number)=> Number.isFinite(n) ? +(n*100).toFixed(3) : 0;
const view   = sortedRows; // ← 见 §8 排序;table 与 chart 同源
const cats   = view.map(r=>r.bucket);
const values = view.map(r=>pct(r.ctr));
const low    = view.map(r=> hasSig && Number.isFinite(r.ciLow)  ? pct(r.ciLow)  : pct(r.ctr));
const high   = view.map(r=> hasSig && Number.isFinite(r.ciHigh) ? pct(r.ciHigh) : pct(r.ctr));
const markers= view.map(r=> r.isBaseline ? '基线' : r.significant===true ? '★ 显著' : null);
const baseIdx= view.findIndex(r=>r.isBaseline);
```

```
<EErrorBar
  categories={cats} values={values} low={low} high={high} markers={markers}
  barName="CTR%" yName="%" height={360} colorIndex={1}
  baselineValue={baseline ? pct(baseline.ctr) : undefined}   // ← 新增 prop
  highlightIndex={baseIdx >= 0 ? baseIdx : undefined}        // ← 新增 prop
  highlightColor={ACCENTS.rank}                              // ← 新增 prop(默认基线柱色)
  fileName="bucket-board-ctr.png"
/>
```

**★ 显著标记**:`markers` 数组已由 `EErrorBar` 在柱顶渲染(`EErrorBar:62–70`),沿用「基线 / ★ 显著」两文案,零改动。

### EErrorBar 演进(3 个**可选、向后兼容**的新 prop —— 不影响现有 `AbSignificancePanel` 调用)

在 `EErrorBar.tsx` 追加 props 与两处 option delta:

1. **`baselineValue?: number`** —— 基线横向锚线(把每根柱读成「相对基线」)。加到柱 series:
   ```ts
   markLine: baselineValue != null ? {
     silent: true, symbol: 'none',
     lineStyle: { color: '#8a94a6', type: 'dashed', width: 1 },
     label: { formatter: '基线', color: '#8a94a6', fontSize: 11, position: 'insideEndTop' },
     data: [{ yAxis: baselineValue }],
   } : undefined,
   ```
2. **`highlightIndex?: number`** + **`highlightColor?: string`**(默认 `#22d3ee`)—— 基线柱换色高亮。把柱 `data` 从 `values.map(fmt)` 改为逐项 itemStyle:
   ```ts
   data: values.map((v,i)=>({
     value: fmt(v),
     itemStyle: i===highlightIndex
       ? { color: rgba(highlightColor ?? '#22d3ee', 0.85),
           borderColor: highlightColor ?? '#22d3ee', borderWidth: 1, borderType: 'dashed',
           borderRadius: [4,4,0,0] }
       : { color: barGradient(base), borderRadius: [4,4,0,0] },
   })),
   ```
   (三 prop 全 `undefined` 时行为与现状逐字节一致 → `AbSignificancePanel` 不受影响。)

**基线视觉锚定(三重,全部就位)**:① 横向 `基线` 虚线(markLine);② 基线柱换成青实心 + 虚线描边(highlightIndex);③ 柱顶 `基线` 文案(markers)。三者叠加,基线在图上一眼可辨、且所有柱都可对齐读差。

**排序联动**:图与表**同一 `sortedRows`**(§8),排序切换时图重排,视觉与表一致。类目多时 x 轴标签 30° 旋转已由 `EErrorBar:52` 处理。

---

## 5. 明细大表

**列集与渲染逐列照抄 `AbSignificancePanel:146–231`**,在其上叠加:①列头排序;②基线行高亮;③「样本不足」标。`Table<AbBucketRow>` + `size="small"` + `rowKey="bucket"` + `pagination={false}` + `scroll={{ x:'max-content' }}` + `dataSource={sortedRows}`。

| 列 | dataIndex/key | align | 渲染 & 颜色规则(照抄现有) | 排序(新增) |
|----|------|------|------|------|
| 分桶 | `bucket` | left | `<span className="mono" fontSize:12>` + 基线 `<Tag>基线</Tag>` | — |
| 曝光 | `impressions` | right | `Number.isFinite ? n : '-'` | `sorter`(数值) |
| 点击 | `clicks` | right | 同上 | — |
| CTR | `ctr` | right | `(n*100).toFixed(3)+'%'` 或 `n/a` | `sorter`(数值) |
| 95% CI | `ci`(key) | left | `hasSig && finite` → `<span className="mono">[low%, high%]</span>`,否则灰 `-` | — |
| lift | `lift` | right | 基线/非有限 → 灰 `(基线)`/`-`;否则 **`n>=0 ? #389e0d(绿) : #cf1322(红)`** + `(+)x.xx%` | `sorter`(数值) |
| p 值 | `pValue` | right | 基线/非有限 → 灰 `-`;否则 `<span className="mono">{n<1e-4?'<1e-4':n.toFixed(4)}</span>` | `sorter`(数值) |
| 显著 | `significant` | center | 基线 `<Tag>基线</Tag>`;`null` 灰 `-`;`true` `<Tag color="green">是</Tag>`;`false` `<Tag>否</Tag>` | — |
| 最小样本/臂 | `minSample` | right | 基线 `-`;`minSampleInf` `<Tag color="orange">∞</Tag>`;有限 `<span className="mono">{n}</span>`;**新增**:有限且 `impressions<minSample` → 追加 `<Tag color="gold">样本不足</Tag>` | `sorter`(数值) |

**列头 Tooltip**:「最小样本/臂」表头照抄现有 `Tooltip`(`AbSignificancePanel:214–216`)。

**排序器(Story 3,基线与 NaN 永远沉底,不受升降序影响)**——所有数值列用同一工厂:

```ts
const numSorter = (key: keyof AbBucketRow) => (a:AbBucketRow,b:AbBucketRow) => {
  const bad = (r:AbBucketRow) => r.isBaseline || !Number.isFinite(r[key] as number);
  if (bad(a) && bad(b)) return 0;
  if (bad(a)) return 1;           // a 沉底
  if (bad(b)) return -1;          // b 沉底
  return (a[key] as number) - (b[key] as number);
};
```
注意 AntD `sorter` 的 asc/desc 会翻转返回值 → 用 `sortDirections: ['descend','ascend']` 并让「沉底」逻辑靠 `bad()` 恒返回正数保证基线/NaN 始终最后(AntD 对相等项稳定,基线因恒 `+1/-1` 被推到当前排序方向的末端)。lift/p 值/CTR/曝光/最小样本 5 列可排(≥3 列达标)。

**基线行高亮**:
```ts
rowClassName={(r)=> r.isBaseline ? 'bb-baseline-row' : ''}
```
在 `index.css` 追加(青色 `ACCENTS.rank` 极淡底,AA 不伤正文对比):
```css
.bb-baseline-row > td { background: rgba(34, 211, 238, 0.08); }
.bb-baseline-row:hover > td { background: rgba(34, 211, 238, 0.14); }
```

---

## 6. 层×变体汇总(视图 B · Segmented 切换)

**切换控件**:对比区控制条左侧 `Segmented`,`options=['完整分桶','层×变体汇总']`,`value`/`onChange` 存本地 state,默认 `完整分桶`。切到「层×变体汇总」时,视图 A 隐藏、渲染视图 B;排序/仅显著控件仅对视图 A 生效(视图 B 有自己的层内顺序,控件在 B 视图下隐藏)。

**数据**:`getExperiment().staticLayers`(`Record<layer,{salt?,variants:Record<variant,weight>}>`)枚举 `(层,变体)`,逐个调 `variantOnlineStat(rows, layer, variant)`。

**布局**:每层一张 `CollapsibleCard`(`accent={ACCENTS.rerank}` `#8b5cf6` —— 与完整分桶的 recall/rank 蓝青**刻意区分**,让「配置视角」与「结果视角」一眼分开),`icon={<PartitionOutlined/>}`,`title={<>层:<span className="mono">{layer}</span></>}`,`extra` 显示 `salt`(若有,`mono` 灰)。层内一张紧凑表:

| 列 | align | 渲染 |
|----|------|------|
| 变体 | left | `<Tag color={channelColor(variant)}>{variant}</Tag>`(mono) —— 用 `channelColor` 保证同变体跨页同色 |
| 合并 CTR | right | `st==null` → 灰「无匹配曝光」;否则 `Number.isFinite(st.ctr)?(st.ctr*100).toFixed(2)+'%':'n/a'`(`mono`) |
| 命中桶数 | right | `st?.buckets ?? '-'`(`mono`) |
| 合计曝光 | right | `st?.impressions ?? '-'`(`mono`) |
| 含显著桶? | center | `st?.anySignificant ? <Tag color="green">含显著桶</Tag> : <Tag>无</Tag>`;`st==null` → 灰 `-` |

渲染逻辑与 `ExperimentConsole:136–152`(在线 CTR / 显著单元格)同源,只是从「表格单元格」升格为「独立分层卡片」。

**可选增强(默认开)**:层内表上方加一条 `EBar`,`categories`=该层各变体,`series=[{name:'合并 CTR%', data: 变体 CTR%}]`,`height={220}`,直观看层内变体高下。变体多(>1)才渲染,单变体只留表。

**ASCII(视图 B)**:
```
[完整分桶 │ 层×变体汇总●]                                          (排序/仅显著 在 B 视图隐藏)

┌ 层:rank                                              salt=rk ▾ ┐   ← CollapsibleCard accent=#8b5cf6
│  ┌ EBar(可选):变体合并 CTR% ────────────────────────────────┐ │
│  │  onnx ▇▇▇▇  din ▇▇▇▇▇▇  dien ▇▇▇▇▇                          │ │
│  └──────────────────────────────────────────────────────────┘ │
│  变体   合并CTR   命中桶数   合计曝光   含显著桶?                │
│  onnx   4.10%     3          12,043     无                       │
│  din    4.88%     3          11,890     含显著桶(绿)             │
│  dien   —（无匹配曝光,灰）                                       │
└────────────────────────────────────────────────────────────────┘
┌ 层:recall  … ┐   ┌ 层:rerank … ┐   ← 每层一张,纵向堆叠、全宽
```

**降级**:某层无匹配 → 该变体行「无匹配曝光」;某层 config 缺失 → 不渲染该层、不报错(Story 4 验收)。

---

## 7. 态(逐态专属 UI,不白屏、不崩页)

取数解耦:结论条/KPI/视图A 由 `useAbReport()` 决定态;视图 B 由 `getExperiment()` 独立决定态。**PageHeader 任何态都渲染**(给页面稳定骨架)。

| 态 | 触发 | 呈现(定死) |
|----|------|------|
| **加载** | `abQuery.isLoading` | PageHeader 下:结论条位 → 一条骨架(`<div className="skl" style={{height:64,borderRadius:8}}/>`);KPI 行 → `<StatCardsSkeleton count={4}/>`;对比区 → `<ChartSkeleton height={360}/>`;明细表 → `<ChartSkeleton height={240}/>`。各段独立骨架,不整页 Spin。 |
| **暂无报表** | `data.file===null` 或 `rows.length===0` | 单张 `<Card>` 内 `<EmptyState accent={ACCENTS.rank} icon={<ExperimentOutlined/>} title="暂无 ab-report" description="还没有带 bucket 的在线分桶 CTR 报表。先跑离线作业生成(需线上已有带 bucket 标记的 IMPRESSION 曝光埋点):" action={<命令块/>}/>`。**命令块**:`<Typography.Paragraph copyable className="mono">mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=ab-report</Typography.Paragraph>`(文案与 `AbSignificancePanel:52–58` 一致,增加 `copyable` 便捷复制)。结论条/KPI/图/表在此态**不渲染**。 |
| **接口报错** | `abQuery.isError` | PageHeader 下单条 `<Alert type="warning" showIcon message="读取 ab-report 失败" description={`离线报表接口暂不可用:${toApiError(error).message}。`} />`(照抄 `AbSignificancePanel:33–37` 口吻)。不崩页。 |
| **旧格式无显著性** | `hasSignificance===false` | ①结论条走 **OLD** 旁支(§2,`info`);②KPI 砖 3/4 显示 `—`;③图退化纯柱(`low=high=ctr` 使须收拢——`EErrorBar` 现有行为,`baselineValue` 仍传、锚线保留);④明细表**按 `hasSig` 过滤列**:隐藏 `95%CI / lift / p值 / 显著 / 最小样本/臂` 五列(`columns.filter(c => hasSig || !['ci','lift','pValue','significant','minSample'].includes(c.key??c.dataIndex))`),只留 分桶/曝光/点击/CTR;⑤对比区顶加一行灰提示 `<Typography.Text type="secondary">旧格式:无 CI/显著性列</Typography.Text>`(照抄 `AbSignificancePanel:94`)。 |
| **层×变体无匹配** | `variantOnlineStat()` 返回 `null` | 该 (层,变体) 行「无匹配曝光」(灰),不报错。 |
| **实验配置不可达** | `getExperiment()` `isError` | 仅**视图 B** 降级:切到「层×变体汇总」显示 `<Alert type="warning" showIcon message="实验配置不可达,层×变体汇总暂不可用" description={toApiError(error).message}/>`;**视图 A / 结论条 / KPI / 明细表照常**(两路取数解耦)。 |

---

## 8. 交互与响应式

**桌面优先**(与全站一致)。交互全部落在对比区控制条 + 表列头。

### 控制条(对比区顶,`<Space wrap size={[16,8]}>`)
```
[完整分桶 │ 层×变体汇总]   排序:[原始│CTR│lift│p 值│曝光]   仅显著 (Switch)
   ▲ Segmented(视图)         ▲ Segmented(排序,仅视图A)      ▲ 仅视图A
```

- **排序(Story 3)**:`Segmented`,`options=['原始','CTR','lift','p 值','曝光']`,存 `sortKey` state。派生**单一 `sortedRows`(图与表同源)**:
  ```ts
  const sortedRows = useMemo(()=>{
    if (sortKey==='原始') return filtered;                 // 保原始顺序
    const key = ({CTR:'ctr', lift:'lift', 'p 值':'pValue', 曝光:'impressions'} as const)[sortKey];
    const asc = sortKey==='p 值';                          // p 值升序(越小越显著),其余降序
    const bad = (r)=> r.isBaseline || !Number.isFinite(r[key]);
    return [...filtered].sort((a,b)=>{
      if (bad(a)&&bad(b)) return 0; if (bad(a)) return 1; if (bad(b)) return -1;
      return asc ? a[key]-b[key] : b[key]-a[key];          // 基线/NaN 永远沉底
    });
  }, [filtered, sortKey]);
  ```
  表列头 `sorter`(§5)提供**表内二次排序**能力;顶部 `Segmented` 是主排序、同时驱动图。二者并存(Story 3 要求列头可排,同时图需跟随)。

- **仅显著筛选(Story 3)**:`Switch` + 标签「仅显著」。谓词 **基线恒保留**:
  ```ts
  const filtered = onlySig ? rows.filter(r=> r.isBaseline || r.significant===true) : rows;
  ```
  同时作用于图与表(经 `sortedRows` 链路)。开启后若仅剩基线 → 图/表上方加灰提示 `当前无显著桶,仅显示基线`。

- **视图切换**:`Segmented`;切到「层×变体汇总」时**隐藏排序 + 仅显著**(它们只对完整分桶有意义)。

### 响应式(reflow 规则,定死)
- KPI 行:`Col xs={24} sm={12} lg={6}` → 窄屏 1 列、平板 2 列、桌面 4 列。
- 控制条:`Space wrap` → 窄屏自动换行,Segmented 不压缩变形。
- 图:`height={360}` 固定,宽 100% 自适应(`ChartFrame` 已处理)。
- 表:`scroll={{ x:'max-content' }}` → 列超宽时**表内横向滚动**,**页面 body 绝不横向滚**。
- 层×变体卡片:每层全宽 `CollapsibleCard` 纵向堆叠(不做多列),窄屏天然可读。
- 结论条:Alert `action` 按钮在窄屏由 AntD 自然换到描述下方,无需特殊处理。

### 无障碍 / 对比
- 全部文本色取自 token:主文 `#1f2a44`、次文 `#6b7280`/`#8a94a6`,浅色玻璃卡上均 ≥ AA。
- lift 绿 `#389e0d` / 红 `#cf1322` 于白底对比 ≥ 4.5:1(AA 正文级);两色不单靠色觉区分——绿带 `+`、红带 `-` 前缀,色盲友好。
- ★ 显著、基线 Tag、`∞`/样本不足 Tag 均文字+色双编码。
- Segmented / Switch / 可折叠卡标题均 AntD 原生键盘可达(`CollapsibleCard` 已实现 Enter/Space 切换)。
- 站点仅浅色;不新增暗色处理。颜色全经 token,未来接暗色算法可整体继承。

---

## 9. 注册(照抄两处,别处不动)

**`api/nav.ts`** —— 在 `/experiment` 那条后追加(同 group 保持域内顺序):
```ts
{ path: '/experiment',    label: '实验管理',   group: '实验与增长', keywords: 'experiment ab test shiyan zengzhang' },
{ path: '/bucket-board',  label: '桶对比大盘', group: '实验与增长', keywords: 'bucket board compare verdict tong duibi dapan jielun' },
```

**`router.tsx`** —— import + 在「Phase 1 在线调试台」`/experiment` 后加一条路由:
```tsx
import BucketBoard from './pages/experiment/BucketBoard';
// …
<Route path="/experiment"    element={<ExperimentConsole />} />
<Route path="/bucket-board"  element={<BucketBoard />} />
```
**页面文件位置**:`console/src/pages/experiment/BucketBoard.tsx`(新建 `pages/experiment/` 目录 —— 域已独立,不塞进 `pages/online/`)。ECharts 已在多页用,是否 `lazy` 由既有约定决定(本页含图,建议 `lazy` 与 `ReportViewer` 一致,但非强制)。

---

## 10. 色 / 图标速查(交接卡)

| 语义 | 值 | 来源 |
|------|----|------|
| PageHeader accent · 基线锚定 · 排序视觉 | `#22d3ee` `ACCENTS.rank` | `theme/tokens.ts` |
| KPI 分桶数 accent | `#2d6cdf` `ACCENTS.recall` | 同 |
| KPI 显著桶数 accent · 层×变体卡 accent | `#8b5cf6` `ACCENTS.rerank` | 同 |
| KPI 最大 lift accent | `#2ee6a6` `ACCENTS.gsp` | 同 |
| lift 正 / 负 | `#389e0d` / `#cf1322` | 照抄 `AbSignificancePanel:182` |
| 基线行底 | `rgba(34,211,238,0.08)` | 本规格 `index.css` 追加 |
| 次文 / 弱文 | `#6b7280` / `#8a94a6` | 全站 |
| 结论条三态 | AntD `success` / `warning` / `info` | 继承 `AbSignificancePanel` Alert |
| 变体 Tag 色 | `channelColor(variant)` | `explain/channelColors.ts` |

**图标(`@ant-design/icons` v5)**:结论条 `TrophyOutlined`/`WarningOutlined`/`InfoCircleOutlined`;KPI `BranchesOutlined`/`AimOutlined`/`StarOutlined`/`RiseOutlined`;层卡 `PartitionOutlined`;空态 `ExperimentOutlined`。

---

## 11. 前端自查清单(交付即验收,对齐 PRD §8)

- [ ] 进页 0 点击即从结论条读出:显著桶数 / 是否需复核(AA) / 最大正向 lift。
- [ ] 结论条三态 WIN(success)/ FLAT(info)/ REVIEW(warning)+ 旧格式旁支,各自图标/文案正确。
- [ ] 4 KPI 砖:分桶数 / 基线 CTR / 显著桶数(绿当 >0)/ 最大正向 lift(绿当 >0),格式如 §3。
- [ ] 图:CTR% + Wilson 须 + ★显著 + 基线柱换色 + 基线横虚线,`EErrorBar` 三新 prop 生效且旧调用不受影响。
- [ ] 表:9 列照抄 + lift 绿/红 + p 值 `<1e-4` + `∞`/样本不足橙标 + 基线行青底高亮。
- [ ] 排序(≥3 列)+ 仅显著筛选(基线恒留)+ 完整分桶/层×变体切换,四项可用且图表同源。
- [ ] 层×变体:每层一卡(accent=#8b5cf6),变体合并 CTR/命中桶数/合计曝光/含显著桶,null→无匹配。
- [ ] 4 态降级:加载(骨架)/ 暂无报表(EmptyState+命令)/ 报错(warning)/ 旧格式(列降级)—— 均不白屏。
- [ ] 视图 A 与视图 B 取数解耦:`getExperiment()` 失败不影响完整分桶段。
- [ ] `nav.ts` + `router.tsx` 注册,菜单落在「实验与增长」组、位于「实验管理」之后。
- [ ] 表 `scroll-x` 于溢出、页面 body 无横向滚;窄屏 KPI 1/2 列 reflow。
```
