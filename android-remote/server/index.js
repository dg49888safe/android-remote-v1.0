require('dotenv').config();
const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const authRouter = require('./routes/auth');
const deviceRouter = require('./routes/device');
const { verifyToken } = require('./middleware/auth');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server, path: '/ws' });

app.use(cors());
app.use(express.json());
app.use('/api/auth', authRouter);
app.use('/api/device', verifyToken, deviceRouter);

// 在线设备表: deviceId -> { ws, info, lastSeen }
const devices = new Map();
// 在线 Web 客户端表: clientId -> { ws, deviceId }
const webClients = new Map();

function broadcast(targetWs, msg) {
  if (targetWs && targetWs.readyState === WebSocket.OPEN) {
    targetWs.send(JSON.stringify(msg));
  }
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const type = url.searchParams.get('type');   // 'agent' | 'web'
  const token = url.searchParams.get('token');

  // --- Agent 连接 ---
  if (type === 'agent') {
    const deviceId = url.searchParams.get('deviceId') || uuidv4();
    const deviceName = url.searchParams.get('name') || 'Unknown Device';

    devices.set(deviceId, {
      ws,
      info: { deviceId, name: deviceName, connectedAt: Date.now() },
      lastSeen: Date.now()
    });

    console.log(`[Agent] 设备上线: ${deviceName} (${deviceId})`);

    // 通知所有 Web 端设备列表变更
    webClients.forEach(({ ws: wWs }) => {
      broadcast(wWs, { type: 'device_online', deviceId, name: deviceName });
    });

    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw);
        const dev = devices.get(deviceId);
        if (dev) dev.lastSeen = Date.now();

        if (msg.type === 'heartbeat') {
          broadcast(ws, { type: 'heartbeat_ack', ts: Date.now() });
          return;
        }

        // 将 Agent 上报的结果转发给对应 Web 客户端
        if (msg.clientId) {
          const client = webClients.get(msg.clientId);
          if (client) broadcast(client.ws, msg);
        } else {
          // 广播给所有监听该设备的 Web 端
          webClients.forEach((c) => {
            if (c.deviceId === deviceId) broadcast(c.ws, msg);
          });
        }
      } catch (e) {
        console.error('[Agent] 消息解析失败:', e.message);
      }
    });

    ws.on('close', () => {
      devices.delete(deviceId);
      console.log(`[Agent] 设备下线: ${deviceId}`);
      webClients.forEach(({ ws: wWs }) => {
        broadcast(wWs, { type: 'device_offline', deviceId });
      });
    });
    return;
  }

  // --- Web 客户端连接 ---
  if (type === 'web') {
    const { verifyTokenRaw } = require('./middleware/auth');
    if (!verifyTokenRaw(token)) {
      ws.close(4001, 'Unauthorized');
      return;
    }

    const clientId = uuidv4();
    webClients.set(clientId, { ws, deviceId: null });
    broadcast(ws, { type: 'connected', clientId });

    // 发送当前在线设备列表
    const list = [];
    devices.forEach(({ info }) => list.push(info));
    broadcast(ws, { type: 'device_list', devices: list });

    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw);

        // Web 端选定目标设备
        if (msg.type === 'select_device') {
          const c = webClients.get(clientId);
          if (c) c.deviceId = msg.deviceId;
          broadcast(ws, { type: 'device_selected', deviceId: msg.deviceId });
          return;
        }

        // 将指令转发给目标 Agent
        const c = webClients.get(clientId);
        const targetDeviceId = msg.deviceId || c?.deviceId;
        const dev = devices.get(targetDeviceId);
        if (dev) {
          broadcast(dev.ws, { ...msg, clientId });
        } else {
          broadcast(ws, { type: 'error', message: '设备不在线' });
        }
      } catch (e) {
        console.error('[Web] 消息解析失败:', e.message);
      }
    });

    ws.on('close', () => {
      webClients.delete(clientId);
    });
    return;
  }

  ws.close(4000, 'Invalid type');
});

// 健康检查
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    devices: devices.size,
    clients: webClients.size,
    uptime: process.uptime()
  });
});

// 获取在线设备列表（REST 备用）
app.get('/api/devices', verifyToken, (req, res) => {
  const list = [];
  devices.forEach(({ info, lastSeen }) => list.push({ ...info, lastSeen }));
  res.json(list);
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`服务已启动: http://0.0.0.0:${PORT}`);
  console.log(`WebSocket: ws://0.0.0.0:${PORT}/ws`);
});
