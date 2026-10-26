# SVG 交互实验室

使用 **JDK 21 或更新版本**（`java -version` 核对）和 Maven 3.8+，在包含 `pom.xml` 的源码根目录启动；Windows PowerShell/cmd、macOS/Linux 使用同一命令：

```shell
mvn -Pdemo test-compile exec:java
```

入口为 `club.xiaojiawei.svg.demo.SvgDemoKt`。演示使用 javafx-graphics、javafx-fxml 和实时编辑器所需的 javafx-controls 测试依赖，界面与素材全部离线；只有「加载网络徽章」会请求远程资源。

界面包含分类目录、大尺寸实时预览、源图对比、源码面板和动画时间轴。宽屏并列展示预览与源码，较窄窗口将源码移到下方。鼠标滚轮与 Page Up/Down 滚动，Tab 切换焦点，Enter/Space 操作按钮；源码区支持独立滚动。

- **界面主题**：蓝、绿、紫三种强调色。「统一换色」决定是否将主题用于当前 SVG；关闭后恢复当前源样式或动画值。
- **视口缩放**：80% / 100% / 120%，配合「保持比例」比较视口适配。
- **源图对比**：两个视图共享文档并保留独立节点；动画对比会同步时间和播放状态。
- **背景**：网格用于观察透明区域，深色背景方便查看浅色图形。
- **播放控制**：播放、暂停、回到起点；可用鼠标拖动时间轴，或用左右方向键、Home/End 定位。无限动画以六秒窗口展示时间轴，当前文档时间仍持续累加。
- **查看与复用**：展示实际 SVG/FXML、JavaFX CSS 和 Kotlin 调用示例，支持复制当前源码。CSS 页的统一主题需通过「统一换色」启用。
- **资源加载**：选择「本地与网络资源」，观察后台加载、失败保留旧内容、清空和恢复本地资源。「恢复默认」返回蓝色、100%、网格背景及首个动画示例。

| 分类 | 示例 | 可观察内容 |
| --- | --- | --- |
| 动画 | 沿曲线运动 | mpath、切线旋转、匀速与 spline，支持暂停/定位 |
| 颜色与效果 | 图案填充与变换 | 圆点平铺、旋转条纹、viewBox 棋盘 |
| 颜色与效果 | 柔和与偏移投影 | 模糊阴影、零模糊偏移、颜色和透明度 |
| 文本与布局 | 沿路径排版 | 弧线/波浪字形、startOffset 与居中锚定 |
| 动画 | 加载与等待 | 圆弧旋转、错峰透明度、轨道运动与负 begin |
| 动画 | 呼吸与颜色过渡 | 缩放、透明度、颜色、线宽与 spline 缓动 |
| 动画 | 路径描边与进度 | 曲线绘制、环形进度、成功勾选及 keyTimes |
| 动画 | 变换与缓动 | 平移、旋转、缩放叠加、倾斜 |
| 动画 | 跳动圆点与半径 | cy/r 同步动画、脉冲圆环、椭圆半径变化 |
| 动画 | 音量柱与尺寸变化 | 底部对齐的高度动画、零宽恢复、动态圆角 |
| 动画 | CSS 关键帧动画 | 纯 CSS 旋转、呼吸、描边进度和 cubic-bezier |
| 动画 | CSS 时序与叠加 | 负延迟、alternate-reverse、steps、多个动画列表 |
| 图形 | 源 SVG | 文件中的原始填充、描边、渐变和 use |
| 图形 | 图形与描边 | 基本图形、曲线、圆角、端点、连接和虚线 |
| 图形 | 固定描边 | 普通描边与固定 3 个逻辑像素描边的缩放对比 |
| 图形 | 箭头与顶点标记 | 双向箭头、曲线顶点、固定尺寸及 context-stroke |
| 颜色与效果 | 主题与路径 CSS | 主题覆盖、独立路径 CSS、悬停状态及源图对比 |
| 颜色与效果 | 线性与径向渐变 | 渐变停止点、透明度及 reflect |
| 颜色与效果 | 几何裁剪 | 圆形裁剪及 even-odd 镂空 |
| 颜色与效果 | 透明与亮度遮罩 | 同一组颜色在 Alpha / luminance 中的差别 |
| 颜色与效果 | 高斯模糊 | 原图与 sRGB 模糊 |
| 文本与布局 | 文字、片段与定长 | 嵌套 tspan、样式、基线、间距及字形缩放 |
| 文本与布局 | 符号复用 | use/symbol、currentColor、组合变换 |
| 文本与布局 | 视口适配 | 嵌套 meet、slice、none |
| 资源 | 共享文档与独立样式 | 相同 source 共享文档，蓝/绿实例各自接受 CSS |
| 资源 | 直接从 FXML 使用 | 实际 icon.fxml、相对资源与样式表 |
| 资源 | 本地与网络资源 | 异步状态、失败回退、清空与本地缓存复用 |

默认动画随窗口显示自动播放。暂停、切换示例、隐藏窗口或关闭窗口后，相应时钟停止推进。示例切换会停止旧视图的动画。

离线快照和交互状态检查：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--smoke"
```

不显示窗口，也不访问网络。生成：

- `target/demo-preview.png`：蓝色、100%、1440px 宽屏，沿路径运动动画与源码并列。
- `target/demo-preview-themed.png`：绿色、120%，源图与路径 CSS 对比。
- `target/demo-preview-compact.png`：紫色、80%、960px 布局，深色背景与下方源码。
- `target/demo-preview-geometry.png`：圆点跳动、脉冲圆环与椭圆几何动画。
- `target/demo-preview-css.png`：纯 CSS 关键帧动画与其实际源文件。
- `target/demo-cases/*.png`：全部已注册示例的预览画布。

检查覆盖全部素材的零诊断、独立节点、共享文档、动画帧变化、主题与路径 CSS、源码页、横向边界、滚动和本地资源清空/恢复。需要 JavaFX 图形环境，Linux 无显示器环境可使用 xvfb-run。PNG 导出使用 Java2D，仅存在于测试示例中。

`--benchmark` 和 `--cache-visual-check` 入口继续使用原有参数；参见主 README 中的基准和缓存对照说明。

## 输入 SVG 代码实时查看

点击源码面板上方的「输入 SVG · 实时预览」，打开独立编辑窗口，初始内容为当前 SVG 示例；FXML 示例使用基础 SVG 模板。已打开窗口会保留当前输入。

- 左侧直接输入或粘贴完整 SVG，停止输入 300 ms 后自动刷新右侧预览。
- XML 解析在后台执行，旧请求的结果不会覆盖新输入；图形节点在 FX 线程创建。
- XML 错误显示行、列；无效几何、安全限制及像素/工作量超限仍显示错误并保留上一次有效预览，修正代码后自动恢复。
- `feTurbulence`、`feDisplacementMap`、`feMorphology` 实际渲染噪声、位移轮廓和膨胀/腐蚀效果；`advanced-filters.svg` 原样例的位移圆环与紫色光晕不再降级为未滤镜源图。
- `foreignObject` 使用原生 JavaFX 节点渲染 XHTML div/span/br 的有界内联 CSS 子集，包含原样例的圆角背景、边框、居中粗体与彩色片段。不是 WebView 或完整浏览器；其他 HTML 元素、flex/grid、定位、margin、外部样式表、图片和脚本等仍未实现，详见主 README 的边界说明。
- 尚未实现且明确允许降级的 `feUnsupported`（测试未知原语） 和 XHTML `p` 会标记「部分渲染」，分别保留未滤镜源图或跳过不支持的 HTML 元素。该提示使用精确诊断白名单，不把其他解析、脚本/事件属性、资源或预算错误当成兼容性警告。
- 像素及工作量预算仍生效：每视图默认 4,194,304 个滤镜中间像素槽、64 个原语、每轴 4096 像素及 2 亿估算分量操作；湍流最多 16 个 octave/4,000,000 像素，形态学半径最多 256 个采样像素，新增内核单次工作量最多 1.5 亿。放大可能触发这些限制，不会静默降采样。
- Ctrl+Enter 或「立即刷新」跳过等待；支持恢复载入示例、清空代码、复制 SVG 和代码换行。
- 保持比例、深色/网格背景、播放/暂停/回到起点均可直接操作。带动画的有效 SVG 默认自动播放。
- 关闭窗口会取消待处理任务、停止动画并释放预览文档。

编辑区使用标准 TextArea，支持选区、复制粘贴、撤销重做和输入法。javafx-controls 仅为 Demo/test 依赖，不增加库运行时依赖。

原样输入回归保留 `src/test/resources/svg/{advanced-filters,foreign-object,regular-filters}.svg`。测试检查高级效果在 1×/1.25×/1.5× 的严格模式像素结果、真实编辑器的零诊断状态、反复编辑和窗口调整、错误保留上一帧及修正后的恢复；输出 `target/advanced-filters-preview.png`、`target/foreign-object-preview.png` 和 `target/regular-filters-preview.png`，截图包含编辑器状态文字。

## 光照效果样例

在 SVG 代码实时预览中打开 `src/test/resources/svg/lighting-filters.svg`，可对比漫反射/镜面反射下的远光、点光、聚光三种光源。拖动缩放、修改 `surfaceScale` 或光源坐标后应重新渲染。两个光照原语原生处理 alpha 高度图，不使用 WebView；完整限制和参数说明见项目 README 的「光照滤镜」一节。

Linux 无显示环境可以执行 `mvn -Pheadless clean verify`；光照回归会在 `target/lighting-filters-1x.png` 保存实际渲染样例，在 `target/lighting-filters-editor-preview.png` 保存实时预览编辑器验证截图。

颜色矩阵与混合样例：将 `src/test/resources/svg/color-blend-filters.svg` 粘贴到实时预览器，检查四类颜色矩阵及五种混合模式。测试覆盖代码编辑、缩放和错误恢复。

卷积与重采样光照样例：`src/test/resources/svg/convolve-lighting-filters.svg`。测试覆盖实际编辑、非法 order 后恢复和重复缩放。


## 阶段 1–6：可操作展示

左侧选择 **「阶段 1–6」**，新增 11 个离线条目，原有分类/示例全部保留。预览不是 PNG：读取对应 SVG 并创建可缩放、可换色的真实 SvgView；源码页显示实际资源，点击「输入 SVG · 实时预览」可编辑当前条目。编辑窗口已打开时按钮会聚焦已有窗口；若要载入另一条目，请关闭编辑窗口、切换示例后重新打开，或直接粘贴另一个 SVG。

| 阶段 | 选择条目 | 资源与操作 |
| --- | --- | --- |
| 1 | 卷积与网格光照 | `svg/convolve-lighting-filters.svg`；锐化、浮雕、边缘与重采样光照 |
| 2 | 通道变换与混合 | `svg/extended-color-filters.svg`；通道映射和半透明混合 |
| 3 | 图像、平铺与模板 | `svg/filter-image-tile.svg`；文档片段、内嵌位图及模板 |
| 4 | 渐变与跨图块图案 | `svg/gradient-pattern-extensions.svg`；变换、fr 和显式图案溢出 |
| 5 | 逐字定位、CSS、滤镜动画 | `svg/positioned-text.svg`、`demo/showcase-roadmap-css.svg`、`svg/animated-filter-parameters.svg`；动画可播放/暂停/定位/回到起点 |
| 6 | 四个 Lucide 原样图标 | `svg/real-corpus/{activity,airplay,atom,cpu}.svg`；实际上游文件，完整许可和来源随目录交付 |

路径相对于 `src/test/resources`。代码、测试资源与 `demo` 文档必须一起保留：单独 `javafx-svg-1.0-SNAPSHOT.jar` 仅为库，不包含演示入口与素材。首次 Maven 运行需下载 Maven Central 依赖；依赖缓存齐全后可加 `-o` 离线运行。操作系统需可用的 JavaFX 图形桌面；Windows/GPU 未在本次 Linux 验证中验证，不能把启动说明当作该平台测试通过。

实际桌面应用自动冒烟（显示窗口、遍历所有条目、两轮切换新增条目、动画帧检查、生成完整界面截图并退出）：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--smoke-show"
```

Linux 无显示器可使用 `xvfb-run -a` 包裹同一命令；`--smoke` 保留既有不显示窗口模式。新增完整界面输出：`target/demo-roadmap-preview.png`、`target/demo-roadmap-animation.png`；全部案例快照位于 `target/demo-cases/`。单独编辑器回归 `SvgRoadmapDemoTest` 使用同一注册资源检查连续载入、窗口调整、动画、非法输入保留上一帧及恢复。真实样本不等于全部浏览器兼容或性能提升证明，性能/内存/安全的实测结论见 `benchmarks/roadmap-stage6` 及交付报告。

中文显示依赖系统字体。Windows 通常使用已有的 Microsoft YaHei；其他平台可通过 `--font-file=/绝对路径/中文字体.ttf`（也支持系统 `.ttc`）显式加载本地 UI 字体，不下载或嵌入字体。例如将 `-Dexec.args` 设为 `"--smoke-show --font-file=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"`。该开关只选演示 UI 字体，不扩展 SVG 嵌入字体支持。

本次云环境实际验证使用 Linux Monocle 软件图形环境：`-Pdemo,headless test-compile` 成功后，以测试运行所用的已核验 classpath 直接执行相同 `club.xiaojiawei.svg.demo.SvgDemoKt --smoke-show --font-file=...`，38 个注册示例全部通过。离线缓存未包含 exec-maven-plugin 3.5.0，因此本环境的 Maven `exec:java` 命令没有成功启动；其失败日志与成功直接启动日志均随证据交付，不能把二者混同。等效 Java 入口命令为 `java -cp "<编译后的 test-classes、classes 及测试依赖 classpath>" club.xiaojiawei.svg.demo.SvgDemoKt --smoke-show`；无显示器运行另需 headless profile 中的 Monocle 依赖及 `-Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw`。
