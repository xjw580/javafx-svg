# 独立创建基准（JMH 1.37）

旧 comparison 工具的 creation.csv 只有一个构建预热，且前后运行顺序不一致，不能用于稳定的创建性能比较。本工具重新测量创建，不复用渲染预热或旧计时结果。

## 固定协议

- 同一 Java/JFX/Kotlin 运行时、512 MiB 堆、相同 SVG 与 500 个实例。
- 每种类型、阶段、版本、重复均使用新 JMH child JVM；默认每个组合独立重复 3 次。
- 每个子进程先执行 **5 × 1 秒创建预热**，再执行 **5 × 1 秒正式测量**。记录全部预热/测量行和操作次数。
- 三种 SVG：纯色、渐变、SMIL；三种阶段见下表。每个 JVM 只测一个组合，消除同 JVM 中场景先后对 JIT 的污染。
- 版本 A/B 交错，组合顺序按固定种子 20261024 打乱；串行运行，不与编译或其他性能任务并行。
- 不强制 GC；保留整批对象直到 invocation teardown，防止消除创建、提前释放改变存活量。teardown 在计时外清除视图/场景。
- 不创建 Stage，不播放动画，不执行 CSS/layout/首绘或快照；无窗口焦点条件。所有核心工作在 FX 线程执行。
- 两个版本均不启用 JFR，避免把旧采样开销带入新协议。

| 阶段 | 计入的工作 |
| --- | --- |
| parse | 每实例重新 parse 内存 XML，包含 AST、默认选项和解析器工作 |
| buildShared | 所有实例共享一个已解析文档，预热后共享文档缓存；节点及绑定逐实例创建 |
| totalMounted | 每实例独立 parse 和建节点，创建 Scene、宿主 Pane/Rotate 并挂载；无 CSS/layout/绘制 |

阶段的缓存与生命周期不同，不能相加或相减推算占比。buildShared 不是 fresh-document 渲染器构建成本。

JMH 主指标为 ms/整批，包含 FX dispatch、等待及计数/记录包装；FX 原始行在 FX 线程内另取 wall/CPU/allocated bytes，排除队列等待、记录和 teardown。两者分开报告，不能混称。GC 记录只表示采集区间的计数/累计时间，不是停顿。

JMH 不保证预热后必然稳态。分析时检查各迭代、三个进程的分散及预热趋势；波动较大时应延长预热并重测，不以单个最好值替代总体结果。不将重复迭代当成独立 JVM 样本，也不把本结果外推至冷启动或首次可见绘制。

## 运行

~~~powershell
# 首次用旧 comparison 工具准备固定 JavaFX/Kotlin 依赖；不修改库依赖
./benchmarks/creation/run.ps1 -Smoke -Output target/svg-creation/smoke
./benchmarks/creation/run.ps1 -BeforeJar target/svg-verification/remediation/before.jar -AfterJar target/javafx-svg-1.0-SNAPSHOT.jar -Output target/svg-creation/formal
~~~

默认 BeforeJar 是此次修复前归档 JAR（116c934），AfterJar 为当前构建；显式参数可比较其他版本。需要先构建本库并准备 comparison/dependencies.json 对应的本地运行时。JMH 的依赖独立置于 target/svg-creation-libs，并校验本目录清单中的 SHA-256，不进入主项目。

Smoke 使用 10 实例、1 × 100ms 预热及测量、各一个新进程，只验证编译/API/计数/退出和结果完整性，**不能用于性能结论**。正式协议默认共 54 个 JMH child JVM。只有全部组合成功且 JSON/FX 行完整时写 completed.txt。

输出保留运行计划、源文件、依赖/JAR 哈希、协议、各进程 JMH JSON/日志、FX 每操作原始 CSV、环境说明及固定 SVG。原始记录不删异常值；汇总以进程为单位，给出全部重复与范围。

方法依据：[JMH fork 隔离示例](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java)、[Invocation fixture 限制](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_07_FixtureLevelInvocation.java)。本工具每次操作是整批毫秒级构建，teardown 与原始 FX 计时分离。

## 汇总

~~~powershell
./benchmarks/creation/analyze.ps1 -InputDirectory target/svg-creation/formal
~~~

分析器要求完成标记、每组合完整的三个独立进程及正式迭代；默认拒绝 Smoke。生成 forks.csv（每进程）、iterations.csv（包含预热）、summary.csv（进程中位数及完整范围）和 warnings.txt。漂移提示是审查入口，不自动剔除样本或证明已经/没有达到稳态；若存在明显持续趋势，需要延长预热再验证。
