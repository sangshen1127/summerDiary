<script setup lang="ts">
// ══════════════════════════════════════════════════════════════
// EmptyState —— 统一的空状态组件
// ══════════════════════════════════════════════════════════════
// 开发文档 §8.5 要求每个数据页面都必须显式处理四种状态：
//   loading / empty / error / 进行中
// 这个组件负责其中的 empty，避免每个页面重复写。
// ══════════════════════════════════════════════════════════════

interface Props {
  /** 图标 emoji 或 Element Plus 图标名 */
  icon?: string
  /** 主标题 */
  title?: string
  /** 补充说明 */
  description?: string
  /** 操作按钮文案。不传则不显示按钮 */
  actionText?: string
}

const props = withDefaults(defineProps<Props>(), {
  icon: '📭',
  title: '暂无数据',
  description: '',
  actionText: '',
})

const emit = defineEmits<{
  action: []
}>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-state__icon" aria-hidden="true">{{ props.icon }}</div>
    <p class="empty-state__title">{{ props.title }}</p>
    <p v-if="props.description" class="empty-state__desc">
      {{ props.description }}
    </p>
    <el-button
      v-if="props.actionText"
      type="primary"
      size="small"
      @click="emit('action')"
    >
      {{ props.actionText }}
    </el-button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 16px;
  text-align: center;
}

.empty-state__icon {
  font-size: 40px;
  line-height: 1;
  opacity: 0.7;
}

.empty-state__title {
  margin: 0;
  font-size: 15px;
  color: var(--ad-text-regular);
}

.empty-state__desc {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--ad-text-secondary);
  max-width: 380px;
}
</style>
