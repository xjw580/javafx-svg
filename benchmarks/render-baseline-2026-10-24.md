# 可见窗口交互基线：2026-10-24

当前样本中，渐变连续缩放是最明显的慢场景，两轮 pulse 间隔中位数均约 32 ms。普通路径与文字场景的中位数约 6–7 ms。此结果只描述本机、当前图形管线及样本，不能外推为库的通用帧率。

## 环境与测量方式

- 库代码基于 `278641d`；本次仅增加测试程序、CSS 和报告，未修改运行时渲染实现。
- Windows 11，Ryzen 9 5900X，Radeon RX 7900 XT，Prism D3D 硬件管线。
- BellSoft JDK 25.0.1、JavaFX 21.0.8+2、Kotlin 2.2.0；独立 JVM，`-Xms512m -Xmx512m -Dprism.verbose=true`。
- 显示器 3840×2160、165 Hz；JavaFX 逻辑屏幕 2560×1440，缩放 1.5。
- 场景 720×570，裁剪区域 680×480；每组 200 个 SvgView，共享一个文档，约 80 个图标可见。
- 路径、渐变、模糊样本各含 12 条矩形路径；文字样本含 4 个原生 Text。模糊标准差为 1.5，明确使用 sRGB。各类型复杂度不同。
- 每个场景预热 2 秒、测量 5 秒，顺序固定；四种类型分别滚动、缩放和 CSS 换色，共 12 个场景。进行了两次独立进程运行，均启用 JFR profile。
- 滚动移动裁剪网格，不包含 ListView 虚拟化；缩放逐帧 resize 所有视图；CSS 每个 pulse 切换网格伪类，属于压力负载。

## 结果

采用第二轮作为主要基线：所有采样时窗口均聚焦、未最小化。第一轮在文字缩放后失去焦点，该轮后半段只作参考；其中模糊滚动的单次 87 ms 间隔不作为稳定性能结论。聚焦统计不能检测窗口遮挡。

以下单位为毫秒，测量的是 AnimationTimer **pulse 间隔**，不是 GPU 绘制时间或实际呈现帧率。

| 场景 | 中位数 | P95 | P99 | 超过 33.33 ms 次数 |
| --- | ---: | ---: | ---: | ---: |
| 普通路径滚动 | 6.038 | 10.806 | 11.804 | 0 |
| 普通路径缩放 | 7.088 | 12.211 | 15.264 | 0 |
| 普通路径 CSS | 6.181 | 8.713 | 10.040 | 0 |
| 渐变滚动 | 6.048 | 10.928 | 11.776 | 0 |
| 渐变缩放 | 31.854 | 33.823 | 48.152 | 10 |
| 渐变 CSS | 6.426 | 8.753 | 10.245 | 0 |
| 文字滚动 | 6.025 | 11.185 | 12.057 | 0 |
| 文字缩放 | 6.046 | 10.161 | 11.010 | 0 |
| 文字 CSS | 6.033 | 9.958 | 10.739 | 0 |
| 模糊滚动 | 6.034 | 10.837 | 11.503 | 0 |
| 模糊缩放 | 11.608 | 16.174 | 16.671 | 0 |
| 模糊 CSS | 8.547 | 13.057 | 14.719 | 0 |

第二轮所有场景均没有超过 50 ms 的间隔。渐变缩放第一轮中位数 31.915 ms、P95 32.949 ms，两轮均复现明显变慢。

完整汇总：[第一轮 CSV](render-baseline-2026-10-24-run1.csv)、[第二轮 CSV](render-baseline-2026-10-24-run2.csv)。`work_p95_ms` 仅统计回调中的属性修改，不包含随后执行的 CSS、布局与绘制；`gc_time_ms` 是 MXBean 累计收集耗时，不是精确停顿时间。

## JFR 观察与后续方向

仅分析 `club.xiaojiawei.svg.RenderBenchmarkPhase` 事件覆盖的正式测量区间，排除创建视图、截图、预热和结果写盘。

- 第二轮渐变缩放共 201 个 native 样本，其中 132 个落在 QuantumRenderer 的 `D3DResourceFactory.nUpdateTextureB`。栈经过 `D3DTexture.update → BaseContext.flushMask → BaseShaderGraphics.renderShape`。属性修改回调 P95 仅 0.149 ms，因此应优先调查形状缩放时的纹理上传及渲染同步，而非继续推测 XML 解析开销。Native 样本可能包含等待，不能转换成 CPU/GPU 耗时占比，也不能据此确定驱动层根因。
- 第二轮模糊缩放、CSS 换色分别发生 9、12 次 GC；JFR 所见最大 GC 停顿分别为 3.359、2.662 ms。缩放执行采样涉及路径解析、边界更新和 Marlin 栅格化，值得继续定位滤镜边界计算及分配，尚未据此修改缓存策略。
- 后续优先做渐变缩放的节点缓存开关对照，验证是否降低 pulse 间隔，同时检查缩放清晰度、CSS 更新正确性和内存代价。只有对照有效后再决定库的缓存接口或默认策略。

两轮短测不是统计置信区间；复测应固定硬件、JavaFX 版本、显示缩放、刷新率、窗口状态、JVM 堆及 JFR 配置。

## 复跑与验证

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--benchmark --count=200 --warmup-seconds=2 --measure-seconds=5"
```

此命令复用 Maven JVM；若要严格匹配本次 JVM 条件，编译后用独立 Java 进程、测试 classpath 和上述堆参数启动 `club.xiaojiawei.svg.demo.SvgDemoKt`，传入同样的基准参数。

基准默认输出到 `target/render-benchmark/时间戳/`，可用 `--output=新目录` 指定位置。保留 `environment.txt`、`results.csv`、逐 pulse CSV、`render.jfr` 和 `completed.txt`。中途关闭不算完成。

本次完整原始记录在本地 `target/svg-performance/render-baseline/run1/` 和 `run2/`；JFR、预览图及临时分析程序不纳入 Git。`mvn verify` 通过：84 项测试，失败、错误、跳过均为 0；四类场景预览已检查。
