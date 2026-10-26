package club.xiaojiawei.svg.demo

/** The same offline resources are used by the runnable browser and editor regression. */
internal object DemoRoadmapSamples {
    data class Entry(val key:String,val title:String,val file:String,val tip:String,val animated:Boolean=false)
    val entries=listOf(
        Entry("stage1","1 · 卷积与网格光照","/svg/convolve-lighting-filters.svg","对比锐化、浮雕、边缘检测与 kernelUnitLength 重采样。"),
        Entry("stage2","2 · 通道变换与混合","/svg/extended-color-filters.svg","查看通道映射与半透明扩展混合；编辑 SVG 比较颜色空间。"),
        Entry("stage3","3 · 图像、平铺与模板","/svg/filter-image-tile.svg","本地片段、内嵌图片与模板组合；外部资源仍默认关闭。"),
        Entry("stage4","4 · 渐变与跨图块图案","/svg/gradient-pattern-extensions.svg","双圆径向渐变、二维变换与显式 overflow；不声明浏览器像素一致。"),
        Entry("stage5-text","5 · 逐字定位与嵌套长度","/svg/positioned-text.svg","位置/旋转列表、嵌套 textLength 和装饰；复杂排版另做专项。"),
        Entry("stage5-css","5 · CSS 选择器","/demo/showcase-roadmap-css.svg","子代、相邻/一般兄弟和属性匹配；查看源码或打开实时编辑器。"),
        Entry("stage5-animation","5 · 滤镜参数动画","/svg/animated-filter-parameters.svg","播放、暂停、定位与回到起点，观察模糊、偏移、形态学和颜色。",true),
        Entry("stage6-activity","6 · Lucide / activity","/svg/real-corpus/activity.svg","真实上游原样 SVG；许可与来源保存在 real-corpus 目录。"),
        Entry("stage6-airplay","6 · Lucide / airplay","/svg/real-corpus/airplay.svg","比较主题、缩放和缓存；性能重复测量结果见 benchmarks/roadmap-stage6。"),
        Entry("stage6-atom","6 · Lucide / atom","/svg/real-corpus/atom.svg","真实图标回归不是浏览器或 Windows/GPU 一致性证明。"),
        Entry("stage6-cpu","6 · Lucide / cpu","/svg/real-corpus/cpu.svg","稳定画面与重复切换；持续编辑/恶意输入验证见交付报告。")
    )
}
