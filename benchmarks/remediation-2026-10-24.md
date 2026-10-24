# 对照缺口修复跟踪：2026-10-24

> 测试更正：旧 creation.csv 在创建计时前仅预热一个实例，且版本间场景顺序、JIT 状态和重复次数未统一。旧创建耗时及分配对比降级为探索记录，不能证明稳定提速、回退或“无法继续下降”。相关结论撤回；后续以独立创建基准的复测报告为准。此更正针对创建比较，功能回归与渲染采样需按各自协议评价。


本任务处理本库在对照报告中的特性缺口和性能问题，不修改第三方库。历史 CSV 保留，修复后以相同输入重新探测。

- [x] 直接样式属性查找，消除 AbstractMap 默认 get 的遍历。
- [x] XML 解析器线程隔离复用，限制 64 次/累计 1 MiB；大输入不保留，错误后丢弃。
- [x] feDropShadow（单原语、有界原生内核；linearRGB 限制见 README）。
- [x] animateMotion / mpath（常用路径与时序，限制见 README）。
- [x] textPath（单段原生字形沿路径排版，限制见 README）。
- [x] pattern（有界原生快照图案；两种坐标单位、变换与模板）。
- [x] 外部旋转与渐变渲染优化、质量及内存边界验证（可关闭的 ROTATE 缓存）。
- [x] 固定描边动画分配优化（相似变换走原生 Shape，非均匀矩阵保留轮廓）。
- [x] 最终特性与性能对照、27 项 Demo 与 341 项回归。

## 批量构建第一阶段

314 项 Maven verify 回归通过，包含深度限制变更、DTD/实体拒绝、失败恢复、并发和文档隔离。

500 个独立解析图标的批量创建 FX 分配量：

| 场景 | 修改前 MiB | 第一阶段 MiB |
| --- | ---: | ---: |
| 纯色 | 121.95 | 79.47 |
| 渐变 | 151.33 | 109.11 |
| SMIL | 124.16 | 81.34 |

对应耗时：纯色 261.1 → 203.0 ms，SMIL 210.4 → 135.1 ms；渐变 303.5 → 330.3 ms，没有观察到统一时延收益。外部旋转仍慢，继续处理渲染路径；没有把创建分配下降写成帧率提升。

同一 Windows/JDK/JavaFX/512 MiB/JFR 配置，100/500 图标，每场景预热 2 秒、测量 5 秒；正式采样均聚焦且未最小化。单轮记录不是置信区间。创建计时不含 CSS/layout/首绘，分配不是最终保留内存。

数据：[创建基线](remediation-2026-10-24-create-before.csv)、[第一阶段](remediation-2026-10-24-create-stage1.csv)、[渲染基线](remediation-2026-10-24-before.csv)、[第一阶段](remediation-2026-10-24-stage1.csv)。完整过程记录已归档至 target/svg-verification/remediation。

## 阴影

318 项 Maven verify 回归通过。新增阴影覆盖源图合成、偏移、滤镜区域及子区域裁剪、SVG CSS/currentColor/flood-opacity、应用 CSS，以及错误输入和内核上限。该项解决原报告 sRGB 阴影样本缺口，不表示完整滤镜管线兼容。

## 路径动画

324 项 Maven verify 回归通过。新增匀速弧长、曲线/圆弧测量、断开子路径、路径预算、mpath 优先级、切线方向、反向关键点、延迟/冻结、叠加、基础及动画变换组合、共享文档独立时钟等验证。没有引入 WebView 或逐帧 XML/路径解析。

## 沿路径文字

330 项 Maven verify 回归通过。新增水平/竖直/曲线文字、CSS 字体和填充、百分比偏移、锚点、pathLength、引用路径变换、路径外字形隐藏、共享文档隔离及不支持内容诊断验证。

## 图案绘制

335 项 Maven verify 回归通过。新增重复填充、描边/透明度、CSS 失效与稳态复用、坐标单位、viewBox、旋转、模板、实例隔离、循环和像素预算。新增 maxPatternPixels 选项，在所有图案实例间限制稳态像素量。

## 固定描边

336 项 Maven verify 回归通过，包含相似/非均匀/反射矩阵反复切换的像素比较；既有椭圆斜向线宽、虚线、渐变、动态几何、遮罩和文字测试继续通过。基准程序开始场景时显式请求焦点。正式分配与时延收益见下方最终复测，失焦采样单独保留。

## 原生渲染缓存

341 项 Maven verify 回归通过，包含缓存开关/绑定、预算和放大回退、缩放重绘、字体/绘制/几何 CSS 更新、文档替换与脱离场景释放，以及旋转像素差异检查。仅内部刚性变换轨道参与缓存；缩放/斜切或内容动画保留向量。关闭缓存会卸载逐帧矩阵/边界监听。缓存预算为保守像素估算，未测精确 GPU 显存；旋转 AA 有差异，不能宣传像素完全相同。

第一轮 500 图标三场景均无失焦/最小化，pulse P95 约为纯色 9.41、渐变 9.06、SMIL 8.37 ms；第二轮约为 14.76、9.32、10.70 ms，显示波动。全部 CSV 及最终 JAR 的缓存开关对照见下文。100 图标第一轮存在失焦，不作为正式时延结论。

## 最终验收

最终源码与隔离构建逐文件比对一致（忽略换行符）。使用当前工作 POM 的 Kotlin 2.2.0、JavaFX 21.0.8、JDK 25.0.1 和 Java 21 字节码目标，Maven verify **341 项测试通过**。POM 及原有用户改动保持不变。

原报告的 **18/18 特性探针通过**：[最终特性 CSV](remediation-2026-10-24-features.csv)。四项缺口均提供常用子集和明确诊断；通过样本不等于完整实现 SVG 标准。主要边界：

| 能力 | 已支持 | 保留限制 |
| --- | --- | --- |
| pattern | 填充/描边、两种坐标单位、viewBox、二维变换、本地模板、CSS 失效缓存 | tile 边界裁剪；像素/节点预算；跨 tile overflow 不支持 |
| feDropShadow | 原生阴影、偏移、两轴模糊、flood 样式、输入/输出区域 | 单原语、内核上限；sRGB 支持多色；默认 linearRGB 仍要求源/阴影颜色匹配 |
| textPath | 本地路径、自身变换、偏移、锚点、pathLength、原生字素与字体/CSS | 单段文字；不支持混合 tspan、stretch、复杂连写/双向排版 |
| animateMotion | path/mpath、弧长匀速、关键点、缓动、切线旋转、叠加及共用时钟 | 不支持 by、累积、百分比坐标和变换后的 mpath |

Demo 已扩展到 **27 个离线示例**；实际运行 smoke，全部零诊断，检查动画变化、主题、共享文档与独立节点、源码、布局和本地加载。四个新示例的快照已逐一查看。

## 500 图标：修复前与最终结果

仍使用同一对照 SVG、24px 图标、全部可见、独立 JVM、512 MiB、JFR、2 秒预热/5 秒测量、1.5 输出比例。主表只接受 unfocused_samples=0 且 iconified_samples=0 的记录；两列均符合。pulse 是 AnimationTimer 间隔，不能换算为实际呈现 FPS。

| 场景 | 修复前 P95 ms | 最终 P95 ms | 修复前 FX CPU ms/pulse | 最终 FX CPU ms/pulse |
| --- | ---: | ---: | ---: | ---: |
| 纯色外部旋转 | 48.30 | 8.55 | 3.10 | 3.15 |
| 渐变外部旋转 | 97.22 | 8.53 | 3.23 | 3.46 |
| 内部 SMIL 旋转 | 32.39 | 9.66 | 4.24 | 4.59 |

基线为 before 的完整 100/500 × 三场景。最终渐变与 SMIL 为 render-final；其纯色场景全部失焦，保留原始行但从主表排除。纯色随后单独复采：先运行 1 图标前置场景，再运行 500 图标，取得有效记录。采用该预先按焦点判断的补采规则，没有从多个数值中挑最快值。

收益主要来自复用栅格化结果。FX CPU/pulse 没有统一下降，FX 分配反而从约 157/157/232 KiB/pulse 增至 294/294/384 KiB/pulse；更密集的 pulse 也会增加每秒更新工作。初始两轮有效 500 图标 P95 分别为 9.41/9.06/8.37 和 14.76/9.32/10.70 ms，说明短测存在波动。没有置信区间，不据此宣称全场景优于其他库。

最终同一 JAR 的缓存关闭对照：渐变 P95 96.94 ms、SMIL 32.43 ms，默认开启对应 8.53/9.66 ms；两组均无失焦/最小化。关闭缓存的纯色行有部分失焦，不用于该因果对照。关闭后卸载矩阵监听，FX 分配恢复至约 157/157/232 KiB/pulse。

数据：[修复前](remediation-2026-10-24-before.csv)、[最终有效行及来源](remediation-2026-10-24-final.csv)、[最终完整原始行](remediation-2026-10-24-final-raw.csv)、[纯色补采](remediation-2026-10-24-path.csv)、[同 JAR 向量对照](remediation-2026-10-24-vector.csv)、[初轮](remediation-2026-10-24-cache-first.csv)、[第二轮](remediation-2026-10-24-cache-repeat.csv)。

## 批量构建：分配下降，耗时仍有边界

500 次独立解析/构建的最终 FX 分配量：

| 场景 | 修复前 MiB | 最终 MiB |
| --- | ---: | ---: |
| 纯色 | 121.95 | 89.84 |
| 渐变 | 151.33 | 127.02 |
| SMIL | 124.16 | 93.21 |

最终加入原生缓存维护后，分配比第一阶段增加，仍低于修复前。该项没有证明批量创建时间全面改善：最终这轮创建计时约 353/317/197 ms，修复前约 261/304/210 ms。最终 JVM 直接从 500 开始，历史基线先运行 100 图标三场景；JIT/类加载状态不同，不能计算统一耗时收益。即使缓存阶段后移也不会消除首绘工作，本报告保留真实阶段定义，不通过移动计时边界宣传提速。

创建不含 CSS/layout/首绘。每视图场景节点由 8 增至 9（新增独立定义容器），节点数量不代表纹理、原生对象或保留堆。原生缓存仍有 AA 与内存代价，预算及关闭方式见 README。

数据：[创建基线](remediation-2026-10-24-create-before.csv)、[最终创建](remediation-2026-10-24-create-final.csv)、[初轮完整创建](remediation-2026-10-24-create-cache-first.csv)。

## 500 个固定描边混合动画

使用原有 css-fixed：CSS 均匀缩放与矩形 width 动画同时运行。优化前 JAR 为 pattern 完成后的构建，仅描边算法与后续缓存实现不同；该混合动画在最终实现中不会启用内部位图缓存。相同采样程序分别运行 1、500 图标，均预热 2 秒、测量 5 秒。

| 指标 | 原轮廓算法 | 原生相似变换分支 |
| --- | ---: | ---: |
| FX 分配 B/pulse | 28,985,380 | 1,648,992 |
| FX CPU ms/pulse | 35.09 | 9.46 |
| pulse P95 ms | 61.41 | 15.64 |
| GC 次数 / 累计 ms | 11 / 37 | 3 / 12 |
| 失焦 / 最小化样本 | 0 / 0 | 0 / 0 |

本场景分配下降约 **94.3%**。源 Shape 与几何绑定复用，按比例调整 native stroke；非均匀/斜切仍走原有布尔轮廓，不能把本收益外推至所有矩阵。更早失焦的 100/500、10 秒预热等探索轮次保留在完整归档，不用于本表。

数据：[固定描边前](remediation-2026-10-24-nss-before.csv)、[固定描边后](remediation-2026-10-24-nss-after.csv)。

## 复现与归档

~~~powershell
mvn verify
./benchmarks/comparison/run.ps1 -Mode features -Providers native
./benchmarks/comparison/run.ps1 -Mode benchmark -Providers native -Counts '100,500'
./benchmarks/comparison/run.ps1 -Mode benchmark -Providers native -Counts '100,500' -NativeRenderCache off
mvn -Pdemo test-compile exec:java "-Dexec.args=--smoke"
mvn -Pdemo test-compile exec:java "-Dexec.args=--animation-benchmark --kinds=css-fixed --counts=1,500 --warmup-seconds=2 --measure-seconds=5"
~~~

正式采样使用独立 JVM，不与编译或其他重负载并行；Maven exec 便于交互复跑但会复用 Maven JVM。原始 JFR、逐 pulse CSV、环境/哈希、SVG/PNG、全部失败和失焦轮次、隔离源码、构建日志与 Demo 快照归档于本地 target/svg-verification/remediation/。首次缓存轮次保留较宽的动画候选策略，最终只允许刚性变换；本表三个旋转场景均属于允许集合。第三方库的历史结果保留在原对照报告，本次未更换第三方版本。
