# 纯变换动画优化：2026-10-24

已为不依赖额外布局的 SMIL/CSS 变换增加直接更新路径。500 图标基准中，SMIL 每 pulse 的 FX 线程分配量减少约 65%，CSS 变换减少约 42%，两轮结果接近。CPU 与 pulse 间隔仍有明显波动，不能把分配收益等同于稳定的帧率提升。

## 实现与边界

- 文档仅包含变换动画、完成过完整布局、没有诊断，且 SvgView 当前不需要布局时，直接更新既有 Affine，跳过本次 SvgView 的几何、绘制属性和完整布局刷新。JavaFX 自身的脏节点处理和必要的 Group 布局仍由 JavaFX 执行。
- 依据已创建的动画绑定和效果回调判断，不用查找 XML 字符串猜测能力。源 SVG 的实际引用和 CSS 轨道同样参与判断。
- 混合样式/几何动画、文字布局、CSS fill-box、固定描边、marker、滤镜、遮罩和 objectBoundingBox 裁剪保留完整流程。静态 userSpaceOnUse 裁剪可以随原生节点变换直接更新。
- resize、preserveRatio、主题或 CSS 触发布局时回到完整布局；完成后可恢复快速路径。完整布局异常或出现诊断时，避免直接更新尚未完成的内容。
- 每个变换绑定复用自己的计算矩阵，SMIL 使用 appendRotation，省去临时 Rotate；矩阵有限性检查不再构造装箱列表。SMIL 的颜色/向量端点及关键帧位置预计算，百分比和动态底层值仍按当前帧解析。缓存属于渲染实例，未把插值结果写入共享文档。
- 先准备时钟并发布 animationTime，再更新节点矩阵；时间和矩阵监听器发出的新控制命令或文档替换都有版本保护。暂停、终点重播、再次 seek 及替换文档保持一致。

公开 SvgView/FXML/CSS 接口保持不变；每视图仍有独立时钟。该优化针对 SVG 内部变换动画，外部旋转数百个渐变图标的渲染管线开销仍需单独调查。

## 验证

Kotlin 2.2.0、JavaFX 21.0.8+2、JDK 25.0.1 隔离构建，Maven verify 共 **311 项通过**，失败/错误/跳过均为 0。新增 14 项回归覆盖：

- SMIL/CSS 连续 seek、倒退、循环回绕、延迟和动画移除后恢复源矩阵。
- 快速帧与强制完整布局的像素一致性；共享文档的矩阵独立性。
- 实际 AnimationTimer 推进过程中，纯变换没有主动触发 SvgView 布局。
- resize、比例设置、主题、应用 CSS、属性绑定及初始布局回退。
- 固定描边、marker、滤镜、遮罩、裁剪、文字、fill-box 和混合轨道保留完整更新。
- 布局中断后的恢复、百分比/底层值变更，以及时间/矩阵监听器中的暂停、重播、seek 和文档替换。

现有文字、遮罩、滤镜、固定描边及异步资源等回归仍通过。

## 测量方式

修改前使用 b6eff1c 所对应运行时代码的原始构建产物，修改后使用上述 311 项回归通过的最终产物。固定同一机器与参数：Windows 11，Ryzen 9 5900X / Radeon RX 7900 XT，JavaFX 21.0.8，JDK 25.0.1，512 MiB 堆，-Djavafx.animation.pulse=60，JFR profile、分配 throttle 300/s。逻辑屏幕 2560×1440，缩放 1.5。

使用两套已有程序，各做修改前一轮、最终修改后两轮，共 30 个正式场景：

1. 动画基准：100/500 图标 × SMIL 变换、CSS 变换、固定描边组合、暂停对照，每轮 8 个场景。共享一个文档，1.5 秒动画。
2. 跨库工具的 native 模式：100/500 图标的相同 SMIL 旋转，每轮 2 个场景。每图标独立解析，沿用 2 秒非对称路径样本。

均为 25 列、24px 图标、28px 间隔，500 个全部可见，节点缓存关闭；预热 2 秒、测量 5 秒。创建、预热、截图与写盘排除在完整 JFR 测量阶段之外。正式采样期间，全部行均未失焦或最小化；焦点计数不能证明没有局部遮挡。

pulse 为 AnimationTimer 间隔，不是呈现 FPS 或 GPU 时间。FX CPU/pulse 和每 pulse 分配量是测量区间的线程计数器增量除以样本数，分配计数近似。每秒分配量同时受实际 pulse 频率影响，因此主表使用每 pulse 数据。短测存在 JIT、GC、JavaFX 渲染线程和窗口系统调度波动，没有统计置信区间。

## 500 图标：共享文档的动画基准

| 场景 | 阶段 | FX CPU ms/pulse | FX KiB/pulse | pulse P95 ms |
| --- | --- | ---: | ---: | ---: |
| SMIL 旋转 | 修改前 | 4.46 | 844.8 | 32.36 |
| SMIL 旋转 | 最终第一轮 | 3.12 | 294.5 | 17.06 |
| SMIL 旋转 | 最终第二轮 | 3.31 | 294.6 | 32.02 |
| CSS 旋转 | 修改前 | 4.39 | 649.5 | 32.22 |
| CSS 旋转 | 最终第一轮 | 2.55 | 376.5 | 16.89 |
| CSS 旋转 | 最终第二轮 | 5.19 | 376.6 | 32.06 |

SMIL 的分配约从 845 KiB/pulse 降至 295 KiB/pulse，CSS 变换从 649 KiB/pulse 降至 377 KiB/pulse。CPU 与脉冲间隔的收益没有同等稳定性：CSS 变换第二轮 FX CPU 高于基线，SMIL 第二轮 pulse P95 接近基线。因此这里仅确认代码路径与分配方面的稳定收益。

固定描边组合继续回退到完整流程，500 个图标仍有每 pulse 约 27 MiB 的 FX 分配，描边轮廓重建成本没有由本次快速路径解决；暂停对照的文档时间保持为零。完整控制场景保留在 CSV 中。

## 500 图标：独立解析的 SMIL 场景

| 阶段 | FX CPU ms/pulse | FX KiB/pulse | pulse P95 ms |
| --- | ---: | ---: | ---: |
| 修改前 | 5.00 | 808.9 | 32.18 |
| 最终第一轮 | 3.16 | 232.8 | 16.75 |
| 最终第二轮 | 5.30 | 233.1 | 32.54 |

这一组直接复用跨库对照的 SVG 和参数。本次修改前重新采样的 CPU 为约 5.00 ms/pulse；两次最终采样约为 3.16 和 5.30 ms/pulse，不能据此确认稳定接近 fxsvgimage 的性能。分配约从 809 KiB/pulse 降至 233 KiB/pulse，两轮均减少约 71%。

历史跨库报告中的 5.66 ms/pulse 属于此前另一轮记录，不与本次结果混算收益。

## JFR 与后续方向

按 AnimationBenchmarkPhase 的完整测量区间分析 FX 线程，排除创建与预热。基线 SMIL 500 图标的主要分配包含 SvgAnimations 生成的 Affine、SvgAnimation.append 中的 Rotate，以及 Curve.sample 的列表和装箱值；最终两轮该场景的主要分配列表不再包含这些项目，库内仍有插值向量和 Duration，更多分配来自 JavaFX 路径处理。

采样权重并非精确分配计数；“最接近的库栈帧”也不是排他耗时。精确到方法的归因不能替代主表的 MXBean 总量。已通过实际时钟与像素测试证明纯变换跳过 SvgView 布局，分配变化与移除的对象构造相符。

下一阶段应调查渲染同步、路径栅格化，以及批量独立构建/文档复用的真实业务成本，再决定进一步优化。现阶段不宣称所有 SVG 动画都变快或跨库排名已经改变。

## 数据与复跑

~~~shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--animation-benchmark --kinds=smil-transform,css-transform,css-fixed,paused --counts=100,500 --warmup-seconds=2 --measure-seconds=5"
~~~

严格对照使用编译后的测试 classpath 和独立 JVM，传入上述相同堆、pulse 和 JFR 参数；Maven exec 命令便于交互复跑，但复用 Maven JVM。跨库同场景使用 [独立工具](comparison/README.md) 的 -Mode benchmark -Providers native -Kinds smil-transform。

- [动画基线](transform-fastpath-2026-10-24-before.csv)、[最终第一轮](transform-fastpath-2026-10-24-after.csv)、[最终第二轮](transform-fastpath-2026-10-24-repeat.csv)
- [同场景基线](transform-fastpath-2026-10-24-cross-before.csv)、[最终第一轮](transform-fastpath-2026-10-24-cross-after.csv)、[最终第二轮](transform-fastpath-2026-10-24-cross-repeat.csv)

本地完整记录归档于 target/svg-verification/transform-fastpath/，包含各轮 environment、JFR、逐 pulse CSV、PNG、完成标记、隔离源码与构建日志。开发中间采样也保留于归档，但主表只采用 before、final、final-repeat 及对应 cross 目录。
