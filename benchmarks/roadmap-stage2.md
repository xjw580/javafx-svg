# 阶段 2：通道映射与扩展混合

实现 feComponentTransfer 的 identity、table、discrete、linear、gamma；缺失通道保持原值，重复通道使用最后一个声明。按非预乘 RGBA 计算，再以输出 alpha 预乘；RGB 运算使用 color-interpolation-filters 指定的颜色空间。

feBlend 增加 overlay、color-dodge、color-burn、hard-light、soft-light、difference、exclusion、hue、saturation、color、luminosity，连同原有五种共 16 种。非分离模式使用规范 SetLum/SetSat/ClipColor，不用 HSL 转换近似。no-composite 与 plus-lighter 不在本阶段范围，明确诊断。

所有计算维持原有像素与每视图工作预算；扩展混合按保守额外工作量收费，极端参数必须在产生非有限像素前处理。没有默认外部资源加载。

测试包含纯数值、透明度、颜色空间、命名/隐式输入、裁剪、无效值及实际编辑器错误恢复；extended-color-filters.svg 为本项目编写的八面板演示，不冒充第三方真实样本。Linux 软件渲染验证，不代表 Windows/GPU/浏览器逐像素一致。

规范依据：
- https://www.w3.org/TR/filter-effects/#feComponentTransferElement
- https://www.w3.org/TR/compositing-1/#blending
