# 阶段 5：滤镜参数动画

支持 `<animate>` 直接作为滤镜原语子元素，或以本地 href/xlink:href 指向该原语。每个 SvgView 使用独立时钟；原始 SvgElement、共享文档和样式缓存不被修改。

已实现参数：
- feGaussianBlur：stdDeviation（一或二个数值）。
- feOffset：dx、dy。
- feDropShadow：stdDeviation、dx、dy、flood-color、flood-opacity。
- feMorphology：radius（一或二个数值）。
- feDisplacementMap：scale。
- feFlood：flood-color、flood-opacity。
- feDiffuseLighting：surfaceScale、diffuseConstant、lighting-color。
- feSpecularLighting：surfaceScale、specularConstant、specularExponent、lighting-color。

使用现有有限 SMIL timing 子集，包括 from/to/by、values、keyTimes、linear/discrete/spline、begin、repeatCount 和 fill freeze/remove。数值滤镜参数动画要求无单位值；颜色使用 color-interpolation 的颜色插值。不同长度的单/双数值端点先扩展为双数值，避免各向异性参数失真。常量范围在端点及每次采样时验证。结构性 in/in2/result/href/type/kernel 变化不支持，明确诊断；光源子节点本身动画仍未在本阶段实现。CSS @keyframes 滤镜参数动画不是本实现的一部分。

动画滤镜使用受预算约束的软件链，避免静态 JavaFX blur/offset 快路径忽略动画。时间变化参与光栅缓存键，预算超限时清除当前输出；回退到有效时间可重新渲染。现有像素/工作上限未提高。

测试覆盖实际像素 seek/reverse-seek、重复/fill、pause、独立共享文档视图、向量参数、颜色与透明度、照明常量、结构性诊断、预算失败恢复及确定性示例缩放。

规范参考：https://www.w3.org/TR/SVG11/animate.html ，https://www.w3.org/TR/filter-effects/ 。
