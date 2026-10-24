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
