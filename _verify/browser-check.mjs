/**
 * ============================================================
 * 模块 2-4 浏览器验收：用 CDP 驱动 headless Edge 真实渲染日记三页
 * ============================================================
 *
 * 【为什么不用 Playwright / Puppeteer】
 * 项目当前没装它们。为了"截几张图"引入一个几十 MB 的浏览器自动化依赖，
 * 会永久留在 package.json 里，而 Phase 5 的 Playwright E2E 是另一件事
 * （那时才该正式引入）。本脚本用 Node 24 自带的 fetch + WebSocket
 * 直连 Chrome DevTools Protocol，**零新增依赖**。
 *
 * 【它验证什么】
 * HTTP 200 只能证明"服务器返回了 HTML"，证明不了页面渲染正确。
 * 这个脚本做的是真实渲染验证：
 *   1. 通过后端 API 造出已登录会话（cookie）
 *   2. 用 CDP 把 cookie 注入浏览器（模拟已登录状态）
 *   3. 真实导航到 /diaries、/diaries/new、详情页
 *   4. 截图 + 抓取 DOM 文本（确认数据真的渲染出来了）
 *   5. 收集 console 错误与页面异常（白屏的常见原因）
 *   6. 跑一次 375px 窄屏，确认响应式没横向溢出
 *
 * 【用法】
 *   node _verify/browser-check.mjs
 * 前置：后端 8080 与 Vite 5173 都在跑。
 */

import { spawn } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'

// ── 配置 ──────────────────────────────────────────────────────
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const FRONT = 'http://localhost:5173'
const API = 'http://localhost:8080/api'
const OUT_DIR = 'D:\\summerDiary\\_verify\\shots'
const CDP_PORT = 9222

const RUN = 'ZZFE' + (Date.now() % 100000)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

mkdirSync(OUT_DIR, { recursive: true })

// ── 步骤 1：通过 API 准备数据并拿到会话 cookie ────────────────
async function prepareData() {
  const username = RUN.toLowerCase()
  const password = 'pw123456'

  const reg = await fetch(`${API}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname: '前端验证' }),
  })
  log(`注册 ${username} → HTTP ${reg.status}`)

  const login = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  log(`登录 → HTTP ${login.status}`)

  const setCookie = login.headers.get('set-cookie') || ''
  const m = /AI_DIARY_SESSION=([^;]+)/.exec(setCookie)
  if (!m) throw new Error('没拿到会话 cookie，登录可能失败了')
  const sessionValue = m[1]

  // 造标签
  const mkTag = async (name) => {
    const r = await fetch(`${API}/tags`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Cookie: `AI_DIARY_SESSION=${sessionValue}` },
      body: JSON.stringify({ name }),
    })
    const j = await r.json()
    return j.data?.id
  }
  const tag1 = await mkTag(RUN + '_读书')
  const tag2 = await mkTag(RUN + '_散步')

  // 造几篇日记，覆盖"有正文/无正文/不同心情"几种展示情况
  const mkDiary = async (body) => {
    const r = await fetch(`${API}/diaries`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Cookie: `AI_DIARY_SESSION=${sessionValue}` },
      body: JSON.stringify(body),
    })
    const j = await r.json()
    if (j.code !== 0) throw new Error('创建日记失败: ' + JSON.stringify(j))
    return j.data.id
  }

  const d1 = await mkDiary({
    title: '雨后的河边',
    content:
      '今天下午雨停了，和阿哲去河边走了走。\n\n水面很静，能看到云的倒影。\n看到一只白鹭，站了很久。',
    mood: '平静',
    weather: '阴',
    location: '河边',
    tag_ids: [tag1, tag2],
  })

  await mkDiary({
    title: '读完了一本书',
    content: '这本断断续续读了两个月，今天终于读完最后一章。',
    mood: '开心',
    tag_ids: [tag1],
  })

  await mkDiary({
    title: '只是记一下今天很累',
    content: '',
    mood: '疲惫',
    tag_ids: [],
  })

  log(`已造 3 篇日记 + 2 个标签，主日记 id=${d1}`)
  return { sessionValue, diaryId: d1 }
}

// ── 步骤 2：CDP 客户端 ────────────────────────────────────────
class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.events = []
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id !== undefined) {
        const p = this.pending.get(msg.id)
        if (p) {
          this.pending.delete(msg.id)
          msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result)
        }
      } else {
        this.events.push(msg)
      }
    })
  }

  send(method, params = {}) {
    const id = ++this.id
    this.ws.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP 超时: ${method}`))
        }
      }, 20000)
    })
  }

  /** 取走并清空累计的 console 错误 / 页面异常 */
  drainProblems() {
    const found = []
    for (const e of this.events) {
      if (e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error') {
        found.push('console.error: ' + e.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
      }
      if (e.method === 'Runtime.exceptionThrown') {
        const d = e.params.exceptionDetails
        found.push('未捕获异常: ' + (d.exception?.description || d.text))
      }
      if (e.method === 'Log.entryAdded' && e.params.entry.level === 'error') {
        // ⚠️ 带上 URL：只写"404"没法判断是 favicon 还是真的接口失败。
        // 这两种 404 的处理方式完全不同 —— 前者无害，后者是真 bug。
        const url = e.params.entry.url || ''
        found.push(`log.error: ${e.params.entry.text}${url ? ' [' + url + ']' : ''}`)
      }
    }
    this.events = []
    return found
  }
}

async function connect() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json()
      const page = list.find((t) => t.type === 'page')
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl
    } catch {
      /* 还没起来，继续等 */
    }
    await sleep(500)
  }
  throw new Error('连不上 CDP，Edge 可能没启动成功')
}

// ── 步骤 3：主流程 ────────────────────────────────────────────
async function main() {
  const { sessionValue, diaryId } = await prepareData()

  log('启动 headless Edge…')
  const edge = spawn(
    EDGE,
    [
      '--headless=new',
      `--remote-debugging-port=${CDP_PORT}`,
      `--user-data-dir=${OUT_DIR}\\edge-profile`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      '--hide-scrollbars',
      '--window-size=1280,900',
      'about:blank',
    ],
    { stdio: 'ignore' },
  )

  const wsUrl = await connect()
  const ws = new WebSocket(wsUrl)
  await new Promise((res, rej) => {
    ws.addEventListener('open', res)
    ws.addEventListener('error', rej)
  })
  const cdp = new Cdp(ws)

  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Log.enable')
  await cdp.send('Network.enable')

  // 注入会话 cookie（模拟已登录），domain 用 localhost
  await cdp.send('Network.setCookie', {
    name: 'AI_DIARY_SESSION',
    value: sessionValue,
    domain: 'localhost',
    path: '/',
    httpOnly: true,
  })
  log('已注入会话 cookie')

  const results = []

  // ── 页面 1：日记列表 ──────────────────────────────────────
  await goto(cdp, `${FRONT}/diaries`)
  let shot = await shoot(cdp, '01-日记列表')
  let text = await bodyText(cdp)
  const hasSearchBox = await searchBoxExists(cdp)
  results.push(
    check('列表页渲染出日记标题', text.includes('雨后的河边') && text.includes('读完了一本书'), text),
    check('列表页显示标签', text.includes('_读书'), text),
    /*
     * ⚠️ 这里**不能**断言 placeholder 文案。
     *
     * 第一版写的是 `text.includes('搜索标题')`，结果失败 ——
     * 因为 `placeholder` 是 DOM **属性**，不会出现在 `innerText` 里。
     * 那是测试写错了，不是页面没渲染（innerText 里明明有 "心情/标签/搜索"）。
     *
     * 正确做法是查 DOM 元素本身，同时断言多个控件，
     * 这样"筛选栏整个没渲染"和"只是文案变了"能区分开。
     */
    check(
      '列表页渲染出筛选控件（搜索框 + 心情/标签下拉 + 搜索按钮）',
      hasSearchBox && text.includes('心情') && text.includes('标签') && text.includes('搜索'),
      '搜索框存在=' + hasSearchBox,
    ),
    /*
     * ── 看板娘 ──────────────────────────────────────────────
     * ⚠️ 只断言"元素存在"是不够的：图片 404 或解码失败时，
     *    <img> 元素依然在 DOM 里，但屏幕上什么都没有。
     *    必须查 naturalWidth —— 它 > 0 才说明图片**真的加载成功**。
     *
     * 同时查 computed style 确认它没有被 display:none 之类的规则隐藏
     * （窄屏媒体查询里确实有 display:none，桌面宽度下不该命中）。
     */
    /*
     * ── 右下角看板娘 ────────────────────────────────────────
     * ⚠️ 只断言"元素存在"不够：图片 404 时元素照样在 DOM 里，
     *    但屏幕上什么都没有。必须查 naturalWidth。
     */
    ...(await mascotChecks(cdp)),
    /*
     * ── 顶栏品牌图标 ────────────────────────────────────────
     * AppShell 的 logo 也是看板娘头像。它只在登录后的页面出现，
     * 所以只能在有会话的这里验证（认证页那两张由图外的 logo-check.mjs 管）。
     */
    ...(await shellLogoChecks(cdp)),
    ...problems(cdp, '列表页'),
  )

  // ── 页面 2：详情页（验证正文解密显示）────────────────────
  await goto(cdp, `${FRONT}/diaries/${diaryId}`)
  shot = await shoot(cdp, '02-日记详情')
  text = await bodyText(cdp)
  results.push(
    check('详情页显示完整正文（含换行内容）', text.includes('水面很静') && text.includes('白鹭'), text),
    check('详情页显示 AI 区块', text.includes('AI 分析'), text),
    /*
     * Phase 3 起 AI 区块是一台完整状态机，而且**创建日记会自动排队一次分析**，
     * 所以打开详情页时落在哪个状态取决于 Worker 跑多快（验收时 mock 故意慢 1.5s）：
     *   尚未分析 / 正在分析这篇日记… / 已有结果（此时显示"重新分析"）
     * 因此断言"落在合法状态之一"，而不是钉死某一种 ——
     * 钉死"尚未分析"会随分析完成时间随机失败，本文件跑 Phase 3 时就是这样失败的。
     */
    check(
      '★ AI 区块落在 Phase 3 的合法状态之一（尚未分析 / 分析中 / 有结果）',
      text.includes('尚未分析') ||
        text.includes('正在分析这篇日记') ||
        text.includes('重新分析'),
      text,
    ),
    check(
      '★ AI 区块没有落到"取状态失败"兜底态（那是请求失败，与"分析失败"是两件事）',
      !text.includes('重新获取'),
      text,
    ),
    ...problems(cdp, '详情页'),
  )

  // ── 页面 3：编辑页（验证先加载详情再渲染表单）────────────
  await goto(cdp, `${FRONT}/diaries/${diaryId}/edit`)
  shot = await shoot(cdp, '03-编辑日记')
  text = await bodyText(cdp)
  const contentValue = await textareaValue(cdp)
  results.push(
    check('编辑页标题显示"编辑日记"', text.includes('编辑日记'), text),
    check(
      '★★ 编辑页正文框已填入原文（证明先加载了详情，不会用空内容覆盖）',
      contentValue.includes('水面很静'),
      'textarea 实际内容前 60 字: ' + contentValue.slice(0, 60),
    ),
    check('编辑页标题框已填入原标题', (await inputValue(cdp, 'input[type="text"]')).includes('雨后的河边'), ''),
    ...problems(cdp, '编辑页'),
  )

  // ── 页面 4：新建页 ────────────────────────────────────────
  await goto(cdp, `${FRONT}/diaries/new`)
  shot = await shoot(cdp, '04-写日记')
  text = await bodyText(cdp)
  const newContent = await textareaValue(cdp)
  results.push(
    check('新建页标题显示"写日记"', text.includes('写日记'), text),
    check('新建页正文框为空（不是编辑模式）', newContent.trim() === '', '内容: ' + JSON.stringify(newContent)),
    ...problems(cdp, '新建页'),
  )

  // ── 页面 5：375px 窄屏（响应式铁律）──────────────────────
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 375,
    height: 780,
    deviceScaleFactor: 2,
    mobile: true,
  })
  await goto(cdp, `${FRONT}/diaries`)
  await shoot(cdp, '05-列表页-375px')
  const overflow = await cdp.send('Runtime.evaluate', {
    expression:
      'document.documentElement.scrollWidth > document.documentElement.clientWidth + 1',
    returnByValue: true,
  })
  results.push(
    check(
      '★★ 375px 下无横向溢出（响应式铁律）',
      overflow.result.value === false,
      'scrollWidth > clientWidth 说明有横向滚动条',
    ),
    /*
     * ⚠️ 窄屏下看板娘**应该被隐藏**（220px 的立绘在 375px 屏上会明显遮挡内容）。
     * 这是一条"反向断言"：验证的不是"存在"而是"正确地不存在"。
     * 只断言桌面可见而不管窄屏，等于放着一个已知会挡内容的问题不管。
     */
    ...(await (async () => {
      const r = await cdp.send('Runtime.evaluate', {
        expression: `(() => {
          const el = document.querySelector('.mascot')
          return el ? getComputedStyle(el).display : 'NOT_FOUND'
        })()`,
        returnByValue: true,
      })
      const display = r.result.value
      return [
        {
          ok: display === 'none' || display === 'NOT_FOUND',
          name: '★ 375px 下看板娘被隐藏（不遮挡内容）',
          detail: `display=${display} —— 窄屏应命中媒体查询的 display:none`,
        },
      ]
    })()),
    ...problems(cdp, '375px 列表页'),
  )

  await cdp.send('Emulation.clearDeviceMetricsOverride')

  // ── 汇总 ──────────────────────────────────────────────────
  console.log('\n' + '='.repeat(70))
  let pass = 0
  let fail = 0
  for (const r of results) {
    console.log(`[${r.ok ? 'PASS' : 'FAIL'}] ${r.name}${r.ok ? '' : '  → ' + r.detail}`)
    r.ok ? pass++ : fail++
  }
  console.log('='.repeat(70))
  console.log(`通过: ${pass}   失败: ${fail}`)
  console.log(`截图目录: ${OUT_DIR}`)

  ws.close()
  edge.kill()
  process.exit(fail > 0 ? 1 : 0)
}

// ── 辅助 ──────────────────────────────────────────────────────
async function goto(cdp, url) {
  await cdp.send('Page.navigate', { url })
  // 等网络安静：轮询 document.readyState + 给 Vue 渲染留时间
  for (let i = 0; i < 30; i++) {
    await sleep(300)
    const r = await cdp.send('Runtime.evaluate', {
      expression: 'document.readyState',
      returnByValue: true,
    })
    if (r.result.value === 'complete') break
  }
  await sleep(900) // Vue 挂载 + 异步请求返回
}

async function shoot(cdp, name) {
  const r = await cdp.send('Page.captureScreenshot', { format: 'png' })
  const file = `${OUT_DIR}\\${name}.png`
  writeFileSync(file, Buffer.from(r.data, 'base64'))
  log(`截图 → ${name}.png`)
  return file
}

async function bodyText(cdp) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: 'document.body.innerText',
    returnByValue: true,
  })
  return r.result.value || ''
}

async function textareaValue(cdp) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: 'document.querySelector("textarea")?.value ?? "(没有 textarea)"',
    returnByValue: true,
  })
  return r.result.value || ''
}

async function inputValue(cdp, selector) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: `document.querySelector(${JSON.stringify(selector)})?.value ?? ""`,
    returnByValue: true,
  })
  return r.result.value || ''
}

/**
 * 搜索框是否存在。
 *
 * ⚠️ 必须查 DOM 而不是查 innerText —— `placeholder` 是 DOM 属性，
 * 不会出现在 innerText 里。第一版脚本因此假失败了一次。
 */
async function searchBoxExists(cdp) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: '!!document.querySelector("input[type=search], .diary-list__search input")',
    returnByValue: true,
  })
  return r.result.value === true
}

/**
 * 看板娘渲染检查。
 *
 * ⚠️ 三条断言缺一不可：
 *   1. 元素存在                —— 组件有没有被挂上
 *   2. naturalWidth > 0        —— 图片**真的加载成功**（防 404 / 解码失败）
 *   3. computed display 不是 none —— 没被媒体查询或样式规则隐藏
 *
 * 只查第 1 条是最容易犯的错：图片挂了，元素还在，断言照样通过。
 */
async function mascotChecks(cdp) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: `(() => {
      const el = document.querySelector('.mascot')
      if (!el) return { exists: false }
      const cs = getComputedStyle(el)
      return {
        exists: true,
        naturalWidth: el.naturalWidth,
        naturalHeight: el.naturalHeight,
        display: cs.display,
        width: cs.width,
        animationName: cs.animationName,
        src: el.getAttribute('src') || '',
      }
    })()`,
    returnByValue: true,
  })
  const m = r.result.value

  if (!m.exists) {
    return [{ ok: false, name: '看板娘已渲染', detail: 'DOM 里找不到 .mascot 元素' }]
  }

  return [
    {
      ok: m.naturalWidth > 0,
      name: '★ 看板娘图片真的加载成功（naturalWidth > 0）',
      detail: `naturalWidth=${m.naturalWidth} —— 为 0 说明图片 404 或解码失败，`
        + `元素虽在但屏幕上什么都没有。src=${m.src}`,
    },
    {
      ok: m.display !== 'none',
      name: '★ 看板娘在桌面宽度下可见（未被隐藏）',
      detail: `display=${m.display} width=${m.width}`,
    },
    {
      ok: m.animationName === 'breathe',
      name: '看板娘应用了呼吸动效',
      detail: `animationName=${m.animationName}（应为 breathe）`,
    },
  ]
}

/**
 * 顶栏品牌图标检查（AppShell 的 .shell__logo）。
 *
 * 与 mascotChecks 同样的道理：必须查 naturalWidth 才能确认图片
 * **真的加载成功**，光看元素存在会漏掉 404 / 解码失败。
 *
 * 另外断言它是 <img> 而不是还留着 emoji —— 改动前的元素是
 * <span>📔</span>，标签类型能直接区分"改了"和"没改"。
 */
async function shellLogoChecks(cdp) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: `(() => {
      const el = document.querySelector('.shell__logo')
      if (!el) return { exists: false }
      const cs = getComputedStyle(el)
      return {
        exists: true,
        tag: el.tagName,
        naturalWidth: el.naturalWidth,
        renderedW: Math.round(el.getBoundingClientRect().width),
        objectFit: cs.objectFit,
        objectPosition: cs.objectPosition,
      }
    })()`,
    returnByValue: true,
  })
  const m = r.result.value

  if (!m.exists) {
    return [{ ok: false, name: '顶栏品牌图标已渲染', detail: '找不到 .shell__logo' }]
  }

  return [
    {
      ok: m.tag === 'IMG' && m.naturalWidth > 0,
      name: '★ 顶栏品牌图标是看板娘图片且加载成功',
      detail: `tag=${m.tag} naturalWidth=${m.naturalWidth} width=${m.renderedW}px `
        + `—— tag=SPAN 说明还是旧 emoji；naturalWidth=0 说明图片没加载`,
    },
    {
      ok: m.objectPosition === '50% 12%',
      name: '顶栏图标用了与认证页相同的裁剪参数（同一个角色不会显示成两个样）',
      detail: `objectFit=${m.objectFit} objectPosition=${m.objectPosition}`,
    },
  ]
}

function problems(cdp, where) {
  const found = cdp.drainProblems()
  return [
    {
      ok: found.length === 0,
      name: `${where}无 console 错误 / 异常`,
      detail: found.slice(0, 3).join(' | '),
    },
  ]
}

function check(name, ok, detail) {
  return { ok: !!ok, name, detail: ok ? '' : (detail || '').slice(0, 200) }
}

function log(msg) {
  console.log('[browser-check] ' + msg)
}

main().catch((e) => {
  console.error('脚本失败:', e)
  process.exit(2)
})
