/**
 * ============================================================
 * 调查：访问 /home 能否绕过登录？（用户报告的安全问题）
 * ============================================================
 *
 * 【为什么必须实测而不是读代码判断】
 * 路由守卫的代码看起来是对的，但"看起来对"和"实际行为对"是两件事。
 * 这个调查要用真实浏览器跑出两种场景的**实际结果**：
 *
 *   场景 A：**无 Cookie** 访问 /home
 *            → 必须被重定向到 /login（否则是真漏洞）
 *
 *   场景 B：**有有效会话**访问 /home
 *            → 正常进入并显示用户名（这是设计预期，不是漏洞）
 *
 * 两者必须明确区分：用户看到"能进 /home"时，如果浏览器里本来就
 * 有有效会话，那是**会话恢复**（正常）；如果**没有会话也能进**，
 * 那才是**认证绕过**（严重漏洞）。
 *
 * 【判定依据】
 * 不看页面"像不像"，而看三个硬指标：
 *   1. 最终 URL（location.pathname）
 *   2. 页面里是否出现「登录」表单的特征元素
 *   3. /api/auth/me 的实际响应（401 还是 200）
 */
import { spawn } from 'node:child_process'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const FRONT = 'http://localhost:5173'
const API = 'http://localhost:8080/api'
const CDP_PORT = 9224
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const RUN = 'ZZSEC' + (Date.now() % 100000)

async function main() {
  const edge = spawn(
    EDGE,
    [
      '--headless=new',
      `--remote-debugging-port=${CDP_PORT}`,
      `--user-data-dir=D:\\summerDiary\\_verify\\shots\\edge-sec`,
      '--no-first-run',
      '--disable-gpu',
      '--window-size=1280,900',
      'about:blank',
    ],
    { stdio: 'ignore' },
  )

  let wsUrl
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json()
      const p = list.find((t) => t.type === 'page')
      if (p?.webSocketDebuggerUrl) {
        wsUrl = p.webSocketDebuggerUrl
        break
      }
    } catch {}
    await sleep(500)
  }
  if (!wsUrl) throw new Error('CDP 连不上')

  const ws = new WebSocket(wsUrl)
  await new Promise((res) => ws.addEventListener('open', res))

  let id = 0
  const pending = new Map()
  const netResponses = []
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data)
    if (m.id !== undefined) {
      const p = pending.get(m.id)
      if (p) {
        pending.delete(m.id)
        m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
      }
    } else if (m.method === 'Network.responseReceived') {
      const url = m.params.response.url
      if (url.includes('/api/auth/me')) {
        netResponses.push({ url, status: m.params.response.status })
      }
    }
  })
  const send = (method, params = {}) => {
    const i = ++id
    ws.send(JSON.stringify({ id: i, method, params }))
    return new Promise((resolve, reject) => {
      pending.set(i, { resolve, reject })
      setTimeout(() => {
        if (pending.has(i)) {
          pending.delete(i)
          reject(new Error('超时 ' + method))
        }
      }, 15000)
    })
  }
  const evaluate = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true })
    return r.result.value
  }

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')

  // ══════════════════════════════════════════════════════════
  console.log('='.repeat(72))
  console.log(' 场景 A：**无 Cookie** 直接访问 /home —— 检验是否存在认证绕过')
  console.log('='.repeat(72))

  // 彻底清空浏览器 cookie，确保是"全新未登录"状态
  await send('Network.clearBrowserCookies')
  await send('Network.clearBrowserCache')

  let meStatusA = null
  netResponses.length = 0

  await send('Page.navigate', { url: `${FRONT}/home` })
  await sleep(4000)

  const urlA = await evaluate('location.pathname + location.search')
  const hasLoginFormA = await evaluate(
    `!!document.querySelector('input[type="password"]')`,
  )
  const bodyA = await evaluate('document.body.innerText.slice(0, 300)')
  const cookiesA = await evaluate('document.cookie')
  const meA = netResponses.find((r) => r.url.includes('/api/auth/me'))

  console.log('最终 URL            :', urlA)
  console.log('页面上有密码输入框  :', hasLoginFormA)
  console.log('/api/auth/me 响应   :', meA ? meA.status : '(没发出该请求)')
  console.log('document.cookie     :', JSON.stringify(cookiesA), '（HttpOnly 所以读不到，正常）')
  console.log('页面文本前 200 字   :')
  console.log('  ' + bodyA.replace(/\n+/g, ' / ').slice(0, 200))

  const aRedirected = String(urlA).startsWith('/login')
  const aBlocked = aRedirected || hasLoginFormA
  console.log()
  console.log(aBlocked
    ? '>>> 场景 A 结论：【已正确拦截】未登录被挡在登录页，不存在认证绕过'
    : '>>> 场景 A 结论：!!! 未登录竟然进入了 /home —— 这是**认证绕过漏洞** !!!')

  // ══════════════════════════════════════════════════════════
  console.log()
  console.log('='.repeat(72))
  console.log(' 场景 B：**有有效会话**访问 /home —— 检验会话恢复是否正常')
  console.log('='.repeat(72))

  // 通过 API 造一个真实会话
  const username = RUN.toLowerCase()
  const password = 'pw123456'
  await fetch(`${API}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname: '安全检查' }),
  })
  const login = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const sv = /AI_DIARY_SESSION=([^;]+)/.exec(login.headers.get('set-cookie') || '')?.[1]
  if (!sv) throw new Error('登录失败，拿不到会话 cookie')
  console.log('已通过 API 创建会话，会话 ID 前 8 位:', sv.slice(0, 8))

  await send('Network.setCookie', {
    name: 'AI_DIARY_SESSION',
    value: sv,
    domain: 'localhost',
    path: '/',
    httpOnly: true,
  })

  netResponses.length = 0
  await send('Page.navigate', { url: `${FRONT}/home` })
  await sleep(4000)

  const urlB = await evaluate('location.pathname + location.search')
  const hasLoginFormB = await evaluate(`!!document.querySelector('input[type="password"]')`)
  const bodyB = await evaluate('document.body.innerText.slice(0, 300)')
  const meB = netResponses.find((r) => r.url.includes('/api/auth/me'))

  console.log('最终 URL            :', urlB)
  console.log('页面上有密码输入框  :', hasLoginFormB)
  console.log('/api/auth/me 响应   :', meB ? meB.status : '(没发出该请求)')
  console.log('是否显示用户名      :', String(bodyB).includes('安全检查') || String(bodyB).includes(username))
  console.log('页面文本前 200 字   :')
  console.log('  ' + bodyB.replace(/\n+/g, ' / ').slice(0, 200))

  const bEntered = String(urlB).startsWith('/home') && !hasLoginFormB
  console.log()
  console.log(bEntered
    ? '>>> 场景 B 结论：【正常进入】有有效会话时会话恢复成功（设计预期）'
    : '>>> 场景 B 结论：有会话却被挡在登录页 —— 会话恢复有问题')

  // ══════════════════════════════════════════════════════════
  console.log()
  console.log('='.repeat(72))
  console.log(' 总结')
  console.log('='.repeat(72))
  console.log(`场景 A（无 Cookie）: ${aBlocked ? '拦截 ✅' : '绕过 ❌'}`)
  console.log(`场景 B（有会话）  : ${bEntered ? '进入 ✅' : '被挡 ❌'}`)
  if (aBlocked && bEntered) {
    console.log()
    console.log('两种行为都正确 —— 说明用户看到的"能进 /home"是**会话恢复**，')
    console.log('不是认证绕过。若用户认为不该如此，那是"会话有效期"的产品决策，')
    console.log('不是安全漏洞。')
  }

  // 顺带验证场景 C：会话失效后是否会被踢出
  console.log()
  console.log('='.repeat(72))
  console.log(' 场景 C：伪造/失效 Cookie 访问 /home')
  console.log('='.repeat(72))
  await send('Network.setCookie', {
    name: 'AI_DIARY_SESSION',
    value: 'DEADBEEFDEADBEEFDEADBEEFDEADBEEF',
    domain: 'localhost',
    path: '/',
    httpOnly: true,
  })
  netResponses.length = 0
  await send('Page.navigate', { url: `${FRONT}/home` })
  await sleep(4000)

  const urlC = await evaluate('location.pathname + location.search')
  const hasLoginFormC = await evaluate(`!!document.querySelector('input[type="password"]')`)
  const meC = netResponses.find((r) => r.url.includes('/api/auth/me'))
  console.log('最终 URL            :', urlC)
  console.log('/api/auth/me 响应   :', meC ? meC.status : '(没发出该请求)')
  console.log('页面上有密码输入框  :', hasLoginFormC)
  const cBlocked = String(urlC).startsWith('/login') || hasLoginFormC
  console.log(cBlocked
    ? '>>> 场景 C 结论：【已踢出】伪造会话被拒绝，跳回登录页'
    : '>>> 场景 C 结论：!!! 伪造会话竟然能进入 —— 严重漏洞 !!!')

  ws.close()
  edge.kill()
  process.exit(0)
}

main().catch((e) => {
  console.error('调查失败:', e)
  process.exit(2)
})
