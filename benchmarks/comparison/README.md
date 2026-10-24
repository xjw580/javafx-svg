# JavaFX SVG 跨库对照工具

该工具独立于 Maven 主项目，比较本库、fxsvgimage 1.7.3（包含 CSS parser）和 JSVG 2.2.0 的 JavaFX/AWT 两种后端。第三方 JAR 仅下载到 target，不改变主项目依赖。固定下载地址及 SHA-256 见 dependencies.json。

## 运行

Windows PowerShell，JDK 21+，需要 JavaFX 图形环境。先构建本库，再运行：

~~~powershell
mvn verify
./benchmarks/comparison/run.ps1 -JavaHome 'S:/jdk25.0.1_bellsoft'
~~~

默认使用 target/javafx-svg-1.0-SNAPSHOT.jar。也可用 -NativeJar 指向隔离构建产物，例如：

~~~powershell
./benchmarks/comparison/run.ps1 -NativeJar '隔离目录/target/javafx-svg-1.0-SNAPSHOT.jar' -Mode features
./benchmarks/comparison/run.ps1 -NativeJar '隔离目录/target/javafx-svg-1.0-SNAPSHOT.jar' -Mode benchmark -Counts '100,500' -Kinds 'path,gradient,smil-transform'
./benchmarks/comparison/run.ps1 -Mode benchmark -Providers jsvg-awt,jsvg-fx,fxsvgimage,native -Output 'target/svg-library-comparison/reverse-run'
~~~

- Mode 可选 both（默认）、features、benchmark。每个库/模式独立启动 JVM，顺序运行。
- WarmupSeconds 默认 2，MeasureSeconds 默认 5；所有库均为 512 MiB 固定堆、pulse=60、JFR profile。该 pulse 设置不保证实际锁定 60 Hz。
- Output 必须是新目录，默认带时间戳。依赖已下载且哈希正确时，不再访问网络。
- 特性检查保存完整 SVG、两个时间点的 PNG、PASS/FAIL/ERROR CSV；ERROR 另保存原因。PASS 只表示当前像素探针通过，FAIL 不等于整个元素完全不受支持。
- 性能检查先验证两个时间点的画面确有变化；失败场景跳过，并记录在 features.csv，不能作为“更快”的数据。
- 保持测试窗口可见；窗口状态计数用于标记失焦和最小化，不能检测遮挡。关闭窗口会中断运行。

## 测量边界

每次创建视图都从相同内存 XML 独立解析，统一关闭文档/资源复用与应用 Node.cache；本库内部 renderCacheEnabled 按 NativeRenderCache 指定。creation.csv 记录批量“解析＋建节点＋挂载”的 FX 线程耗时和分配；计数在 applyCss、layout、首绘之前停止。node_count 为随后 CSS/layout 后的渲染子树节点数，不含所有库共用的宿主 Pane。不是冷 JVM 启动时间，也不是最终保留堆大小。使用文档缓存的真实应用应另外测量复用模式。

path/gradient 使用相同的应用侧 Rotate 更新固定 24px 宿主，所有 100/500 图标都在窗口中。这时 Canvas/图像可复用像素表面，节点树仍可能重新栅格化；该场景不代表它们重绘 SVG 的速度相同。smil-transform 使用源 SVG 中的 2 秒旋转及各库自己的自动播放实现，按实际实现的语义执行，不保证完整 SVG 动画规范一致。

results.csv 包含 pulse 分位数、CPU、分配、GC、窗口状态。pulse 是 AnimationTimer 间隔，不是实际呈现 FPS。进程 CPU 百分比以一个核心为 100%；FX CPU 按当前线程计数器读取。MXBean 分配量近似，GC 毫秒为收集耗时，不等于停顿。

截图、写盘、预热与创建排除在完整的 AnimationBenchmarkPhase 事件之外，开始计数后跳过第一个 pulse 间隔，避免包含预热截图时间。所有模式均启用 JFR，配置相同，但高分配场景也会受到采样本身的额外开销。

汇总 CSV 位于输出目录根部；每个子目录保留原始 CSV、逐 pulse 明细、SVG、PNG、JFR 和 environment.txt。只有录制保存后才写 completed.txt；提前关闭写 aborted.txt，异常写 failure.txt。完成标记表示场景全部处理，不表示所有特性通过。复现时检查逐场景结果，并保持相同 JDK、JavaFX、硬件、屏幕缩放和窗口状态。

测量报告见 [当前版本跨库对照](../library-current-2026-10-24.md)，历史记录见 [旧报告](../library-comparison-2026-10-24.md)。


NativeRenderCache 可选 default/on/off。default 保留目标 JAR 的内部缓存默认策略；off 可用于纯向量对照，兼容没有该 API 的历史 JAR；on 要求 JAR 提供该开关。environment.txt 分别记录应用 Node.cache 和内部渲染缓存策略。开始每个场景时请求窗口焦点，但操作系统可能拒绝，仍须检查 unfocused_samples。历史对照报告中“缓存关闭”指当时 JAR 的实现，不与新增内部缓存混淆。

## 完整像素结果与完全静态显示

~~~powershell
./benchmarks/comparison/display-run.ps1 -JavaHome 'S:/jdk25.0.1_bellsoft' -NativeJar '目标.jar' -Smoke -Output target/svg-display-comparison/smoke
./benchmarks/comparison/display-run.ps1 -JavaHome 'S:/jdk25.0.1_bellsoft' -NativeJar '目标.jar' -Output target/svg-display-comparison/formal
~~~

工具比较 native、fxsvgimage、jsvg-fx，复用本目录已下载并校验的依赖。每模式三个独立 JVM，轮换模式顺序并在偶数轮反转场景；512 MiB 堆，默认 500 个图标，纯色和渐变分别测试。不会打开动画，不开启 JFR，不强制 GC。

- 完整像素：每批新建 Scene、独立解析并构建全部实例，执行 CSS/layout，再异步快照为 740×650 逻辑像素图像，计时结束后验证每个图标的像素。覆盖延迟创建的 skin/Canvas，包含快照与像素回读。至少预热 5 批并持续 10 秒，再采 5 批；预热也全部保存。这是端到端光栅结果就绪时间，不是屏幕实际呈现延迟或纯 GPU 绘制耗时。
- 完全静态：显示最后一批完整场景，预热 3 秒、测量 5 秒。测量区间没有 AnimationTimer、FX 定时轮询或快照；后台线程只在结束时读取 FX CPU/分配及进程计数。窗口焦点变化单独记录，仍无法探测局部遮挡。接近零的 CPU/分配可能低于计数器分辨率，不能据此断言绝对零成本。
- 输入相同；各模式使用独立文档，关闭自动播放和应用 Node.cache，本库内部缓存保留默认策略。光栅快照在无可见窗口时进行，目标逻辑像素比例为 1；静态窗口使用设备缩放，环境文件记录其值。不得将两种像素密度的绝对数值混为同一场景。

参数 Count、Repeats、WarmupBatches、RasterWarmupSeconds、MeasureBatches、IdleWarmupSeconds、IdleMeasureSeconds 可调整；正式比较必须相同。Smoke 使用 10 实例、一个预热/测量批次和 1 秒空闲区间，仅检查 API、像素、生命周期与输出完整性，不能用于性能结论。

每进程保留 raster.csv（全部批次）、idle.csv、PNG、输入 SVG、环境和日志，根目录保留源码、运行计划、协议与 JAR 哈希。仅全部进程完成才写 completed.txt。汇总应先在各独立进程内统计，再比较进程中位数与完整范围；批次不能充当独立 JVM 重复。短测与固定预热都不保证稳态，需审查迭代漂移和 GC，不能只挑最快值。

可单独使用 `-RecordJfr` 诊断完整光栅分配与调用栈。每批记录 RasterBenchmarkPhase，字段区分库、场景、预热和完成状态；分析时只选择 complete=true、warmup=false 的事件区间，截图验证与写盘在区间外。默认仍关闭 JFR；采样结果不能与关闭录制的数据直接合并排名。display-analyze.ps1 默认拒绝录制数据，诊断汇总需显式传 -AllowJfr。此开关不影响原有动画基准的录制策略。

