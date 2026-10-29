<script setup lang="ts">
/**
 * 镜像仓库的**类型**标记：Docker 还是 Harbor。
 *
 * <p>自绘而不是引入品牌原图 —— 依赖里没有品牌图标集，内网部署也用不了 CDN。
 * 两个形状刻意做得差异很大（集装箱堆叠 vs 灯塔），扫一眼就能分：
 * 之前左栏那个图标表达的是「平台 / 公共 / 私有 / 外部」的来源，跟类型无关，
 * 所以一眼看不出是 Docker 还是 Harbor（2026-10-30 反馈）。
 */
defineProps<{
  kind: 'HARBOR' | 'DOCKER'
  size?: number
}>()
</script>

<template>
  <svg v-if="kind === 'DOCKER'"
       :width="size ?? 18" :height="size ?? 18" viewBox="0 0 24 24" fill="#2496ED"
       role="img" aria-label="Docker">
    <title>Docker</title>
    <!-- 集装箱 3×2 + 底座：Docker 的容器堆叠意象 -->
    <rect x="3.2" y="5.6" width="4.2" height="4.2" rx="1" />
    <rect x="8.9" y="5.6" width="4.2" height="4.2" rx="1" />
    <rect x="14.6" y="5.6" width="4.2" height="4.2" rx="1" />
    <rect x="8.9" y="11.1" width="4.2" height="4.2" rx="1" />
    <rect x="14.6" y="11.1" width="4.2" height="4.2" rx="1" />
    <path d="M2 16.9h20c0 2.6-2.1 4.6-4.7 4.6H6.7C4.1 21.5 2 19.5 2 16.9z" />
  </svg>

  <svg v-else
       :width="size ?? 18" :height="size ?? 18" viewBox="0 0 24 24"
       fill="none" stroke="#2F8FBF" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"
       role="img" aria-label="Harbor">
    <title>Harbor</title>
    <!-- 锚：Harbor 没有像 Docker 鲸鱼那样公认的品牌图形，锚是最不会认错的"港口"意象，
         而且这个尺寸下比灯塔清楚（灯塔的两道光在小尺寸会被看成胳膊） -->
    <circle cx="12" cy="4.4" r="2.1" />
    <path d="M12 6.6V19.6" />
    <path d="M8.2 10.2h7.6" />
    <path d="M4.4 14.2c0 3.9 3.2 6.4 7.6 6.4s7.6-2.5 7.6-6.4" />
  </svg>
</template>
