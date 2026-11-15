---
title: "IP 代理与出口 IP"
description: "让 Browser4 托管的浏览器通过一条固定 HTTP/SOCKS 代理或一个可轮换代理池出网，以及它与 CLI 下载代理的区别和出口 IP 的验证方法。"
tier: reference
---

# IP 代理与出口 IP

[English](ip-proxy.md)

Browser4 里有两个互不相干的"代理"配置。把它们搞混，是"我配了代理却不生效"最常见的原因：

| 想代理的对象 | 用什么开关 | 覆盖范围 |
|---|---|---|
| **页面流量** —— 浏览器自己发出的请求，即网站看到的出口 IP | `browser.launch.chrome.args=--proxy-server=…`（一条固定代理）或 `proxy.rotation.url`（代理池） | Browser4 启动的那个 Chrome |
| 安装/更新运行时、`webminer` 下载 | `--proxy <url>`、`config set proxy`、`BROWSER4_CLI_PROXY`、`https_proxy` | CLI 自己的 HTTP 客户端 |
| 你自己已经在跑的浏览器 | 那个浏览器自身的代理设置 | Browser4 侧不改任何东西 |

`browser4-cli --proxy` 只属于**第二行**。它的帮助原文就是
"HTTP proxy for downloads (runtime bundle, webminer)"，唯一消费者是
`cli/browser4-cli/src/daemon.rs` 里的 `resolve_download_proxy()` —— 它不会到达浏览器，
更不会影响任何一个页面请求。

一句话决策：**页面流量 → 第 1 节或第 2 节；下载 → 第 3 节。**

## 1. 一条固定代理

后端**只在启动时**读一次配置。用户可编辑的文件是：

```
~/.browser4/config/conf-enabled/application-private.properties
# Windows: C:\Users\<你>\.browser4\config\conf-enabled\application-private.properties
```

`browser4-cli doctor --fix` 会从内置模板创建并启用这个文件，`browser4-cli doctor`
会打印实际生效的路径。

加一行：

```properties
browser.launch.chrome.args=--proxy-server=http://127.0.0.1:7890
```

Chrome 接受带 scheme 的写法（`http://`、`https://`、`socks4://`、`socks5://`），
也接受裸 `host:port`（按 HTTP 处理）。重启后验证：

```shell
browser4-cli stop                                # 配置只在启动时读取
browser4-cli open --fresh https://httpbin.org/ip
browser4-cli eval "document.body.innerText"      # {"origin": "<代理的 IP>"}
```

几条能解释大部分"为什么不生效"的规则：

- 这个值是原样交给 Chrome 的。参数以空白分隔，含空白的参数用双引号包住；仓库自带的
  `browser4-core/browser4-resources/src/main/resources/config/application.properties`
  里就有 `--proxy-server` 的示例注释。
- 配置文件里的参数**优先级最低**：只有程序自己没有"有效设置"同名开关时才会生效
  （`ChromeOptions.toList()`）。代理池（第 2 节）是**按 context 用程序设置**
  `--proxy-server` 的，所以它一定赢过这一行 —— 别同时开。
- 同样的值也可以从环境变量来（同样必须在后端启动前）：`BROWSER_LAUNCH_CHROME_ARGS=--proxy-server=http://127.0.0.1:7890`
  （宽松绑定会解析成 `browser.launch.chrome.args`）。
- 已经存在的 session 会继续用它已经启动的浏览器。用 `open --fresh`（或先 `close`），
  否则新配置只对之后启动的浏览器生效。

### 认证

Chrome 的 `--proxy-server` 塞不进 `user:pass@host:port`：需要认证时，有头模式弹一个认证框，
无头模式直接失败（407）。三条出路：

1. **服务商按出口 IP 白名单放行** —— 商业代理最常见的做法，Browser4 侧不用配任何东西。
2. **走代理池**（第 2 节）。代理条目里的 `username`/`password` 会在创建浏览器 context 时
   通过 CDP `Fetch.authRequired` 自动应答。
3. **Attach** —— 先在浏览器本身（或扩展）里配好代理，再 `browser4-cli attach --cdp <endpoint>`（第 4 节）。

## 2. 可轮换的代理池

设置轮换地址 —— 每次访问它都应返回一个或多个新的代理：

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
# Windows PowerShell: $env:PROXY_ROTATION_URL = "https://..."
```

```properties
# 或者写在同一个 application-private.properties 里：
proxy.rotation.url=https://your-proxy-provider.com/rotation-endpoint
```

值必须是标准的 `http(s)` URL：`ProxyPoolManager.isProxyEnabled()` 会把其它任何形式
当作"代理关闭"，于是代理池根本不会启动。

### 为什么轮换需要 `SEQUENTIAL` / `TEMPORARY`

代理是**按浏览器 context 绑定**的，不是按请求：`ProxyContext.create()` 从池里取一条，
存进以 context 目录为键的 `activeProxyEntries`。一个 context 在它整个生命周期里只有
一个出口 IP。

`DEFAULT` 用的是唯一的、长期存在的共享 context，所以它实际上是**粘性**代理。
IP 轮换之所以发生，是因为不断有新的 context 被创建 —— 这正是 `SEQUENTIAL`
（一个 profile 池）和 `TEMPORARY`（每个 context 都新建 profile）在做的事：

```shell
# 在同一个 session 内轮换
browser4-cli open --fresh --profile-mode sequential https://example.com

# 批量抓取：context 是吞吐的旋钮，也是 IP 的旋钮
browser4-cli swarm create --profile-mode TEMPORARY --max-browser-contexts 3
browser4-cli swarm query --sql @q.sql --seed-file urls.txt
browser4-cli swarm close
```

服务端默认值是 `browser.profile.mode`（环境变量 `BROWSER_PROFILE_MODE`）；CLI 的
`--profile-mode` capability 对它创建的那个 session 优先。命名 session（`-s <name>`）按设计
会钉住专用 profile 目录，因此固定一条代理 —— 需要**稳定 IP** 时用它，需要轮换时别用。

### 轮换地址应该返回什么

响应会被解析成代理条目（`host`、`port`，可选的 username/password/type/TTL）：

| 解析器 | 接受的格式 | 何时被使用 |
|---|---|---|
| `ProxyHubParser` | `{"status":"success","data":{"proxies":[{"host":"…","port":1080,"username":"…","password":"…","type":"SOCKS5","declaredTTL":"…"}]}}` | **没有**配置轮换 URL 时的默认解析器 |
| `UniversalProxyParser` | 任意文本/JSON —— 由 LLM 抽取条目 | 今天解析已配置的 `proxy.rotation.url` 响应的就是它 |
| 为那个 URL 注册的厂商解析器 | 由它自己实现 | `ProxyParserFactory.register(<轮换URL>, parser)`，优先级最高 |

一个需要提前规划的结论：**配置了 `proxy.rotation.url` 时，解析需要可用的 LLM**
（`DEEPSEEK_API_KEY` / `OPENROUTER_API_KEY` / …，见 [config.md](config.md)）。
没有 LLM 时日志是 `LLM not available`，代理池永远是空的，任务最终抛 `NoProxyException`。
让 LLM 的活最省事的办法，是把轮换地址指向
[ProxyHub](https://github.com/platonai/ProxyHub)
（`docker run galaxyeye88/proxy-hub`；`GET http://localhost:8192/api/proxies`），
它已经把各厂商的格式归一化了。

### 调优

| 键 | 默认值 | 含义 |
|---|---|---|
| `proxy.pool.size` | `100` | 代理池容量 |
| `proxy.pool.polling.interval` | `1s` | 取代理时的最长等待 |
| `proxy.idle.timeout` | `10m` | 池管理器进入空闲的时间 |
| `proxy.max.fetch.success` | 不限制 | 一个代理服务多少页后从 context 退休 |

代理池是懒加载的：队列空了就再去抓一次轮换 URL（最多 10 次，间隔为上面的 polling interval）。

### 失败形态

- `NoProxyException: No proxy found in pool` —— 轮换 URL 没返回内容、没配 LLM、或服务商挂了。
- `ProxyVendorUntrustedException` —— 一天内累计约 200 次取不到代理；计数器在新的一天开始时归零。
- "没有发生轮换" —— session 跑在 `DEFAULT` 模式（唯一的长命共享 context），或是钉住了专用
  profile 的命名 session。改用 `--profile-mode sequential|temporary` 或 `swarm`。

## 3. 只给下载用的代理

CLI 自己的 HTTP 客户端（运行时 bundle、`webminer`、安装/升级）按下面的顺序解析代理：

1. `BROWSER4_CLI_PROXY` —— 由 `--proxy <url>` 或 `config set proxy <url>` 设置
2. `https_proxy` / `HTTPS_PROXY` / `http_proxy` / `HTTP_PROXY` / `all_proxy` / `ALL_PROXY`
3. Windows 上：系统代理（先 WinHTTP，再 IE/Edge 注册表）

`--proxy` 是全局标志，必须放在**命令之前**：

```shell
browser4-cli --proxy http://127.0.0.1:7890 install
browser4-cli config set proxy http://127.0.0.1:7890   # 持久默认值
```

后端侧另一类下载有自己的键：`image.download.proxy`、`pptx.download.proxy`、
`captcha.solve.proxy`（见 [config.md](config.md)）。它们同样不会给浏览器换 IP，
只控制 JVM 自己的 HTTP 客户端。

## 4. Attach 进来的浏览器

`attach --cdp` 和 `attach --extension` 绑定的是已经在运行的浏览器，因此任何启动参数都
施加不上：出口 IP 就是那个浏览器自己配置的样子。两种可行做法：

- 在 Chrome 里配好代理（或装一个负责认证的扩展），保留调试端口，然后
  `browser4-cli attach --cdp 9222`。
- 用已经走目标 IP 出网的云端/指纹浏览器，再 attach 到它。

## 5. 验证出口 IP

```shell
browser4-cli open --fresh https://httpbin.org/ip
browser4-cli eval "document.body.innerText"        # origin 应该就是代理 IP
```

`http://api.ipify.org`、`https://ifconfig.me/ip`、`https://ipinfo.io/ip` 同理。

想确认 Chrome 到底有没有带着这个开关启动：

```shell
browser4-cli open --fresh chrome://version
browser4-cli htmlsnapshot grep -i "proxy-server"
```

（`chrome://version` 的快照只是顺手一看，权威判断仍以 IP 回显页为准。）

## 6. 排查表

| 现象 | 原因 | 处理 |
|---|---|---|
| 页面仍然显示自己的 IP | 后端没重启；或 session 复用了旧浏览器 | `browser4-cli stop`，再 `open --fresh` |
| 同上，但配了代理池 | `--proxy-server` 被按 context 的程序设置覆盖（这是预期），可代理池是空的 | 检查轮换 URL 和 LLM 日志 |
| `NoProxyException` | 轮换 URL 不是 `http(s)`；服务商返回空；没配 LLM | 修 URL/key，重启 |
| `407` 或 `ERR_TUNNEL_CONNECTION_FAILED` | 代理需要认证，或 scheme 写错 | 用代理池带凭据，或 attach |
| 这台机器生效，另一台不生效 | 设置来自后端没有继承到的 shell 环境变量 | 写进 `application-private.properties` |
| `--proxy` "没起作用" | 它是下载代理 | 页面流量用第 1 节或第 2 节 |

## 7. 代码位置

| 路径 | 作用 |
|---|---|
| `browser4-core/browser4-resources/src/main/resources/config/application.properties` | `browser.launch.chrome.args` 的说明和 `--proxy-server` 示例 |
| `browser4-boot/src/main/kotlin/ai/platon/pulsar/boot/autoconfigure/Browser4AutoConfiguration.kt` | `proxyPool`、`proxyPoolManagerFactory`、`proxyPoolManager` 三个 bean |
| `browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/context/BrowserPrivacyContext.kt` | `initProxyContextIfNecessary()` —— 每个隐私 context 的"代理是否启用"关口 |
| `browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/context/ProxyContext.kt` | 按 context 分配代理、缺代理计数 |
| `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/LlmConfigTemplate.kt` | `conf-enabled/application-private.properties` 的路径与模板安装 |
| `cli/browser4-cli/src/daemon.rs` | `resolve_download_proxy()` —— 下载代理的解析链 |
| `cli/browser4-cli/src/help.rs` | `--proxy` 的帮助文本（"downloads"） |
| `browser4-base`：`ProxyPoolManager`、`ProxyPool`、`ProxyVendorLoader`、`UniversalProxyParser`、`ProxyHubParser`、`PulsarWebDriver` | 代理池、解析器，以及通过 CDP `Fetch.authRequired` 完成的代理认证 |

## 相关文档

- [config.md](config.md) —— 完整配置参考
- [browser-modes.md](../skills/browser4-cli/references/browser-modes.md) —— profile 模式与显示模式
- [ProxyHub](https://github.com/platonai/ProxyHub) —— 把各厂商格式归一化的轮换端点
