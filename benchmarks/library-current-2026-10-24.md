# 当前版本跨库性能对照：2026-10-24

本报告同时比较本库、fxsvgimage 和 JSVG 两种后端，使用当前核心实现重新实测。此前的本库 before/after 表只说明本库优化收益，不能代替跨库对照；旧协议的创建成绩继续撤回。

- 整体创建：本库的纯色、SMIL 中位数低于 fxsvgimage，渐变高于 fxsvgimage；JSVG 返回对象阶段更轻，但绘制延后，不能据此判断首绘更快。
- 渲染：在本次缓存策略下，本库渐变旋转的 pulse P95 约 8.76 ms；fxsvgimage 唯一合格轮为 93.71 ms。样本数量有限，不作为固定倍数承诺。
- 内部 SMIL：本库 pulse P95 为 8.40–10.26 ms，JSVG JavaFX 为 80.17–80.23 ms，JSVG AWT 为 16.82–27.04 ms；fxsvgimage 为 10.15–10.54 ms。本库 FX CPU 和分配仍高于 fxsvgimage，不能称为全面领先。
- JSVG JavaFX 纯色渲染三次均失焦，主表留空；完整失焦记录保留，不填成零耗时，也不参与排名。

## 被测版本与共同环境

本库使用 d727de6 对应的核心实现，JAR SHA-256：

`B19BCB98AC6570407BAC028DF174BCF12083FFF48C49E0BC0975287DF3B10B50`

本次 Demo 实时编辑功能不修改核心渲染代码，验证在隔离目录构建，实测 JAR 未被覆盖。fxsvgimage 固定 1.7.3，JSVG/jsvg-javafx 固定 2.2.0；所有依赖下载和解包哈希由 [清单](comparison/dependencies.json) 校验。codecentric JavaFxSVG 未运行，不填写成绩。

Windows 11、Ryzen 9 5900X、Radeon RX 7900 XT、JDK 25.0.1、JavaFX 21.0.8、Kotlin stdlib 2.2.0、512 MiB 堆，逻辑屏幕 2560×1440、缩放 1.5。创建与渲染不同时运行；渲染期间不执行项目编译或测试。未控制其他应用的后台活动，因此不把短测当成硬件极限。

所有库使用相同的内存 SVG、每视图独立解析、500 个 24px 图标，关闭文档/资源共享和应用 Node.cache。**本库内部 renderCacheEnabled 保持默认开启，JSVG 可复用 Canvas/像素表面。** 这是默认实现策略对照，未测统一关闭所有栅格缓存时的绘制性能；缓存的画质与内存代价见 [修复报告](remediation-2026-10-24.md)。

## 创建：解析、构建返回对象并挂载

JMH 1.37；每模式/场景三个独立 child JVM，共 36 个；15×1 秒创建预热、5×1 秒正式测量，顺序轮换并按固定种子打乱。没有 Stage、自动播放、CSS/layout、首绘、快照或 JFR。原始迭代全部保留，6/36 个进程触发趋势审查提示，未剔除，不能保证所有进程已经稳态。

单位 ms/500 个实例；每格为三个独立进程 JMH 均值的中位数 [最小–最大]，包含 FX 调度和记录包装。

| 模式 | 纯色 | 渐变 | SMIL |
| --- | ---: | ---: | ---: |
| 本库 | 42.72 [41.41–54.41] | 66.06 [64.26–68.37] | 48.26 [39.53–54.35] |
| fxsvgimage 1.7.3 | 55.31 [52.96–72.80] | 59.71 [56.76–74.37] | 66.69 [64.05–87.40] |
| JSVG 2.2 JavaFX | 35.24 [34.87–35.29] | 38.71 [38.45–50.52] | 44.45 [40.36–51.07] |
| JSVG 2.2 AWT | 34.38 [33.59–47.05] | 38.76 [38.48–51.71] | 38.58 [37.57–39.96] |

JSVG 在此阶段主要构建模型与 Control，skin/Canvas 和绘制延至 CSS/layout/首绘；原生树提前准备更多内容。这不是冷启动或首次可见显示的完整成本，不能将该表直接解释为端到端加载速度。

创建区间内 FX 线程分配，单位 MiB/500 个实例，三个独立进程的中位数：

| 模式 | 纯色 | 渐变 | SMIL |
| --- | ---: | ---: | ---: |
| 本库 | 84.22 | 120.94 | 90.59 |
| fxsvgimage 1.7.3 | 74.27 | 76.83 | 77.14 |
| JSVG 2.2 JavaFX | 28.23 | 32.08 | 32.33 |
| JSVG 2.2 AWT | 28.23 | 32.07 | 32.33 |

FX 分配不含其他线程、原生/AWT 内存或显存，不是最终保留堆。JSVG 的部分成本在本表计时之后发生。JMH 主时长、FX 内部 wall/CPU/分配分别归档，不能混称。

## 渲染：实际画面变化与动画

每模式一个新 JVM，正序和逆序各一轮，场景顺序也反转；每场景预热 5 秒、采样 5 秒。四种模式均启用相同 JFR profile。纯色/渐变使用相同的宿主 Rotate；SMIL 使用相同的 2s SVG 旋转，由各库自己的动画实现执行。

两轮共 24 个场景；仅对两轮都没有合格结果的两个 JSVG 纯色组合各补测一次，共 26 次，画面变化探针全部通过。完整主表筛选规则为 unfocused_samples=0 且 iconified_samples=0：共 19 次合格，7 次失焦保留为附表。全部合格重复都纳入；只有一个合格重复时直接标为 1 次，不挑最快值。没有最小化记录；焦点计数不能证明窗口未被局部遮挡。

每格为合格轮指标的中位数 [最小–最大]；“合格/尝试”明确独立进程数。pulse 是 AnimationTimer 回调间隔，**不是呈现 FPS 或 GPU 时间**。FX CPU、分配按 pulse 计，不能替代单位时间总 CPU 或整进程内存。

| 场景 | 模式 | 合格/尝试 | pulse P95 ms | FX CPU ms/pulse | FX KiB/pulse |
| --- | --- | ---: | ---: | ---: | ---: |
| 纯色外部旋转 | 本库 | 2/2 | 8.86 [8.84–8.89] | 2.56 [2.41–2.71] | 300.0 [294.2–305.9] |
| 纯色外部旋转 | fxsvgimage 1.7.3 | 1/2 | 10.29 [10.29–10.29] | 1.12 [1.12–1.12] | 157.3 [157.3–157.3] |
| 纯色外部旋转 | JSVG 2.2 JavaFX | 0/3 | 无有效样本 | — | — |
| 纯色外部旋转 | JSVG 2.2 AWT | 1/3 | 9.62 [9.62–9.62] | 2.08 [2.08–2.08] | 16.6 [16.6–16.6] |
| 渐变外部旋转 | 本库 | 2/2 | 8.76 [8.56–8.96] | 2.78 [2.50–3.06] | 300.0 [294.2–305.9] |
| 渐变外部旋转 | fxsvgimage 1.7.3 | 1/2 | 93.71 [93.71–93.71] | 1.98 [1.98–1.98] | 157.4 [157.4–157.4] |
| 渐变外部旋转 | JSVG 2.2 JavaFX | 2/2 | 10.19 [10.15–10.23] | 1.06 [0.87–1.25] | 32.3 [32.3–32.3] |
| 渐变外部旋转 | JSVG 2.2 AWT | 2/2 | 10.04 [9.97–10.11] | 1.11 [1.04–1.17] | 16.6 [16.6–16.6] |
| 内部 SMIL 旋转 | 本库 | 2/2 | 9.33 [8.40–10.26] | 4.13 [3.31–4.95] | 376.4 [368.4–384.4] |
| 内部 SMIL 旋转 | fxsvgimage 1.7.3 | 2/2 | 10.34 [10.15–10.54] | 1.60 [1.57–1.63] | 141.6 [141.6–141.6] |
| 内部 SMIL 旋转 | JSVG 2.2 JavaFX | 2/2 | 80.20 [80.17–80.23] | 9.35 [9.05–9.66] | 9767.4 [9729.9–9804.9] |
| 内部 SMIL 旋转 | JSVG 2.2 AWT | 2/2 | 21.93 [16.82–27.04] | 11.59 [9.97–13.22] | 5768.7 [5753.6–5783.9] |

纯色/渐变旋转中，Canvas 与本库缓存可以复用已有像素，本表没有要求每帧重新解析或重绘完整 SVG。两个近似 9–10 ms 的 P95 不足以支持普遍性胜负结论；单轮合格结果的重复性也更弱。内部 SMIL 本库 FX 分配约 376 KiB/pulse，高于 fxsvgimage 的约 142 KiB/pulse，但低于本次 JSVG 两种后端；这些只是当前样本与策略的观测。

### 未纳入主表的完整失焦记录

下列值仅供追溯，不能与主表合格成绩拼接排名。

| 轮次 | 模式 | 场景 | 失焦样本数 | pulse P95 ms |
| --- | --- | --- | ---: | ---: |
| current-round1 | fxsvgimage 1.7.3 | path | 670 | 12.81 |
| current-round1 | JSVG 2.2 AWT | path | 823 | 9.94 |
| current-round1 | JSVG 2.2 JavaFX | path | 819 | 9.95 |
| current-round2 | fxsvgimage 1.7.3 | gradient | 88 | 64.92 |
| current-round2 | JSVG 2.2 AWT | path | 823 | 9.95 |
| current-round2 | JSVG 2.2 JavaFX | path | 821 | 10.15 |
| current-path-retry | JSVG 2.2 JavaFX | path | 812 | 9.28 |

## 特性范围

本次只重新测性能。当前本库的 18/18 代表性特性探针及 pattern、feDropShadow、textPath、animateMotion 的实现边界见 [修复报告](remediation-2026-10-24.md)；第三方相同固定版本的探针见 [历史特性表](library-comparison-2026-10-24.md#特性实际样本结果)。探针通过不等于完整 SVG 规范支持，也不能用单个失败断言某库完全不支持该元素。Demo 实时编辑加入后的 Maven verify 为 346 项通过，27 个原有演示冒烟检查通过。

## 数据与复现

- [创建汇总](library-current-2026-10-24-creation-summary.csv)、[全部独立进程](library-current-2026-10-24-creation-forks.csv)、[全部预热/测量迭代](library-current-2026-10-24-creation-iterations.csv)、[趋势提示](library-current-2026-10-24-creation-quality.txt)。
- [创建协议](library-current-2026-10-24-creation-protocol.txt)、[实际运行顺序](library-current-2026-10-24-creation-plan.csv)、[依赖与 JAR 哈希](library-current-2026-10-24-creation-sha256.txt)、[相同 SVG 输入哈希](library-current-2026-10-24-svg-sha256.csv)。
- [渲染汇总](library-current-2026-10-24-render-summary.csv)、[26 次原始结果及合格标记](library-current-2026-10-24-render-all.csv)、[正序](library-current-2026-10-24-current-round1.csv)、[逆序](library-current-2026-10-24-current-round2.csv)、[两次补测](library-current-2026-10-24-current-path-retry.csv)。
- 完整创建 JMH JSON、逐操作 CSV、日志、源码、输入在本地 `target/svg-creation/cross-formal-20261024`。
- 完整渲染 JFR、逐 pulse CSV、SVG、PNG、环境、日志在本地 `target/svg-library-comparison/current-round1`、`current-round2`、`current-path-retry`。

~~~powershell
./benchmarks/creation/run.ps1 -Providers native,fxsvgimage,jsvg-fx,jsvg-awt -Output target/svg-creation/cross
./benchmarks/creation/analyze.ps1 -InputDirectory target/svg-creation/cross
./benchmarks/comparison/run.ps1 -Mode benchmark -Counts 500 -WarmupSeconds 5 -MeasureSeconds 5 -Output target/svg-library-comparison/forward
./benchmarks/comparison/run.ps1 -Mode benchmark -Providers jsvg-awt,jsvg-fx,fxsvgimage,native -Kinds 'smil-transform,gradient,path' -Counts 500 -WarmupSeconds 5 -MeasureSeconds 5 -Output target/svg-library-comparison/reverse
~~~

需要指定正确的 JavaHome 和 NativeJar；目录必须为新目录。重现时保留全部失焦/失败尝试并检查完成标记，不能直接将全部 CSV 平均成排名。基准 API 与测量范围分别见 [创建工具](creation/README.md) 和 [渲染工具](comparison/README.md)。
