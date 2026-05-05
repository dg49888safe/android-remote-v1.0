const express = require('express');
const router = express.Router();

// 设备历史日志（简单内存存储，生产环境改用数据库）
const cmdLogs = [];

router.get('/logs', (req, res) => {
  const { deviceId, limit = 50 } = req.query;
  let logs = deviceId ? cmdLogs.filter(l => l.deviceId === deviceId) : cmdLogs;
  res.json(logs.slice(-Number(limit)));
});

router.post('/log', (req, res) => {
  const entry = { ...req.body, ts: Date.now() };
  cmdLogs.push(entry);
  if (cmdLogs.length > 1000) cmdLogs.shift();
  res.json({ ok: true });
});

module.exports = router;
