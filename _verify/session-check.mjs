/**
 * 追加验证：模拟用户的真实操作序列，看"刷新页面"能否恢复登录态。
 *
 * 目的是区分两种可能：
 *   可能一：用户浏览器里**一直有有效会话**（没真正退出过）
 *           → 访问 /home 进入是【会话恢复】，正常
 *   可能二：前端把登录态缓存在了本地（localStorage / sessionStorage）
 *           → 那才是漏洞（不查后端就放行）
 *
 * 验证方式：
 *   D1. 登录后刷新页面 → 是否仍登录（应该"是"，因为 Cookie 还在）
 *   D2. 登录后清掉 Cookie，再刷新页面 → 是否被踢出（必须"是"）
 *   D3. 检查有没有把登录态写进 localStorage/sessionStorage（有就是漏洞）
 */
import { spawn } from 'node:child_process'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const FRONT = 'http://localhost:5173'
const API = 'http://localhost:8080/api'
const CDP_PORT = 9225
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const RUN = 'ZZSS' + (Date.now() % 100000)

async function main() {
  const edge = spawn(
    EDGE,
    [
      '--headless=new',
      `--remote-debugging-port=${CDP_PORT}`,
      `--user-data-dir=D:\\summerDiary\\_verify\\shots\\edge-ss`,
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
  const ws = new WebSocket(wsUrl)
  await new Promise((res) => ws.addEventListener('open', res))

  let id = 0
  const pending = new Map()
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data)
    if (m.id !== undefined) {
      const p = pending.get(m.id)
      if (p) {
        pending.delete(m.id)
        m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
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
  const nav = async (url, wait = 4000) => {
    await send('Page.navigate', { url })
    await sleep(wait)
  }

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Network.clearBrowserCookies')

  // 建一个真实会话
  const username = RUN.toLowerCase()
  const password = 'pw123456'
  await fetch(`${API}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname: 'SS检查' }),
  })
  const login = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const sv = /AI_DIARY_SESSION=([^;]+)/.exec(login.headers.get('set-cookie') || '')?.[1]
  if (!sv) throw new Error('登录失败')

  await send('Network.setCookie', {
    name: 'AI_DIARY_SESSION',
    value: sv,
    domain: 'localhost',
    path: '/',
    httpOnly: true,
  })

  console.log('='.repeat(72))
  console.log(' D1：有会话 → 访问 /home → 再刷新页面')
  console.log('='.repeat(72))
  await nav(`${FRONT}/home`)
  const d1a = await evaluate('location.pathname')
  await send('Page.reload')
  await sleep(4000)
  const d1b = await evaluate('location.pathname')
  const d1User = await evaluate(`document.body.innerText.includes('SS检查')`)
  console.log('首次进入 URL :', d1a)
  console.log('刷新后 URL   :', d1b)
  console.log('刷新后仍显示用户名 :', d1User)
  console.log(d1b === '/home' && d1User
    ? '>>> 刷新后仍登录（正确 —— Cookie 还在，会话恢复）'
    : '>>> 刷新后被踢出（异常，会话恢复没生效）')

  console.log()
  console.log('='.repeat(72))
  console.log(' D3：检查有没有把登录态缓存在前端存储（有 = 漏洞）')
  console.log('='.repeat(72))
  const ls = await evaluate('JSON.stringify(Object.keys(localStorage))')
  const ss = await evaluate('JSON.stringify(Object.keys(sessionStorage))')
  console.log('localStorage 键 :', ls)
  console.log('sessionStorage 键:', ss)
  const lsVals = await evaluate(
    `JSON.stringify(Object.entries(localStorage).map(([k,v]) => k + '=' + String(v).slice(0,80)))`,
  )
  console.log('localStorage 内容:', lsVals)
  const leaksUser = String(lsVals).includes(username) || String(lsVals).includes('currentUser')
  console.log(leaksUser
    ? '>>> !!! 前端存储里出现了用户信息 —— 需要检查是否被用于放行 !!!'
    : '>>> 前端存储里没有登录态（正确 —— 登录态只存在于 HttpOnly Cookie + 内存 Pinia）')

  console.log()
  console.log('='.repeat(72))
  console.log(' D2：清掉 Cookie 后刷新 —— 必须被踢出')
  console.log('='.repeat(72))
  await send('Network.clearBrowserCookies')
  await send('Page.reload')
  await sleep(4500)
  const d2Url = await evaluate('location.pathname + location.search')
  const d2Pwd = await evaluate(`!!document.querySelector('input[type="password"]')`)
  console.log('清 Cookie 后刷新 URL :', d2Url)
  console.log('页面上有密码输入框   :', d2Pwd)
  const d2Blocked = String(d2Url).startsWith('/login') || d2Pwd
  console.log(d2Blocked
    ? '>>> 已被踢回登录页（正确 —— 每次进受保护页面都会问后端）'
    : '>>> !!! 清掉 Cookie 仍能停留 —— 前端在凭缓存放行，是漏洞 !!!')

  console.log()
  console.log('='.repeat(72))
  console.log(' 总结')
  console.log('='.repeat(72))
  console.log(`D1 刷新保持登录（会话恢复）: ${d1b === '/home' && d1User ? '正常' : '异常'}`)
  console.log(`D2 清 Cookie 后被踢出      : ${d2Blocked ? '正常' : '漏洞'}`)
  console.log(`D3 前端未缓存登录态        : ${leaksUser ? '有问题' : '正常'}`)

  ws.close()
  edge.kill()
  process.exit(0)
}

main().catch((e) => {
  console.error('验证失败:', e)
  process.exit(2)
})
