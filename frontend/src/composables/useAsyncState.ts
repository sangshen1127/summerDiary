import { ref, computed, type Ref } from 'vue'

/**
 * 异步请求的统一状态管理。
 *
 * <h2>为什么需要它</h2>
 *
 * 开发文档 §8.5 要求每个数据页面都处理 loading / empty / error 三种状态。
 * 如果每个页面都手写这三个 ref，会出现：
 *
 * <pre>{@code
 * const loading = ref(false)
 * const error = ref('')
 * const data = ref(null)
 *
 * async function load() {
 *   loading.value = true
 *   error.value = ''
 *   try {
 *     data.value = await api.get()
 *   } catch (e) {
 *     error.value = e.message      // ← 每页重复
 *   } finally {
 *     loading.value = false        // ← 每页重复，且容易漏
 *   }
 * }
 * }</pre>
 *
 * 重复代码的问题不在于啰嗦，而在于<b>容易漏</b> ——
 * 某个页面忘了在 finally 里重置 loading，按钮就永远转圈。
 *
 * <h2>设计决策：不吞掉异常</h2>
 *
 * 出错时把错误信息存进 {@code error}，但<b>不 re-throw</b>。
 * 理由：页面已经把错误渲染成提示了，再抛出去需要调用方 try/catch，
 * 反而多一层样板。需要区分错误类型的场景（如 401 要跳登录）
 * 由 Axios 拦截器统一处理，不在这里做。
 *
 * @param fn 要执行的异步函数
 * @returns 状态与执行方法
 */
export function useAsyncState<T>(fn: () => Promise<T>) {
    const loading = ref(false)
    const error = ref('')
    const data = ref<T | null>(null) as Ref<T | null>

    /**
     * 是否已经执行过至少一次。
     *
     * 用于区分「还没请求」和「请求了但返回空」——
     * 不区分的话，页面刚挂载时会先闪一下空状态。
     */
    const executed = ref(false)

    /** 是否处于「执行过且成功且无数据」的空状态 */
    const isEmpty = computed(
        () => executed.value && !loading.value && !error.value && !hasContent(data.value),
    )

    async function execute(): Promise<T | null> {
        loading.value = true
        error.value = ''
        try {
            const result = await fn()
            data.value = result
            return result
        } catch (e) {
            // 交给统一的错误对象处理（Axios 拦截器已经把各种错误
            // 归一化成 { code, message, isAuthError, isAiError }）
            const err = e as { message?: string }
            error.value = err?.message || '请求失败，请稍后重试'
            data.value = null
            return null
        } finally {
            loading.value = false
            executed.value = true
        }
    }

    function reset(): void {
        loading.value = false
        error.value = ''
        data.value = null
        executed.value = false
    }

    return { loading, error, data, executed, isEmpty, execute, reset }
}

/**
 * 判断数据是否「有内容」。
 *
 * 覆盖几种常见形态：
 *   - null / undefined        → 空
 *   - 数组长度为 0            → 空
 *   - 对象没有任何 key        → 空
 *   - 分页对象的 items 为空   → 空
 *
 * 不做「空字符串算空」的判断 —— 那属于业务语义，
 * 由页面自己决定（例如摘要为空串可能要显示占位符而不是空状态）。
 */
function hasContent(value: unknown): boolean {
    if (value === null || value === undefined) {
        return false
    }
    if (Array.isArray(value)) {
        return value.length > 0
    }
    if (typeof value === 'object') {
        const obj = value as Record<string, unknown>
        // 分页结构特殊处理：items 为空即视为空，尽管对象本身有 key
        if (Array.isArray(obj.items)) {
            return obj.items.length > 0
        }
        return Object.keys(obj).length > 0
    }
    return true
}
