---
name: upstream-sync
description: SmartTube 上游同步与本 fork 字幕增强的保留方式
type: project
---

**Why**

本仓库 origin 为 dingba809/SmartTube；原始上游为 yuliskov/SmartTube。字幕翻译、批量预取及 CC 自动选轨属于本 fork 的定制提交。直接将 master 重置为上游会丢失这些功能。

**How to apply**

使用 merge 合入上游 master，保留定制历史；先用 git merge-tree --write-tree 预演。合并后使用 git submodule update --init --recursive 同步 MediaServiceCore 与 SharedModules，不能只更新主仓库。检查字幕选轨、预取、设置入口及 CI 的交集，并运行字幕测试、lint、构建。不要因完成编译自动安装电视 APK，安装应遵循会话授权。

**Related**

[[subtitle-translation]]
