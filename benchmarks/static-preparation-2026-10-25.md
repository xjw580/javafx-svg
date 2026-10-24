# 静态 SVG 加载分配优化与跨库复测：2026-10-25

**分配已明显下降，但完整光栅耗时尚未稳定改善，仍未达到整体持平 fxsvgimage 的目标。** 500 图标的 FX 分配由纯色 93.62 MiB / 渐变 129.81 MiB 降至 61.75 / 77.74 MiB，减少约 34% / 40%，低于本轮 fxsvgimage 的 79.21 / 81.47 MiB；仍高于 JSVG JavaFX。当前耗时中位数为 63.82 / 124.39 ms，fxsvgimage 为 53.11 / 95.69 ms。

## 实现与验证

核心提交：e613436。没有改变渲染后端、缓存策略、场景图层级或对外 API。

- URL 引用正则与常用元素集合只初始化一次。
- 仅在父样式带动画时收集继承属性，直接构建一次计算样式映射，去除中间映射复制。
- 静态元素跳过 CSS 变换原点与矩阵准备，继续校验 transform-box，并保留显式单位变换以供后续修改。
- 字符串入口直接使用 UTF-8 字节解析，避免经 InputStream.readNBytes 再复制；流入口的读取上限、异常包装与所有权保持原有行为。
- 有填充且没有有效描边的 SVGPath 使用自身 layoutBounds 获取几何边界，避免临时路径对象和重复解析；有描边或无填充时继续使用原有几何计算。

隔离 Maven verify：364 项通过，0 失败/错误/跳过。新增测试覆盖 UTF-8 精确字节上限、调用方流未关闭、读取异常、失败后重试、几何边界排除裁剪/效果/变换/描边及内容更新、静态变换诊断与显式单位矩阵。既有动画、CSS、FXML、缩放、非缩放描边测试通过。另用当前 JAR 重新执行 20 项代表性像素/动画探针，20/20 PASS；这些探针不是完整规范覆盖率，也不代表复杂素材的视觉一致性已全部验证。

改前 JAR（核心 9b5ee35）SHA-256：`E850B0491939E8EB1CE6A43C1F64DAB409F2CF7CC4B316D8D813C5D1AC4C3BDA`。
改后 JAR SHA-256：`1E09B83F0C62D0D87BFD363098CBE9620D91937A3AC8874CAEDADE9F645007CD`。
构建沿用当前工作区 Kotlin 2.2.0 配置；未将工作区原有 POM 改动纳入本次提交。

## 正式完整光栅比较

同机、JDK 25.0.1、JavaFX 21.0.8+2、512 MiB 固定堆和相同完整依赖类路径。每批 500 个 24px 图标，从同一内存 XML 独立解析；关闭自动播放、应用 Node.cache 与 JFR，内部缓存使用库默认策略，不强制 GC。每类型至少预热 5 批且持续 10 秒，再测 5 批。测量期间不并行构建、测试或采样。

先顺序完成改前本库 3 个新 JVM，再运行改后本库、fxsvgimage 1.7.3、JSVG JavaFX 2.2.0 各 3 个新 JVM；后组轮换库的顺序，偶数轮反转场景。前后并非逐进程交错配对；系统状态及 JIT/GC 波动仍可影响结果。本轮改前耗时与历史记录存在明显波动，不能用历史报告的 wall 直接计算本次优化收益。

计时包含新 Scene、独立解析、构建、CSS/layout、延迟 skin/Canvas、异步 740×650 逻辑像素快照和回读；计时结束后验证所有图标像素。12 个正式进程共 120 个测量批次及全部预热验证完成，输入 SVG 哈希一致。它表示完整光栅结果就绪成本，**不是屏幕实际呈现、纯 GPU 时间或冷 JVM 启动**。

先取每进程 5 批中位数，再取 3 个独立进程中位数 [最小–最大]。CPU 计数器粒度较粗，不能将相同读数解释为精确相同成本。分配是区间 Java 堆分配，不是保留内存、原生内存或显存。

| 类型 | 模式 | wall ms/500 | FX CPU ms | 进程 CPU ms | FX 分配 MiB | 总线程分配 MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| path | 本库改前 | 65.24 [62.64–71.20] | 46.88 | 62.50 | 93.62 | 96.18 |
| path | 本库改后 | 63.82 [52.55–82.32] | 46.88 | 62.50 | 61.75 | 64.30 |
| path | fxsvgimage 1.7.3 | 53.11 [52.22–54.10] | 46.88 | 46.88 | 79.21 | 81.72 |
| path | JSVG JavaFX 2.2 | 153.62 [146.63–169.38] | 46.88 | 156.25 | 48.65 | 51.27 |
| gradient | 本库改前 | 111.67 [107.18–125.55] | 62.50 | 125.00 | 129.81 | 132.36 |
| gradient | 本库改后 | 124.39 [89.95–141.95] | 62.50 | 125.00 | 77.74 | 80.29 |
| gradient | fxsvgimage 1.7.3 | 95.69 [94.63–105.79] | 46.88 | 93.75 | 81.47 | 84.12 |
| gradient | JSVG JavaFX 2.2 | 164.64 [163.97–167.73] | 46.88 | 156.25 | 54.80 | 57.42 |

纯色 wall 中位数略降，渐变中位数反而升高，FX CPU 中位数均未变；范围重叠、进程间差异较大，**不能声称本次已证明耗时优化或与 fxsvgimage 持平**。本库两个样本的 wall 低于 JSVG JavaFX，后者分配仍更少，不能扩展为全面优于 JSVG。

改前 1/6、改后对照组 1/18 个类型/进程组合触发趋势提示（分别为改前 r3-native/path、改后 r2-native/path）；所有原始行均保留，未筛选最快进程。固定预热不是稳态保证。

改后本库每进程 5 批的 GC 累计读数：纯色 12–16 ms、渐变 23–26 ms；fxsvgimage 分别为 1–2 / 3 ms。本库分配更少却仍有更高 GC 累计成本；当前数据没有确定对象存活情况和 GC 原因，不能直接认定泄漏或把全部 wall 差距归因于 GC。GC MXBean 毫秒也不是精确暂停测量。

## 静态空闲与独立诊断

空闲阶段显示最后一批场景，设备缩放 1.5，预热 3 秒、测量 5 秒，无 AnimationTimer、FX 轮询或快照，后台只在区间结束读一次计数。改后组 18/18 区间全程聚焦、未最小化；六种库/类型组合的 FX CPU 中位数均为 0 ms/s，表示低于本次分辨率，不表示绝对零开销。本库 CPU 最大读数约 3.125 ms/s；分配随离散事件波动，完整范围在 idle-summary.csv。当前短区间数据不支持空闲成本排名，也无法排除遮挡和其他系统活动。

正式测量结束后，单独对改前、改后各录制一个 JFR 诊断进程（改前录制早于正式比较）。每次仅分析 complete=true、warmup=false 的 10 个 RasterBenchmarkPhase 区间；预热、像素验证、写盘和空闲不混入。诊断数据不并入上表。

原先静态变换原点准备、URL 引用正则编译、继承集合及样式映射复制等主要分配栈在改后摘要中不再出现。改后仍观察到渐变样式计算、节点/peer 创建、SvgBox.transform 的正则编译、缓存层级监听重建；CPU 样本还涉及 JavaFX CSS/边界计算、Prism 纹理上传/回读。样本数量少，ObjectAllocationSample 的 weight 也可能高度离散，不能用栈上的权重计算精确分配比例或 CPU 占比。

下一步应排查缓存层级监听的重复重建、渐变重复样式计算以及场景图与 GC 成本；需要分阶段计数和对象存活证据，不能继续只削减分配就宣布解决耗时问题。本轮未重跑动画稳态性能或创建 JMH，相关历史成绩没有升级为当前成绩。

## 数据和复现

- [改前独立进程](static-preparation-2026-10-25-before-raster-forks.csv)、[全部批次](static-preparation-2026-10-25-before-raster-all.csv)、[汇总](static-preparation-2026-10-25-before-raster-summary.csv)、[趋势提示](static-preparation-2026-10-25-before-warnings.txt)
- [改后三库独立进程](static-preparation-2026-10-25-after-raster-forks.csv)、[全部批次](static-preparation-2026-10-25-after-raster-all.csv)、[汇总](static-preparation-2026-10-25-after-raster-summary.csv)、[趋势提示](static-preparation-2026-10-25-after-warnings.txt)
- [空闲汇总](static-preparation-2026-10-25-after-idle-summary.csv)、[全部空闲及焦点](static-preparation-2026-10-25-after-idle-all.csv)、[20 项探针](static-preparation-2026-10-25-features.csv)
- [改前 JFR 摘要](static-preparation-2026-10-25-profile-before.txt)、[改后 JFR 摘要](static-preparation-2026-10-25-profile-after.txt)、[分析器源码](comparison/DisplayProfile.java)
- [改前协议](static-preparation-2026-10-25-before-protocol.txt)、[改后协议](static-preparation-2026-10-25-after-protocol.txt)、[改后计划](static-preparation-2026-10-25-after-plan.csv)、[全部环境](static-preparation-2026-10-25-environment.txt)、[依赖/JAR 哈希](static-preparation-2026-10-25-after-sha256.txt)、[输入哈希](static-preparation-2026-10-25-svg-sha256.csv)

完整本地产物：`target/static-fastpath-20261025`，包括两版 JAR、隔离构建、SVG/PNG、完整 JFR、源码副本、逐进程日志和完成标记。大文件未提交仓库。

~~~powershell
./benchmarks/comparison/display-run.ps1 -JavaHome 'S:/jdk25.0.1_bellsoft' -NativeJar '改前.jar' -Providers native -Output target/recheck-before
./benchmarks/comparison/display-run.ps1 -JavaHome 'S:/jdk25.0.1_bellsoft' -NativeJar '改后.jar' -Output target/recheck-after
./benchmarks/comparison/display-analyze.ps1 -InputDirectory target/recheck-before
./benchmarks/comparison/display-analyze.ps1 -InputDirectory target/recheck-after
~~~

诊断复现使用独立输出目录并增加 `-Providers native -Repeats 1 -RecordJfr`，仅诊断汇总可显式使用 `-AllowJfr -ExpectedRepeats 1`。用 JDK 21+ 编译 DisplayProfile.java 到单独目录，再运行 `java -cp 分析器目录 DisplayProfile 路径/display.jfr` 即可提取上述区间摘要；不要将诊断 wall 与正式数据混合比较。
