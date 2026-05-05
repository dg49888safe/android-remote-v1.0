const express = require('express');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const router = express.Router();

const SECRET = process.env.JWT_SECRET || 'dev_secret';
// 简单内存用户（生产环境替换为数据库）
const USERS = [
  {
    username: process.env.ADMIN_USER || 'admin',
    // 默认密码: admin123（生产环境务必修改 .env）
    hash: bcrypt.hashSync(process.env.ADMIN_PASS || 'admin123', 10)
  }
];

router.post('/login', (req, res) => {
  const { username, password } = req.body;
  const user = USERS.find(u => u.username === username);
  if (!user || !bcrypt.compareSync(password, user.hash)) {
    return res.status(401).json({ error: '用户名或密码错误' });
  }
  const token = jwt.sign({ username }, SECRET, { expiresIn: '7d' });
  res.json({ token, username });
});

module.exports = router;
