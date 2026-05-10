# AI generated single page application

The assets are created by Browser4 team for test purpose. 

The files are created by AI.

本目录包含多份用于交互/可视化/自动化测试的纯前端示例页面。以下为所有 `interactive-*.html` 文件的结构、交互点与涉及的 DOM 事件，便于测试脚本（如 RPA、爬虫、可视化回归、事件触发验证）编写。

---
## 总览
| 文件 | 主要主题 | 特色交互 | 典型事件 | 适合测试场景 |
| ---- | -------- | -------- | -------- | ------------ |
| interactive-1.html | 基础输入/选择/计算/显示 | 输入回显、动态背景、加法计算、隐藏/显示切换 | input, change, click | 基础事件监听、DOM 更新、条件显示 |
| interactive-2.html | 信息采集 + 动态样式 | 汇总按钮、字号滑块实时调整、hover 动效 | click, input, change, (range) input, hover(CSS) | 复合状态汇总、range 控件处理、样式变更检测 |
| interactive-3.html | 动画进入 + 控件集合 | IntersectionObserver 动画、范围滑块显示值、动态显示/隐藏盒子 | input(range onchange), click, checkbox(change), alert, IntersectionObserver 回调 | 视口检测、动画触发、渐入、延时显示/隐藏 |
| interactive-4.html | 深色模式 + Drag & Drop | 深色模式切换、拖拽排序 | change, dragstart, dragover, dragend, IntersectionObserver | DnD 顺序稳定性、主题切换样式断言、滚动动画 |
| interactive-screens.html | 多段内容聚合页面 | 输入回显、背景色切换、加法、隐藏/显示、表单按钮 | input, change, click | 多区块定位、表单多实例、可视内容抽取 |

---
## 1. interactive-1.html
**板块 (sections)**
1. Header（标题+说明）
2. User Information：文本输入实时回显
3. Preferences：下拉选择改变 `body` 背景色
4. Quick Calculator：两个 number 输入 + Add 按钮计算并输出
5. Dynamic Toggle：按钮切换隐藏消息的显示/隐藏

**交互与逻辑**
- 名字输入：监听 `input` → 更新结果文本
- 颜色选择：监听 `change` → 修改 `document.body.style.backgroundColor`
- 计算按钮：`click` → 读取 num1/num2，校验并输出结果
- Toggle 按钮：`click` → 切换 `hidden` class

**DOM 事件**
- input(#name)
- change(#colorSelect)
- click(Add 按钮、Toggle 按钮)

**测试要点示例**
- 输入空字符串时回显应为空
- 选择空值应恢复默认背景 `#f9f9f9`
- 非数字输入（留空）计算结果提示文本包含 “Please enter valid numbers”
- Toggle 多次点击应在显示/隐藏间切换

---
## 2. interactive-2.html
**板块**
1. Header
2. Introduction：静态文本
3. Your Info：姓名输入 + 语言选择 + 订阅复选框 + “Show Summary” 按钮
4. Dynamic Styling：range 滑块实时调整段落字体大小
5. CSS Hover Effect：悬浮卡片（纯 CSS :hover 动效）
6. Footer

**交互与逻辑**
- Show Summary 按钮：`click` → 汇总 name / language / checkbox 状态
- 字号滑块：`input` → 设置 `#dynamicText.style.fontSize`
- Hover 卡片：CSS 过渡测试（无 JS 事件）

**DOM 事件**
- click(Show Summary 按钮)
- input(range #textSizeSlider)
- 读取状态：text input, select, checkbox（未单独绑定 change，按钮点按时取值）

**测试要点**
- range 边界：最小 12 / 最大 36 px 字号断言
- 空姓名时使用占位 “Anonymous”
- 切换语言后再输出，校验文本拼接正确
- 复选框勾选/取消显示 “Subscribed: Yes/No”
- Hover 卡片可用截图/计算样式验证 transform 与 box-shadow

---
## 3. interactive-3.html
**板块**
1. Header
2. User Information：文本、邮箱、textarea + Submit 按钮（alert）
3. Controls & Toggles：select、Dark Mode（checkbox + 自定义开关）、Volume range 显示当前值
4. Interaction Demo：按钮 Toggle Box（淡入淡出区域）
5. Footer

**动画/可视性**
- 含 `.fade-in-up` 元素：进入视口由 IntersectionObserver 添加 `visible` class 触发过渡

**交互与逻辑**
- Submit 按钮：`click` → `alert('Form Submitted!')`
- Volume range：`onchange` → 更新 span#volumeValue 文本
- Toggle Box 按钮：`click` → 显示/隐藏 demoBox（动态 style 与 opacity 过渡）
- Dark Mode checkbox：仅在 interactive-3 中声明但实际未切换主题样式（只是控件演示）

**DOM 事件 / API**
- click(Submit, Toggle Box)
- change(range Volume)
- alert side effect（阻塞型弹窗）
- IntersectionObserver 回调（视口进入）

**测试要点**
- 观察 IntersectionObserver：滚动至 section 时 `classList` 包含 `visible`
- Toggle Box：首次点击后 style.display=block 且 opacity 最终为 1；再次点击后逐步隐藏
- Volume 改变：更新文本即时性（change 触发点在鼠标释放）
- Submit：捕获 alert 文本

---
## 4. interactive-4.html
**板块**
1. Header：介绍 Drag & Drop 与 Dark Mode
2. User Information：基础表单 + Submit alert
3. Appearance：Dark Mode toggle（真正切换 body.classList）
4. Drag & Drop List：可拖拽排序的任务列表 `<li draggable="true">`
5. Footer

**交互与逻辑**
- Dark Mode：`change`(#darkToggle) → 切换 `body.classList.toggle('dark')`
- Drag & Drop：监听 dragstart / dragend（添加/移除 dragging class），dragover（计算插入位置）
- Submit：`click` → alert
- IntersectionObserver 同 interactive-3：`.fade-in-up` 进入视口添加 `visible`

**DOM 事件 / API**
- change(checkbox)
- dragstart / dragend / dragover (HTML5 DnD)
- click(Submit)
- IntersectionObserver 回调

**Drag & Drop 逻辑说明**
- dragover 中通过当前鼠标 Y 坐标与其它列表项中心比较计算插入位置
- 排序完成后 DOM 顺序即为最终顺序（无持久化）

**测试要点**
- 拖拽前后 innerText 顺序差异
- dragging 过程中元素添加 `dragging` class（opacity 变化）
- Dark Mode：切换后 :root 派生的 CSS 变量变化，可断言背景/文字颜色
- 多次切换模式无残留闪烁

---
## 5. interactive-screens.html
（与 interactive-1 结构相似，但增加多内容段落）

**板块**
1. Header
2. User Information：输入回显
3. Preferences：背景色切换
4. Quick Calculator：加法
5. Dynamic Toggle：隐藏/显示文本
6. Email Validation：HTML5 email 输入（无自定义校验脚本）
7. Customer Story：静态长文本 + 链接
8. Blogs：链接重复展示
9. Contact Us：email + textarea + Send 按钮（alert） + 感谢文字

**交互与逻辑**
- 与 interactive-1 相同基础：input/change/click
- Send 按钮：`click` → alert('Message sent!')
- Email/Email2：依赖浏览器原生 email 类型校验（若表单提交会触发，但此处未提交 form，仅输入）

**DOM 事件**
- input(#name)
- change(#colorSelect)
- click(Add、Toggle、Send)

**测试要点**
- 背景色循环选择验证
- Add 非法输入提示
- Toggle 显示状态与 classList
- Send 按钮 alert 捕获
- 多链接定位（第一个 Blogs 区块的两个相同 href）

---
## 跨页面事件/控件汇总
| 控件/行为 | 出现页面 | 事件 | 备注 |
| -------- | -------- | ---- | ---- |
| 文本输入实时回显 | 1, screens | input | 输出空→清空文本 |
| 背景色下拉 | 1, screens | change | body.style.backgroundColor |
| 加法计算按钮 | 1, screens | click | NaN 时提示字符串 |
| Toggle 显示/隐藏 | 1, screens | click | 切换 hidden class |
| Summary 汇总按钮 | 2 | click | 读取 input/select/checkbox |
| Range 字号调整 | 2 | input | 改 font-size |
| IntersectionObserver 动画 | 3,4 | 回调 | 添加 visible class |
| Toggle Box 动态区域 | 3 | click | 逐步淡入/淡出 |
| Volume range | 3 | change | 显示当前值 |
| Dark Mode 开关 (视觉有效) | 4 | change | 切换 body.dark |
| Drag & Drop 列表 | 4 | dragstart/dragover/dragend | 计算插入点 |
| 发送/提交按钮 alert | 3,4,screens | click | alert side-effect |

---
## 建议的自动化测试脚本断言点
- 事件触发后 DOM 文本节点内容（innerText / textContent）
- class 切换：`hidden`、`visible`、`dragging`、`body.dark`
- 样式属性：背景色、字体大小、元素 display/opacity
- 列表顺序：拖拽前后 `querySelectorAll('li')` 顺序文本
- alert 拦截：替换 `window.alert` 收集消息
- IntersectionObserver 可通过滚动或直接触发模拟（必要时 polyfill）

---
## 使用提示
1. 页面均为静态文件，可直接通过本地服务器或文件协议加载。若需 IntersectionObserver 一致行为，推荐使用 HTTP 服务（避免某些安全限制）。
2. 事件监听多为内联或直接在脚本底部绑定，加载完成后即可使用，无需额外初始化。
3. 不涉及外部依赖库，可安全在离线环境回放。
4. 若扩展测试，可在控制台覆写原生函数（如 alert）进行捕获。

---
## 后续可扩展方向
- 为 DnD 添加顺序持久化(localStorage)
- 为 Email 输入添加正则即时验证
- 添加键盘可访问性（Enter/Space 触发按钮、focus outline 强化）
- 提供统一的 data-testid 属性以稳定定位

---
文档生成时间：自动生成。
