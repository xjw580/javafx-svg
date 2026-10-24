# 缓存监听与渐变复用：四轮交错复测（2026-10-25）

**本轮两个静态样本的耗时中位数接近 fxsvgimage；相对改前的提速仍未得到稳定证明，分配下降更明确。** 500 图标的完整光栅结果：本库纯色 53.01 ms、渐变 104.28 ms，fxsvgimage 为 53.75 / 112.16 ms，JSVG JavaFX 为 157.39 / 159.83 ms。进程间范围较宽，不能将中位数或范围重叠解释为稳定持平、全面更快。

## 本次改动与验证

- 6088195：缓存层级监听增量更新。保留仍在祖先链中的 LocalScale，只移除离开的节点、加入新增节点；几何边界监听仅在启用状态变化时绑定/解绑。保留重挂载、Scene/窗口变化、DPI 与像素预算检查。
- be08c64：每个渲染实例按 id 复用渐变引用链、合并属性、颜色停止点定义及解析变换；仅缓存最近一次透明度的停止点列表，不按动画每个透明度无限累积。视口、几何边界与最终渐变坐标仍按当前实例重新计算。未改场景图层级、渲染后端、公开 API 或按需缓存策略。
- 36f29c7：正式工具支持改前 JAR 与改后/第三方一起轮换，逐进程记录计划哈希及实际类加载来源。
- d459385：JFR 诊断模式额外记录创建、CSS/layout 和快照就绪三个分段；正式比较仍关闭这些诊断计数及 JFR。

隔离 Maven verify：369 项通过，0 失败/错误/跳过。新增测试涵盖共享祖先下反复重挂载、离开分支不再影响缓存、Scene 根替换及重新挂载、缓存开关；渐变的不同边界/视口/透明度、共享文档的实例独立性、继承透明度动画的倒放、多用户共享引用、空/单停止点早返回与循环/缺失引用。既有 CSS/FXML、动画、缩放、描边、裁剪等测试通过。当前 JAR 另测 20 项代表性像素/动画探针，20/20 PASS；不是完整规范覆盖率或复杂素材的全面视觉验证。

改前 JAR（核心 e613436）SHA-256：`1E09B83F0C62D0D87BFD363098CBE9620D91937A3AC8874CAEDADE9F645007CD`。
改后 JAR（核心 be08c64）SHA-256：`2396CD9B0C12FDA388A698E7A16A1CC0F874BC4D4A8365D0BDC6E5A036019156`。
构建沿用当前工作区 Kotlin 2.2.0 配置，未提交原有 POM、IDE 和其他暂存改动。两项核心改动本轮合并测量，没有分别运行完整消融组，不能按总体差值精确分配各自收益。

## 正式完整光栅比较

相同机器、JDK 25.0.1、JavaFX 21.0.8+2、512 MiB 固定堆、同一组依赖；native-before 仅替换类路径中的本库 JAR。fxsvgimage 1.7.3、JSVG JavaFX 2.2.0 固定版本及哈希。四模式各四个独立 JVM，每轮轮换次序，各自恰好占一次第 1/2/3/4 位；偶数轮反转 path/gradient。实际加载的本库类来源逐一与计划 JAR 哈希核对。

每批 500 个独立文档、24px 图标，从同一内存 XML 解析。至少 5 批且持续 10 秒预热，再测 5 批；关闭自动播放、应用 Node.cache 和 JFR，不强制 GC，内部缓存使用库默认策略。测量期间不并行编译、测试或分析采样。相同 SVG 输入哈希已跨全部 16 个进程核对；160 个正式批次及全部预热的图标像素验证完成。

计时包含新 Scene、解析/创建/挂载、CSS/layout、异步 740×650 逻辑像素快照与回读；验证和写盘在区间外。这是完整光栅结果就绪成本，不是屏幕实际呈现、纯 GPU 时间或冷 JVM 启动。

先取每进程 5 批中位数，再取四个独立进程中位数 [最小–最大]；偶数重复的中位数是中间两值的平均。FX CPU 计数器较粗，不能将相同读数视为成本精确相等。分配指区间 Java 堆分配，不是保留堆、原生内存或显存。

| 类型 | 模式 | wall ms/500 | FX CPU ms | 进程 CPU ms | FX 分配 MiB | 总线程分配 MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| path | 本库改前 | 54.17 [47.10–60.01] | 39.06 | 62.50 | 61.79 | 64.35 |
| path | 本库改后 | 53.01 [47.87–71.54] | 39.06 | 54.69 | 58.59 | 61.15 |
| path | fxsvgimage | 53.75 [51.77–71.14] | 46.88 | 54.69 | 79.21 | 81.71 |
| path | JSVG JavaFX | 157.39 [143.93–170.41] | 46.88 | 156.25 | 48.64 | 51.26 |
| gradient | 本库改前 | 104.93 [92.28–112.54] | 46.88 | 109.38 | 78.22 | 80.77 |
| gradient | 本库改后 | 104.28 [85.06–111.88] | 54.69 | 101.56 | 71.84 | 74.39 |
| gradient | fxsvgimage | 112.16 [93.98–121.29] | 54.69 | 101.56 | 81.57 | 84.21 |
| gradient | JSVG JavaFX | 159.83 [149.63–178.09] | 46.88 | 156.25 | 54.82 | 57.43 |

本库前后 wall 中位数仅变化约 -2.1% / -0.6%。按轮查看，纯色前三轮分别变化 +19.2%、+13.3%、-15.8%，第四轮 +1.6%；渐变约 -0.6%、-4.4%、-7.8%、+3.0%。次序平衡仍无法消除所有系统、JIT/GC 和驱动波动，因此**未证明稳定提速，也未证明已整体完成性能目标**。渐变 FX CPU 中位数本轮没有下降，不宣称更省 FX CPU。

FX 分配下降约 5.2% / 8.2%，四轮分别均下降。本库两个样本的分配均低于 fxsvgimage，仍高于 JSVG JavaFX；不能称为更省保留内存或全面优于 JSVG。与历史报告不同批次的 wall 不用于计算本次收益。

4/32 个类型/进程组合触发趋势提示，全部保留：r2-native-before/path、r4-jsvg-fx/path、r4-native-before/path、r4-native/path。固定预热不保证稳态；每轮原始结果与趋势提示均已归档，未挑选最快结果。

## 静态空闲

显示最后一批，设备缩放 1.5，预热 3 秒、测量 5 秒，无 AnimationTimer/FX 轮询/快照，后台只在结束读一次计数。32 次尝试中 31 次全程聚焦且未最小化；改前纯色第 3 轮失焦保留并排除该空闲汇总，其余组合均 4/4 合格。

全部组合 FX CPU 中位数为 0 ms/s，表示低于本次计数分辨率，不是绝对零开销。分配包含离散框架/窗口活动；完整范围和焦点见 idle-summary/all.csv。当前短区间不足以排名空闲成本，也不能检测局部遮挡。

## 独立分阶段诊断

正式比较完成后，对改前、改后、fxsvgimage 各单独录制一个 JVM，保持每类型 10 秒及至少 5 批预热、5 批测量。JFR 执行/原生方法采样周期为 2 ms，分配采样 300/s；额外增加两次阶段边界计数。这些录制数据**不并入正式排名**，单进程也不能替代独立重复。

build 为新 Scene、解析/建节点和挂载；css-layout 为显式 applyCss/layout；snapshot-ready 包括快照参数与图像准备、隐式同步、渲染/回读、回调等待。后者不等于 GPU 时间，也不能把 wall 减 FX CPU 的差值全部归给 GPU。

每批三段 wall、FX 分配之和与整段读数核对通过，共 30 个正式分段记录（每库/类型 5 批）。下表是各段五批中位数，分段中位数之和不必等于整段中位数。

| 类型 | 模式 | build ms | CSS/layout ms | snapshot-ready ms | build FX MiB |
| --- | --- | ---: | ---: | ---: | ---: |
| path | 本库改前 | 31.87 | 4.06 | 20.75 | 56.35 |
| path | 本库改后 | 28.72 | 4.54 | 20.64 | 53.26 |
| path | fxsvgimage | 49.12 | 1.71 | 9.51 | 74.52 |
| gradient | 本库改前 | 48.41 | 5.14 | 60.36 | 72.95 |
| gradient | 本库改后 | 42.43 | 3.83 | 49.40 | 66.61 |
| gradient | fxsvgimage | 51.42 | 1.72 | 46.69 | 76.84 |

本次诊断未表现为本库创建阶段落后于 fxsvgimage；CSS/layout 和纯色快照阶段仍较高。不能再仅凭总体差距断言 XML 解析是首要瓶颈。录制会改变调度和分配，阶段差值尚不能视为精确收益。

JFR 的 FX 栈仍涉及样式计算、布局与节点 peer 同步；渲染线程涉及 Prism mask 纹理上传、绘制和读回。采样栈与 ObjectAllocationSample 权重不是精确 CPU/分配份额，原生调用栈也不能直接测得 GPU 时间或锁定某一根因。下一步应对场景图层级、视口裁剪及首次 peer/形状同步做保持像素一致的控制实验，验证 CSS/layout 和 snapshot-ready 成本；这些目前只是待验证方向，不应直接删除必要裁剪或削弱 CSS 行为。

本轮未重跑创建 JMH、动画稳态性能、复杂素材全量视觉对照或屏幕呈现延迟，不将历史成绩标记为当前版本结果。

## 数据与复现

- [正式汇总](hierarchy-gradient-2026-10-25-raster-summary.csv)、[独立进程](hierarchy-gradient-2026-10-25-raster-forks.csv)、[全部批次](hierarchy-gradient-2026-10-25-raster-all.csv)、[逐轮前后变化](hierarchy-gradient-2026-10-25-round-changes.csv)、[趋势提示](hierarchy-gradient-2026-10-25-warnings.txt)
- [空闲汇总](hierarchy-gradient-2026-10-25-idle-summary.csv)、[原始空闲与焦点](hierarchy-gradient-2026-10-25-idle-all.csv)、[20 项特性探针](hierarchy-gradient-2026-10-25-features.csv)
- [诊断分段汇总](hierarchy-gradient-2026-10-25-steps-summary.csv)、[全部分段含预热](hierarchy-gradient-2026-10-25-steps-all.csv)、[诊断协议](hierarchy-gradient-2026-10-25-profile-protocol.txt)、[诊断环境](hierarchy-gradient-2026-10-25-profile-environment.txt)
- [改前采样摘要](hierarchy-gradient-2026-10-25-profile-native-before.txt)、[改后采样摘要](hierarchy-gradient-2026-10-25-profile-native.txt)、[fxsvgimage 摘要](hierarchy-gradient-2026-10-25-profile-fxsvgimage.txt)
- [正式协议](hierarchy-gradient-2026-10-25-protocol.txt)、[计划及 JAR 哈希](hierarchy-gradient-2026-10-25-plan.csv)、[位置平衡](hierarchy-gradient-2026-10-25-positions.csv)、[实际类加载来源和环境](hierarchy-gradient-2026-10-25-environment.txt)、[全部依赖哈希](hierarchy-gradient-2026-10-25-sha256.txt)、[SVG 输入哈希](hierarchy-gradient-2026-10-25-svg-sha256.csv)

完整本地产物为 `target/hierarchy-gradient-20261025`，包括两版本库 JAR、中间仅缓存改动的 JAR（未单独计分）、隔离构建、SVG/PNG、完整 JFR、逐进程日志、测量源码副本和完成标记。大文件未提交。

~~~powershell
./benchmarks/comparison/display-run.ps1 -JavaHome 'S:/jdk25.0.1_bellsoft' -NativeJar '改后.jar' -BaselineNativeJar '改前.jar' -Providers native-before,native,fxsvgimage,jsvg-fx -Repeats 4 -Output target/recheck-interleaved
./benchmarks/comparison/display-analyze.ps1 -InputDirectory target/recheck-interleaved -ExpectedRepeats 4
~~~

诊断使用新输出目录，增加 `-RecordJfr -Repeats 1 -Providers native-before,native,fxsvgimage`。正式数据与录制数据分开汇总；RecordJfr 的 steps.csv 校验可对照同批 raster.csv，完整 JFR 用 DisplayProfile.java 提取已完成、非预热的 RasterBenchmarkPhase。后续若比较另一个实现，需同时固定像素密度、缓存策略、运行顺序与全部原始重复。
