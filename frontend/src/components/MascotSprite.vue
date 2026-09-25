<script setup lang="ts">
import mascotUrl from '@/image/SummerDiary看板娘.png'

// ══════════════════════════════════════════════════════════════
// MascotSprite —— 页面看板娘
// ══════════════════════════════════════════════════════════════
// 图片：frontend/src/image/SummerDiary看板娘.png
//   · 1254 × 1254 · **真正带 Alpha 通道的透明 PNG**（四角 alpha = 0）
//   · 角色实际占 x 127–1250、y 2–1253 —— 左侧 127px 是留白
//   · 构图是**半身像**（头 + 肩 + 拿书的手），下缘齐口裁断
//
// 上面的数据不是估的，是美术风格预览页里实测记录的（见
// 美术风格预览.html 的 `.hero__mascot` 注释）。据此推出两个关键处理：
//
//   1. **左侧留白让视觉重心偏右** → 用 `translateX(-5.06%)` 补回来
//      （-127/2/1254 = -5.06%）
//   2. **半身像下缘齐口** → **绝不能加落地阴影**，那会凭空造出一条
//      不存在的"地面线"，角色就不像"浮"在纸面上了。
//      所以只用 `drop-shadow` 贴着角色轮廓投影（透明区不投影）。
//
// ── 为什么放在右下角悬浮，而不是页面内某处 ────────────────────
// 需求是"当看板娘"，而看板娘的本质是**陪伴感** —— 每个页面都在，
// 而不是只在首页出现一次。悬浮在右下角满足这一点，同时：
//   · 不挤占内容布局（不会把日记列表推下去）
//   · `pointer-events: none`，绝不挡住用户点按钮
//   · 窄屏自动隐藏（375px 下会明显遮挡内容）
//
// ── ⚠️ 素材体积（已知问题，记录在案）────────────────────────
// 原图 1.5MB（PNG 未压缩）。它会在首屏之外按需加载，但确实偏大。
// 优化方向（Phase 8 工程化时做）：转 WebP 或用图片压缩插件，
// 通常能降到 150–300KB。这里**先不引入新依赖**，保持零新增依赖。
// ══════════════════════════════════════════════════════════════

withDefaults(
  defineProps<{
    /** 画布边长（px）。角色实际呈现约为此值的 0.9 倍 */
    size?: number
  }>(),
  { size: 220 },
)
</script>

<template>
  <!--
    ⚠️ aria-hidden + alt=""：
    这是纯装饰性插画，不承载任何信息。读屏软件跳过它，
    否则用户会听到"SummerDiary看板娘"这种无意义的图片说明。
    若将来它承载了提示作用（比如点它出帮助），再去掉 aria-hidden 并补 alt。
  -->
  <img
    class="mascot"
    :src="mascotUrl"
    alt=""
    aria-hidden="true"
    :style="{ '--mascot-size': `${size}px` }"
  />
</template>

<style scoped>
.mascot {
  --mascot-size: 220px;

  position: fixed;
  right: 18px;
  /* 避开浏览器可能出现的横向滚动条，也不贴死底边 */
  bottom: 10px;
  z-index: 5;

  width: var(--mascot-size);
  height: var(--mascot-size);

  /* 左侧 127px 留白让重心偏右，这里补回来（-127/2/1254 ≈ -5.06%） */
  transform: translateX(-5.06%);

  /* 呼吸动效（keyframes 在 theme.css 里，全站共用） */
  animation: breathe 6.5s var(--ease-soft, ease-in-out) infinite;
  /* 缩放锚点放在角色胸口，缩放时头不会顶出视口 */
  transform-origin: center 72%;

  /* 贴着角色轮廓的柔影 —— 透明区不投影，"浮起来"的关键。
     ⚠️ 刻意不用 box-shadow / 落地阴影：原图是半身像、下缘齐口裁断，
     加了会凭空造出一条不存在的"地面线"。 */
  filter: drop-shadow(0 6px 14px rgba(85, 96, 79, 0.18));

  /* 绝不拦截点击：右下角可能压住分页、按钮等可交互元素 */
  pointer-events: none;
  user-select: none;
}

/* 窄屏：375px 下 220px 的立绘会明显遮挡内容，直接隐藏 */
@media (max-width: 640px) {
  .mascot {
    display: none;
  }
}

/* 尊重"减少动效"偏好（开发文档 §8.6 无障碍要求） */
@media (prefers-reduced-motion: reduce) {
  .mascot {
    animation: none;
  }
}
</style>
