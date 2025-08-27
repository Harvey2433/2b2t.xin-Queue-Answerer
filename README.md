# XinQueueAnswerer

## 简介

XinQueueAnswerer 是一个专为 2b2t.xin 服务器设计的 Minecraft Fabric 模组。该模组旨在提供一个全自动的排队答题解决方案，使排队答题自动化。

## 功能

- **全自动答题**：内置了 2b2t.xin 排队问题库，能够自动识别并回答排队时出现的问题。
- **智能排队管理**：
  - 当玩家进入排队队列时，系统会自动启动答题功能。
  - 在排队即将结束时，系统会自动关闭并提供一份排队统计报告。
  - 优化的坐标和消息检测机制，避免在排队时间极短时出现误判。
- **优化:**
  - 在连接服务器时自动发送欢迎信息，且该信息只会发送一次，不会因服务器重定向而重复出现。


## 使用方法

1. 确保你已安装 Minecraft Fabric 和 Fabric API。
2. 将 XinQueueAnswerer 模组文件放入 `.minecraft/mods` 文件夹中。
3. 启动游戏，并连接到 `2b2t.xin` 服务器。
4. 模组会自动运行。

Powered By Maple Bamboo Team
