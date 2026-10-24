# 编辑器元数据兼容性修复：2026-10-24

修复 Inkscape/Sodipodi 等外部命名空间私有数据导致严格模式拒收的问题。SVG 允许外部命名空间私有数据，通常保留在文档模型中但排除渲染，参见 [SVG 1.1 扩展规则](https://www.w3.org/TR/SVG11/extend.html)、[SVG 2 文档结构](https://www.w3.org/TR/SVG/struct.html)。

## 行为

- 外部命名空间节点及其子树不渲染，也不参与 SVG 样式表编译。
- 形状、文字、textPath 和滤镜内部的私有子元素不会被误判为不支持的 SVG 绘图或动画原语。
- 原始解析模型和索引继续保留，字节、深度、元素数量、DTD 和外部实体限制不变。
- SVG 命名空间中的未知绘图元素、foreignObject、script 仍按既有规则诊断；这不是增加 foreignObject 或脚本支持。
- 4 项新回归覆盖私有图形排除、私有样式隔离、叶子/文字/滤镜子元素，以及解析边界。

## 原始文件对照

使用已下载并通过固定发布包 SHA-256 校验的 fxsvgimage 1.7.3 samples 中全部 16 个 SVG，输入内容未改写。对照本库修复前、修复后及 fxsvgimage；本库开启 strict，fxsvgimage 使用自己的加载 API。关闭动画，按自然尺寸渲染后统一缩略到 256px 宽，保留每个结果 PNG。

修复前 JAR：488E0D171D4D12F7B7FA6B95AE59E3251DABF0A8C36D2B08EAA04CCFBEF4F457。

修复后 JAR：E850B0491939E8EB1CE6A43C1F64DAB409F2CF7CC4B316D8D813C5D1AC4C3BDA。

| 对象 | 非空图像 NONEMPTY | 空图像 EMPTY | 加载/严格校验异常 ERROR |
| --- | ---: | ---: | ---: |
| 本库修复前 | 9 | 1 | 6 |
| 本库修复后 | 12 | 2 | 2 |
| fxsvgimage | 14 | 2 | 0 |

这张表只验证实际文件能否加载并形成图像，不是保真度排名或完整 SVG 兼容率。NONEMPTY 不证明图像正确；EMPTY 也不等于失败。例如 rect.svg 为 fill=none 且没有描边，本来就可为空。backlight.svg 的渐变末端透明且路径坐标超出渐变区间，不应自动把其空画面判成缺陷。所有差异保留，尚未与独立参考渲染器逐像素核对。

本次明确消除 bluerect.svg、circle.svg、ellipse.svg、backlight.svg 的私有 namedview 异常；前三个变为非空图像，backlight.svg 变为空图像而非异常。其余差异单列：

- ArmourHostile.svg：本库严格模式仍报告未支持的 enable-background CSS 属性。
- issue1.svg：包含 Unicode 减号 U+2212（−）；当前数值/路径解析拒收，不能描述为不支持标准 ASCII 负数。
- cross.svg：没有 viewBox，原始内容从 translate(300,300) 开始。初查固定 256×256 视口会裁掉内容，因此已改为按自然尺寸渲染后缩略；该样例修复前后均为非空。
- materialsymbol.svg：本库非空，fxsvgimage 在此检查中为空；没有据此否定第三方整个路径能力。

初查固定视口的结果保留在本地 target/fxsvgimage-improvements/editor-corpus，未作为主表。正确自然尺寸检查位于 editor-corpus-natural；空图像与异常分开记录，未删除任何输入。

## 验证与复现

全量 Maven verify：361 项通过；原有 27 个 Demo 冒烟检查通过。feOffset 演示及元数据样例 PNG 已查看。本次元数据补丁没有重跑性能基准；[性能报告](motion-cache-2026-10-24.md) 的数值仍明确对应缓存优化版本的固定 JAR，不能替换哈希冒充新测量。

[原始结果](editor-metadata-2026-10-24-corpus.csv)、[输入 SHA-256](editor-metadata-2026-10-24-inputs.csv)、[独立检查源码](comparison/EditorCorpusCheck.java)。

检查源码不参与 Maven 主项目或运行时依赖。使用 JDK 21+，classpath 包含目标本库 JAR 和 comparison/dependencies.json 指定的完整依赖：

~~~powershell
javac -encoding UTF-8 -cp '完整classpath' -d '检查类目录' benchmarks/comparison/EditorCorpusCheck.java
java --enable-native-access=ALL-UNNAMED -cp '检查类目录;完整classpath' EditorCorpusCheck native-after target/svg-comparison-libs/fxsvgimage/samples '新结果目录'
~~~

provider 参数使用 native 前缀时运行本库，其他值运行 fxsvgimage；更换本库 JAR 可复测前后版本。每个模式使用独立 JVM，输出 CSV 和 PNG；此工具没有性能计时，不用于任何耗时比较。
