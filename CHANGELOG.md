## 0.2.1
- 新增 MixinBlockItemPlacement，在 interactBlock 内部自动修正方块状态
- 修复半砖、楼梯、朝向性方块放置方向不正确的问题
- 移除 Printer.java 中临时开关 EASY_PLACE_MODE 的逻辑
- 不再需要依赖 Forgematica 的 Easy Place Mode 开关

## 0.2.0
- 完全重构放置管线，使用 Forgematica 的 MaterialCache + 精准放置协议
- 支持 V2/V3/Slab-Only 精准放置协议，兼容性更佳
- 支持浮空放置
- 修复服务器延迟导致的重复放置和错位问题
- 修复 WATERLOGGED 方块误判
- 移除旧的 Guide + Action 系统，代码更简洁
- 移除 modpublisher 插件

## 0.1.0
- port to 1.20.1 forge