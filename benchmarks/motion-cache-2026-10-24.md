# 静态内容按需缓存及当前跨库对照：2026-10-24

**本库完整像素结果的成本明显下降，但整体性能仍未持平 fxsvgimage。** 新一轮 500 图标完整光栅结果中位数：本库纯色 102.32 ms、渐变 159.54 ms；fxsvgimage 64.56/128.06 ms；JSVG JavaFX 180.23/201.26 ms。本库在这两个样本中低于 JSVG JavaFX 的 wall，但分配更高，不能称为全面优于 JSVG。

## 改动

无 SVG 动画的文档初次直接以原生节点绘制，发生实际平移/旋转等稳定尺度变换后，才启用原有有界位图缓存。单纯尺寸与样式更新不要求先建立缓存。带刚体动画的缓存策略继续保留，其他动画沿用原有矢量处理。

这避免为首次静态画面立即建立每个图标的额外缓存；没有把这项工作排到紧随其后的空闲帧。没有运动的静态内容继续直接显示；运动后缓存可保留复用，仍随几何、样式、DPI 和预算变化正确更新。保留原生节点、CSS/FXML、源 id/class、裁剪及 maxCachePixels 限制。

节点 transform 属性和可变 Translate/Rotate 都可触发按需启用；初始构造、resize、CSS 换色、变换后更新及超预算回退由回归测试覆盖。初次父布局若产生位移也可能触发缓存，不能保证任意界面首次显示都获得同样收益。

本次 JAR SHA-256：`488E0D171D4D12F7B7FA6B95AE59E3251DABF0A8C36D2B08EAA04CCFBEF4F457`。此前立即缓存基线为 78f76a9，对应哈希 `735646FE7E1E1B7EF7661D66E8D59338113175555B2D68111F5F487BD74F2E86`。

## 完整光栅结果

与 [立即缓存基线](static-display-2026-10-24.md) 使用同一工具和参数：每模式三个新 JVM，固定 512 MiB 堆，相同 XML、500 个独立文档、24px 图标；模式顺序轮换、类型逆序。每类型至少 5 批且持续 10 秒预热，再测 5 批；不同时编译或运行其他基准，不启用 JFR/自动播放/强制 GC，应用 Node.cache 关闭，内部使用各库默认策略。

计时包含新 Scene、解析、创建、CSS/layout、延迟 skin/Canvas、异步 740×650 逻辑像素快照及读回。每批计时结束后检查全部 500 个图标；90 个正式批次及全部预热的像素验证均完成。**这是完整光栅结果就绪成本，不是屏幕呈现延迟、纯 GPU 时间或冷 JVM 启动。**

先取每进程 5 批的中位数，再汇总三个独立进程中位数 [最小–最大]。CPU 与分配同样以进程为单位；进程 CPU 包含其他线程，可超过 wall。分配是创建/绘制区间的 Java 堆分配，不是保留内存、原生内存或显存。

| 类型 | 模式 | wall ms/500 | FX CPU ms | 进程 CPU ms | FX 分配 MiB | 总线程分配 MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| path | 本库（按需缓存） | 102.32 [87.79–104.36] | 62.50 | 93.75 | 93.42 | 95.98 |
| path | fxsvgimage 1.7.3 | 64.56 [61.24–78.16] | 62.50 | 62.50 | 79.23 | 81.74 |
| path | JSVG JavaFX 2.2 | 180.23 [147.93–186.79] | 46.88 | 171.88 | 48.68 | 51.30 |
| gradient | 本库（按需缓存） | 159.54 [141.79–165.47] | 78.13 | 140.63 | 129.55 | 132.10 |
| gradient | fxsvgimage 1.7.3 | 128.06 [102.81–171.96] | 62.50 | 125.00 | 81.51 | 84.16 |
| gradient | JSVG JavaFX 2.2 | 201.26 [189.37–206.56] | 62.50 | 218.75 | 54.78 | 57.40 |

本库前后比较：

| 类型 | 立即缓存 ms/500 | 按需缓存 ms/500 |
| --- | ---: | ---: |
| path | 212.78 [192.87–214.84] | 102.32 [87.79–104.36] |
| gradient | 460.46 [343.24–508.08] | 159.54 [141.79–165.47] |

前后为独立顺序运行的批次，第三方两轮基线本身也有波动，不能将中位数比例解释为固定收益。本轮 1/18 个类型/进程组合触发趋势提示（fxsvgimage 第二轮纯色），完整保留；固定预热不是稳态保证。wall 与 FX CPU 的差值不能直接归因于 GPU，缓存、GC 等各自耗时尚未精确拆分。

本轮 FX 分配本库纯色约 93.42 MiB/批、渐变约 129.55 MiB/批，仍高于 fxsvgimage 的 79.23/81.51 及 JSVG JavaFX 的 48.68/54.78。不能因首次结果更快而宣布更省内存。

## 静态空闲

显示最后一批，预热 3 秒、测量 5 秒，无 AnimationTimer/FX 轮询/快照，后台线程只在结束读取一次计数。CPU 零读数表示低于本次计数分辨率。光栅快照为逻辑比例 1，静态窗口使用设备缩放 1.5。

| 类型 | 模式 | 合格/尝试 | FX CPU ms/s | FX KiB/s |
| --- | --- | ---: | ---: | ---: |
| path | 本库（按需缓存） | 3/3 | 0.000 [0.000–3.124] | 32.260 [0.503–32.261] |
| path | fxsvgimage 1.7.3 | 1/3 | 0.000 [0.000–0.000] | 0.503 [0.503–0.503] |
| path | JSVG JavaFX 2.2 | 2/3 | 0.000 [0.000–0.000] | 32.312 [32.310–32.314] |
| gradient | 本库（按需缓存） | 3/3 | 0.000 [0.000–0.000] | 0.503 [0.503–32.261] |
| gradient | fxsvgimage 1.7.3 | 3/3 | 0.000 [0.000–6.247] | 32.256 [0.503–51.944] |
| gradient | JSVG JavaFX 2.2 | 3/3 | 0.000 [0.000–0.000] | 0.498 [0.498–32.304] |

18 次尝试中 15 次全程聚焦、未最小化；fxsvgimage 纯色只有一次合格区间，JSVG 纯色两次，均未补成三次或挑最快值。全部失焦记录保留。短区间可能包含窗口/系统事件和框架尾部工作，当前数据不足以给完全空闲成本排名，也没有测保留堆/显存。焦点不能检测局部遮挡，其他应用后台活动未控制。

## 变换后缓存仍有效

本库单独用两个新 JVM、正反场景顺序复测，500 图标，每场景 5 秒预热/5 秒采样，JFR 开启。所有 6 次尝试均通过画面变化检查；第一轮 SMIL 有 762 个失焦样本，原始值保留并排除主汇总，SMIL 仅一个合格进程。

| 场景 | 合格/尝试 | pulse P95 ms | FX CPU ms/pulse | FX KiB/pulse |
| --- | ---: | ---: | ---: | ---: |
| gradient | 2/2 | 8.76 [8.60–8.93] | 2.71 | 157.2 |
| path | 2/2 | 8.61 [8.56–8.65] | 2.74 | 157.5 |
| smil-transform | 1/2 | 8.52 [8.52–8.52] | 3.21 | 247.1 |

纯色和渐变运动后的分配仍约 157 KiB/pulse，保留此前 [分配优化](cache-allocation-2026-10-24.md) 的收益；SMIL 约 247 KiB/pulse。当前本库 FX CPU 仍高于此前同协议 fxsvgimage 对照，未重新宣称 CPU 持平。此处第三方渲染参照来自此前两轮固定版本测量；本节只复核本库按需缓存后的行为，完整像素主表则是本轮重新运行的三模式对照。

## 功能与后续重点

本轮已补齐单原语 feOffset：SourceGraphic/SourceAlpha、偏移、两套坐标单位、输入/输出裁剪、元素变换与 CSS 更新。当前 [20 项代表性像素/动画探针](fxsvgimage-improvements-2026-10-24-features.csv) 本库 20 项通过、fxsvgimage 12 项通过；不是完整 SVG 规范覆盖率，单个失败不能否定第三方整个元素的实现。

Maven verify 为 357 项通过。功能仍有明确差距：本库尚不支持通用 feFlood/feMerge/feComposite 滤镜链；fxsvgimage 的官方说明包含这些能力，同时说明部分复合操作有限制。参见 [fxsvgimage 滤镜说明](https://fxsvgimage.sourceforge.io/articles/article_filters.html)。下一步性能重点仍为场景图边界计算、完整显示时的分配与渐变成本，不能继续用单项 pulse 或创建成绩代替整体目标。

## 数据与复现

- [完整光栅汇总](motion-cache-2026-10-24-raster-summary.csv)、[独立进程](motion-cache-2026-10-24-raster-forks.csv)、[全部批次](motion-cache-2026-10-24-raster-all.csv)、[趋势审查](motion-cache-2026-10-24-quality.txt)
- [空闲汇总](motion-cache-2026-10-24-idle-summary.csv)、[全部空闲及焦点](motion-cache-2026-10-24-idle-all.csv)
- [变换原始结果](motion-cache-2026-10-24-render-all.csv)、[变换有效结果汇总](motion-cache-2026-10-24-render-summary.csv)
- [协议](motion-cache-2026-10-24-protocol.txt)、[计划](motion-cache-2026-10-24-plan.csv)、[JAR 哈希](motion-cache-2026-10-24-sha256.txt)、[输入 SVG 哈希](motion-cache-2026-10-24-svg-sha256.csv)
- 本地完整源码、SVG、PNG、环境、日志与标记：`target/fxsvgimage-improvements/motion-display`；变换/JFR 为 `motion-render-r1`、`motion-render-r2`。

~~~powershell
./benchmarks/comparison/display-run.ps1 -JavaHome 'S:/jdk25.0.1_bellsoft' -NativeJar '目标.jar' -Output target/svg-display-comparison/formal
./benchmarks/comparison/display-analyze.ps1 -InputDirectory target/svg-display-comparison/formal
~~~

本轮未重跑创建 JMH 或测量屏幕实际呈现时间；历史创建成绩不能直接标为当前版本成绩。复现时保留相同像素密度、缓存策略、全部原始重复与失焦尝试。
