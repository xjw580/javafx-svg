# 路线阶段 4：径向渐变与图案溢出

## 实现范围

- 径向渐变采用逆仿射映射和 SVG 2 双圆方程；支持 gradientTransform、fr、外部焦点锥、pad/repeat/reflect、透明度及 sRGB/linearRGB 插值。隐式居中、普通 sRGB 径向渐变继续使用 JavaFX 原生快路径。显式 fx/fy 始终使用软件路径，牺牲部分原生加速以保证混合百分比/绝对值焦点在视口变化后的语义一致。
- 双圆方程先按几何量归一化，用稳定二次方程根求解并选取半径非负的最大参数根。缩放不依赖固定的绝对 epsilon；奇异或不可表示的逆变换会诊断。
- 软件渐变按目标/缩放/视口/透明度缓存，独立视图不共享可变图像。缓存和图案共用 maxPatternPixels，包含重建时旧图像。
- pattern 默认 overflow=hidden；显式 visible 将所有影响当前图块的相邻内容按行从左到右合成，支持负位置、重叠、viewBox 和二维变换。
- 图案同时存活的源图像、Canvas/快照和旧缓存计入峰值预算；超限和失败不会遗留临时预算。单轴 4096、最多 4096 个贡献图块和 1600 万图块像素工作，不提升既有资源上限。

## 验证与边界

新增数值和渲染测试覆盖焦点圆端点、中点、渐变空间、外部锥、重复/反射、零半径、相同圆、巨大/微小坐标等价、仿射和透明度、切点平均、缓存缩放、奇异变换、预算/恢复、图案跨边界/重叠及 viewBox。四面板样例位于 src/test/resources/svg/gradient-pattern-extensions.svg；测试生成 target/gradient-pattern-extensions.png，并重复执行 1×、2×、1.25×、1× 布局。

独立 Inkscape 对照只作为辅助证据：居中仿射/fr 面板四个内部像素差异不超过 1/255，但外部焦点反射面板存在明显差异，不宣称图像一致。Inkscape 对照未显示 visible 图案溢出，本实现遵循 SVG 2 推荐的行序扩展。SVG 2 尚未完全规定该 visible 溢出行为，不能以差异单独推定任何实现错误。

软件像素采样并非无损矢量输出。Windows/GPU/真实 HiDPI 和浏览器运行时一致性未验证；普通径向渐变的 JavaFX 原生快路径保留其既有采样特征。

规范依据：https://www.w3.org/TR/SVG2/pservers.html
