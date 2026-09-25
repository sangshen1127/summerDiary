/**
 * 截取登录页，检查品牌图标（看板娘头像）的裁剪效果。
 *
 * 为什么单独写这个：logo 的 object-position 是"视觉参数"，
 * 断言只能证明"图片加载成功了"，证明不了"裁出来的是脸而不是身子"。
 * 这类参数必须看图调整。
 */
import { spawn } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const FRONT = 'http://localhost:5173'
const CDP_PORT = 9226
const OUT = 'D:\\summerDiary\\_verify\\shots'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

mkdirSync(OUT, { recursive: true })

async function main() {
  const edge = spawn(
    EDGE,
    [
      '--headless=new',
      `--remote-debugging-port=${CDP_PORT}`,
      `--user-data-dir=${OUT}\\edge-logo`,
      '--no-first-run',
      '--disable-gpu',
      '--hide-scrollbars',
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

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.clearBrowserCookies')

  // ⚠️ 两个页面都要核 —— 它们是各自独立的 <style scoped>，
  //    没有共用文件，只改一个就会出现"两页不一致"。
  const pages = [
    { path: '/login', name: 'login', label: '登录页' },
    { path: '/register', name: 'register', label: '注册页' },
  ]

  for (const p of pages) {
    console.log()
    console.log('='.repeat(60))
    console.log(` ${p.label}  ${FRONT}${p.path}`)
    console.log('='.repeat(60))

    await send('Page.navigate', { url: `${FRONT}${p.path}` })
    await sleep(5000)

    const info = await evaluate(`(() => {
      const el = document.querySelector('.auth-page__logo')
      if (!el) return { exists: false }
      const cs = getComputedStyle(el)
      return {
        exists: true,
        tag: el.tagName,
        naturalWidth: el.naturalWidth,
        renderedW: Math.round(el.getBoundingClientRect().width),
        renderedH: Math.round(el.getBoundingClientRect().height),
        objectFit: cs.objectFit,
        objectPosition: cs.objectPosition,
        borderRadius: cs.borderRadius,
      }
    })()`)

    console.log('  ' + JSON.stringify(info))
    console.log(info.exists && info.tag === 'IMG'
      ? `  >>> 是 <img> 且加载成功（naturalWidth=${info.naturalWidth}）`
      : '  >>> !!! 仍是旧元素或图片未加载')

    const full = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT}\\${p.name}-full.png`, Buffer.from(full.data, 'base64'))
    console.log(`  已截图: ${p.name}-full.png`)

    const clip = await evaluate(`(() => {
      const el = document.querySelector('.auth-page__brand')
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: Math.max(0, r.x - 12), y: Math.max(0, r.y - 12), width: r.width + 24, height: r.height + 24 }
    })()`)
    if (clip) {
      const zoom = await send('Page.captureScreenshot', {
        format: 'png',
        clip: { ...clip, scale: 3 },
      })
      writeFileSync(`${OUT}\\${p.name}-brand-zoom.png`, Buffer.from(zoom.data, 'base64'))
      console.log(`  已截图: ${p.name}-brand-zoom.png（品牌区 3 倍放大）`)
    }
  }

  ws.close()
  edge.kill()
  process.exit(0)
}

main().catch((e) => {
  console.error('失败:', e)
  process.exit(2)
})
