# AI 日记系统

> 带长期记忆与语义检索的私人 AI 日记本。
> 写日记 → 异步 AI 分析 → 三层记忆 → 对话式检索历史。

**当前进度：Phase 0（项目骨架）已完成。** 详见文末「开发进度」。

---

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java **17** · Spring Boot **3.3.5** · MyBatis 3.0.5 · MySQL 8 · Flyway |
| AI | LangChain4j 0.35.0 + DeepSeek（Chat）· Chroma（向量库） |
| 前端 | Vue 3 · TypeScript（strict）· Vite 6 · Pinia · Vue Router · Element Plus |
| 部署 | Docker Compose |

完整技术规范见 [`md文档/AI日记系统开发文档.md`](md文档/AI日记系统开发文档.md)。

---

## 快速开始

### 前置要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| **JDK** | **17** | ⚠️ 必须 17。用 21/25 会因 Lombok 版本或 `maven.compiler.release` 不匹配而报错 |
| Maven | 3.9+ | 本项目**不用** `mvnw` wrapper，直接用系统 Maven |
| Node.js | **≥ 22.12** | Vite 6 / Vitest 3 的要求。Node 24 LTS 为佳 |
| Docker Desktop | 最新 | 跑 MySQL 和 Chroma；**必须先启动 Docker Desktop** |

### 1. 配置环境变量（1 条命令）

```powershell
Copy-Item .env.example .env
```

**然后手动编辑 `.env`**（这一步不是命令，是打开文件改两个值）：

```dotenv
DB_PASSWORD=your-password
DIARY_ENCRYPTION_KEY=<Base64 编码的 32 字节>
```

生成加密密钥（**独立的一条命令，属于「准备密钥」而非「配置」**）：

```powershell
# PowerShell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
```

> 💡 你也可以让我代劳 —— 我可以直接生成 `.env` 并写入一个密码学安全的随机密钥，
> 你就不用手动编辑了。当前工作区已经这样处理过。

> ⚠️ **密钥丢失 = 所有日记正文永久无法解密**，请务必备份。
> `.env` 已在 `.gitignore` 中，不会被提交。

### 2. 启动依赖服务（1 条命令，1 条校验）

> 🔴 **必须先复制 `.env` 再执行这一步。**
> MySQL 只在**首次初始化数据卷时**创建账号，之后改 `.env` 不会更新已有用户。
> 顺序错了会得到 `Access denied for user 'ai_diary'@'localhost'`，且只能靠
> `docker compose rm -sf mysql && docker volume rm ai-diary-mysql-data` 重来。

```powershell
docker compose up -d mysql chroma     # ← 要执行的命令
```

```powershell
docker compose ps                     # ← 校验，不是必须执行的步骤
```

**预期**：`docker compose ps` 里两个服务都显示 `running` / `healthy`。

> ⚠️ 执行前必须先启动 **Docker Desktop**，否则报
> `failed to connect to the docker API`。

#### 为什么 MySQL 在 3307 而不是 3306

本机通常已经装了 MySQL 服务（Windows 服务 `MySQL80`）长期占用 3306，
若这里也映射 3306，`docker compose up` 会报端口被占用。

项目把容器映射到 **3307**，好处是：

- 项目自包含，不依赖也不影响本机已装的 MySQL（课程练习数据不受影响）
- 谁的机器上都能一把起来，不用先关服务

容器内始终是 3306，只有宿主机映射端口是 3307 —— 所以：

| 谁连 | 地址 |
| --- | --- |
| 宿主机上的后端（IDE / `mvn spring-boot:run`） | `localhost:3307` |
| `backend` 容器里的后端（`--profile full`） | `mysql:3306` |

想改回 3306：先在「服务」里停掉 `MySQL80`，再把 `.env` 的 `DB_PORT` 改成 3306。

### 3. 启动后端

```powershell
# 确认用的是 JDK 17（关键！）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
mvn -v                     # 应显示 Java version: 17.x

cd backend
mvn spring-boot:run
```

后端启动后：

| 地址 | 说明 |
| --- | --- |
| http://localhost:8080/api/health | 自检接口（统一响应体 + UTC 时间） |
| http://localhost:8080/actuator/health | 运维健康检查（含数据库状态） |

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

打开 **http://localhost:5173** —— 首页会显示「后端已连通」。
这一步同时验证了 Vite 代理链路（浏览器 5173 → 后端 8080）。

---

## 常用命令

### 后端

```powershell
cd backend
mvn spring-boot:run                    # 启动
mvn test                               # 跑测试
mvn clean package                      # 打包
mvn validate                           # 依赖收敛检查（Enforcer）

# 依赖冲突排查（本项目最常用，见开发文档 §6.1）
mvn dependency:tree "-Dincludes=com.squareup.okhttp3,com.fasterxml.jackson.core,org.jetbrains.kotlin"
```

### 前端

```powershell
cd frontend
npm run dev            # 开发服务器（5173）
npm run build          # 生产构建（含 vue-tsc 严格检查）
npm run type-check     # 只做 TypeScript 检查
npm run test:unit      # 单元测试
```

### 数据库

```powershell
# 进 MySQL（密码见 .env 的 DB_PASSWORD）
docker exec -it ai-diary-mysql mysql -uroot -p ai_diary

# 看迁移历史
docker exec -it ai-diary-mysql mysql -uroot -p ai_diary -e "select * from flyway_schema_history"
```

---

## 需要知道的三个仓库配置

### 1. Maven 依赖装在项目内（`.mvn/maven.config`）

本机 Maven 全局 `settings.xml` 把本地仓库指到了工作区外的位置，且那份仓库不含本项目需要的依赖。
为了让「谁 clone 下来行为都一样」，本项目把依赖仓库固定在 `.m2repo/`：

```properties
-Dmaven.repo.local=${maven.multiModuleProjectDirectory}/.m2repo
```

**代价**：首次构建需联网下载约 200–300 MB，之后都是增量。
`.m2repo/` 已在 `.gitignore` 中。

### 2. 前端走 Vite 代理，不用 CORS

`vite.config.ts` 把 `/api` 代理到 `http://localhost:8080`。

**为什么**：认证方案是 HttpOnly + SameSite Cookie（开发文档 §5.1），
跨域请求下浏览器默认不带这种 Cookie。走代理后浏览器认为是同源，Cookie 正常工作。
详见 `vite.config.ts` 顶部注释。

因此前端的 `VITE_API_BASE_URL` 是相对路径 `/api`，**不要改成 `http://localhost:8080/api`**。

### 3. ⚠️ `.env` 靠一个自定义类加载（这是最容易踩的坑）

**`docker compose` 会自动读 `.env`，但 Spring Boot 原生不读。**
这两件事不一致，会让很多人以为后端也读了 `.env`，实际没有。

本项目用 `DotenvEnvironmentPostProcessor` 补上这个能力，注册在：

```
backend/src/main/resources/META-INF/spring.factories
```

**这个文件绝不能删** —— 删掉后：

```text
编译通过 ✅   启动不报错 ✅   `.env` 静默失效 ❌
→ 后端退回 application.yml 的默认密码
→ Access denied for user 'ai_diary'@'localhost'
```

报错指向数据库，真实原因却在配置文件注册上，非常容易被误导。

**判断 `.env` 有没有生效**：启动日志里搜 `配置自检`（dev profile 会打印）：

```text
========== 配置自检 ==========
.env 是否加载成功  : 是 ✅
datasource.url     : jdbc:mysql://localhost:3307/ai_diary?...
datasource.password: (长度 15，内容不打印)
DB_PORT 来源       : dotenvFile        ← 关键：必须是 dotenvFile
==============================
```

若显示 `DB_PORT 来源 : (未找到)`，说明 `.env` 没被加载。

**配置优先级**（从高到低）：

```text
操作系统环境变量  >  JVM -D 参数  >  application.yml  >  .env
```

所以 CI / 生产直接用真实环境变量注入，能覆盖 `.env`（容器里通常没有 `.env`）。


---

## 常见问题排查

Phase 0 开发过程中实际踩到并已修复的问题，记在这里避免重犯。

| 症状 | 真实原因 | 解决 |
| --- | --- | --- |
| `failed to connect to the docker API` | Docker Desktop 没启动 | 启动 Docker Desktop |
| `ports are not available: ... 0.0.0.0:3306` | 本机 MySQL80 服务占用 3306 | 项目已改用 3307，无需处理；或停掉 MySQL80 |
| `Access denied for user 'ai_diary'@'localhost'` | ① `.env` 不存在就起了容器（密码被卷固化）<br>② 或 `.env` 加载器失效 | ① `docker compose rm -sf mysql && docker volume rm ai-diary-mysql-data && docker compose up -d mysql`<br>② 看启动日志的「配置自检」 |
| 启动直接失败，提示「数据库密码仍是占位值 change-me」 | **这是刻意设计的快速失败，不是 bug** —— 说明 `.env` 没创建或没填 | 按错误信息里的步骤做：`Copy-Item .env.example .env` 并填写密码与密钥 |
| 该出 DEBUG 日志却看不到 | 改过包名但 `logging.level` 没同步 | 检查 `application*.yml` 里的 `logging.level.com.sangshen.aidiary` |
| IDEA 点绿色三角运行失败，提示找不到主类 | 包名改动后 `.idea/workspace.xml` 里的启动配置没同步 | 删除该 Run Configuration 重新生成，或直接改 `SPRING_BOOT_MAIN_CLASS` |
| `Unsupported character encoding 'utf8mb4'` | JDBC URL 里 `characterEncoding` 写了 `utf8mb4`（不是合法 Java charset 名） | 已改为 `characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci` |
| Chroma 一直 `unhealthy`，但服务其实正常 | 镜像里没有 `curl`/`wget`/`python`，只能借 bash 的 `/dev/tcp` 探测 | 已修复。注意本检查只证明端口在监听 |
| `Port 8080 was already in use` | 上次的后端进程没退干净 | `.\scripts\check-port.ps1 -Port 8080 -Kill` |
| `Port 5173 is already in use` | 上次的 Vite 进程没退干净 | **现在会自动切到 5174，不报错了** —— 看终端打印的实际地址即可。要清理旧进程：`.\scripts\check-port.ps1 -Port 5173 -Kill` |
| 明明有服务在跑，`Get-NetTCPConnection` 却说端口空闲 | **Vite 只监听 IPv6**（`[::1]:5173`），该命令会漏报 IPv6-only 监听 | 用 `netstat -ano \| Select-String ":5173.*LISTENING"`，或直接用 `.\scripts\check-port.ps1`（内部用 netstat） |
| Java 程序连 `localhost:5173` 超时或连不上 | 两个原因叠加：<br>① Vite **只监听 IPv6**，而 Java 把 `localhost` 解析成 IPv4 且**不回退**<br>② Java HttpClient **默认用 HTTP/2**，会先发 `Upgrade: h2c` 升级请求，Vite 的 Node HTTP 服务器对它不响应，导致请求挂住 | ① 用 `http://[::1]:5173` 而不是 `localhost`<br>② 客户端加 `.version(HttpClient.Version.HTTP_1_1)` |
| `invalid target release: 21` / Lombok 报错 | `JAVA_HOME` 指向了 JDK 25 | `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` |
| 前端 `spawn EPERM` | 沙箱/受限环境禁止 esbuild 起子进程 | 在普通终端里运行 `npm run dev` |
| `Cannot find module 'node:url'` 之类 | Node 版本过低 | 用 Node ≥ 22.12（推荐 24 LTS） |
| `.ps1` 脚本报 `Unexpected token` / 中文变乱码 | **PowerShell 5.1 按 ANSI(GBK) 读取无 BOM 的 `.ps1`** | 项目脚本一律**只用 ASCII 内容**（中文说明写在 `md文档/` 里） |

### 端口排查工具

```powershell
.\scripts\check-port.ps1              # 查看前端/后端/MySQL/Chroma 常用端口
.\scripts\check-port.ps1 -Port 5173   # 查看指定端口
.\scripts\check-port.ps1 -Port 5173 -Kill   # 查看并终止占用进程
```


### 快速健康检查

```powershell
# 容器
docker compose ps

# 后端 + 数据库
curl.exe http://localhost:8080/actuator/health

# 完整链路（需前端在跑）
curl.exe http://localhost:5173/api/health
```

---

## 项目结构

```text
summerDiary/
├── backend/                     Spring Boot 后端
│   ├── src/main/java/com/yourname/aidiary/
│   │   ├── common/              Result、ErrorCode（统一响应与错误码）
│   │   ├── config/              Security、MyBatis、Jackson 配置
│   │   ├── controller/          HTTP 层，只做参数校验和权限上下文
│   │   ├── exception/           BusinessException、全局异常处理
│   │   ├── service/             业务规则（Phase 1+）
│   │   ├── mapper/              MyBatis 接口（Phase 1+）
│   │   ├── entity/              数据库实体（Phase 1+）
│   │   └── dto/                 request / response / ai（Phase 1+）
│   └── src/main/resources/
│       ├── application.yml          公共配置
│       ├── application-dev.yml      开发环境
│       ├── application-prod.yml     生产环境
│       ├── application-ai.yml       AI 配置（由 AI 模块负责人主改）
│       ├── mapper/                  MyBatis XML（Phase 1+）
│       ├── prompts/                 Prompt 模板（Phase 4）
│       └── db/migration/            Flyway 迁移脚本
├── frontend/                    Vue 3 前端
│   └── src/
│       ├── api/                     Axios 封装 + 接口（唯一的 HTTP 入口）
│       ├── components/              可复用组件
│       ├── layouts/                 AppShell（导航外壳）
│       ├── pages/                   路由页面
│       ├── stores/                  Pinia：user / app
│       ├── router/                  路由表与守卫
│       ├── types/                   后端契约的 TS 镜像
│       └── utils/                   时间格式化等
├── md文档/                      项目文档（规划与规范）
├── docker-compose.yml           MySQL + Chroma
├── .env.example                 环境变量模板
└── .mvn/maven.config            Maven 项目级配置
```

---

## 重要约定（写代码前必读）

### 权限铁律

所有涉及用户数据的 SQL 必须显式带 `user_id`：

```xml
<!-- ✅ 正确 -->
WHERE id = #{id} AND user_id = #{userId}

<!-- ❌ 禁止：先查全表再在 Java 里过滤 -->
```

**Controller 绝不接受前端传入的 `userId` 作为权限依据**，一律从安全上下文取。

### 日志红线

以下内容**禁止**出现在任何日志、异常消息、监控中：
日记正文 · 密码 · Cookie · API Key · 完整 Prompt · 其他用户数据

### 前端纪律

- 所有时间显示必须走 `src/utils/formatTime.ts`，禁止页面里散落 `new Date()` 拼接
- Axios 拦截器必须检查业务 `code`，不能只看 HTTP 状态码
- 模型输出当纯文本渲染，**禁止 `v-html` 直出**
- 每个数据页面必须处理 4 种状态：loading / empty / error / 进行中

完整规范见 [`md文档/AI日记系统开发文档.md`](md文档/AI日记系统开发文档.md)。

---

## 开发进度

| Phase | 内容 | 状态 |
| --- | --- | --- |
| **0** | 项目骨架、统一响应、全局异常、健康检查、Docker、Vite 代理 | ✅ **已完成** |
| 1 | 注册 / 登录 / HttpOnly Cookie 会话 | ⬜ |
| 2 | 日记 CRUD、标签、筛选分页、正文 AES 加密 | ⬜ |
| 3 | 异步任务框架 + Mock AI（无 Key 也能跑通） | ⬜ |
| 4 | 真实 AI 分析、对话 | ⬜ |
| 5 | Embedding、Chroma、语义检索 | ⬜ |
| 6 | 三层记忆、Memory Center、画像 | ⬜ |
| 7 | 导出、删除补偿、账户清除 | ⬜ |
| 8 | 工程化、CI、部署、响应式验收 | ⬜ |

---

## 文档索引

| 文档 | 用途 |
| --- | --- |
| [`md文档/AI日记系统开发文档.md`](md文档/AI日记系统开发文档.md) | ⭐ **技术规范真源** —— 决策、契约、数据库、前端规范 |
| [`md文档/AI日记系统开发模式文档.md`](md文档/AI日记系统开发模式文档.md) | 开发节奏与验收方式 |
| [`md文档/我的职责与任务清单.md`](md文档/我的职责与任务清单.md) | 职责边界 |
| [`md文档/开发前置准备与避坑指南.md`](md文档/开发前置准备与避坑指南.md) | 20 个坑与已确认决策 |
| [`md文档/README.md`](md文档/README.md) | 文档目录索引 |

---

## License

待定（Phase 8 补充）。
