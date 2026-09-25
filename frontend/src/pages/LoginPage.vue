<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { ErrorCode } from '@/types/api'
import logoUrl from '@/image/看板娘2.png'

// ══════════════════════════════════════════════════════════════
// 登录页
// ══════════════════════════════════════════════════════════════
// 认证方案：HttpOnly Cookie 会话。
//   前端<b>不接触会话 ID</b> —— 登录成功后由后端 Set-Cookie 下发，
//   浏览器自动保存，后续请求自动携带。
//   所以这个页面里没有任何 token 存取代码，这是刻意的。
//
// 必须处理的状态（开发文档 §8.5）：
//   - 提交中：按钮 loading + 禁用，防重复提交
//   - 校验错误：表单内联提示
//   - 凭据错误：表单上方的 alert（而不是 toast，避免用户没看到就消失了）
//   - 服务端错误：alert 显示可读文案
// ══════════════════════════════════════════════════════════════

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 表单上方的错误提示。用 ref 而不是 toast —— 凭据错误需要持续可见 */
const serverError = ref('')

const form = reactive({
  username: '',
  password: '',
})

/**
 * 校验规则。
 *
 * ⚠️ 注意与后端的差异：登录<b>不</b>校验密码复杂度
 * （不要求"必须含字母和数字"）。
 *
 * 原因：密码策略可能变过。若登录也套用新规则，
 * 老用户会被永久锁在门外，连登录去改密码的机会都没有。
 * 后端 LoginRequest 也是同样的设计。
 */
const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 50, message: '用户名长度不能超过 50 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 64, message: '密码长度不能超过 64 位', trigger: 'blur' },
  ],
}

/** 登录成功后要跳转的目标。默认首页 */
const redirectTarget = computed(() => {
  const r = route.query.redirect
  // 只接受站内相对路径，防止开放重定向（?redirect=https://evil.com）
  if (typeof r === 'string' && r.startsWith('/') && !r.startsWith('//')) {
    return r
  }
  return '/home'
})

onMounted(() => {
  // 已登录用户不该停留在登录页
  if (userStore.isLoggedIn) {
    void router.replace(redirectTarget.value)
    return
  }

  // 从注册页跳过来时会带上 username，预填省一次输入。
  // 只读取字符串，避免 query 是数组时出现 [object Object]
  const prefill = route.query.username
  if (typeof prefill === 'string' && prefill) {
    form.username = prefill
  }
})

async function handleSubmit(): Promise<void> {
  if (submitting.value) return

  // 先做前端校验，减少无意义的请求
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  serverError.value = ''

  try {
    await userStore.login({
      username: form.username.trim(),
      password: form.password,
    })
    ElMessage.success('登录成功')
    await router.replace(redirectTarget.value)
  } catch (e) {
    const err = e as { message?: string; code?: number }

    // 后端对「用户不存在」和「密码错误」返回完全相同的文案
    // （防用户名枚举），所以前端也不区分，只做通用提示。
    serverError.value = err.message || '登录失败，请稍后重试'

    // 凭据错误时清空密码并聚焦，方便重试
    if (err.code === ErrorCode.UNAUTHORIZED) {
      form.password = ''
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-page__card" shadow="always">
      <!-- ── 品牌 ──────────────────────────────────────────── -->
      <div class="auth-page__brand">
        <!--
          品牌图标：看板娘头像。
          ⚠️ alt="" + aria-hidden：右侧紧接着就是「AI 日记」标题，
          图标是纯装饰，重复朗读只会干扰读屏用户。
        -->
        <img class="auth-page__logo" :src="logoUrl" alt="" aria-hidden="true" />
        <h1 class="auth-page__title">AI 日记</h1>
        <p class="auth-page__subtitle">记录今天，让它记住你</p>
      </div>

      <!-- ── 服务端错误（持续可见，不用 toast）────────────── -->
      <el-alert
        v-if="serverError"
        type="error"
        :closable="false"
        show-icon
        class="auth-page__alert"
        :title="serverError"
      />

      <!-- ── 表单 ──────────────────────────────────────────── -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            :disabled="submitting"
            clearable
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            :disabled="submitting"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button
          type="primary"
          class="auth-page__submit"
          size="large"
          :loading="submitting"
          @click="handleSubmit"
        >
          {{ submitting ? '登录中…' : '登录' }}
        </el-button>
      </el-form>

      <!-- ── 去注册 ────────────────────────────────────────── -->
      <div class="auth-page__footer">
        <span>还没有账号？</span>
        <el-button text type="primary" size="small" @click="router.push('/register')">
          立即注册
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 16px;
  background: var(--ad-bg-page);
}

.auth-page__card {
  width: 100%;
  max-width: 380px;
  border-radius: 8px;
}

.auth-page__brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  margin-bottom: 20px;
}

/*
 * 品牌图标 —— 看板娘头像。
 *
 * ── 尺寸 ────────────────────────────────────────────────────
 * 演进过程：emoji「📔」(font-size 36px) → 图片 48px → **现在的 64px**。
 * 用户看完 48px 的效果后觉得还可以再大一点，故调至 64px。
 *
 * ⚠️ 尺寸要与 RegisterPage 的 .auth-page__logo 保持同步。
 *    两页用的是各自的 <style scoped>，没有共用文件 ——
 *    所以改一处必须记得改另一处，否则两页外观不一致。
 *    （若以后还要再改，值得把它抽成一个 AuthBrand.vue 组件。）
 *
 * ── ⚠️ 为什么必须裁切（本样式最关键的一点）─────────────────
 * 原图 1280 × 1280，但**角色占满整个画布**：头在上半部，
 * 张开的手和身体在下半部。如果整张图直接缩到 64px，
 * 屏幕上的实际效果是一个小方块里塞着"整个人"，
 * 五官只有几个像素 —— 完全看不清是谁。
 *
 * 所以这里用 object-fit: cover + object-position 把**头部**裁出来：
 *   · cover 保证短边铺满容器（不会出现拉伸变形）
 *   · object-position: 50% 12% 让可视窗口上移，聚焦到脸
 *     （角色头部约在图片 y 的 2%–45%，脸的中心约在 y≈20%；
 *       但实测 20% 时脸只占图标约 40%、偏小，故上移到 12% 让脸更突出）
 *
 * ── ⚠️ 素材体积（已知问题）──────────────────────────────────
 * 原图 1.6MB。登录页是**首屏**，这 1.6MB 会直接拖慢第一眼的速度，
 * 这是目前最值得优化的一处。修法与看板娘那边相同：
 * 转 WebP / 预先裁出头部小图，留到 Phase 8 工程化时统一做
 * （现在不引入图片处理依赖）。
 */
.auth-page__logo {
  width: 64px;
  height: 64px;
  border-radius: 14px;
  object-fit: cover;
  object-position: 50% 12%;
  /* 与全站一致的柔和投影（带绿调，不用纯黑） */
  box-shadow: 0 1px 3px rgba(85, 96, 79, 0.12);
}

.auth-page__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.auth-page__subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ad-text-secondary);
}

.auth-page__alert {
  margin-bottom: 16px;
}

.auth-page__submit {
  width: 100%;
  margin-top: 4px;
}

.auth-page__footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  margin-top: 16px;
  font-size: 13px;
  color: var(--ad-text-secondary);
}
</style>
