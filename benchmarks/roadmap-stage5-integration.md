# 阶段 5 集成说明

本阶段组合文字、静态 CSS 和 SMIL 滤镜参数扩展；具体已实现白名单与专项边界见同目录三个 stage5 专题说明。

跨阶段回归包含：继承 filter 模板中的本地 feImage 与动态偏移组合，往返 seek 不丢失原始资源/样式身份；feImage 创建软件径向渐变后滤镜构造失败，清理径向绘制器、动画绑定及滤镜状态，之后的合法渐变仍正常显示。CSS 属性子串搜索在调用平台 contains 前按 Long 长度乘积预付最坏比较成本，重复前缀恶意输入在既有预算内拒绝。class 字符串首次按元素身份分词前也计费；同一元素复用缓存，重复 use 合成身份共享总预算，候选规则构造阶段的超限与匹配阶段一起诊断。

旧测试的两项预期随规范支持范围更新：surfaceScale 动画现在是合法已实现项，原负例改为仍未实现的 kernelUnitLength 动画；use 的 `.host .part` 复杂选择器不再穿透实例边界，独立动画上下文测试改用宿主动画和可继承 fill，保留原有两种时长、共享文档和独立时钟断言。并非删除失败断言或扩大安全上限。

第一轮文字编译修正了局部 children 变量遮蔽 Group.children 的调用。所有初始失败日志均保留。最终测试计数及干净补丁复现以交付 verification.json 为准；不把此说明当作未执行步骤的通过证据。

实际 PNG：stage5-positioned-text-preview.png、stage5-text-editor-preview.png、stage5-filter-animation.png。编辑器回归覆盖修改文字、重复调整窗口、错误输入保留旧画面及恢复，实际截图已检查。
