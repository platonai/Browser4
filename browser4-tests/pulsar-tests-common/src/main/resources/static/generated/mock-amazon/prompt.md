# Prompt to generate mock amazon site for testing

## Prompt to generate list page

https://www.amazon.com/b?node=1292115011

仅使用html,javascript,css，复制该网页的布局和内容，制作一个测试用的模拟网页。

动态效果：
1. 网页打开后，仅完全显示前两屏的组件（不精确要求，视实现方便），其他各个组件，需要加载框架，但是内容细节需要屏幕滚动到的时候才加载
2. 在搜索框输入时，模拟 autocomplete，点击任意一个选项，导航到 product 页面
3. 点击搜索按钮，导航到 product 页面

附加组件：
在右下角弹出一个层，说明本页面的目的和动态效果，方便测试者查看。

## Prompt to generate product page
https://www.amazon.com/dp/B08PP5MSVB

仅使用html,javascript,css，复制该网页的布局和内容，制作一个测试用的模拟网页。

动态效果：
1. 价格信息在网页打开后，有任意键盘鼠标动作的时候加载 
2. 网页打开后，仅完全显示前两屏的组件（不精确要求，视实现方便），其他各个组件，需要加载框架，但是内容细节需要屏幕滚动到的时候才加载
3. 在搜索框输入时，模拟 autocomplete，点击任意一个选项，导航到 product 页面
4. 点击搜索按钮，导航到 product 页面

附加组件：
在右下角弹出一个层，说明本页面的目的和动态效果，方便测试者查看。
