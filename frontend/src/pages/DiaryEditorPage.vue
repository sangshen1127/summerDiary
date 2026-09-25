<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { diaryApi, tagApi } from '@/api/diary'
import type { Tag } from '@/types/diary'
import type { AppError } from '@/types/api'
import { useAsyncState } from '@/composables/useAsyncState'

// ══════════════════════════════════════════════════════════════
// DiaryEditorPage —— 新建 / 编辑日记
// ══════════════════════════════════════════════════════════════
// 一个页面同时承担新建与编辑：靠路由里有没有 id 区分
//   /diaries/new   → 新建
//   /diaries/:id/edit → 编辑
//
// ⚠️⚠️ 编辑模式的**核心安全约束**：必须先调详情接口拿到 content。
//
//   后端列表接口返回的 `content` 恒为 null（列表刻意不解密正文）。
//   如果编辑页从列表数据填充表单，content 就是空串，
//   一提交就会用空正文**覆盖掉用户原来的内容** —— 那是静默数据丢失，
//   比报个错糟糕得多，因为用户不会立刻发现。
//
//   所以本页在编辑模式下：
//     1. 挂载时调 `diaryApi.detail(id)` 拿明文全文
//     2. 拿到之前**不渲染表单**（只显示加载态），避免用户
//        对着空表单开始输入然后被覆盖
//     3. 详情加载失败就整个页面转错误态，不给"空表单提交"的机会
//
// ⚠️ PUT 是整体替换语义：提交时必须带上全部字段。
//   本页因为先加载了完整详情，所以表单里就是完整数据，天然满足。
// ══════════════════════════════════════════════════════════════

const route = useRoute()
const router = useRouter()

/**
 * 从路由取日记 ID。
 *
 * 路由参数在类型上可能是 string | string[]，必须规范化 ——
 * 直接 `Number(route.params.id)` 在数组情况下会得到 NaN，
 * 而 NaN 传给后端会变成 `/diaries/NaN`，得到一个莫名其妙的 400。
 */
const diaryId = computed<number | null>(() => {
  const raw = route.params.id
  const value = Array.isArray(raw) ? raw[0] : raw
  if (!value) return null
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
})

/** 是否是编辑模式 */
const isEdit = computed(() => diaryId.value !== null)

// ── 表单 ───────────────────────────────────────────────────────
interface DiaryForm {
  title: string
  content: string
  mood: string
  weather: string
  location: string
  tagIds: number[]
}

const form = reactive<DiaryForm>({
  title: '',
  content: '',
  mood: '',
  weather: '',
  location: '',
  tagIds: [],
})

const formRef = ref<FormInstance>()

/**
 * 校验规则必须与后端 `DiaryWriteFields` / `DiaryCreateRequest` 对齐。
 *
 * ⚠️ 前端校验是**体验优化**，不是安全边界 —— 后端有同样的校验，
 *    绕过前端直接调接口一样会被 40001 拒绝。
 *    这里的规则与后端保持一致的目的是：让用户尽早看到提示，
 *    而不是填完一大篇才被后端拒绝。
 *
 * 对齐关系（后端 @Size / @NotBlank）：
 *   title    1-200   ← @NotBlank + @Size(max=200)
 *   content  0-8000  ← @NotNull + @Size(max=8000)，允许空串
 *   mood     ≤20
 *   weather  ≤20
 *   location ≤100
 *   tagIds   ≤20
 */
const rules: FormRules<DiaryForm> = {
  title: [
    { required: true, message: '标题不能为空', trigger: 'blur' },
    { max: 200, message: '标题最长 200 字', trigger: 'blur' },
  ],
  content: [{ max: 8000, message: '正文最长 8000 字', trigger: 'blur' }],
  mood: [{ max: 20, message: '心情最长 20 字', trigger: 'blur' }],
  weather: [{ max: 20, message: '天气最长 20 字', trigger: 'blur' }],
  location: [{ max: 100, message: '地点最长 100 字', trigger: 'blur' }],
  tagIds: [
    {
      type: 'array',
      max: 20,
      message: '一次最多关联 20 个标签',
      trigger: 'change',
    },
  ],
}

// ── 标签 ───────────────────────────────────────────────────────
const tags = ref<Tag[]>([])
const newTagName = ref('')
const creatingTag = ref(false)

// ── 详情加载（仅编辑模式用）────────────────────────────────────
/*
 * ⚠️⚠️ 解构而不是 `const detailState = useAsyncState(...)`。
 *
 * 模板的 ref 自动解包只对**顶层绑定**生效，`detailState.loading` 拿到的是
 * ref 对象本身，对象永远 truthy —— 于是 `v-if="loadingDetail"`
 * 恒为假、而 `v-if="detailState.loading"` 恒为真，两种写法都会出错。
 *
 * 这个坑是模块 2-4 用 headless 浏览器实测抓到的（列表页骨架屏永不消失），
 * 详见 DiaryListPage 里那段完整说明。
 */
const {
  loading: loadingDetail,
  error: detailError,
  data: detailData,
  execute: loadDetail,
} = useAsyncState(() => {
  const id = diaryId.value
  if (id === null) {
    // 新建模式不会走到这里（模板里有 v-if 拦着），兜底返回一个拒绝，
    // 避免"id 为 null 却去请求 /diaries/null"这种更难懂的报错
    return Promise.reject(new Error('缺少日记 ID'))
  }
  return diaryApi.detail(id)
})

/** 用详情数据填表单 */
function fillFormFromDetail(): void {
  const d = detailData.value
  if (!d) return
  form.title = d.title
  // ⚠️ content 在详情接口里是明文全文；理论上不会是 null，
  //    但类型上允许（列表接口为 null），所以用 ?? '' 兜底，
  //    避免把 null 塞进 v-model 导致输入框显示 "null"
  form.content = d.content ?? ''
  form.mood = d.mood ?? ''
  form.weather = d.weather ?? ''
  form.location = d.location ?? ''
  form.tagIds = d.tags.map((t) => t.id)
}

// ── 提交 ───────────────────────────────────────────────────────
const submitting = ref(false)

/** 可选的文本字段：空串要转成 undefined，避免把 "" 当成有效值提交 */
function optionalText(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

/**
 * 组装请求体。
 *
 * ⚠️ 刻意**不标返回类型**：创建和更新两个请求体的字段完全一致
 * （后端也是共用 `DiaryWriteFields` 接口来保证一致的），
 * 所以这里让 TS 自己推断，两边都能用。
 *
 * 如果硬标成 `DiaryCreateRequest & DiaryUpdateRequest`，
 * 读代码的人会去琢磨"为什么是交集"，而答案只是"这样两边都能传" ——
 * 用一个更奇特的类型表达一个更普通的意思，反而增加理解成本。
 *
 * ⚠️ `content` 必须原样提交（**不能 trim**）——
 *    用户在正文里刻意留的缩进和空行是内容的一部分。
 *    而标题 trim 掉首尾空白是合理的（避免" 标题"和"标题"看起来一样）。
 */
function buildPayload() {
  return {
    title: form.title.trim(),
    content: form.content,
    mood: optionalText(form.mood),
    weather: optionalText(form.weather),
    location: optionalText(form.location),
    // 空数组等价于"没有标签"，后端能正确处理（会清空关联）
    tag_ids: form.tagIds,
  }
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload = buildPayload()
    if (isEdit.value && diaryId.value !== null) {
      const updated = await diaryApi.update(diaryId.value, payload)
      ElMessage.success('已保存')
      // 保存后进详情页，让用户立刻看到最终结果（含服务端返回的时间）
      await router.replace(`/diaries/${updated.id}`)
    } else {
      const created = await diaryApi.create(payload)
      ElMessage.success('已写下')
      await router.replace(`/diaries/${created.id}`)
    }
  } catch (e) {
    const err = e as AppError
    // 40401：日记不存在或不属于我（编辑模式下可能是别人删掉了）
    if (err?.code === 40401) {
      ElMessage.error('这篇日记不存在或已被删除')
      await router.replace('/diaries')
      return
    }
    // 其余错误保留用户输入，只提示 —— 不能让用户白写一篇
    ElMessage.error(err?.message || '保存失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

async function handleCancel(): Promise<void> {
  if (isEdit.value && diaryId.value !== null) {
    await router.push(`/diaries/${diaryId.value}`)
  } else {
    await router.push('/diaries')
  }
}

/**
 * 新建标签。
 *
 * ⚠️ 后端这个接口是「创建或复用」：同名标签会返回已存在的那个，
 *    状态码仍是 201，不报 409。所以这里不用先查重 ——
 *    直接调，拿返回的 id 用即可。少一次往返，也少一处竞态。
 */
async function handleCreateTag(): Promise<void> {
  const name = newTagName.value.trim()
  if (!name) {
    ElMessage.warning('请输入标签名')
    return
  }
  if (name.length > 30) {
    ElMessage.warning('标签名最长 30 字')
    return
  }

  creatingTag.value = true
  try {
    const tag = await tagApi.create({ name })
    // 复用的情况：可能已经在列表里了，避免重复项
    if (!tags.value.some((t) => t.id === tag.id)) {
      tags.value.push(tag)
    }
    // 自动勾选刚创建的标签 —— 用户刚建它就是打算用的
    if (!form.tagIds.includes(tag.id)) {
      form.tagIds.push(tag.id)
    }
    newTagName.value = ''
    ElMessage.success('标签已就绪')
  } catch (e) {
    const err = e as AppError
    // 40901 理论上不会出现（后端是"创建或复用"），但万一出现要说清楚
    ElMessage.error(err?.message || '创建标签失败')
  } finally {
    creatingTag.value = false
  }
}

onMounted(async () => {
  // 标签列表与详情并行取，两者互不依赖
  const tagPromise = tagApi.list().catch(() => [] as Tag[])

  if (isEdit.value) {
    await loadDetail()
    fillFormFromDetail()
  }

  tags.value = await tagPromise
})
</script>

<template>
  <div class="editor">
    <!-- ── 页头 ──────────────────────────────────────────── -->
    <header class="editor__head">
      <h1 class="editor__title">{{ isEdit ? '编辑日记' : '写日记' }}</h1>
      <p class="editor__sub">
        {{ isEdit ? '正文以 AES-256-GCM 加密存储，每次保存都会用新的随机 IV' : '正文会在保存时加密存储' }}
      </p>
    </header>

    <!-- ── 编辑模式：详情加载中 / 失败 时不给表单 ─────────── -->
    <!-- ⚠️ 这一层是为了防止"空表单覆盖原文"：详情没拿到就不渲染输入框 -->
    <div v-if="isEdit && loadingDetail" class="editor__blocking">
      <div class="editor__skeleton">
        <div class="editor__skeleton-line editor__skeleton-line--short" />
        <div class="editor__skeleton-line editor__skeleton-line--tall" />
      </div>
      <p class="editor__blocking-text">正在读取日记内容…</p>
    </div>

    <div v-else-if="isEdit && detailError" class="editor__blocking">
      <div class="alert alert--danger">
        <span aria-hidden="true">⚠</span>
        <div>
          <p class="editor__error-title">读取日记失败</p>
          <p class="editor__error-msg">{{ detailError }}</p>
          <p class="editor__error-hint">
            为避免用空内容覆盖原文，这里不会显示编辑表单。
          </p>
        </div>
      </div>
      <div class="editor__blocking-actions">
        <button
          class="btn btn--primary btn--sm"
          type="button"
          @click="loadDetail().then(fillFormFromDetail)"
        >
          重试
        </button>
        <button class="btn btn--ghost btn--sm" type="button" @click="handleCancel">
          返回
        </button>
      </div>
    </div>

    <!-- ── 表单 ──────────────────────────────────────────── -->
    <el-form
      v-else
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      class="editor__form"
      @submit.prevent
    >
      <el-form-item label="标题" prop="title">
        <input
          v-model="form.title"
          class="field__input"
          type="text"
          maxlength="200"
          placeholder="给今天起个名字"
        />
      </el-form-item>

      <el-form-item label="正文" prop="content">
        <textarea
          v-model="form.content"
          class="field__input field__area"
          rows="12"
          maxlength="8000"
          placeholder="今天发生了什么？（可以只写标题，正文留空）"
        />
        <p class="field__hint">
          {{ form.content.length }} / 8000 字
          <span v-if="isEdit" class="editor__hint-strong">
            · 保存后正文会重新加密
          </span>
        </p>
      </el-form-item>

      <!-- 三个可选字段并排 -->
      <div class="editor__row">
        <el-form-item label="心情" prop="mood" class="editor__row-item">
          <input
            v-model="form.mood"
            class="field__input"
            type="text"
            maxlength="20"
            placeholder="平静"
          />
        </el-form-item>

        <el-form-item label="天气" prop="weather" class="editor__row-item">
          <input
            v-model="form.weather"
            class="field__input"
            type="text"
            maxlength="20"
            placeholder="晴"
          />
        </el-form-item>

        <el-form-item label="地点" prop="location" class="editor__row-item">
          <input
            v-model="form.location"
            class="field__input"
            type="text"
            maxlength="100"
            placeholder="河边"
          />
        </el-form-item>
      </div>

      <!-- 标签 -->
      <el-form-item label="标签" prop="tagIds">
        <div class="editor__tags">
          <el-select
            v-model="form.tagIds"
            multiple
            filterable
            placeholder="选择标签（可多选）"
            class="editor__tag-select"
          >
            <el-option
              v-for="tag in tags"
              :key="tag.id"
              :label="tag.name"
              :value="tag.id"
            />
          </el-select>

          <div class="editor__tag-new">
            <input
              v-model="newTagName"
              class="field__input"
              type="text"
              maxlength="30"
              placeholder="新标签名"
              @keyup.enter.prevent="handleCreateTag"
            />
            <button
              class="btn btn--dashed btn--sm"
              type="button"
              :disabled="creatingTag"
              @click="handleCreateTag"
            >
              {{ creatingTag ? '创建中…' : '+ 新建' }}
            </button>
          </div>
        </div>
        <p class="field__hint">
          同名标签会自动复用，不会重复创建。
        </p>
      </el-form-item>

      <!-- 操作 -->
      <div class="editor__actions">
        <button
          class="btn btn--primary"
          type="button"
          :disabled="submitting"
          @click="handleSubmit"
        >
          {{ submitting ? '保存中…' : isEdit ? '保存修改' : '保存' }}
        </button>
        <button
          class="btn btn--ghost"
          type="button"
          :disabled="submitting"
          @click="handleCancel"
        >
          取消
        </button>
      </div>
    </el-form>
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 760px;
  margin: 0 auto;
}

.editor__head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.editor__title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--ad-text-primary);
}

.editor__sub {
  margin: 0;
  font-size: 13px;
  color: var(--ad-text-secondary);
}

/* ── 表单 ─────────────────────────────────────────────────── */
.editor__form {
  background: var(--ad-bg-card);
  border: 1px solid var(--ad-border-color);
  border-radius: 12px;
  padding: 20px 22px 22px;
}

.editor__row {
  display: flex;
  gap: 12px;
}

.editor__row-item {
  flex: 1;
  min-width: 0;
}

.editor__hint-strong {
  color: var(--ad-color-primary);
}

/* ── 标签区 ───────────────────────────────────────────────── */
.editor__tags {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.editor__tag-select {
  width: 100%;
}

.editor__tag-new {
  display: flex;
  gap: 8px;
}

.editor__tag-new .field__input {
  flex: 1;
}

/* ── 操作区 ───────────────────────────────────────────────── */
.editor__actions {
  display: flex;
  gap: 10px;
  margin-top: 4px;
}

/* ── 阻断态（详情没拿到时不渲染表单）─────────────────────── */
.editor__blocking {
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: var(--ad-bg-card);
  border: 1px solid var(--ad-border-color);
  border-radius: 12px;
  padding: 20px 22px;
}

.editor__blocking-text {
  margin: 0;
  text-align: center;
  font-size: 13px;
  color: var(--ad-text-secondary);
}

.editor__blocking-actions {
  display: flex;
  gap: 8px;
}

.editor__error-title {
  margin: 0;
  font-weight: 600;
}

.editor__error-msg {
  margin: 2px 0 0;
  font-size: 13px;
}

.editor__error-hint {
  margin: 6px 0 0;
  font-size: 12.5px;
  opacity: 0.85;
}

.editor__skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor__skeleton-line {
  border-radius: 8px;
  background: linear-gradient(
    90deg,
    var(--ad-bg-page) 25%,
    var(--ad-border-color) 37%,
    var(--ad-bg-page) 63%
  );
  background-size: 400% 100%;
  animation: editor-skeleton 1.6s ease-in-out infinite;
}

.editor__skeleton-line--short {
  height: 14px;
  width: 30%;
}

.editor__skeleton-line--tall {
  height: 140px;
}

@keyframes editor-skeleton {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .editor__skeleton-line {
    animation: none;
  }
}

/* ── 窄屏 375px ───────────────────────────────────────────── */
@media (max-width: 480px) {
  .editor__title {
    font-size: 20px;
  }

  .editor__row {
    flex-direction: column;
    gap: 0;
  }

  .editor__form {
    padding: 16px 14px 18px;
  }

  .editor__tag-new {
    flex-direction: column;
  }

  .editor__actions {
    flex-direction: column;
  }

  .editor__actions .btn {
    width: 100%;
  }
}
</style>
