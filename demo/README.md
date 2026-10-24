# SVG 特性演示

启动交互画廊：

```shell
mvn -Pdemo test-compile exec:java
```

入口为 `club.xiaojiawei.svg.demo.SvgDemoKt`。默认使用本地资源，只有点击 **Load network** 才请求网络徽章。窗口可缩放，画廊会换列；鼠标滚轮、方向键、Page Up/Down、Home/End 用于滚动。Tab 移动焦点，Enter/Space 激活按钮。

- **Theme**：切换蓝、绿、紫主题。主题图标使用 `-svg-fill/-svg-stroke/-svg-stroke-width`；紫色勾选由路径自己的 `-fx-stroke` 保留。悬停时轮廓改为橙色。
- **Preview**：以 80%、100%、120% 调整 SVG 视口。**Reset** 恢复蓝色、100% 和本地徽章，并返回页首。
- **Resource loading**：展示后台加载、成功、失败保留旧内容、清空，以及回到本地资源的状态。重新加载相同网络 URL 会请求新结果。

| 卡片 | 观察内容 |
| --- | --- |
| Original SVG | 原始渐变与路径；与主题卡片共享文档 |
| Theme & path CSS | 根主题属性、独立路径 CSS、悬停状态 |
| Shapes & strokes | 基本图形、曲线、端点、连接、虚线 |
| Non-scaling stroke | 普通描边与固定 3 逻辑像素描边；切换 Preview 比较缩放 |
| Linear & radial | 线性、径向、reflect 渐变 |
| Geometric clipping | 圆形裁剪和 even-odd 镂空 |
| Text, tspan & length | 嵌套文字片段、颜色/斜体、基线偏移、间距调整、横向字形缩放 |
| Reusable symbols | use/symbol、currentColor、组合变换 |
| Alpha & luminance | 同一组黑灰白颜色在两种遮罩中的差别 |
| Gaussian blur | 原始图形与 sRGB 高斯模糊 |
| Viewport fitting | 嵌套 SVG 的 meet、slice、none |
| Shared resource | 相同 source 共享文档，各自保持节点和样式 |
| Loaded from FXML | 直接加载现有 icon.fxml 及其相对资源 |

离线快照与交互状态检查：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--smoke"
```

不会显示窗口，也不会访问网络。生成：

- `target/demo-preview.png`：蓝色、100%、宽屏布局。
- `target/demo-preview-themed.png`：绿色、120%、宽屏布局。
- `target/demo-preview-compact.png`：紫色、80%、780px 紧凑布局。

检查会验证共享文档但独立节点、路径 CSS 优先、主题与尺寸切换、横向边界、滚动及资源清空/恢复；静态样本需没有渲染诊断。需要 JavaFX 图形环境。Linux 无显示器时可使用 `xvfb-run`。

示例仅使用 javafx-graphics 与已有测试依赖 javafx-fxml，不新增 javafx-controls。PNG 导出使用 Java2D，仅存在于测试示例中。原有 `--benchmark` 和 `--cache-visual-check` 入口保持可用。
