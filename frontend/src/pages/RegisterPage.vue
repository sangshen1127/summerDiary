<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { ErrorCode } from '@/types/api'
import logoUrl from '@/image/看板娘2.png'

// ══════════════════════════════════════════════════════════════
// 注册页
// ══════════════════════════════════════════════════════════════
// 注册成功后<b>不自动登录</b> —— 与后端设计一致（注册接口不下发 Cookie）。
// 跳转到登录页，让「会话创建」只有一条代码路径。
//
// 前端校验规则必须与后端 RegisterRequest 保持一致，
// 否则会出现「前端放过了但后端拒绝」的困惑体验。
// ══════════════════════════════════════════════════════════════

const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const serverError = ref('')

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

/**
 * 密码强度校验 —— 与后端 StrongPasswordValidator 对应。
 *
 * ⚠️ 前端校验只是<b>体验优化</b>，不是安全边界。
 *    真正的校验必须在后端（前端代码用户可改）。
 *    这里做同样的检查是为了让用户立刻知道规则，而不是提交后才被拒绝。
 */
function validatePassword(_rule: unknown, value: string, callback: (e?: Error) => void): void {
  if (!value) {
    callback(new Error('请输入密码'))
    return
  }
  if (value.length < 8 || value.length > 64) {
    callback(new Error('密码长度需为 8-64 位'))
    return
  }
  // 用 ASCII 字母数字，与后端一致 —— 避免全角字符被误判为"含字母"
  if (!/[A-Za-z]/.test(value)) {
    callback(new Error('密码需至少包含一个字母'))
    return
  }
  if (!/[0-9]/.test(value)) {
    callback(new Error('密码需至少包含一个数字'))
    return
  }
  callback()
}

function validateConfirm(_rule: unknown, value: string, callback: (e?: Error) => void): void {
  if (!value) {
    callback(new Error('请再次输入密码'))
    return
  }
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
    return
  }
  callback()
}

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度需为 3-50 位', trigger: 'blur' },
    {
      // 与后端完全相同的正则：字母、数字、下划线、汉字
      pattern: /^[A-Za-z0-9_\u4e00-\u9fa5]+$/,
      message: '用户名只能包含字母、数字、下划线或汉字',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, validator: validatePassword, trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, validator: validateConfirm, trigger: 'blur' },
  ],
  nickname: [
    { max: 50, message: '昵称最长 50 位', trigger: 'blur' },
  ],
}

async function handleSubmit(): Promise<void> {
  if (submitting.value) return

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  serverError.value = ''

  try {
    await userStore.register({
      username: form.username.trim(),
      password: form.password,
      // 昵称为空时不传该字段，让后端归一化成 null
      ...(form.nickname.trim() ? { nickname: form.nickname.trim() } : {}),
    })

    ElMessage.success('注册成功，请登录')
    // 带上用户名，让登录页预填，省一次输入
    await router.replace({ path: '/login', query: { username: form.username.trim() } })
  } catch (e) {
    const err = e as { message?: string; code?: number }

    if (err.code === ErrorCode.CONFLICT) {
      // 用户名冲突：给出明确指引并聚焦用户名框，而不是只说"操作冲突"
      serverError.value = '该用户名已被占用，请换一个'
    } else {
      serverError.value = err.message || '注册失败，请稍后重试'
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
          品牌图标：看板娘头像（与登录页一致）。
          ⚠️ alt="" + aria-hidden：右侧紧接着就是标题文字，
          图标是纯装饰，重复朗读只会干扰读屏用户。
        -->
        <img class="auth-page__logo" :src="logoUrl" alt="" aria-hidden="true" />
        <h1 class="auth-page__title">注册账号</h1>
        <p class="auth-page__subtitle">日记内容加密存储，只有你能看到</p>
      </div>

      <el-alert
        v-if="serverError"
        type="error"
        :closable="false"
        show-icon
        class="auth-page__alert"
        :title="serverError"
      />

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
            placeholder="3-50 位，字母/数字/下划线/汉字"
            autocomplete="username"
            :disabled="submitting"
            clearable
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="8-64 位，至少含字母和数字"
            autocomplete="new-password"
            show-password
            :disabled="submitting"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再次输入密码"
            autocomplete="new-password"
            show-password
            :disabled="submitting"
          />
        </el-form-item>

        <el-form-item label="昵称（可选）" prop="nickname">
          <el-input
            v-model="form.nickname"
            placeholder="不填则显示用户名"
            :disabled="submitting"
            clearable
          />
        </el-form-item>

        <el-button
          type="primary"
          class="auth-page__submit"
          size="large"
          :loading="submitting"
          @click="handleSubmit"
        >
          {{ submitting ? '注册中…' : '注册' }}
        </el-button>
      </el-form>

      <div class="auth-page__footer">
        <span>已有账号？</span>
        <el-button text type="primary" size="small" @click="router.push('/login')">
          去登录
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
  max-width: 400px;
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
 * 品牌图标 —— 看板娘头像（与登录页完全一致）。
 *
 * ⚠️ 尺寸要与 LoginPage 的 .auth-page__logo 保持同步。
 *    两页用的是各自的 <style scoped>，没有共用文件 ——
 *    所以改一处必须记得改另一处，否则两页外观不一致。
 *    （若以后还要再改，值得把它抽成一个 AuthBrand.vue 组件。）
 *
 * ── ⚠️ 为什么必须裁切 ──────────────────────────────────────
 * 原图 1280 × 1280，但**角色占满整个画布**：头在上半部，
 * 张开的手和身体在下半部。整张图直接缩到 64px，屏幕上是
 * "一个小方块里塞着整个人"，五官只剩几个像素，认不出是谁。
 *
 * 所以用 object-fit: cover + object-position 把**头部**裁出来：
 *   · cover 保证短边铺满容器（不会拉伸变形）
 *   · object-position: 50% 12% 让可视窗口上移，聚焦到脸
 *     （角色头部约在图片 y 的 2%–45%，脸的中心约在 y≈20%；
 *       但实测 20% 时脸只占图标约 40%、偏小，故上移到 12% 让脸更突出）
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
  text-align: center;
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
