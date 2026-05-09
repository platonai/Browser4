# ?? Browser4

[![Docker Pulls](https://img.shields.io/docker/pulls/galaxyeye88/browser4?style=flat-square)](https://hub.docker.com/r/galaxyeye88/browser4)
[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

[English](README.md) | �������� | [�й�����](https://gitee.com/platonai_galaxyeye/Browser4)

> ���ļ�����Ӣ�İ� README ͬ����ͬ�����ڣ�2026-04-20�������в�������Ӣ�İ�Ϊ׼��

<!-- TOC -->
**Ŀ¼**
- [?? Browser4](#-browser4)
    - [?? ��Ŀ����](#-��Ŀ����)
        - [? ��������](#-��������)
    - [?? ��ʾ��Ƶ](#-��ʾ��Ƶ)
    - [?? ���ٿ�ʼ](#-���ٿ�ʼ)
    - [?? ʹ��ʾ��](#-ʹ��ʾ��)
        - [�����������](#�����������)
        - [�������Զ���](#�������Զ���)
        - [LLM + X-SQL](#llm--x-sql)
        - [���ٲ��д���](#���ٲ��д���)
        - [�Զ���ȡ](#�Զ���ȡ)
    - [?? ģ�����](#-ģ�����)
    - [?? �ĵ�](#-�ĵ�)
    - [?? �������� - ������վ����](#-��������---������վ����)
    - [? ��������](#-��������)
    - [?? ֧��������](#-֧��������)
<!-- /TOC -->

## ?? ��Ŀ����

?? **Browser4��Ϊ AI ������������١�Э�̰�ȫ��coroutine-safe�������������** ??

### ? ��������

* ?? **����������壨Browser Agents��** �� ������������������滮��ִ�ж˵�����������������塣
* ?? **������Զ���** �� ����������������������ȡ�ĸ������Զ���������
* ?? **����ѧϰ������** �� �ڸ���ҳ����ѧϰ�ֶνṹ���������� token��
* ? **��������** �� ��ȫЭ�̰�ȫ��֧�ֵ���ÿ����� 100k ~ 200k ����ҳ�档
* ?? **���ݳ�ȡ** �� ��� LLM��ML ��ѡ�������ڸ���ҳ���л�øɾ����ݡ�

## CLI & SKILLS

```shell
# �������������
browser4-cli open

# ������ҳ��
browser4-cli goto https://playwright.dev

# �鿴ҳ����գ�ע�⽻���ڵ��ϵ� eN ��ǩ
browser4-cli snapshot

# ʹ�ÿ����е� refs ���н���
browser4-cli click e15
browser4-cli type "Hello World" e15
browser4-cli press Enter e15
browser4-cli keydown Shift
browser4-cli mousemove 150 300
browser4-cli mousewheel 0 100
browser4-cli keyup Shift

# ��ͼ�����浽����
browser4-cli screenshot

# ʹ���Զ�������ַ
browser4-cli open --server http://localhost:9090

# ��ͬһ������ִ�ж�������
browser4-cli batch "goto https://playwright.dev" "snapshot"

# ������һ��ʧ�����ֹͣ
browser4-cli batch --bail "goto https://playwright.dev" "click e1" "screenshot"

# ͨ�� stdin �� JSON ��ʽ��������������
echo '[
  ["open", "https://playwright.dev"],
  ["snapshot"],
  ["click", "e1"],
  ["screenshot", "--filename=result.png"]
]' | browser4-cli batch --json

# ʹ����ɺ�رջỰ
browser4-cli close
```

---

## ?? ��ʾ��Ƶ

?? YouTube:
[![Watch the video](https://img.youtube.com/vi/rJzXNXH3Gwk/0.jpg)](https://youtu.be/rJzXNXH3Gwk)

?? Bilibili:
[https://www.bilibili.com/video/BV1fXUzBFE4L](https://www.bilibili.com/video/BV1fXUzBFE4L)

---

## ?? ���ٿ�ʼ

**ǰ��Ҫ��**��Java 17+

1. **��¡�ֿ�**
   ```shell
   git clone https://github.com/platonai/browser4.git
   cd browser4
   ```

2. **������� LLM API key**

   > �༭ [application.properties](application.properties) ��������� API key��

3. **������Ŀ**
   ```shell
   ./mvnw -DskipTests
   ```

4. **����ʾ��**
   ```shell
   ./mvnw -pl examples/browser4-examples exec:java -D"exec.mainClass=ai.platon.pulsar.examples.agent.Browser4AgentKt"
   ```
   ������� Windows �������������⣺
   ```shell
   ./bin/run-agent-examples.ps1
   ```

   �� `browser4-examples` ģ����̽��������ʾ����ֱ�ۿ��� Browser4 ��������
   Java ����ʾ�����Ƴ�������� Kotlin API��SDK �� CLI ���ߡ�

Docker ����� [Docker Hub repository](https://hub.docker.com/r/galaxyeye88/browser4)��

**Windows �û�**����Ҳ���Խ� Browser4 ����Ϊ���� Windows ��װ������� [Windows Installer Guide](browser4-app/browser4-agents/README.md)��

---

## ?? ʹ��ʾ��

## CLI & SKILLS

Browser4 CLI ��һ��ǿ��������нӿڣ���ֱ�ӽ�����������ƺ��Զ��������������û��� AI agents��
���ṩ���﷨����������д���뼴����ɸ��������������

Browser4 CLI �� Playwright ���ݣ�֧�ֵ�����������������ȡ�ȹ㷺���
���������ڽű����ն˻Ự����ͨ�� SKILLS ���ɽ� AI agents��

```shell
# ��װ���� Unix CLI������ Browser4.jar ��������ʱ��
curl -fsSL https://raw.githubusercontent.com/platonai/Browser4/master/sdks/browser4-cli/install.sh | bash

# Windows������ Browser4 �ֿ���֧�� localhost Maven �Զ�������Browser4.jar ���Ƕ�������ʱ
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.browser4\lib" | Out-Null
Invoke-WebRequest 'https://github.com/platonai/Browser4/releases/latest/download/Browser4.jar' -OutFile "$env:USERPROFILE\.browser4\lib\Browser4.jar"
git clone https://github.com/platonai/Browser4.git
cd Browser4\sdks\browser4-cli
cargo install --path . --locked

# �������������
browser4-cli open

# ������ҳ��
browser4-cli goto https://playwright.dev

# �鿴ҳ����գ�ע�⽻���ڵ��ϵ� eN ��ǩ
browser4-cli snapshot

# ʹ�ÿ����е� refs ���н���
browser4-cli click e15
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter
browser4-cli keydown Shift
browser4-cli mousemove 150 300
browser4-cli mousewheel 0 100
browser4-cli keyup Shift

# ��ͼ�����浽����
browser4-cli screenshot

# ʹ���Զ�������ַ
browser4-cli open --server http://localhost:9090

# ��ͬһ������ִ�ж�������
browser4-cli batch "goto https://playwright.dev" "snapshot"

# ������һ��ʧ�����ֹͣ
browser4-cli batch --bail "goto https://playwright.dev" "click e1" "screenshot"

# ͨ�� stdin �� JSON ��ʽ��������������
echo '[
  ["open", "https://playwright.dev"],
  ["snapshot"],
  ["click", "e1"],
  ["screenshot", "--filename=result.png"]
]' | browser4-cli batch --json

# ʹ����ɺ�رջỰ
browser4-cli close
```

��Դ�빹�� CLI��

[README.md](sdks/browser4-cli/README.md)

Browser4 CLI Ϊ AI agents ͨ�� SKILLS + CLI ʹ�ö���ơ�

[SKILL.md](sdks/skill/SKILL.md)

---

### �����������

��������Ȼ����ָ�ִ�и�������������������������塣

```kotlin
val agent = AgenticContexts.getOrCreateAgent()

val task = """
    1. go to amazon.com
    2. search for pens to draw on whiteboards
    3. compare the first 4 ones
    4. write the result to a markdown file
    """

agent.run(task)
```

### �������Զ���

�Ͳ�������Զ�����������ȡ��֧��ϸ���ȿ��ơ�

**���ԣ�**
- ͬʱ֧��ʵʱ DOM ���������߿��ս���
- ֱ���������� Chrome DevTools Protocol��CDP�����ƣ�Э�̰�ȫ
- ��ȷԪ�ؽ�������������������룩
- ���� CSS ѡ����/XPath �Ŀ���������ȡ

```kotlin
val session = AgenticContexts.getOrCreateSession()
val agent = session.companionAgent
val driver = session.getOrCreateBoundDriver()

// �������� URL ��Ӧ�ĳ�ʼҳ��
var page = session.open(url)

// ����Ȼ�����������������
agent.act("scroll to the comment section")
// ��ʵʱ DOM �ж�ȡ�׸�ƥ�����۽ڵ�
val content = driver.selectFirstTextOrNull("#comments")

// ��ҳ����ս���Ϊ�ڴ��ĵ����������߽���
var document = session.parse(page)
// һ���Խ� CSS ѡ����ӳ��Ϊ�ṹ���ֶ�
var fields = session.extract(document, mapOf("title" to "#title"))

// �� companion agent ִ�жಽ����/��������
val history = agent.run(
    "Go to amazon.com, search for 'smart phone', open the product page with the highest ratings"
)

// �����º�������״̬����� PageSnapshot
page = session.capture(driver)
document = session.parse(page)
// �Ӳ����������ȡ�����ֶ�
fields = session.extract(document, mapOf("ratings" to "#ratings"))
```

### LLM + X-SQL

�����ڸ߸��Ӷ����ݳ�ȡ��ˮ�ߣ����ͳ���������ʮ��ʵ�塢ÿ��ʵ�����ٸ��ֶΡ�

**���ƣ�**
- ��ȴ�ͳ�������ɶ���ȡ 10 ��ʵ���� 100 ���ֶ�
- ��� LLM �����뾫׼ CSS ѡ����/XPath
- �� SQL �﷨��ѧϰ�ɱ���

```kotlin
val context = AgenticContexts.create()
val sql = """
select
  llm_extract(dom, 'product name, price, ratings') as llm_extracted_data,
  dom_first_text(dom, '#productTitle') as title,
  dom_first_text(dom, '#bylineInfo') as brand,
  dom_first_text(dom, '#price tr td:matches(^Price) ~ td, #corePrice_desktop tr td:matches(^Price) ~ td') as price,
  dom_first_text(dom, '#acrCustomerReviewText') as ratings,
  str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
from load_and_select('https://www.amazon.com/dp/B08PP5MSVB -i 1s -njr 3', 'body');
"""
val rs = context.executeQuery(sql)
println(ResultSetFormatter(rs, withHeader = true))
```

ʾ�����룺

* [ʹ�� X-SQL ������ѷ��Ʒҳץȡ 100+ �ֶ�](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)
* [ץȡ����������ѷҳ��� X-SQL ����](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)

### ���ٲ��д���

ͨ�����������������������Դ�Ż���ü������¡�

**���ܣ�**
- ����ÿ����� 10k ~ 20k ����ҳ��
- �����Ự����
- ����޹���Դ������ҳ�����

```kotlin
val args = "-refresh -dropContent -interactLevel fastest"
val blockingUrls = listOf("*.png", "*.jpg")
val links = LinkExtractors.fromResource("urls.txt")
    .map { ListenableHyperlink(it, "", args = args) }
    .onEach {
        it.eventHandlers.browseEventHandlers.onWillNavigate.addLast { page, driver ->
            driver.addBlockedURLs(blockingUrls)
        }
    }

session.submitAll(links)
```

?? YouTube:
[![Watch the video](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

?? Bilibili:
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

### �Զ���ȡ

�����Լල/�޼ල����ѧϰ���Զ��������ģ���߾����ֶη������ȡ������ LLM API������ token��ȷ���ҿ��١�

**������**
- �߾���ѧϰ��Ʒ/����ҳ�ϵ�ȫ���ɳ�ȡ�ֶΣ�ͨ����ʮ���ϰٸ�����
- �� Browser4 �� GitHub �ﵽ 10K stars ʱ��Դ��

**Ϊʲô��ֻ�� LLM��**
- LLM ��ȡ������ӳ١��ɱ��� token ���ơ�
- ���� ML ���Զ���ȡ���ؿɸ��֣�����չ�� 100k+ ~ 200k ҳ/�졣
- �����ʹ�ã��Զ���ȡ����ṹ�����ߣ�LLM ����������ǿ��

**������PulsarRPAPro����**
```bash
# ע�⣺��Ҫ MongoDB
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v4.8.2/PulsarRPAPro.jar
```

**����״̬��**
- ��ǰ��ͨ��������Ŀ [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro) ʹ�á�
- �ƻ��ṩ Browser4 ԭ�� API ��¶�����ע�����汾������

**�ؼ����ƣ�**
- �߾��ȣ�>95% �ֶη����ʣ������Ѳ�վ���ֶξ��� >99%��ָʾ�����ݣ���
- ��ѡ����Ư���� HTML ������³����
- ���ⲿ���������� API key������ģ���ɱ����š�
- �ɽ��ͣ�����ѡ������ SQL ͸���ҿ���ơ�

?? ʹ�û���ѧϰ������������ݳ�ȡ��

![Auto Extraction Result Snapshot](docs/assets/images/amazon.png)

�������Ƴ������ḻ�Ĳֿ���ʾ����ֱ�� API �ҹ�����

---

## ?? ģ�����

| ģ�� | ˵�� |
|-------------------|---------------------------------------------------------|
| `browser4-core` | �������棺�Ự�����ȡ�DOM����������� |
| `browser4-agentic` | ������ʵ�֡�MCP �뼼��ע�� |
| `browser4-rest` | Spring Boot REST ��������˵� |
| `browser4-agents` | ��������������ż���Ʒ��� |
| `sdks` | Rust ʵ�ֵ� CLI��֧�� SKILLS |
| `examples` | ������ʾ������ʾ���� |
| `browser4-tests` | E2E �����ͼ���/�������� |

---

## ? ��������

״̬˵����[���ṩ] �ڲֿ��У�[ʵ����] ���ڵ�����[�滮��] ��δ�ڲֿ��У�[ָ��] ����Ŀ��ֵ��

### AI ��������
- [���ṩ] ���������������������������
- [���ṩ] ����������Ự
- [ʵ����] LLM ������ҳ���������ȡ

### ������Զ����� RPA
- [���ṩ] ���ڹ����������������
- [���ṩ] Э�̰�ȫ�ľ�ȷ���ƣ��������������ȡ��
- [���ṩ] �����¼��������������ڹ���

### ���ݳ�ȡ���ѯ
- [���ṩ] һ������ʽ���ݳ�ȡ
- [���ṩ] ���� DOM/���ݵ� X-SQL ��չ��ѯ����
- [ʵ����] �ṹ�� + �ǽṹ����ϳ�ȡ��LLM + ML + ѡ������

### ���������չ��
- [���ṩ] ��Ч����ҳ����Ⱦ
- [���ṩ] �������������������
- [ָ��] ��ͨӲ���´ﵽ 100,000+ ����ҳ��/��

### ������ɿ���
- [ʵ����] �Ƚ��������˼���
- [���ṩ] ͨ�� `PROXY_ROTATION_URL` ���д����ֻ�
- [���ṩ] ���Ե�������������

### ����������
- [���ṩ] ��� API ���ɣ�REST��ԭ�����ı����
- [���ṩ] �ḻ���÷ֲ�
- [���ṩ] �����Ľṹ����־��ָ��

### �洢����
- [���ṩ] �����ļ�ϵͳ�� MongoDB ֧�֣�����չ��
- [���ṩ] ȫ����־��͸����

---

## ?? ֧��������

��ӭ����������ȡ֧�֡��������Ⲣ����Э����

- **GitHub Discussions**���뿪���ߺ��û�������
- **Issue Tracker**������ bug ���ύ��������
- **Social Media**����ע���ǵ����¶�̬��

���ǻ�ӭ���ף���� [CONTRIBUTING.md](CONTRIBUTING.md)��

---

## ?? �ĵ�

�����ĵ����� `docs/` Ŀ¼�� [GitHub Pages site](https://platonai.github.io/browser4/) �鿴��

---

## ?? �������� - ������վ����

<details>

���������� `PROXY_ROTATION_URL` ����Ϊ�����������ṩ���ֻ� URL��

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

ÿ�η��ʸ��ֻ� URL ʱ��Ӧ����һ�������µĴ��� IP��
��������� URL������ϵ��Ĵ��������̡�

</details>

---

## ����֤

Apache 2.0 License����� [LICENSE](LICENSE)��




