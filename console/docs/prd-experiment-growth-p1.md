# PRD: 桶对比大盘 (Bucket Comparison Dashboard) — 实验与增长 P1 旗舰模块

**Status**: Approved (ready for design + build)
**Author**: Alex (PM)  **Last Updated**: 2026-07-11  **Version**: 1.0
**Domain**: 实验与增长  **Route(建议)**: `/bucket-board`  **标签**: 桶对比大盘
**Stakeholders**: UI Designer, Frontend Dev
**Constraint**: 纯前端,只消费现有 API,**零后端改动**。沿用 console 既有约定(react-query + ECharts + AntD5,全中文标签,逐页优雅降级)。

---

## 0. 旗舰选型与理由(为什么先做这个)

**决策:先做 #2 桶对比大盘。**

三个候选里,桶对比大盘是「用户价值 × 复用度 × Demo 冲击力」三项同时最高的一个:
1. **它回答增长 PM 的核心问题** —「哪个变体赢了?显著吗?样本够吗?」——而现有 `/experiment` 页把这块能力(`AbSignificancePanel`)埋在放量控件下方,是一个**控制台**而非**结论台**;做一个独立的结论大盘,正好给 实验与增长 域补上「控制 ↔ 结论」两页纵深。
2. **复用度最高、当天可交付**:`abReport.ts` 已产出经实战验证的 `AbBucketRow` 数据模型 + `useAbReport()` 取数 + `variantOnlineStat()`(按层×变体的边际 CTR 汇总,目前只在 `ExperimentConsole` 里当一个表格单元格用,**价值被严重低估**)+ `significantBuckets()`;`AbSignificancePanel` / `EErrorBar` / AA 校验文案全部现成。前端把现有件重新编排成一屏即可,不造新契约。
3. **Demo 是天然中心**:一屏搞定 baseline 高亮 + 可排序 + 显著性筛选 + AA 提示 + 「谁赢了」结论条,这是演示 实验与增长 时最该打开的那一屏。

另外两个候选(分层矩阵、数据质量趋势)判为 fast-follow,理由见 §9。

---

## 1. Problem Statement

增长 PM 与算法工程师跑完分层 A/B(recall×rank×rerank 确定性分桶)后,当前只能:
- 在 `/experiment` 页看到一个内嵌的 `AbSignificancePanel`(最新一份 ab-report 的逐桶 CTR + Wilson CI),但它**和放量控件挤在同一页**,信息密度低、不能排序/筛选、也没有「结论」;
- 或者去 `/reports/ab-report` 看原始 CSV 可视化(`AbViz`),那是**面向报表的**、不是面向决策的。

缺一个**面向决策的对比大盘**:一屏之内把每个分桶的 CTR / lift / 显著性 / 最小样本量横向铺开,标出基线、可排序、可按显著性筛选、并给出「是否有桶显著赢过基线 / 是否疑似 AA 有偏 / 样本是否不足」的直接结论。

**Evidence(均来自代码,非臆测)**:
- `console/src/components/experiment/abReport.ts` 已定义 `AbBucketRow`(bucket / impressions / clicks / ctr / ciLow / ciHigh / lift / pValue / significant / minSample / minSampleInf / isBaseline)与 `useAbReport()`,证明后端 ab-report 契约完备、数据齐全,只是没有一个「大盘」视图去消费它。
- `variantOnlineStat(rows, layer, variant)` 已实现「按 `层:变体` token 跨完整分桶聚合边际 CTR」,但目前仅在 `ExperimentConsole.tsx` 第 139 行当一个表格单元格渲染 —— **能力已建好、几乎没被使用**。
- `nav.ts` 中 实验与增长 域**只有 `/experiment` 一个页面**(第 46 行),域纵深明显不足。

---

## 2. Goals & Success Metrics

| Goal | Metric | 判定方式 | Target |
|------|--------|----------|--------|
| 单屏出实验结论 | 打开大盘到看清「哪些桶显著赢/输基线」的点击数 | 走查:进页面 0 次点击即见结论条 | 0 次点击 |
| 复用而非重造 | 新增取数函数/契约数量 | 代码走查 | 0 个新后端契约(仅可选新增 1 个前端 helper,见 §5) |
| 优雅降级完备 | 覆盖的状态数 | 走查:加载/报错/暂无报表/旧格式无显著性列 4 态各有专属 UI | 4/4 |
| 决策可操作 | 支持的交互 | 走查:排序 + 显著性筛选 + baseline 高亮 + 层×变体汇总切换 全部可用 | 4/4 |
| 当天可交付 | 设计 + 前端各自今日完成 | 交付即验收 | 1 天 |

---

## 3. Non-Goals(本期明确不做)

- **不做**任何后端改动 / 新端点 —— 只读 `getExperiment()` 与 ab-report 报表族。
- **不做**放量/权重编辑 —— 那是 `/experiment` 控制台的职责;本页只读、只做结论。大盘上「去调流量」按钮直接跳 `/experiment`。
- **不做**跨多份历史报表的时间趋势 —— 那是 fast-follow 的 数据质量趋势页 思路(§9);本期默认只消费**最新一份** ab-report(`useAbReport()`),历史文件选择器列为 v1 的可选增量(§5 已标注实现成本)。
- **不做**分层矩阵的完整笛卡尔积树 —— 见 §9 fast-follow #1;本期用 `variantOnlineStat` 做**层×变体边际汇总**(轻量版),不铺满全笛卡尔积。

---

## 4. User Personas & Stories

**主 Persona A — 增长 PM(林,负责推荐 CTR 增长)**:每周跑 1–2 个分层实验,要快速判断「能不能放量 / 要不要停」。看不懂 z 检验细节,但要看懂「赢没赢、显不显著、样本够不够」。

**主 Persona B — 算法工程师(陈,排序/召回策略作者)**:关心自己那一层(如 rank)的某个变体(如 `din`)相对基线的边际效果,要能从「完整分桶」下钻到「我这层这个变体」的汇总 CTR。

用户故事(带验收标准):

**Story 1(林)**:作为增长 PM,我想在一屏看到所有分桶的 CTR + lift + 显著性并**默认高亮基线桶**,以便一眼看出谁赢了。
- [ ] Given 最新 ab-report 有多个桶,when 打开大盘,then 以 `EErrorBar`(CTR% + Wilson CI 须)+ 明细表并置呈现,基线桶(`row.isBaseline`)带「基线」标签且视觉高亮。
- [ ] Given 存在 `significant === true` 的桶,then 该桶在图上打 `★ 显著` marker、表格「显著」列显示绿色「是」。

**Story 2(林)**:作为增长 PM,我想看到一条**结论条(verdict)**,直接告诉我「有 N 个桶显著赢过基线 / 未发现显著差异 / 疑似 AA 有偏」,不用自己读 p 值。
- [ ] Given `significantBuckets(rows).length > 0`,then 顶部结论条列出这些桶名,并复用现有 AA 提示文案(见 `AbSignificancePanel` 第 116–138 行的 warning/success 双态)提醒「若含与基线同策略的桶即分流/埋点有偏」。
- [ ] Given 无显著桶,then 结论条显示 success 态「未发现显著差异(可能效果为零或样本不足,见最小样本/臂)」。

**Story 3(林)**:作为增长 PM,我想**按 CTR / lift / p 值 / 曝光排序**,并能**只看显著桶**,以便聚焦有结论的桶。
- [ ] Given 明细表,when 点列头,then 按该列排序(CTR / lift / pValue / impressions / minSample 均可排;NaN/基线值排末尾)。
- [ ] Given 顶部「仅显著」筛选开关,when 打开,then 表格与图只保留 `significant === true` 的桶(基线始终保留作参照)。

**Story 4(陈)**:作为算法工程师,我想把视角从「完整分桶」切到「按层×变体的边际汇总」,看我这层某变体的合并 CTR 与是否含显著桶。
- [ ] Given `getExperiment().staticLayers` 枚举出所有 `(层, 变体)`,when 切到「层×变体汇总」视图,then 对每个 `(层,变体)` 调 `variantOnlineStat(rows, 层, 变体)` 渲染 { 合并 CTR, 命中桶数, 合计曝光, 含显著桶? };命名对不上(返回 null)显示「无匹配曝光」。
- [ ] Given 某层未开实验(config 缺失),then 该层不渲染,不报错。

**Story 5(林)**:作为增长 PM,我想看到每个桶的**最小样本/臂**并对「样本不足」给出显式提示,避免用不足的样本下结论。
- [ ] Given `row.minSampleInf === true`,then 显示 `∞` 橙标(lift 太小、不可检出);Given `minSample` 有限且 `impressions < minSample`,then 该桶标「样本不足」提示。

**Story 6(林/陈)**:作为使用者,当后端离线报表未跑 / 接口不可用时,我想看到清晰的降级说明与「怎么生成」的命令,而不是白屏。
- [ ] Given `useAbReport()` 返回 `file === null`,then 展示「暂无 ab-report」+ 生成命令(复用 `AbSignificancePanel` 第 43–64 行文案:`mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=ab-report`)。
- [ ] Given `isError`,then warning 提示「读取 ab-report 失败」不崩页。

---

## 5. 精确数据契约(必须复用,不得臆造端点)

全部来自 `console/src/**`,**无新增后端端点**:

| 用途 | 复用的函数 / 类型 | 位置 | 返回/形状 |
|------|-------------------|------|-----------|
| 取最新 ab-report(主数据源) | `useAbReport()` | `components/experiment/abReport.ts` | `{ file: ReportFileInfo\|null, rows: AbBucketRow[], hasSignificance: boolean }` |
| 逐桶行模型 | `AbBucketRow` | 同上 | `bucket, impressions, clicks, ctr, ciLow, ciHigh, users, lift, pValue, significant(boolean\|null), minSample, minSampleInf, isBaseline` |
| 显著桶筛选 | `significantBuckets(rows)` | 同上 | `AbBucketRow[]`(`significant === true`) |
| 层×变体边际汇总(Story 4 核心) | `variantOnlineStat(rows, layer, variant)` | 同上 | `{ impressions, clicks, ctr, buckets, anySignificant } \| null` |
| 枚举层与变体(配置侧) | `getExperiment()` → `ExperimentSnapshot.staticLayers` | `api/experiment.ts` / `api/types.ts` | `Record<layer, { salt?, variants: Record<variant, weight> }>` |
| 逐桶 CTR + CI 图 | `AbSignificancePanel`(整体复用) 或 `EErrorBar`(自绘) | `components/experiment/AbSignificancePanel.tsx` / `components/charts/EErrorBar` | — |
| 报表分类判定 | `vizCategoryOf(fileName)` → `'ab-report'` | `api/report.ts` | 供历史文件选择器筛选 |

**关键数据事实(前端必须遵守,避免误读)**:
- ab-report 的 `bucket` 是**完整分桶**,形如 `recall:base;rank:onnx;rerank:mmr`,`;` 分隔的每个 token 是 `层:变体`。`variantOnlineStat` 就是靠匹配 `层:变体` token 跨完整桶聚合边际 CTR 的。
- 基线桶:`isBaseline === true`(曝光最多或 `--baseline` 指定);基线桶 `lift / significant / minSample` 均空,渲染需按 `isBaseline` / `Number.isFinite` 判空,不能直接算。
- **旧格式**报表无 CI/显著性列 → `hasSignificance === false`:此时图退化为纯柱(无须),表隐藏 CI/lift/p/显著/最小样本列(参照 `AbSignificancePanel` 第 74–75、`AbViz` 第 15/39 的降级写法)。

**关于历史文件选择器(v1 可选增量,诚实标注成本)**:`useAbReport()` 只读**最新**一份。若要选历史 ab-report,需在 `abReport.ts` 里**新增一个前端 helper**(如 `useAbReportFile(name)`:`getReportIndex()` → `vizCategoryOf==='ab-report'` 过滤 → `getReportFile(name)` → 复用现有 `parseRow`;需把 `parseRow` 从模块内 `export` 出来)。这是**纯前端**改动、不动后端。**建议**:v1 先只做「最新一份」满足核心结论;文件选择器排到 fast-follow,避免拖慢当天交付。

---

## 6. 关键视图与交互

单页三段式(自上而下),整页用 `PageHeader`(accent 用 `ACCENTS.rank`,description 一句话点题),外层 `Space direction="vertical"`:

1. **结论条(Verdict Bar,置顶)** — 一行 KPI + 一条 AA/success/warning Alert:
   - KPI:桶数、基线桶名、显著桶数、最大正向 lift(`Math.max` over 非基线有限 lift)。
   - Alert:复用 `AbSignificancePanel` 的 AA 双态文案(有显著桶 → warning + AA 提醒;无 → success)。
   - 右侧「去调流量 →」次要按钮,`navigate('/experiment')`。

2. **对比图 + 视图切换(中段)**:
   - Segmented 切换「完整分桶 ↔ 层×变体汇总」。
   - 完整分桶:`EErrorBar`(CTR% + Wilson CI + `★ 显著`/`基线` markers),直接复用 `AbSignificancePanel` 的映射逻辑(第 71–76 行)或整块内嵌 `AbSignificancePanel`。
   - 层×变体汇总:对 `staticLayers` 的每个 `(层,变体)` 调 `variantOnlineStat`,渲染分层卡片/柱状,展示合并 CTR + 命中桶数 + 含显著桶标签。

3. **明细大表(底段)** — `Table<AbBucketRow>`,`scroll={{ x: 'max-content' }}`:
   - 列:分桶(mono + 基线 Tag)、曝光、点击、CTR、95% CI、lift(正绿负红)、p 值(`<1e-4` 处理)、显著(Tag)、最小样本/臂(`∞` 橙标)。列渲染直接照搬 `AbSignificancePanel` 第 146–231 行。
   - 交互:列头排序(CTR/lift/pValue/impressions/minSample;基线与 NaN 排末尾);顶部「仅显著」`Switch` 筛选(基线恒保留);baseline 行加浅色 `rowClassName` 高亮。

**设计交接要点**:视觉语言沿用 console 既有(`ACCENTS` / `STATUS` / `rgba`、`mono` 类名、`CollapsibleCard`);结论条要「0 点击可读」,是本页的记忆点;层×变体汇总卡片要与完整分桶图视觉可区分(用不同 accent)。

---

## 7. 空 / 加载 / 错误态(逐页优雅降级,与 console 一致)

| 态 | 触发 | 呈现 |
|----|------|------|
| 加载 | `useAbReport().isLoading` | `ChartSkeleton` / `Spin`(顶部结论条与图各自骨架),不阻塞其它段 |
| 暂无报表 | `data.file === null` 或 `rows.length === 0` | `EmptyState`/Alert:「暂无 ab-report」+ 生成命令(复用 `AbSignificancePanel` 文案),说明需要带 bucket 的 IMPRESSION 曝光埋点 |
| 接口报错 | `isError` | `Alert type="warning"`「读取 ab-report 失败」+ `toApiError(error).message`,不崩页 |
| 旧格式无显著性 | `hasSignificance === false` | 图退化为纯柱、隐藏 CI/lift/p/显著/最小样本列,顶部提示「旧格式:无 CI/显著性列」 |
| 层×变体无匹配 | `variantOnlineStat` 返回 `null` | 该 (层,变体) 显示「无匹配曝光」,不报错 |
| 实验配置不可达 | `getExperiment()` 报错 | 「层×变体汇总」段降级为不可用提示,**完整分桶段照常**(两段取数解耦,互不阻塞——参照 `ExperimentConsole` 里 `query` 与 `abQuery` 独立取数) |

---

## 8. Success Criteria(可度量)

1. **0 点击出结论**:进页面无需任何交互即可从结论条读出「显著桶数 / 是否 AA 疑似有偏 / 最大正向 lift」。
2. **零新后端契约**:代码走查确认只消费 §5 所列现有函数;至多新增 1 个前端 helper(`useAbReportFile`,且仅当做历史选择器时)。
3. **4 态降级齐全**:加载 / 暂无报表 / 报错 / 旧格式 四态各有专属 UI,任一态都不白屏、不崩页(可用旧格式 CSV 与删档场景走查)。
4. **交互齐全**:排序(≥3 列)+ 仅显著筛选 + baseline 高亮 + 完整分桶/层×变体切换,四项均可用。
5. **当天交付**:设计稿(结论条 + 两视图 + 大表布局)与前端页面(接线 + 降级)当日各自完成,新页面注册进 `router.tsx` 与 `nav.ts`(group=`实验与增长`)。

---

## 9. 另外两个候选 —— Fast-follow(1–2 行each)

- **实验分层矩阵(#1,P1.1)**:消费 `getExperiment().staticLayers` 把 recall×rank×rerank 变体×权重渲染成矩阵/树 + 每个完整分桶的**流量占比**(各层权重归一化连乘)。可直接嵌进本大盘「层×变体汇总」段之上,做「配置(占比)↔ 结果(CTR)」并置——是本旗舰的自然续作。
- **数据质量趋势页(#3,P1.2)**:`getReportIndex()` 过滤 `data-quality-*` → 逐份 `getReportFile` 解析(复用 `DataQualityViz` 的 `metric,value` 长表解析)→ ECE/PSI/embedding 覆盖率随时间折线。价值高,但语义更偏 **数据与模型(MLOps)** 域、且需多文件扇出;单列一页更合适,放在 P1 之后。

---

## 10. Appendix(交接引用)

- 现有实验控制台:`console/src/pages/online/ExperimentConsole.tsx`
- A/B 数据模型 + 取数 + 汇总:`console/src/components/experiment/abReport.ts`
- A/B 显著性面板(可整体复用/借鉴):`console/src/components/experiment/AbSignificancePanel.tsx`
- 报表族取数:`console/src/api/report.ts`(`getReportIndex` / `getReportFile` / `vizCategoryOf` / `tableToObjects` / `num`)
- 图表件:`console/src/components/charts/`(`EErrorBar` / `EBar` / `EGauge`)
- 参考新页面写法(同为对比台风格):`console/src/pages/online/StrategyLab.tsx`
- 页面注册:`console/src/router.tsx`、`console/src/api/nav.ts`(group `实验与增长`)
