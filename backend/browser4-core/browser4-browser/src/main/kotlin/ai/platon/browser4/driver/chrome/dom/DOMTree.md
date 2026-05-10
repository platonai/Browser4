# DOM 树获取与 bounds 全链路说明

本文梳理 `browser_use/dom/service.py` 中 `_get_all_trees` 的程序流程，并追踪 `bounds` 值从最初通过 CDP 获取到最终影响 `llm_representation` 输出的完整传递与变更过程。

注：本文基于以下核心实现文件的阅读与确认：
- `browser_use/dom/service.py`：获取 CDP 各树与构建增强 DOM 树
- `browser_use/dom/enhanced_snapshot.py`：从 DOMSnapshot 解析并构建 `EnhancedSnapshotNode`（含 bounds）
- `browser_use/dom/views.py`：核心数据结构（`EnhancedDOMTreeNode`、`DOMRect`、`SerializedDOMState` 等）
- `browser_use/dom/serializer/serializer.py`：序列化为供 LLM 消费的文本（`llm_representation`）


## 一、_get_all_trees 执行流程
位置：`browser_use/dom/service.py` -> `DomService._get_all_trees(self, target_id: TargetID) -> TargetAllTrees`

高层概览：
- 建立/获取 CDP 会话
- 并发请求四类数据：
  - DOMSnapshot（含布局、样式、bounds 等）
  - DOM 树（`DOM.getDocument(depth=-1, pierce=True)`）
  - 可访问性树（AX Tree，包含所有 frame）
  - 设备像素比（device pixel ratio）
- 超时与重试机制
- 限制过多 iframe 文档以避免内存/性能问题
- 返回聚合结果 `TargetAllTrees`

详细步骤：
1) 获取或创建 CDP 会话：
   - `cdp_session = get_or_create_cdp_session(target_id, focus=False)`
   - 可选尝试 `Runtime.evaluate('document.readyState')`（仅为就绪探测/日志，不影响结果）

2) 额外调试：尝试读取页面内各 `<iframe>` 的实际滚动位置（`Runtime.evaluate` 注入 JS）。
   - 该数据仅用于 DEBUG 日志，不参与后续计算。

3) 定义并发请求工厂：
   - Snapshot: `DOMSnapshot.captureSnapshot`（包含：`computedStyles`, `includePaintOrder`, `includeDOMRects` 等）
   - DOM: `DOM.getDocument(depth=-1, pierce=True)`
   - AX: 通过 `_get_ax_tree_for_all_frames` 遍历所有 frame 的 AX 树并合并
   - DPR: `_get_viewport_ratio` 读取 `Page.getLayoutMetrics`，计算 devicePixelRatio

4) 并发执行与超时处理：
   - 首轮等待 10 秒
   - 对未完成任务取消并重试（额外 2 秒）
   - 若仍有失败/超时，抛出 `TimeoutError`

5) 后处理：
   - 如果 `snapshot['documents']` 数量超过 `max_iframes`，截断以避免处理爆炸
   - 聚合结果并返回 `TargetAllTrees(snapshot, dom_tree, ax_tree, device_pixel_ratio, cdp_timing)`


## 二、bounds 全链路追踪（从 CDP 到 LLM 输出）

目标：追踪 `bounds` 如何被创建、转换（缩放/偏移/可见性判断）、传递，并最终如何影响 LLM 输出。

重要数据结构：
- `DOMRect { x, y, width, height }`（`views.DOMRect`）
- `EnhancedSnapshotNode { bounds, clientRects, scrollRects, computed_styles, ... }`
- `EnhancedDOMTreeNode { snapshot_node, is_visible, absolute_position, ... }`
- `PropagatingBounds { tag, bounds, node_id, depth }`（序列化阶段的 bbox 传播）

关键结论先知：当前实现中，`bounds` 数值不会被直接打印到 `llm_representation` 字符串里；它们通过“可见性判断、滚动/iframe 偏移、以及 bbox 过滤”间接决定哪些节点进入输出，以及以何种结构呈现。

### 2.1 CDP 侧获取与初始缩放（DPR 校正）
位置：`browser_use/dom/enhanced_snapshot.py::build_snapshot_lookup`
- 输入：`DOMSnapshot.captureSnapshot` 返回的 `snapshot`，以及 `_get_all_trees` 并行获得的 `device_pixel_ratio`。
- 对每个文档：
  - 建立 `backendNodeId -> snapshotIndex` 映射
  - 用 `layout.nodeIndex -> layout_idx` 的首个出现位置映射提升性能
  - 解析 `layout.bounds[layout_idx]` 得到原始边界（raw_x, raw_y, raw_w, raw_h）
  - 关键：将 `bounds` 从设备像素转换为 CSS 像素（符合 JS 视角），计算：
    - `x = raw_x / device_pixel_ratio`
    - `y = raw_y / device_pixel_ratio`
    - `width = raw_width / device_pixel_ratio`
    - `height = raw_height / device_pixel_ratio`
  - 解析并保存：
    - `clientRects`（视口坐标系）、`scrollRects`（可滚动区域）、`computed_styles`、`paint_order` 等
- 输出：`snapshot_lookup: { backendNodeId -> EnhancedSnapshotNode(bounds=DOMRect(...), ...) }`

备注：当前代码仅对 `bounds` 做了 DPR 缩放；`clientRects/scrollRects` 按原样使用（来自 CDP 的布局数组）。

### 2.2 构建增强 DOM 树与 iframe/滚动偏移叠加
位置：`browser_use/dom/service.py::get_dom_tree` 内部 `_construct_enhanced_node`
- 输入：
  - `dom_tree`（`DOM.getDocument`）
  - `ax_tree`（AX 合并树；用于可访问性属性）
  - `snapshot_lookup`（含 `bounds` 等）
  - 递归参数：`html_frames`（HTML/IFRAME 框架栈）、`total_frame_offset`（累计偏移）

- 为每个 `DOM Node`：
  1) 取对应 `snapshot_data = snapshot_lookup[backendNodeId]`
  2) 若存在 `snapshot_data.bounds`，计算节点的文档绝对位置：
     - `absolute_position = snapshot.bounds + total_frame_offset`
     - 即：`x_abs = bounds.x + total_frame_offset.x`，`y_abs = bounds.y + total_frame_offset.y`
  3) 维护两个关键上下文：
     - `html_frames`：自外向内的 HTML/IFRAME 节点栈
     - `total_frame_offset`：累计坐标变换（iframe 位置 + 滚动修正）

- 偏移规则（关键变更点）：
  - 进入 `<iframe>/<frame>` 元素时：
    - 若该元素有 `snapshot_data.bounds`，将其 `bounds.x/y` 累加到 `total_frame_offset`，表示“嵌套内容相对顶层文档的平移”。
  - 进入 `<html>`（frame 的 HTML 根）且存在 `scrollRects` 时：
    - 将 `scrollRects.x/y` 从 `total_frame_offset` 中减去，表示“视口滚动将内容相对视口上移/左移”，因此要反向校正至绝对文档坐标。

- 可见性判断：`is_element_visible_according_to_all_parents(node, html_frames)`
  - 起始于元素的局部 `current_bounds = snapshot_node.bounds`
  - 逆序遍历 `html_frames`：
    - 若遇到 IFRAME：将该 iframe 的 `bounds.x/y` 加回 `current_bounds`（与上面的构建偏移相呼应）
    - 若遇到 HTML：用 HTML 的 `scrollRects` 与 `clientRects` 计算是否与视口相交（包含容错余量），再把 `scrollRects` 影响计入坐标修正
  - 若任一层判断不相交，则元素视为不可见

- 结果：每个 `EnhancedDOMTreeNode` 附带：
  - `snapshot_node.bounds`（原始 CSS 像素坐标，文档坐标系）
  - `absolute_position`（叠加 iframe/滚动后的全局绝对坐标）
  - `is_visible`（结合父级 iframe/HTML 视口判断的可见性）

### 2.3 在序列化阶段（LLM 输出前）bounds 的进一步作用
位置：`browser_use/dom/serializer/serializer.py`
- `serialize_accessible_elements()` 主要步骤：
  1) `_create_simplified_tree`：基于 `is_visible`、滚动性、shadow DOM 等裁剪/保留节点
  2) `PaintOrderRemover.calculate_paint_order()`：基于绘制顺序做过滤（与 bounds 无直接数值交互）
  3) `_optimize_tree`：结构优化（与 bounds 间接相关，因为 `is_visible` 受 bounds 影响）
  4) `_apply_bounding_box_filtering`（关键）：
     - 概念：某些元素会把自身的 `bounds` 作为“传播边界”（`PropagatingBounds`）向所有后代传播，用于过滤掉“完全包含于父交互区域内”的子元素，减少冗余点击目标。
     - 传播条件（默认规则）：`a`、`button`、`div[role=button]`、`div[role=combobox]`、`span[role=button]`、`span[role=combobox]`、`input[role=combobox]` 等
     - 传播逻辑：
       - 若节点满足传播条件且有 `snapshot_node.bounds`，则以该 `bounds` 作为新的 `active_bounds` 传递给其所有子孙
       - 对每个子节点，若其 `snapshot_node.bounds` 被父 `active_bounds` 以阈值（默认 99%）充分包含，且不满足保留例外（表单控件/自身可传播/显式 onclick/特定 aria/role），则标记为 `excluded_by_parent`
     - 这一步直接使用 `snapshot_node.bounds`（CSS 像素、文档坐标系）进行矩形包含判断
  5) `_assign_interactive_indices_and_mark_new_nodes`：只对“既可点击又可见”的节点分配索引（用于选择映射）

- `serialize_tree(...) -> str`（最终 LLM 文本输出）：
  - 输出包含：层级结构、交互索引（使用 `backend_node_id`）、选定属性、滚动提示等
  - 不直接输出 `bounds` 数值
  - 但 `bounds` 已在之前的可见性和 bbox 过滤中决定了最终出现哪些节点以及结构如何呈现


## 三、bounds 值的形态与坐标系约定
- `snapshot_node.bounds`：
  - 来源：CDP DOMSnapshot `layout.bounds`
  - 单位/坐标系：在代码中被转换到 CSS 像素；文档（page）坐标系，原点为页面左上角，忽略当前滚动
- `absolute_position`：
  - 计算：`snapshot_node.bounds + total_frame_offset`
  - `total_frame_offset` 记录 iframe 平移与 HTML 滚动修正的累计结果
  - 用途：供需要绝对坐标的逻辑/调试使用（当前序列化未直接输出）
- `clientRects`：
  - 视口坐标系（`getClientRects()` 语义），结合 HTML 的 `scrollRects` 用于可见性判断与滚动信息
- `scrollRects`：
  - 可滚动区域信息（宽高/当前 scrollTop/Left），用于滚动提示与可见性判断


## 四、从“CDP 获得”到“LLM 输出”的简图
```
CDP:
  DOMSnapshot.captureSnapshot  --> layout.bounds (device px)
  Page.getLayoutMetrics        --> device_pixel_ratio (DPR)

build_snapshot_lookup:
  bounds := layout.bounds / DPR  --> EnhancedSnapshotNode.bounds (CSS px, doc coords)
  + clientRects, scrollRects, computed_styles, ...

构建增强树 _construct_enhanced_node:
  absolute_position = bounds + total_frame_offset
  total_frame_offset:  + iframe.bounds  - html.scrollRects
  is_visible: 通过 html_frames 逐层检查 (iframe 偏移 + HTML 视口交集)

序列化 serializer:
  _create_simplified_tree  (用 is_visible 等)
  _apply_bounding_box_filtering (用 snapshot_node.bounds 传播/过滤)
  serialize_tree -> 字符串 (不打印 bounds 数值)
```


## 五、与 LLM 输出的关系与结论
- `llm_representation` 的字符串不会直接包含 `bounds` 的数值。
- `bounds` 影响两处关键决策：
  1) 可见性：通过 iframe/HTML 视口相交判断（`is_element_visible_according_to_all_parents`）
  2) BBox 传播过滤：将父交互区域的 `bounds` 传播给后代进行包含裁剪，减少冗余
- 因此，`bounds` 最终通过“保留/剔除/结构化”间接决定了 LLM 看到的 DOM 片段与交互索引集合。

如需在 `llm_representation` 中显式输出坐标，可在 `DOMTreeSerializer.serialize_tree` 中扩展：
- 在构造行时追加 `bounds` 或 `absolute_position` 信息（注意控制长度、只在调试/评估模式启用）。


## 六、验证要点与边界情况
- DPR 读取失败时回退为 `1.0`，此时不做缩放，`bounds` 等价于设备像素
- 多 iframe 页面会被截断到 `max_iframes`（默认 100），过多子文档会被忽略
- 跨域 iframe：默认不抓取其 DOM（除非 `cross_origin_iframes=True` 且满足可见/尺寸阈值），但对同域 iframe 会叠加其 `bounds` 参与偏移
- `clientRects/scrollRects` 直接来自 snapshot 布局数据并被用于滚动/可见性逻辑
- BBox 过滤默认阈值 99%（可通过构造参数覆盖），并带有若干“保留例外”规则（表单元素、具备交互语义的子节点等）


## 七、相关符号与函数索引
- `_get_all_trees`：`browser_use/dom/service.py`
- `build_snapshot_lookup`：`browser_use/dom/enhanced_snapshot.py`
- `_construct_enhanced_node`、`is_element_visible_according_to_all_parents`：`browser_use/dom/service.py`
- `EnhancedSnapshotNode`、`EnhancedDOMTreeNode`、`DOMRect`：`browser_use/dom/views.py`
- `DOMTreeSerializer.serialize_accessible_elements`、`_apply_bounding_box_filtering`、`serialize_tree`：`browser_use/dom/serializer/serializer.py`

