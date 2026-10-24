# 节点缓存对照：2026-10-24

结论：固定视口、只改变外部 Scale 变换时，`SvgView.isCache=true` 配合 `CacheHint.SCALE` 能显著改善本次渐变样本的 pulse 间隔。持续 resize 没有改善，CSS 高频换色也没有稳定收益。SCALE 缓存在放大时可见模糊，并增加内存占用，因此继续保持库的缓存默认关闭；无需新增缓存接口。

## 方法与数据范围

运行时库基于 `e739e9f`，本次只修改测试程序及文档。硬件与[首次基线](render-baseline-2026-10-24.md)相同：Windows 11、Ryzen 9 5900X、RX 7900 XT、165 Hz 显示器、JavaFX 输出缩放 1.5；JDK 25.0.1、JavaFX 21.0.8+2、Kotlin 2.2.0、Prism D3D。每组独立 JVM，固定 512 MiB 堆并启用 JFR profile。

200 个共享文档的 SvgView，每个含 12 条渐变矩形路径，约 80 个视图可见。每个动作预热 2 秒、正式测量 5 秒，依次执行 resize、变换、CSS 换色。缓存策略分别为关闭、SCALE、QUALITY，缓存节点均为 SvgView 本身。

- resize：每帧修改 SvgView 大小，在 36–60 之间变化；内部视口变换及裁剪随之改变。
- 变换：SvgView 固定为 48×48，每帧改变外部 Scale 的 x/y，在 0.75–1.25 之间变化，中心为左上角；不触发布局尺寸变化。
- CSS：每个 pulse 切换整个网格的伪类，改变渐变色，缓存内容也随之变化。

共执行 10 个进程：首轮 off/scale/quality、第二轮反向顺序、两次 off/scale 补测。首轮三组所有采样均聚焦、未最小化，作为主要对照。后续 SCALE 进程出现失焦，第二轮该组 CSS 还出现明显异常波动，均保留原始数据但不纳入主要收益结论。后续聚焦的无缓存变换中位数也在 16.79–47.72 ms 之间变化，说明该管线存在较大波动；不能将首轮倍数视为通用或稳定保证。未最小化和保持聚焦也不能检测遮挡。

## 首轮结果

单位为毫秒；每格为 pulse 间隔中位数 / P95。Pulse 是 AnimationTimer 回调间隔，不是 GPU 耗时或显示器实际呈现帧率。

| 动作 | 关闭缓存 | SCALE 缓存 | QUALITY 缓存 |
| --- | ---: | ---: | ---: |
| resize | 31.967 / 32.836 | 32.078 / 48.413 | 32.076 / 48.314 |
| Scale 变换 | 32.052 / 47.947 | 5.952 / 10.785 | 32.028 / 48.134 |
| CSS 换色 | 7.522 / 13.195 | 8.595 / 13.949 | 6.949 / 12.385 |

固定视口的变换动画中，SCALE 缓存的中位间隔减少约 81%；持续 resize 的 P95 反而增加。QUALITY 的变换性能接近未缓存，符合它优先保持画质、缩放时可能重新绘制的语义。相关语义见 [JavaFX 21 CacheHint 文档](https://openjfx.io/javadoc/21/javafx.graphics/javafx/scene/CacheHint.html)。

完整 30 条场景记录见 [性能 CSV](cache-comparison-2026-10-24.csv)，包含运行编号、焦点及最小化计数，保留受干扰样本。

## JFR 与内存观察

只分析首轮 `club.xiaojiawei.svg.RenderBenchmarkPhase` 标出的正式测量区间。每个事件附带缓存策略，创建视图、预热、截图和写盘均排除。

首轮 Scale 变换时，未缓存的 QuantumRenderer native 采样有 115 个落在 `D3DResourceFactory.nUpdateTextureB`；QUALITY 为 125 个，SCALE 的主要 native 栈转为 `D3DSwapChain.nPresent`。这支持缓存减少重复形状绘制及纹理更新的解释。采样次数不是调用次数，也不是 CPU/GPU 耗时占比；native 栈可能包含等待。

另以 500 ms 间隔采集整个进程的内存，首轮结果如下，单位 MiB：

| 策略 | 采样工作集峰值 | 采样私有内存峰值 |
| --- | ---: | ---: |
| off | 533.69 | 866.45 |
| SCALE | 569.12 | 904.23 |
| QUALITY | 556.62 | 893.13 |

SCALE 首轮工作集峰值高约 35 MiB。此处包含 JVM 堆、JFR、JavaFX、驱动和整个进程生命周期，采样可能遗漏峰值，不能解释为位图缓存或显存的精确增量。其他运行也有明显差异，详见[全部内存汇总](cache-memory-2026-10-24.csv)。本次没有测量显存，不能据此设置通用缓存内存预算。

## 画质与 CSS 验证

独立可见窗口先在 1 倍大小预热 off/SCALE/QUALITY 三列，再等待自然 pulse，依次检查 1 倍、2 倍、0.75 倍、1.25 倍、CSS 换色及恢复。使用设备缩放比例生成截图，包含渐变和文字。

- SCALE 在 2 倍和 1.25 倍放大时出现可见边缘模糊，文字也变模糊；QUALITY 更接近未缓存画面。
- CSS 换色和恢复后，三列输出一致；渐变内部颜色断言通过，没有观察到旧缓存残留。样本覆盖填充颜色，未穷举所有 CSS 属性。
- 保留[像素差异 CSV](cache-visual-2026-10-24.csv)。均值按 128×128 逻辑区域统计，包含空白背景，仅用于同一截图对照，不能作为通用画质评分。

## 建议用法与复跑

短时缩放动画可以临时启用已有 Node 属性，结束后关闭缓存，按最终变换重新绘制矢量：

```kotlin
val zoom = javafx.scene.transform.Scale(1.0, 1.0, 0.0, 0.0)
view.transforms += zoom
view.cacheHint = javafx.scene.CacheHint.SCALE
view.isCache = true
val animation = javafx.animation.Timeline(
    javafx.animation.KeyFrame(javafx.util.Duration.millis(180.0),
        javafx.animation.KeyValue(zoom.xProperty(), 1.25),
        javafx.animation.KeyValue(zoom.yProperty(), 1.25)))
animation.setOnFinished { view.isCache = false }
animation.play()
```

示例假定 view 初始未缓存；若动画可取消或替换，也应在对应结束路径恢复原策略。外部变换不改变 Region 布局尺寸，布局占位需要由调用方处理。

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--benchmark --kinds=gradient --motions=scale,transform,css --cache=off"
mvn -Pdemo test-compile exec:java "-Dexec.args=--benchmark --kinds=gradient --motions=scale,transform,css --cache=scale"
mvn -Pdemo test-compile exec:java "-Dexec.args=--benchmark --kinds=gradient --motions=scale,transform,css --cache=quality"
mvn -Pdemo test-compile exec:java "-Dexec.args=--cache-visual-check --output=target/cache-visual-check-1"
```

以上命令便于复跑，但会使用 Maven JVM。本次记录使用独立 Java 进程，带 `-Xms512m -Xmx512m -Dprism.verbose=true`，入口为 `club.xiaojiawei.svg.demo.SvgDemoKt`，测试 classpath 包括 JavaFX graphics/base 与 Kotlin stdlib。独立运行方式可避免 Maven 自身影响进程内存统计。

本地原始目录为 `target/svg-performance/cache-comparison/`，包含启动脚本、隔离源码、两轮及补测 CSV/JFR、进程内存采样、视觉截图和临时分析程序；不纳入 Git。`mvn verify` 通过，84 项测试无失败、错误或跳过，视觉检查通过。
