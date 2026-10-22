# 前端风格重做 — 概览

## 第二轮：GitHub 风格
- 新建 `team-project/src/main/resources/static/github.html`：GitHub Primer 风格页面（浅色画布 #f6f8fa、细边框卡片、下划线 Tabs、绿色主按钮、议题列表、52 周贡献热力图、语言占比条、侧栏 About/Releases/贡献者）。
- 交互：Star 切换 + 计数、Tab 切换、"/" 快捷键聚焦搜索、成功提示条。

## 第一轮：液态玻璃风格
- 新建 `team-project/src/main/resources/static/glass.html`：苹果 Liquid Glass（液态玻璃）风格的独立前端页面，自包含（内联 CSS/JS，无外部依赖）。
- 页面不含任何项目说明性文字，仅保留通用 UI 文案（首页 / 数据 / 消息 / 设置等）。

## 关键设计
- 玻璃材质：`backdrop-filter: blur(28px) saturate(180%)` + 半透明白色渐变 + 内嵌高光边框 + 柔和外阴影。
- 顶部镜面高光与跟随鼠标的流光（`::after` 径向渐变，CSS 变量 --mx/--my）。
- 背景：4 个彩色模糊光斑 + SVG 噪点纹理，鼠标移动产生视差。
- 交互：macOS 风格 Dock 放大、圆环进度动画、柱状图入场动画、开关切换、实时时钟。

## 使用方式
- 直接双击打开 HTML 预览；或放入 Spring Boot `static/` 后访问 `/glass.html`（已放置于此路径）。
- 若需替换现有 `index.html` 的整体风格，可在此玻璃样式基础上移植 `app.js` 的视图结构（待用户确认后再做）。
