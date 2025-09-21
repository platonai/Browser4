## WebDriver 事件清单

| 事件名                    | 描述                                                                                                                                                      |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **onClose**            | 当页面被关闭时触发。事件携带这个 Page 实例（被关闭的页面）。                                                                                                      |
| **onConsoleMessage**   | 当页面中的脚本调用 `console.*`（如 `console.log`, `console.error` 等）时触发。处理器可获得 `ConsoleMessage` 对象，其中包含调用时的参数等。                                   |
| **onCrash**            | 当页面崩溃时触发（例如在消耗大量资源或浏览器遇到严重错误时）。之后对该页的操作通常会抛异常或失效。事件携带 Page 对象。                                                                         |
| **onDialog**           | 当页面弹出对话框时触发，如 `alert`, `prompt`, `confirm`, `beforeunload` 等类型的 JS 弹窗。处理器必须调用 `Dialog.accept()` 或 `Dialog.dismiss()`，否则页面可能冻结等待对话框响应。  |
| **onDOMContentLoaded** | 当 DOMContentLoaded 事件被触发，也就是文档解析完成但资源（如图片、样式表等）可能尚未加载完毕。事件携带 Page。                                                                     |
| **onDownload**         | 当页面开始一个附件下载（download），即用户或页面触发下载行为时触发。处理器可获得 `Download` 对象，用于操作下载内容。                                                                   |
| **onFileChooser**      | 当页面中将要弹出文件选择器（file input）时触发，比如点击 `<input type="file">` 或类似动作。处理器获得 `FileChooser` 对象，可以在其中设定要上传的文件等。                                   |
| **onFrameAttached**    | 当一个 frame 被附加（插入 DOM 树中）到页面时触发。处理器获得这个 `Frame` 对象。                                                                                     |
| **onFrameDetached**    | 当一个 frame 从页面中移除（被分离）时触发。处理器获得该 `Frame` 对象。                                                                                            |
| **onFrameNavigated**   | 当某个 frame 导航到新的 URL（或文档内容发生导航）时触发。处理器获得对应的 `Frame`。                                                                                    |
| **onLoad**             | 当页面的 `load` 事件被触发，即所有资源（脚本、图片等）加载完成时触发。事件携带 Page 对象。                                                                                   |
| **onPageError**        | 当页面内部发生未被捕获的异常（Javascript 抛出的错误）时触发。处理器得到一个字符串或错误消息。                                                                                   |
| **onPopup**            | 当页面打开一个新窗口或标签页（popup，例如通过 `window.open`）时触发。事件携带新打开的 `Page` 对象。此事件在 popup 已经开始加载初始 URL 时触发。                                            |
| **onRequest**          | 当页面发起一个网络请求（Request）时触发。请求对象是只读的。                                                                                                      |
| **onRequestFailed**    | 当某个网络请求失败时触发（例如因为网络问题、超时等）。HTTP 错误状态（404、500 等）本身不算失败——那时是 `onResponse` + `onRequestFinished` 的流程，而不是 `onRequestFailed`。               |
| **onRequestFinished**  | 当请求成功完成，包括响应头与响应体下载完成时触发。通常触发顺序是：`onRequest` → `onResponse` → `onRequestFinished`。                                                     |
| **onResponse**         | 当某个请求的状态与头部响应已经被接收到时触发。即服务器已返回响应头 & 状态码，但响应体可能还在下载中。处理器得 `Response` 对象。                                                                |
| **onWebSocket**        | 当页面发起一个 WebSocket 请求时触发。处理器获得 `WebSocket` 对象。                                                                                          |
| **onWorker**           | 当页面创建一个 dedicated Web Worker 时触发。处理器获得 `Worker` 对象。                                                                                    |

