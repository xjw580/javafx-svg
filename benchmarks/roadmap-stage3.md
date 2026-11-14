# 路线阶段 3：滤镜图像、平铺和模板

## 实现

- feTile 按输入原语子区域周期平铺，包含透明内容；默认输出区域是整个 filter 区域，而非输入区域。支持显式裁剪和浮点周期的双线性预乘采样。
- feImage 的本地 #id 复用原生 SVG 渲染，支持 g/use 等既有节点、用户空间和 objectBoundingBox 坐标。片段保留自然几何，不把任意 g 的包围盒强制拉伸至图像区域；x/y 是 use 式位移。
- PNG/JPEG 通过现有加载期资源会话准备，包括 data URI 和显式 resolver 授权的外部资源。渲染、缩放不调用 resolver。保留请求、字节、总字节及解码像素限制。
- 位图按原语区域和 preserveAspectRatio（默认 meet、none、slice）放置；无效或缺失资源透明输出并诊断，严格模式拒绝。
- filter 本地 href/xlink:href 继承区域与单位属性；自己的原语列表替代模板列表，无自身原语时使用最近模板。原语保持原对象身份，从而保留 CSS 祖先匹配和已准备的图像资源。

## 安全与生命周期

复用 activeReferences、最大引用深度、展开元素计数及每视图像素/工作预算。不打开新的资源通道，也不递归构造 SvgLoader。构造滤镜失败时回滚嵌套的几何/样式/动画回调和滤镜/遮罩/图案预算所有者。重复图片 URI 复用加载期缓存。

## 验证

新增数值、实际渲染、资源安全、模板、循环引用、预算恢复和编辑器测试。包含整数/非零原点/浮点周期、meet/slice、g/use、objectBoundingBox、图像模板资源身份、resolver 只调用一次、恶意循环、重复缩放和 XML 错误恢复。样例 `src/test/resources/svg/filter-image-tile.svg` 导出原生和实际编辑器截图。

## 已知边界

- 不支持独立外部 SVG 图像文件，只支持文档内片段及已有 PNG/JPEG 路径。
- filterRes 仍明确不支持。
- 本地片段省略 x/y 时保留自然坐标；滤镜区域本身只裁剪，不附加位移。不同浏览器历史实现对此有差异，不承诺浏览器逐像素一致性。
- 当前证据为 Linux 软件渲染，不代表 Windows/GPU 验证。

规范参考：
- https://www.w3.org/TR/filter-effects/#feImageElement
- https://www.w3.org/TR/filter-effects/#feTileElement
- https://www.w3.org/TR/filter-effects/#FilterElement
