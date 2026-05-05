# 安装与部署指南

## 目录
1. [环境要求](#1-环境要求)
2. [VPS 服务端部署](#2-vps-服务端部署)
3. [Web 控制面板部署](#3-web-控制面板部署)
4. [Android Agent 安装](#4-android-agent-安装)
5. [宝塔面板配置](#5-宝塔面板配置)
6. [使用说明](#6-使用说明)
7. [常见问题](#7-常见问题)

---

## 1. 环境要求

| 组件 | 要求 |
|------|------|
| VPS 服务端 | Node.js 18+、npm、Linux (Ubuntu 20.04+) |
| Web 前端 | Node.js 18+（仅构建时需要） |
| Android | Android 8.0 (API 26) 及以上 |
| 网络 | VPS 开放 3000 端口（或 Nginx 反代 80/443） |

---

## 2. VPS 服务端部署

### 2.1 安装 Node.js
```bash
# Ubuntu / Debian
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt-get install -y nodejs

# 验证
node -v   # v18.x.x
npm -v    # 9.x.x
```

### 2.2 部署服务
```bash
# 上传项目到 VPS
cd /www/wwwroot/         # 或任意目录
unzip android-remote.zip
cd android-remote/server

# 复制并编辑配置
cp .env.example .env
nano .env
```

**.env 必填项：**
```
PORT=3000
JWT_SECRET=请替换为随机长字符串如：xK9mP2qL8nR5vT1w
ADMIN_USER=admin
ADMIN_PASS=你的密码
```

```bash
# 安装依赖
npm install

# 使用 PM2 守护进程运行（推荐）
npm install -g pm2
pm2 start index.js --name android-remote
pm2 startup   # 设置开机自启
pm2 save

# 验证服务
curl http://localhost:3000/api/health
```

### 2.3 开放防火墙端口
```bash
# Ubuntu UFW
sudo ufw allow 3000/tcp
sudo ufw reload

# 宝塔面板：安全 → 放行端口 3000
```

---

## 3. Web 控制面板部署

### 3.1 本地构建
```bash
cd android-remote/web
npm install
npm run build
# 生成 dist/ 目录
```

### 3.2 部署到 VPS（Nginx / 宝塔）
```bash
# 将 dist/ 目录上传到网站根目录
scp -r dist/* root@your_vps:/www/wwwroot/remote-panel/
```

**Nginx 配置（宝塔建站后修改）：**
```nginx
server {
    listen 80;
    server_name your_domain.com;
    root /www/wwwroot/remote-panel;
    index index.html;

    # 前端路由（Vue Router hash 模式无需此配置）
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 代理 API 到 Node.js
    location /api/ {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
    }

    # 代理 WebSocket
    location /ws {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }
}
```

### 3.3 开发模式（本地调试）
```bash
cd web
npm run dev
# 访问 http://localhost:5173
```

---

## 4. Android Agent 安装

### 4.1 用 Android Studio 构建
1. 用 Android Studio 打开 `agent-android/` 目录
2. 等待 Gradle 同步完成
3. 点击 **Build → Build APK**
4. APK 路径：`app/build/outputs/apk/debug/app-debug.apk`

### 4.2 安装到手机
```bash
# 通过 ADB 安装
adb install app-debug.apk

# 或直接传到手机安装（需开启"未知来源"）
```

### 4.3 配置 Agent
1. 打开 **RemoteAgent** App
2. **服务器地址** 填写：`ws://你的VPS_IP:3000/ws`
3. **设备名称** 随意填写（用于在控制台识别）
4. 点击 **启动 Agent**

### 4.4 开启无障碍服务（App 自动化必须）
```
设置 → 无障碍 → 已安装的服务 → RemoteAgent 自动操作 → 开启
```
> ⚠️ 部分厂商（小米/华为）路径不同，搜索「无障碍」即可找到

### 4.5 保活设置（防止被系统杀掉）
- **小米**: 设置 → 电池 → 应用电池设置 → RemoteAgent → 无限制
- **华为**: 设置 → 电池 → 启动管理 → RemoteAgent → 手动管理全部开启
- **OPPO/vivo**: 设置 → 电池 → 耗电保护 → 将 RemoteAgent 加入白名单

---

## 5. 宝塔面板配置

### 5.1 Node.js 项目管理
1. 宝塔 → 软件商店 → 搜索 **PM2管理器** → 安装
2. PM2 管理器 → 添加项目：
   - 项目路径：`/www/wwwroot/android-remote/server`
   - 启动文件：`index.js`
   - 项目名称：`android-remote`

### 5.2 网站配置
1. 宝塔 → 网站 → 添加站点（填写域名）
2. 修改站点配置，加入上方 Nginx WebSocket 代理配置
3. 可申请 **Let's Encrypt SSL** 证书，将 `ws://` 改为 `wss://`

---

## 6. 使用说明

### 登录控制台
- 浏览器访问：`http://你的域名` 或 `http://VPS_IP`
- 用户名 / 密码：`.env` 中配置的 `ADMIN_USER` / `ADMIN_PASS`

### 功能说明

| 功能 | 说明 |
|------|------|
| Shell 终端 | 在设备上执行任意 Shell 命令，结果实时返回 |
| 模拟点击 | 输入屏幕坐标，模拟用户点击 |
| 输入文字 | 向当前焦点输入框发送文字 |
| 启动 App | 通过包名启动目标应用 |
| 文件管理 | 浏览设备文件目录（需存储权限） |

### 常用 Shell 命令示例
```bash
# 查看设备信息
getprop ro.product.model

# 查看运行进程
ps -ef | grep com.example

# 截图（存到 /sdcard）
screencap /sdcard/shot.png

# 查看已安装应用
pm list packages

# 安装 APK
pm install /sdcard/app.apk

# 查看网络信息
ip addr show
```

---

## 7. 常见问题

**Q: Android Agent 连接失败？**
- 确认 VPS 防火墙已开放 3000 端口
- 检查 App 中填写的服务器地址格式是否正确（`ws://` 前缀）
- 查看服务端日志：`pm2 logs android-remote`

**Q: Shell 命令无权限？**
- 普通命令无需 root，系统级操作（如 `/data` 目录）需要 root
- 已 root 设备：Shell 命令前加 `su -c "命令"`

**Q: 无障碍服务被系统自动关闭？**
- 在电池设置中将 App 加入白名单（见 4.5）
- 部分系统需要在开发者选项中关闭「后台进程限制」

**Q: 多台设备同时管理？**
- 控制台左侧显示所有在线设备，点击切换即可
- 每台设备独立通信，互不干扰

**Q: 如何升级服务端？**
```bash
cd /www/wwwroot/android-remote/server
# 覆盖新文件后
pm2 restart android-remote
```
