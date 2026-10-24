# 对照缺口修复跟踪：2026-10-24

本任务处理本库在对照报告中的特性缺口和性能问题，不修改第三方库。历史 CSV 保留，修复后以相同输入重新探测。

- [x] 直接样式属性查找，消除 AbstractMap 默认 get 的遍历。
- [x] XML 解析器线程隔离复用，限制 64 次/累计 1 MiB；大输入不保留，错误后丢弃。
- [x] feDropShadow（单原语、有界原生内核；linearRGB 限制见 README）。
- [x] animateMotion / mpath（常用路径与时序，限制见 README）。
- [x] textPath（单段原生字形沿路径排版，限制见 README）。
- [ ] pattern。
- [ ] 外部旋转与渐变渲染优化、质量及内存边界验证。
- [ ] 固定描边动画分配优化。
- [ ] 最终特性与性能对照、Demo 与完整回归。

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

数据：[创建基线](remediation-2026-10-24-create-before.csv)、[第一阶段](remediation-2026-10-24-create-stage1.csv)、[渲染基线](remediation-2026-10-24-before.csv)、[第一阶段](remediation-2026-10-24-stage1.csv)。完整过程记录暂存 verification-svg-remediation，最终归档至 target/svg-verification/remediation。

## 阴影

318 项 Maven verify 回归通过。新增阴影覆盖源图合成、偏移、滤镜区域及子区域裁剪、SVG CSS/currentColor/flood-opacity、应用 CSS，以及错误输入和内核上限。该项解决原报告 sRGB 阴影样本缺口，不表示完整滤镜管线兼容。

## 路径动画

324 项 Maven verify 回归通过。新增匀速弧长、曲线/圆弧测量、断开子路径、路径预算、mpath 优先级、切线方向、反向关键点、延迟/冻结、叠加、基础及动画变换组合、共享文档独立时钟等验证。没有引入 WebView 或逐帧 XML/路径解析。

## 沿路径文字

330 项 Maven verify 回归通过。新增水平/竖直/曲线文字、CSS 字体和填充、百分比偏移、锚点、pathLength、引用路径变换、路径外字形隐藏、共享文档隔离及不支持内容诊断验证。
