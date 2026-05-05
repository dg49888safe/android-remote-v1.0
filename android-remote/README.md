# Android Remote Control System

基于 WebSocket 信令的 Android 远程管理平台，支持远程 Shell、文件管理、App 自动化操作。

## 架构总览

```
Web控制面板 (Vue3)
      ↓  WebSocket / REST
  VPS 信令服务 (Node.js + Express)
      ↓  WebSocket 长连接
  Android Agent (Kotlin 无障碍服务)
      ↓
  远管执行 / App自动操作
```

## 项目结构

```
android-remote/
├── server/          # Node.js 信令服务端
├── web/             # Vue3 控制面板前端
├── agent-android/   # Android Agent (Kotlin)
└── docs/            # 文档
```

## 快速开始

见 [docs/INSTALL.md](docs/INSTALL.md)
