# SVG 动画测量：2026-10-24

基于运行时代码 50ba9fd 的首轮基线：100 个普通动画图标的 pulse P95 为 9–16 ms；500 个固定描边组合图标 P95 为 48.6 ms，是本次最慢场景。结果仅描述当前机器与样本，不代表库的通用帧率。

## 方法

- Windows 11；BellSoft JDK 25.0.1、JavaFX 21.0.8+2、Kotlin 2.2.0；24 个逻辑处理器。
- 独立 JVM：-Xms512m -Xmx512m -Djavafx.animation.pulse=60 --enable-native-access=ALL-UNNAMED；JFR profile，分配采样 throttle 为 300/s。
- JavaFX 逻辑屏幕 2560×1440，缩放 1.5；窗口 740×650；25 列、间隔 28、图标 24，500 个全部在窗口内。所有测量样本窗口聚焦且未最小化；焦点计数无法证明没有遮挡。
- 每类分别 100/500 个 SvgView，共享一个文档、节点独立、缓存关闭。预热 2 秒，测量 5 秒。创建、截图、预热和写盘排除在完整的 AnimationBenchmarkPhase 事件之外。
- SMIL/CSS 变换为一组 4 条路径旋转；几何为 4 个圆的半径动画；CSS 换色为 4 条路径；固定描边组合为一个圆角矩形的 CSS 缩放与 SMIL 宽度动画。周期 1.5 秒。
- paused 使用换色文档且关闭 autoplay；用于测量的 AnimationTimer 仍运行，并非完全空闲程序。
- pulse 是 AnimationTimer 回调间隔，不是 GPU 耗时或呈现帧率。尽管指定 pulse=60，活跃动画实测部分约 165 次回调/秒；不能假定该属性强制锁定帧率。
- CPU 百分比以一个核心为 100%，进程值可以超过 100%；FX CPU 来自当前线程计数器，短测分辨率有限。分配量来自近似 MXBean 计数，gc_time_ms 为累计收集耗时，不等于停顿时间。不可用值为 -1。

## 首轮基线

| 场景 | 数量 | pulse P95 ms | FX CPU ms/pulse | FX 分配 MiB/s | FX KiB/pulse |
| --- | ---: | ---: | ---: | ---: | ---: |
| SMIL 旋转 | 100 | 11.07 | 0.90 | 23.40 | 150.2 |
| SMIL 几何 | 100 | 9.33 | 1.99 | 73.45 | 456.4 |
| CSS 旋转 | 100 | 11.08 | 0.81 | 18.64 | 118.2 |
| CSS 换色 | 100 | 10.35 | 1.00 | 29.24 | 181.5 |
| 固定描边组合 | 100 | 11.55 | 5.48 | 652.00 | 5664.0 |
| 暂停对照 | 100 | 16.51 | 0.00 | 0.05 | 0.84 |
| SMIL 旋转 | 500 | 31.87 | 3.66 | 47.77 | 844.7 |
| SMIL 几何 | 500 | 17.75 | 12.40 | 160.20 | 2262.6 |
| CSS 旋转 | 500 | 32.03 | 4.02 | 35.35 | 649.4 |
| CSS 换色 | 500 | 12.41 | 5.66 | 101.75 | 903.3 |
| 固定描边组合 | 500 | 48.63 | 31.00 | 682.26 | 27771.0 |
| 暂停对照 | 500 | 16.69 | 0.05 | 0.05 | 0.84 |

完整数据：[基线 CSV](animation-2026-10-24-before.csv)。各类节点复杂度不同，不能把它们当作等价实现互相比速度。MiB/s 会受实际 pulse 频率影响，优化对比应同时看每 pulse 分配和 CPU。

JFR 按完整测量事件分段、仅分析 FX 线程：普通几何/换色场景大量分配来自 SvgLengthCache.resolve 的绑定方法引用及 SAM 适配器；固定描边组合执行采样主要落在 SvgNonScalingStroke.refresh，栈包含 Shape.union 与轮廓生成。这属于每帧原生几何重建，不是重新解析 XML。JFR 样本与权重为统计估计，最接近的库栈帧也不是排他耗时，不能直接换算为精确分配占比。

## 复跑

~~~shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--animation-benchmark --counts=100,500 --warmup-seconds=2 --measure-seconds=5"
mvn -Pdemo test-compile exec:java "-Dexec.args=--animation-report=target/animation-benchmark/运行目录/animation.jfr"
~~~

可用 --kinds=smil-transform,smil-geometry,css-transform,css-paint,css-fixed,paused 选择场景，--output=新目录 指定输出。工具拒绝覆盖已有结果。上述 Maven 命令复用 Maven JVM；严格对照应在编译后使用独立 Java 进程、测试 classpath、上述 JVM 参数启动 club.xiaojiawei.svg.demo.SvgDemoKt。

每次保留 environment.txt、results.csv、逐 pulse CSV、各场景 PNG、animation.jfr 和 jfr-analysis.txt。只有 JFR 保存完成后才产生 completed.txt；提前关闭产生 aborted.txt，异常另有 failure.txt，不作为完整基线。分析器以两遍流式读取 JFR，排除未完成阶段。

本地完整原始记录归档于 target/svg-verification/animation-perf/before/；JFR、逐 pulse 明细和 PNG 不纳入 Git。基准样本测试在严格模式渲染每种文档，并验证两个时间点的像素确实变化。


## 生命周期验证与修复

新增 7 项回归，完整验证后共 297 项测试通过：

- 暂停、自身隐藏、祖先隐藏、窗口隐藏/关闭、移出场景后，连续多个 pulse 的文档时间保持不变；恢复显示/挂载后继续推进。
- 缓存、共享 SvgDocument、窗口与场景继续存活时，移除的 40 个播放视图均通过 WeakReference 回收检查。
- 保留当前播放视图与资源缓存，连续 40 次交替设置本地 source/document 后，旧图形节点均通过回收检查。
- 修复前复现：最小化窗口仍计时；有限动画的时间监听器内重播被旧帧的结束处理覆盖；stop 回调内发出的新 play 同样被覆盖。
- 修复后监听 Stage.iconifiedProperty，最小化暂停、还原继续；独立的播放命令版本号使回调内的新命令优先。文档版本号仍单独控制替换流程，保证 reset 回调中的暂停不会遗漏新时长。

回收测试包含 GC 请求和有上限的等待，只证明本次场景下对象可回收，不是任意应用代码都不会泄漏的保证。最小化与回收测试保留宿主窗口，避免把关闭整个进程误认为资源释放。
