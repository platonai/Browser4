#!/usr/bin/env node
/**
 * weibo-ego-lite.mjs —— 连上 ego lite 里已经登录的微博页面，找到某个博主，抓最新 N 条微博并打印要点汇总。
 *
 * 用法：
 *   node examples/scripts/weibo-ego-lite.mjs                      # 默认：爱可可-爱生活，最新 20 条
 *   node examples/scripts/weibo-ego-lite.mjs --user 某博主 --limit 10 --out weibo.json
 *   node examples/scripts/weibo-ego-lite.mjs --dry-run            # 只打印将要执行的 browser4-cli 命令
 *   node examples/scripts/weibo-ego-lite.mjs --print-js           # 只打印注入页面的抓取 JS
 *
 * 前提：
 *   1. ego lite 以 CDP 方式在跑（默认 http://localhost:9222，通道名 ego-lite）；
 *   2. 这个浏览器里已经打开并登录了 weibo.com —— 脚本不自己起浏览器、不重新登录，
 *      它 attach 到 ego lite，复用那套登录态。
 *
 * 抓取走页面自己的 web 接口（/ajax/profile/info + /ajax/statuses/mymblog），
 * 一次拿整页 20 条、正文是完整的 text_raw，比在虚拟滚动的个人主页上刮 DOM 稳得多。
 */

import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const DEFAULTS = {
  user: '爱可可-爱生活',
  limit: 20,
  channel: 'ego-lite',
  session: 'weibo-ego-lite',
  tabMatch: 'weibo.com',
  digestChars: 90,
  out: null,
  dryRun: false,
  printJs: false,
  close: false,
};

function usage() {
  console.log(`weibo-ego-lite —— 用 ego lite 里已登录的微博页面收集某博主最新 N 条微博

用法: node examples/scripts/weibo-ego-lite.mjs [选项]

  --user <name>       要看的博主昵称（默认 ${DEFAULTS.user}）
  --limit <n>         取最新多少条（默认 ${DEFAULTS.limit}）
  --channel <name>    attach 的浏览器通道（默认 ${DEFAULTS.channel}）
  --session <name>    browser4-cli 会话名（默认 ${DEFAULTS.session}，不踩默认匿名会话）
  --tab-match <s>     在标签 URL 里找这个片段（默认 ${DEFAULTS.tabMatch}）
  --digest-chars <n>  摘要里每条正文截多少字（默认 ${DEFAULTS.digestChars}）
  --out <file>        把完整数据（全文/链接/互动数）写成 JSON
  --close             结束后关掉这个 CLI 会话（浏览器本身不动）
  --dry-run           只打印将执行的 browser4-cli 命令，不真跑
  --print-js          只打印注入页面的抓取脚本
  -h, --help          看这段帮助

例子:
  node examples/scripts/weibo-ego-lite.mjs
  node examples/scripts/weibo-ego-lite.mjs --user 微博CEO王高飞 --limit 5 --out .b4scratch/weibo.json`);
}

function parseArgs(argv) {
  const opts = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} 需要一个值`);
      return v;
    };
    switch (a) {
      case '--user': opts.user = next(); break;
      case '--limit': opts.limit = Number(next()); break;
      case '--channel': opts.channel = next(); break;
      case '--session': opts.session = next(); break;
      case '--tab-match': opts.tabMatch = next(); break;
      case '--digest-chars': opts.digestChars = Number(next()); break;
      case '--out': opts.out = next(); break;
      case '--close': opts.close = true; break;
      case '--dry-run': opts.dryRun = true; break;
      case '--print-js': opts.printJs = true; break;
      case '-h':
      case '--help': usage(); process.exit(0); break;
      default: throw new Error(`看不懂的参数: ${a}（--help 看用法）`);
    }
  }
  if (!Number.isFinite(opts.limit) || opts.limit <= 0) throw new Error('--limit 必须是正整数');
  if (!Number.isFinite(opts.digestChars) || opts.digestChars < 0) throw new Error('--digest-chars 必须是非负整数');
  return opts;
}

/** 博主主页地址：微博会把 /n/<昵称> 重定向到该用户的 uid 主页 */
function profileUrl(user) {
  return 'https://weibo.com/n/' + encodeURIComponent(user);
}

/**
 * 注入页面的抓取脚本（返回 JSON 字符串）：按昵称查 uid，再逐页取该 uid 的最新 LIMIT 条微博。
 * 注意：这段是「生成出来的代码」，正则里的反斜杠要双写。
 */
function pageScript(user, limit) {
  return `(async () => {
  const NAME = ${JSON.stringify(user)};
  const LIMIT = ${Number(limit)};
  const getJson = async (url) => {
    const res = await fetch(url, { credentials: 'include' });
    if (!res.ok) throw new Error(url + ' -> HTTP ' + res.status);
    return res.json();
  };

  const info = await getJson('https://weibo.com/ajax/profile/info?screen_name=' + encodeURIComponent(NAME));
  const target = info && info.data && info.data.user;
  if (!target) throw new Error('查不到这个昵称的 uid: ' + NAME);

  // 逐页翻，凑够 LIMIT 条为止（置顶帖/删帖会让单页少于 20 条）
  const seen = new Set();
  const list = [];
  for (let page = 1; page <= 5 && list.length < LIMIT; page++) {
    const feed = await getJson('https://weibo.com/ajax/statuses/mymblog?uid=' + target.idstr +
      '&page=' + page + '&feature=0');
    const batch = (feed.data && feed.data.list) || [];
    if (!batch.length) break;
    for (const p of batch) {
      if (list.length >= LIMIT) break;
      if (seen.has(p.mblogid)) continue;
      seen.add(p.mblogid);
      list.push(p);
    }
  }

  return JSON.stringify({
    user: { id: target.idstr, name: target.screen_name, followers: target.followers_count },
    page: location.href,
    fetchedAt: new Date().toISOString(),
    posts: list.map((p, i) => ({
      index: i + 1,
      time: p.created_at,
      source: String(p.source || '').replace(/<[^>]+>/g, '').trim(),
      text: String(p.text_raw || p.text || '').replace(/\\u200b/g, '').trim(),
      reposts: p.reposts_count,
      comments: p.comments_count,
      likes: p.attitudes_count,
      url: 'https://weibo.com/' + (p.user && p.user.idstr) + '/' + p.mblogid,
    })),
  });
})()`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Thu Nov 19 15:49:00 +0800 2026" -> "11-19 15:49"（不换算时区，微博本来就按 +0800 记） */
function formatTime(raw) {
  const m = /^[A-Za-z]{3} ([A-Za-z]{3}) (\d{1,2}) (\d{2}:\d{2}):\d{2} \+\d{4} (\d{4})$/.exec(String(raw || ''));
  if (!m) return String(raw || '');
  const mon = MONTHS.indexOf(m[1]) + 1;
  return `${String(mon).padStart(2, '0')}-${m[2].padStart(2, '0')} ${m[3]}`;
}

/** 从一堆 stdout 里挑出最后一个像 CLI 响应信封的 JSON 对象 */
function parseJsonLoose(text) {
  const s = String(text || '').trim();
  const candidates = [s];
  const first = s.indexOf('{');
  const last = s.lastIndexOf('}');
  if (first >= 0 && last > first) candidates.push(s.slice(first, last + 1));
  for (const line of s.split(/\r?\n/).reverse()) {
    const t = line.trim();
    if (t.startsWith('{')) candidates.push(t);
  }
  for (const c of candidates) {
    try {
      const parsed = JSON.parse(c);
      if (parsed && typeof parsed === 'object' && 'status' in parsed) return parsed;
    } catch { /* 试下一个 */ }
  }
  return null;
}

/** Windows 上 browser4-cli 是 .cmd shim，spawnSync 必须走 shell 才找得到；顺手把带空格的参数括起来 */
function spawnCli(cli, argv, stdin) {
  const isWin = process.platform === 'win32';
  const args = isWin ? argv.map((a) => (/[\s"&|<>^]/.test(a) ? `"${a.replace(/"/g, '""')}"` : a)) : argv;
  return spawnSync(cli, args, {
    input: stdin,
    encoding: 'utf8',
    shell: isWin,
    maxBuffer: 64 * 1024 * 1024,
  });
}

function runCli(cliArgs, { stdin = '', opts }) {
  const cli = process.env.BROWSER4_CLI || 'browser4-cli';
  const argv = ['-s', opts.session, ...cliArgs];
  const printable = `${cli} ${argv.map((a) => (/\s/.test(a) ? JSON.stringify(a) : a)).join(' ')}`;
  if (opts.dryRun) {
    console.log(`# ${printable}` + (stdin ? '  <<< 页面抓取脚本（--print-js 可看全文）' : ''));
    return { status: 'ok', output: {} };
  }
  const res = spawnCli(cli, argv, stdin);
  if (res.error) throw new Error(`起不来 browser4-cli：${res.error.message}`);
  const out = res.stdout || '';
  const err = res.stderr || '';
  const env = parseJsonLoose(out);
  if (res.status !== 0 || (env && env.status === 'error')) {
    const detail = (env && env.error && env.error.message) || err.trim() || out.trim() || `exit ${res.status}`;
    throw new Error(`${printable}\n  ↳ ${detail.split('\n')[0]}`);
  }
  return env || { status: 'ok', output: { raw: out } };
}

/** attach 到 ego lite；同名会话已存在是好事（说明已经连着），当作复用 */
function attach(opts) {
  try {
    const env = runCli(['attach', '--cdp', opts.channel, '--json'], { opts });
    return (env && env.output) || {};
  } catch (e) {
    if (/already exists|already attached/i.test(e.message)) return { reused: true };
    throw e;
  }
}

/** 在标签列表里挑一个微博标签：优先已经在目标博主主页上的那个 */
function pickWeiboTab(tabs, opts) {
  const hits = tabs.filter((t) => String(t.url || '').includes(opts.tabMatch));
  if (!hits.length) return null;
  const encUser = encodeURIComponent(opts.user);
  const onProfile = hits.find((t) => String(t.url).includes(encUser) || String(t.url).includes(opts.user));
  return onProfile || hits[0];
}

function digest(data, opts) {
  const posts = data.posts || [];
  const pad = (s, n) => String(s).padEnd(n);
  const sum = (k) => posts.reduce((n, p) => n + (Number(p[k]) || 0), 0);
  const lines = [];
  lines.push(`博主：${data.user.name}（uid ${data.user.id}，粉丝 ${data.user.followers ?? '-'}）`);
  lines.push(`页面：${data.page}`);
  lines.push(`条数：${posts.length}`);
  if (posts.length) lines.push(`区间：${formatTime(posts[posts.length - 1].time)} → ${formatTime(posts[0].time)}`);
  lines.push(`互动：转发 ${sum('reposts')} / 评论 ${sum('comments')} / 点赞 ${sum('likes')}`);
  lines.push('');
  for (const p of posts) {
    const body = String(p.text || '').replace(/\s+/g, ' ');
    const head = body.length > opts.digestChars ? body.slice(0, opts.digestChars) + '…' : body;
    lines.push(`${pad('#' + p.index, 4)} ${pad(formatTime(p.time), 12)} ${pad(p.source || '', 10)} 赞${p.likes ?? 0}/转${p.reposts ?? 0}/评${p.comments ?? 0}`);
    lines.push(`     ${head}`);
  }
  return lines.join('\n');
}

function main() {
  const opts = parseArgs(process.argv.slice(2));

  if (opts.printJs) {
    process.stdout.write(pageScript(opts.user, opts.limit) + '\n');
    return;
  }

  attach(opts);

  if (opts.dryRun) {
    const profile = profileUrl(opts.user);
    console.log('# ----- 真实执行序列（--dry-run 只打印，不执行）-----');
    console.log(`# 1) attach 到 ${opts.channel}（CDP 通道，复用已登录的浏览器）`);
    console.log(`# 2) tab list —— 找 URL 含 "${opts.tabMatch}" 的标签`);
    console.log(`# 3) tab select <该标签下标>`);
    console.log(`# 4) 标签不在 @${opts.user} 主页上时：goto ${profile}`);
    console.log('# 5) eval --stdin --await --json <<< 页面抓取脚本（--print-js 可看全文）');
    runCli(['tab', 'list', '--json'], { opts });
    runCli(['tab', 'select', '<weibo-tab-index>'], { opts });
    runCli(['goto', profile], { opts });
    runCli(['eval', '--stdin', '--await', '--json'], { stdin: '(页面抓取脚本)', opts });
    return;
  }

  const tabsEnv = runCli(['tab', 'list', '--json'], { opts });
  const tabs = (tabsEnv.output && tabsEnv.output.tabs) || [];
  let tab = pickWeiboTab(tabs, opts);
  if (!tab) {
    console.error(`⚠️ 这个浏览器里没有 URL 含 "${opts.tabMatch}" 的标签，新开一个 https://weibo.com/（用的还是同一个登录态）`);
    const made = runCli(['tab', 'new', 'https://weibo.com/'], { opts });
    tab = (made.output && made.output.tab) || { index: made.output && made.output.index, url: 'https://weibo.com/' };
  }
  if (tab.index !== undefined) runCli(['tab', 'select', String(tab.index)], { opts });
  console.log(`🔗 已连接 ${opts.channel}，用标签 #${tab.index} ${tab.url}`);

  // 「搜索」这一步：标签不在目标博主主页上时，把已登录的标签直接导航过去
  const profile = profileUrl(opts.user);
  const onProfile = String(tab.url || '').includes(encodeURIComponent(opts.user)) ||
    String(tab.url || '').includes(opts.user);
  if (!onProfile) {
    console.log(`🧭 标签不在 @${opts.user} 的主页上，导航到 ${profile}`);
    runCli(['goto', profile], { opts });
  }

  const env = runCli(['eval', '--stdin', '--await', '--json'], {
    stdin: pageScript(opts.user, opts.limit),
    opts,
  });
  const raw = env.output && env.output.result;
  const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
  if (!data || !Array.isArray(data.posts) || !data.posts.length) {
    throw new Error('页面没返回任何微博（登录态失效？或者这个博主这一页确实为空）');
  }

  console.log('');
  console.log(digest(data, opts));

  if (opts.out) {
    writeFileSync(opts.out, JSON.stringify(data, null, 2), 'utf8');
    console.log(`\n💾 完整数据（含全文/链接/互动数）已写入 ${opts.out}`);
  }
  if (opts.close) {
    runCli(['close'], { opts });
    console.log(`🧹 已关闭 ${opts.session} 会话（浏览器本身还在）`);
  } else {
    console.log(`\n会话 ${opts.session} 保留着，要收掉跑：browser4-cli -s ${opts.session} close`);
  }
}

try {
  main();
} catch (e) {
  console.error(`\n✗ ${e.message}`);
  process.exit(1);
}
