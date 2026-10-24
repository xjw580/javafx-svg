# JavaFX SVG 库对照：2026-10-24

> 测试更正：旧 creation.csv 在创建计时前仅预热一个实例，且版本间场景顺序、JIT 状态和重复次数未统一。旧创建耗时及分配对比降级为探索记录，不能证明稳定提速、回退或“无法继续下降”。相关结论撤回；后续以独立创建基准的复测报告为准。此更正针对创建比较，功能回归与渲染采样需按各自协议评价。


本文保留 eb0a6d2 的历史对照结果。后续已补齐本报告列出的 pattern、feDropShadow、textPath、animateMotion 四个缺口，当前 18 个样本全部通过，并完成批量构建、固定描边和原生旋转缓存优化。341 项回归及 27 项 Demo 检查通过；最新能力边界、性能数据与缓存代价见 [缺口修复报告](remediation-2026-10-24.md)。下表及旧 CSV 不改写为新版本成绩，不能据历史缺口判断当前支持范围。

后续 [纯变换动画优化](transform-fastpath-2026-10-24.md) 已完成；下表保留优化前版本与原始数据，当前实现的收益及波动见新报告。

本报告包括 2 轮、共 48 个性能场景，以及每种模式 18 个像素/动画样本。完整原始记录、SVG、PNG 和 JFR 位于本地 target/svg-library-comparison/；对照工具独立放在 [comparison](comparison/README.md)，第三方依赖不进入本库运行时。

## 版本与渲染方式

| 对象 | 本次版本 | 渲染方式 | 图形级 JavaFX CSS / FXML 集成 |
| --- | --- | --- | --- |
| 本库 | eb0a6d2，Kotlin 2.2.0 构建 | 原生 Shape、Text、Group 与独立 SvgView 时钟 | 源 id/class、统一主题、绑定；FXML 直接设置 source |
| fxsvgimage | 1.7.3，另带 CSS parser | SVGImage / Group 原生节点树 | 可操作返回的原生节点；加载后挂载，业务侧可包装控件 |
| JSVG JavaFX | jsvg + jsvg-javafx 2.2.0 | FXSVGCanvas，直接绘制 Canvas | Control/document 属性可绑定；内部 SVG 图形不是 JavaFX Shape 样式节点 |
| JSVG AWT | 同上，RenderBackend.AWT | AWT 渲染后显示像素结果 | 同一 Control API；图形样式在 SVG 模型中处理 |
| codecentric JavaFxSVG | 只查文档/源码，未运行 | Batik 转码，接入 JavaFX ImageLoader | 使用 Image/ImageView 接口，不能对位图内部路径施加 JavaFX CSS |

fxsvgimage 的返回类型、CSS parser 分包和发布版本见 [项目说明](https://github.com/hervegirod/fxsvgimage)、[1.7.3 发布](https://github.com/hervegirod/fxsvgimage/releases/tag/1.7.3)。JSVG 的 JavaFX 适配器仍标为实验 API，两种后端的渲染路径及限制见 [JSVG JavaFX 说明](https://github.com/weisJ/jsvg#javafx)，本次固定使用 [2.2.0 发布](https://github.com/weisJ/jsvg/releases/tag/v2.2.0)。

codecentric JavaFxSVG 采用旧式 com.sun.javafx.iio 内部接口，见 [工厂源码](https://github.com/codecentric/javafxsvg/blob/master/src/main/java/de/codecentric/centerdevice/javafxsvg/SvgImageLoaderFactory.java) 和 [项目用法](https://github.com/codecentric/javafxsvg)。本次没有迁移/运行其 JavaFX 21 适配，不填写性能数值；Batik 引擎的能力也不能直接等同于该 ImageLoader 的集成功能。

## 特性：实际样本结果

“通过”只表示本次代表性样本的像素或动画变化满足探针，不等于完整支持该元素所有属性。“未通过”表示画面不满足预期，不能推断整个元素完全没有实现。原生节点与 Canvas 的抗锯齿、字体和插值细节可能不同。

| 样本 | 本库 | fxsvgimage | JSVG JavaFX | JSVG AWT |
| --- | --- | --- | --- | --- |
| path 纯色 | 通过 | 通过 | 通过 | 通过 |
| 线性渐变 | 通过 | 通过 | 通过 | 通过 |
| clipPath | 通过 | 通过 | 通过 | 通过 |
| alpha mask | 通过 | 未通过 | 通过 | 通过 |
| sRGB 高斯模糊 | 通过 | 通过 | 通过 | 通过 |
| 两色 tspan | 通过 | 未通过 | 通过 | 通过 |
| userSpaceOnUse 箭头 marker | 通过 | 未通过 | 通过 | 通过 |
| 缩放下 non-scaling-stroke | 通过 | 未通过 | 通过 | 通过 |
| 内部 style/class 换色 | 通过 | 通过 | 通过 | 通过 |
| SMIL 旋转，2s | 通过 | 通过 | 通过 | 通过 |
| SMIL 半径，2s | 通过 | 通过 | 通过 | 通过 |
| SMIL 半径，1500ms | 通过 | 通过 | 通过 | 通过 |
| SMIL 半径，1.5s | 通过 | 未通过 | 通过 | 通过 |
| CSS @keyframes 换色 | 通过 | 未通过 | 未通过 | 未通过 |
| pattern 平铺 | 不支持，诊断拒收 | 未通过 | 通过 | 通过 |
| feDropShadow | 不支持，诊断拒收 | 通过 | 通过 | 通过 |
| textPath | 不支持，诊断拒收 | 未通过 | 通过 | 通过 |
| animateMotion | 不支持，诊断拒收 | 通过 | 未通过 | 未通过 |

[特性 CSV](library-comparison-2026-10-24-features.csv) 保留原始 PASS/FAIL/ERROR。本库后四项由公开 diagnostics 报告未支持，工具将其记为 ERROR；不是探测进程崩溃。其他未通过项保留 SVG 和画面对照，尤其 fxsvgimage 的 tspan 和 marker 本身有实现，本表只指出当前样本中的颜色/位置问题。

动画样本在约 0.2–0.8 秒间采集多个相位，选取变化最大的画面作对照，避免两个相同外观的端点误判为没有播放。fxsvgimage 1.7.3 源码中的时长解析采用整数格式，该样本里 1.5s 未播放，而 1500ms 正常；共同性能场景统一使用 2s。

文档层面的范围也应保留：fxsvgimage 明确列有 animateMotion 与多种滤镜，见 [动画说明](https://fxsvgimage.sourceforge.io/articles/article_animations.html)、[滤镜说明](https://fxsvgimage.sourceforge.io/articles/article_filters.html)；JSVG 的 [能力表](https://github.com/weisJ/jsvg#supported-features) 覆盖 pattern、textPath 及多种滤镜，但动画为部分实现。这次几个样本不能替代这些完整的限制说明。

## 性能测量方法

- Windows 11；Ryzen 9 5900X、Radeon RX 7900 XT（系统报告驱动 32.0.31041.1004）；JDK 25.0.1、JavaFX 21.0.8+2；逻辑屏幕 2560×1440，缩放 1.5。
- 各模式独立 JVM，固定 512 MiB 堆；JFR profile 与分配采样始终启用。第二轮逆序运行各模式。
- 每个模式各测 100/500 图标 × path/gradient/SMIL 旋转，共 6 场景；每场景预热 2 秒、测量 5 秒。24px 图标全部可见，没有虚拟化。
- 所有模式使用相同 SVG XML，每视图独立解析，关闭文档/资源复用及节点缓存。加载计时是 FX 线程上的“解析＋节点构建＋挂载”，不含随后的 CSS/layout/首绘，也不是冷 JVM 启动时间。本库与 JSVG 的共享文档优势没有在此模式中使用。
- path/gradient 是固定 24px 宿主上的相同外部 Rotate；Canvas 可复用已绘制表面。SMIL 则使用 SVG 内部动画与各库自己的播放实现，需要内部更新。使用非对称的 4 条矩形路径，避免旋转对称造成变化检测误判。
- 性能场景在开始正式计数前检查画面变化；两轮 48 个场景均通过，没有用静态漏画的场景填充性能数据。
- pulse 不是呈现 FPS 或 GPU 耗时；FX CPU/pulse 为线程计数器估计值，分配为近似 MXBean 计数，不是最终保留内存。GC 毫秒是收集时间。未测 GPU 时间或原生内存。

窗口状态必须单列：第一轮本库后续场景失焦，第二轮其他库出现失焦。因此主表按“整轮全部场景 unfocused_samples=0”筛选：本库统一取第 2 轮，其余统一取第 1 轮，不挑各自最快结果。主表各行均未失焦、未最小化；焦点仍不能证明没有局部遮挡。两轮完整数据均保留，短测无置信区间，以下用于当前样本观察。

## 500 图标：正式结果

| 场景 | 模式 | pulse P95 ms | FX CPU ms/pulse | FX KiB/pulse |
| --- | --- | ---: | ---: | ---: |
| 纯色外部旋转 | 本库 | 48.60 | 3.57 | 157.4 |
| 纯色外部旋转 | fxsvgimage | 10.67 | 2.01 | 157.2 |
| 纯色外部旋转 | JSVG JavaFX | 10.28 | 0.95 | 32.3 |
| 纯色外部旋转 | JSVG AWT | 10.34 | 0.91 | 16.6 |
| 渐变外部旋转 | 本库 | 112.17 | 4.51 | 157.4 |
| 渐变外部旋转 | fxsvgimage | 75.69 | 1.84 | 157.4 |
| 渐变外部旋转 | JSVG JavaFX | 10.26 | 1.02 | 32.3 |
| 渐变外部旋转 | JSVG AWT | 10.32 | 0.85 | 16.8 |
| SVG 内部 SMIL 旋转 | 本库 | 32.34 | 5.66 | 774.1 |
| SVG 内部 SMIL 旋转 | fxsvgimage | 11.18 | 1.71 | 153.3 |
| SVG 内部 SMIL 旋转 | JSVG JavaFX | 64.51 | 7.81 | 9901.1 |
| SVG 内部 SMIL 旋转 | JSVG AWT | 25.79 | 13.09 | 5958.9 |

外部旋转中 Canvas 的较低开销来自复用绘制结果；它不代表 Canvas 在相同成本下完成了每帧 SVG 重绘，放大时的画质也没有在此测量。SMIL 场景更能体现内部更新：本库每 pulse FX CPU 高于 fxsvgimage，但分配低于 JSVG 两种后端；本库没有全方面性能优势。

本库在 500 个渐变图标外部旋转时 P95 约 112 ms，FX CPU/pulse 约 4.51 ms。该间隔还包含渲染管线及调度影响，不能只归因于 XML 或 FX 布局。500 个完全可见图标属于压力样本，实际界面应控制同时播放数量，并按场景考虑节点缓存及画质代价。

## 500 图标：创建成本

下表为两轮原始创建耗时范围，单位 ms；表中没有包含 CSS/layout/首绘。本库与 JSVG 可以复用文档，实际重复 source 的缓存场景应另测，不能把这组独立解析数据当成所有使用方式。

| 模式 | 纯色 | 渐变 | SMIL 旋转 |
| --- | ---: | ---: | ---: |
| 本库 | 245.0–263.6 | 333.8–341.5 | 221.7–223.0 |
| fxsvgimage | 177.8–180.8 | 154.1–156.5 | 131.9–147.5 |
| JSVG JavaFX | 95.7–130.7 | 118.9–123.6 | 113.5–119.8 |
| JSVG AWT | 126.4–130.3 | 115.2–125.3 | 124.3–126.9 |

JSVG 每个 Control 的像素面绘制主要在后续阶段发生，因此创建更轻并不意味着全部首绘成本也低。对应原生渲染子树在当前样本约为本库 8 节点/图标、fxsvgimage 6、JSVG 在 CSS/layout 后计数为 2；宿主 Pane、Canvas 延迟创建的内部绘制对象、AWT 及纹理占用不在这一计数中，不据此排名内存。

## 结论与改进优先级

- 本库应优先优化批量解析/建节点成本，以及纯变换动画时进入通用时钟/布局刷新流程的开销。现有共享 SvgDocument、资源缓存仍应作为批量使用方式的一部分，另建复用模式后再评价真实业务收益。
- 对需要 pattern、textPath 或多种滤镜的文件，JSVG 在这组样本中覆盖更广；JavaFX 后端仍是实验适配层，复杂文件需要同时验证 AWT 后端。
- fxsvgimage 对基本旋转和路径运动有实用价值；当前两色文字、marker、固定描边和小数时长样本需逐个验证。
- 本库的价值主要在原生控件集成、应用 CSS/绑定/统一主题、异步网络 source 生命周期与已有 CSS/SMIL 播放控制，不能以此替代复杂 SVG 兼容性或默认性能优势的证明。

这些结论来自当前版本和样本，不构成全规范测试。后续实现优先级应由真实文件决定；本次没有为第三方差异引入任何运行时依赖或临时修改 SVG 内容来隐藏失败。

## 复现与数据

完整步骤见 [工具说明](comparison/README.md)。依赖下载地址和 SHA-256 固定在 dependencies.json，每轮还记录本库 JAR 哈希及环境信息。源 SVG 以 UTF-8 内存内容输入，正式测量的同场景文件已按哈希检查一致。

- [性能第一轮](library-comparison-2026-10-24-run1.csv)、[性能第二轮](library-comparison-2026-10-24-run2.csv)
- [创建第一轮](library-comparison-2026-10-24-creation-run1.csv)、[创建第二轮](library-comparison-2026-10-24-creation-run2.csv)
- [18 项特性 × 4 模式](library-comparison-2026-10-24-features.csv)

本库原有性能优化与生命周期验证另见 [动画报告](animation-2026-10-24.md)：长度缓存分配减少、最小化暂停、播放回调重入和旧节点回收已验证，完整 Maven verify 为 297 项通过。跨库工具使用独立 javac 编译与原生 JavaFX 窗口运行，不计入 JUnit 数量。
