// 漏斗带的全部作用域样式,前缀 .fnl-*(从 RecFunnelHero 的 .rfh-* 抽取并泛化)。
// 由 FunnelBand 唯一持有 <style>{FUNNEL_CSS}</style>;系统总览 hero 与在线调试台各页共用同一套视觉语言。
// 纯 CSS transform/opacity 动效,尊重 prefers-reduced-motion。
export const FUNNEL_CSS = `
.fnl-root{
  position:relative;overflow:hidden;border-radius:16px;
  /* 建立行内尺寸查询容器:漏斗按「自身盒宽」而非视口宽决定横/竖排。
     修复策略对比台 A/B 漏斗在 lg={12} 半栏(或浏览器缩放使 CSS 视口变窄)时,
     视口仍 >820px、竖排媒体查询未触发,横向流水线溢出被 overflow:hidden 裁切的问题。 */
  container:fnl / inline-size;
  padding:26px 30px;color:#e6edf7;
  font-variant-numeric:tabular-nums;
  background-color:#080d1a;
  background-image:
    radial-gradient(900px 420px at 10% 0%, rgba(45,108,223,0.24), transparent 60%),
    radial-gradient(820px 420px at 96% 108%, rgba(34,211,238,0.13), transparent 58%),
    radial-gradient(760px 520px at 88% -10%, rgba(139,92,246,0.14), transparent 54%),
    linear-gradient(rgba(120,150,210,0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(120,150,210,0.05) 1px, transparent 1px);
  background-size:auto,auto,auto,40px 40px,40px 40px;
  border:1px solid rgba(130,160,220,0.16);
}
.fnl-root--dense{padding:18px 20px;}
.fnl-mono{font-family:ui-monospace,'SF Mono',Menlo,Consolas,monospace;}

.fnl-head{display:flex;flex-wrap:wrap;align-items:flex-end;justify-content:space-between;gap:16px;margin-bottom:22px;}
.fnl-root--dense .fnl-head{margin-bottom:16px;}
.fnl-kicker{display:inline-flex;align-items:center;gap:8px;font-size:11.5px;letter-spacing:2px;text-transform:uppercase;}
.fnl-title{font-size:19px;font-weight:800;letter-spacing:.4px;margin-top:10px;color:#f2f6ff;}
.fnl-root--dense .fnl-title{font-size:16px;margin-top:8px;}
.fnl-sub{font-size:13px;color:#9aa7bd;margin-top:4px;max-width:560px;line-height:1.6;}

.fnl-dot{width:8px;height:8px;border-radius:50%;flex:0 0 auto;}
.fnl-dot--pulse{animation:fnl-blink 2s ease-in-out infinite;}

.fnl-metrics{display:flex;flex-wrap:wrap;gap:9px;}
.fnl-chip{
  display:inline-flex;align-items:center;gap:7px;padding:6px 13px;border-radius:999px;
  background:rgba(255,255,255,0.04);border:1px solid rgba(130,160,220,0.18);
  color:#cfe0f5;font-size:12.5px;font-weight:500;backdrop-filter:blur(6px);
}
.fnl-chip .anticon{color:#5fb0ff;font-size:13px;}
.fnl-chip b{color:#f2f6ff;font-weight:700;}

/* ── 流水线:桌面横向,窄屏竖向 ── */
.fnl-pipe{display:flex;align-items:stretch;gap:0;}
.fnl-stage{
  flex:1 1 0;min-width:0;
  display:flex;align-items:center;gap:13px;
  padding:15px 17px;border-radius:14px;
  background:rgba(255,255,255,0.035);border:1px solid rgba(130,160,220,0.14);
}
.fnl-root--dense .fnl-stage{padding:13px 14px;gap:11px;}
.fnl-stage-ic{
  width:44px;height:44px;border-radius:12px;flex:0 0 auto;
  display:inline-flex;align-items:center;justify-content:center;font-size:19px;
}
.fnl-root--dense .fnl-stage-ic{width:40px;height:40px;font-size:17px;}
.fnl-stage-body{min-width:0;flex:1 1 auto;}
.fnl-stage-head{display:flex;align-items:center;gap:8px;}
.fnl-stage-title{font-size:15px;font-weight:700;color:#eaf1fc;letter-spacing:.3px;}
.fnl-root--dense .fnl-stage-title{font-size:14px;}
.fnl-stage-sub{font-size:12px;color:#8ea0ba;margin-top:3px;line-height:1.45;}

/* 数据驱动:大号计数 + 次级指标 */
.fnl-stage-count{font-size:22px;font-weight:800;line-height:1.05;color:#f4f8ff;letter-spacing:.5px;transition:color .5s ease;}
.fnl-stage-count--empty{color:#586a86;}
.fnl-stage-metric{font-size:11.5px;color:#9aa7bd;margin-top:3px;letter-spacing:.2px;}
.fnl-stage-metric b{color:#cfe0f5;font-weight:700;}

/* 连接器(横向) */
.fnl-conn{position:relative;flex:0 0 46px;align-self:center;height:2px;margin:0 4px;}
.fnl-root--dense .fnl-conn{flex-basis:34px;}
.fnl-conn-line{position:absolute;inset:0;height:2px;border-radius:2px;}
.fnl-flow-dot{
  position:absolute;top:50%;left:0;width:6px;height:6px;margin-top:-3px;border-radius:50%;
  animation:fnl-flow-x 2.2s linear infinite;
}

@keyframes fnl-flow-x{0%{left:-4px;opacity:0}15%{opacity:1}85%{opacity:1}100%{left:46px;opacity:0}}
@keyframes fnl-flow-y{0%{top:-4px;opacity:0}15%{opacity:1}85%{opacity:1}100%{top:34px;opacity:0}}
@keyframes fnl-blink{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.4;transform:scale(.78)}}

/* 窄「容器」(非视口):竖排,连接器转为纵向。阈值 640px ≈ 3 个横向 stage(含图标/连接器/标题)的临界宽,
   低于此横向流水线会挤出容器 → 改竖排。用 @container 使半栏/缩放场景也能正确触发(见 .fnl-root 说明)。 */
@container fnl (max-width:640px){
  .fnl-pipe{flex-direction:column;}
  .fnl-stage{flex:1 1 auto;}
  .fnl-conn{flex-basis:auto;height:34px;width:2px;margin:5px 0 5px 21px;align-self:flex-start;}
  .fnl-conn-line{inset:0;width:2px;height:auto;}
  .fnl-flow-dot{left:50%;top:0;margin-top:0;margin-left:-3px;animation-name:fnl-flow-y;}
}

@media (prefers-reduced-motion:reduce){
  .fnl-dot--pulse{animation:none !important;}
  .fnl-flow-dot{display:none !important;}
}
`;
