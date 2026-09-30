# js 工件对照

## 真实实现：插件内 JS 资源

`browser4-seo/src/main/resources/<domain>/extractMeta.js` —— 随插件打包、由 Service 经
`WebDriver.evaluateValue(script)` 在浏览器上下文执行。

## 脚手架输出：generated/extract-prices.js

由 `coding.scaffold(type="js", name="extract-prices", purpose="extract")` 生成：

```js
/**
 * extract-prices — DOM data extraction
 * Runs in browser context via tab.eval or plugin resource.
 * Returns a JSON string with extracted data.
 */
(function() {
    'use strict';
    var result = { url: window.location.href, title: document.title, ... };
    return JSON.stringify(result);
})();
```

`purpose` 决定形态：`extract`（采集数据） / `inject`（注入内容） / `interact`（交互）。

## 校验与运行

- `coding.validate(type="js", path=...)` — 括号平衡、return、use strict、反模式
- `tab.eval(expression=<读入的 JS>)` + `tab.console()` — 浏览器内运行时测试
- 插件内资源路径一致性：`validate(plugin)` 检查 Service 的 loadResource 路径存在对应文件
