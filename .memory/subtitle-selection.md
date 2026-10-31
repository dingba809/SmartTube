---
name: subtitle-selection
description: CC 自动选轨顺序、手动翻译及共享字幕 ID 的处理
type: project
---

**Why**

用户要求短按 CC 依次选择普通字幕中文（直接显示）、普通字幕英文（LLM 翻译）、自动生成字幕英文（LLM 翻译）。其他语言只由用户手动选择。YouTube 自动生成轨 ID 可以是 a.en；多个机翻字幕共用原始轨 vssId，不能只按 ID 选轨或取下载地址。

**How to apply**

按实际可选轨下标选择，结合 vssId 与轨道名称匹配 MediaSubtitle 的语言代码和下载地址。自动生成中文字幕不抢占英文原文。两个字幕分类的手动选择共用入口，中文直接显示、非中文作为翻译原文；切轨时重置去重、缓存和预取，忽略旧请求回调。编译完成不自动安装电视，等待用户明确同意。

**Related**

[[upstream-sync]]
