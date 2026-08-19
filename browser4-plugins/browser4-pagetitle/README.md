# browser4-pagetitle

获取当前页面的 title、URL 和 meta description。

## 功能说明

- 通过 `WebDriver` 在浏览器端执行 JavaScript，获取当前页面标题、地址和 `meta[name="description"]` 内容。
- 对外暴露工具方法 `pagetitle.getPageInfo`。
- 支持通过配置 `pagetitle.maxLength` 限制标题/描述文本的截断长度（默认 200）。
- 自动装配类 `ai.platon.pulsar.pagetitle.config.PagetitleAutoConfiguration` 注册 Processor 与 Service。

## 构建命令

进入插件目录后执行：

```bash
cd browser4-plugins/browser4-pagetitle
mvn -f pom.xml package
```

该命令会执行编译和测试，并在 `target/` 目录生成插件 JAR。

## JAR 结构校验

构建完成后，可用以下命令检查 JAR 内容：

```bash
jar tf target/browser4-pagetitle-*.jar
```

校验要点：

- 必须包含插件类，例如 `ai/platon/pulsar/pagetitle/config/PagetitleAutoConfiguration.class`。
- 必须包含 Processor/Service 等运行时类。
- 不应包含测试类（`*Test.class`）。
- 若插件包含资源配置或服务描述文件，也应一并存在。

## 部署方式

1. 将构建产物 `target/browser4-pagetitle-*.jar` 复制到 Browser4 的插件目录。
2. 重启 Browser4 服务以加载插件。

如使用附带脚本，也可以执行：

```bash
./build.ps1 -DeployDir <plugins目录>
```

或通过 REST API 安装：

```bash
./build.ps1 -RestInstall
```

该方式会向 `POST /api/plugins/install` 提交 JAR，安装后同样需要重启以激活。
