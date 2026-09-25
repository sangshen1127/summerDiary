<script setup lang="ts">
// ══════════════════════════════════════════════════════════════
// NotFoundPage —— 404
// ══════════════════════════════════════════════════════════════
// 路由表末尾的 catch-all 匹配到这里。
//
// 注意：这与后端的 404 不是一回事 ——
//   前端 404 = 路由不存在
//   后端 40401 = 接口的「资源不存在或不属于当前用户」
// 两者都在开发文档 §4.2 的错误码字典里有明确定义。
// ══════════════════════════════════════════════════════════════
</script>

<template>
  <div class="not-found">
    <el-result icon="warning" title="404" sub-title="页面不存在或已被移除">
      <template #extra>
        <el-button type="primary" @click="$router.push('/home')">
          返回概览
        </el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 16px;
  background: var(--ad-bg-page);
}
</style>
