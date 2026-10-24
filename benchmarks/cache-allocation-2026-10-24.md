# 缓存监听分配优化：2026-10-24

本次减少本库在外部旋转和内部刚体动画中的 FX 分配；CPU 尚未持平 fxsvgimage，不能宣布整体性能目标达成。

## 实现与边界

JFR 正式采样区间显示，原缓存预算监听 localToSceneTransform 时，大量创建 Transform.ImmutableTransform、弱引用队列及相关对象。改为观察祖先缩放、旋转轴、变换列表和可变变换参数，使用各局部最大拉伸的乘积及内容包围圆估算设备像素预算。平移及二维旋转不触发重复预算计算。

Rotate、Scale、Translate、Shear 使用参数监听，避免为常见变换分配 TransformChangedEvent；Affine 和其他变换保留公共变换事件回退。保留窗口 DPI、重挂载、尺寸变化、二维限制、4096 尺寸及 maxCachePixels 总预算。

新预算更保守：细长内容、互相抵消的缩放或复杂剪切可能更早关闭缓存并使用矢量绘制。没有放宽像素限制，也没有移除原生节点或 CSS 能力。测试覆盖三维轴、动态变换列表、几何更新、预算回收、关闭/绑定和旧节点生命周期。

## 同协议实测

before SHA-256：`B19BCB98AC6570407BAC028DF174BCF12083FFF48C49E0BC0975287DF3B10B50`

after SHA-256：`735646FE7E1E1B7EF7661D66E8D59338113175555B2D68111F5F487BD74F2E86`

Windows 11、JDK 25.0.1、JavaFX 21.0.8、512 MiB 堆；每批 500 个图标；每场景 5 秒预热、5 秒测量，所有模式开启同样 JFR。before 两个独立进程、after 与两个第三方模式各两个独立进程，逆序复测。输入相同；所有视图独立解析，应用 Node.cache 关闭，内部缓存保留默认策略。编译与分析不和采样并行。

下表是两个合格进程指标的中位数。每个进程自身的 pulse P95 单独计算后汇总，不混合全部 pulse。CPU/分配按 pulse，不是总内存或 GPU 时间；pulse 不是呈现 FPS。

| 场景 | FX KiB/pulse：前 → 后 | 分配下降 | FX CPU ms/pulse：前 → 后 | pulse P95 ms：前 → 后 |
| --- | ---: | ---: | ---: | ---: |
| path | 300.0 → 157.2 | 47.6% | 2.46 → 2.43 | 8.91 → 8.98 |
| gradient | 300.1 → 157.2 | 47.6% | 2.80 → 2.57 | 8.97 → 8.92 |
| smil-transform | 376.4 → 247.3 | 34.3% | 3.35 → 3.60 | 8.33 → 8.57 |

CPU 没有一致改善：纯色基本相近，渐变较低，SMIL 中位数较高（3.35 → 3.60 ms/pulse）。短测和两个独立重复不足以保证该差异稳定，也不能无证据将其归因于 GC 或 JIT。本次仅确认分配下降，CPU 仍需继续优化。

当前第三方对照中，纯色分配本库约 157.25 KiB/pulse，fxsvgimage 157.27，JSVG JavaFX 32.27；纯色 FX CPU 本库 2.43 ms/pulse，fxsvgimage 1.15，JSVG JavaFX 0.93。SMIL 分配本库约 247.26 KiB/pulse，仍高于 fxsvgimage 的 141.59。功能/画质优势不能抵消这些性能差距。

本次 24 次渲染尝试的图像变化检查全部通过；23 次全程聚焦且未最小化。fxsvgimage 第一轮渐变有 12 个失焦样本，该轮完整保留并排除主汇总，渐变参考只有一个合格进程。其他组合均有两个合格进程。不能检测局部遮挡，也未控制其他应用的后台活动。

第一版仅将 localToSceneTransform 改为局部矩阵组合，实测未减少分配，未合入。该尝试的源码/JAR/JFR/原始数据仍保留在本地 `target/fxsvgimage-improvements/render-r1-after`、`render-r2-after`；第二版快速检查仅诊断，不作为正式成绩。主表使用最终固定 JAR。

## 验证与数据

全量 Maven verify：356 项通过。当前 JFR 的主要分配采样栈剩余在 Shape/Parent 的边界计算；此报告不把采样权重当作精确分配总量。

- [全部 24 次结果与窗口状态](fxsvgimage-improvements-2026-10-24-render-all.csv)
- [独立进程中位数、完整范围与合格次数](fxsvgimage-improvements-2026-10-24-render-summary.csv)
- [before 分配与 CPU 采样栈](cache-allocation-2026-10-24-before-jfr.txt)、[after 采样栈](cache-allocation-2026-10-24-after-jfr.txt)
- 本地完整记录：`target/fxsvgimage-improvements/render-r1-before`、`render-r2-before`、`final-render-r1`、`final-render-r2`，保留源码、JAR 哈希、环境、SVG、PNG、逐 pulse、JFR、日志和完成标记。

当前改动没有重新运行创建 JMH；旧创建报告属于此前 JAR，不能当作本次创建成绩。新增完整像素和完全静态基准将单列报告，不能用本表替代首次显示或空闲开销。
