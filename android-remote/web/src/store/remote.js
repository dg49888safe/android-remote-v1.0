import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'

const SERVER = import.meta.env.VITE_SERVER_URL || window.location.hostname

export const useRemoteStore = defineStore('remote', () => {
  const ws = ref(null)
  const clientId = ref('')
  const connected = ref(false)
  const devices = reactive([])
  const selectedDevice = ref(null)
  const cmdOutput = ref('')
  const fileList = reactive([])
  const logs = reactive([])
  const smsList = reactive([])
  const photoList = reactive([])
  const screenshot = ref('')
  const photoPreview = ref('')
  const screenFrame = ref('')
  const screenStreaming = ref(false)

  function connect(token) {
    const wsUrl = `ws://${SERVER}:3000/ws?type=web&token=${token}`
    ws.value = new WebSocket(wsUrl)

    ws.value.onopen = () => { connected.value = true }
    ws.value.onclose = () => {
      connected.value = false
      setTimeout(() => connect(token), 3000) // 断线重连
    }
    ws.value.onmessage = ({ data }) => {
      const msg = JSON.parse(data)
      handleMessage(msg)
    }
  }

  function handleMessage(msg) {
    switch (msg.type) {
      case 'connected':
        clientId.value = msg.clientId
        break
      case 'device_list':
        devices.splice(0, devices.length, ...msg.devices)
        break
      case 'device_online':
        if (!devices.find(d => d.deviceId === msg.deviceId)) {
          devices.push({ deviceId: msg.deviceId, name: msg.name })
        }
        break
      case 'device_offline':
        const idx = devices.findIndex(d => d.deviceId === msg.deviceId)
        if (idx !== -1) devices.splice(idx, 1)
        if (selectedDevice.value === msg.deviceId) selectedDevice.value = null
        break
      case 'shell_output':
        cmdOutput.value += msg.output
        break
      case 'file_list':
        fileList.splice(0, fileList.length, ...msg.files)
        break
      case 'cmd_result':
        logs.unshift({ ts: Date.now(), cmd: msg.cmd, result: msg.result, deviceId: msg.deviceId })
        break
      case 'sms_list':
        smsList.splice(0, smsList.length, ...msg.messages)
        break
      case 'photo_list':
        photoList.splice(0, photoList.length, ...msg.photos)
        break
      case 'photo_data':
        photoPreview.value = msg.image
        break
      case 'screenshot':
        screenshot.value = msg.image
        break
      case 'screen_frame':
        screenFrame.value = msg.image
        break
    }
  }

  function send(payload) {
    if (ws.value?.readyState === WebSocket.OPEN) {
      ws.value.send(JSON.stringify({ ...payload, deviceId: selectedDevice.value }))
    }
  }

  function selectDevice(deviceId) {
    selectedDevice.value = deviceId
    send({ type: 'select_device', deviceId })
    cmdOutput.value = ''
    fileList.splice(0, fileList.length)
  }

  function sendShell(cmd) {
    send({ type: 'shell', cmd })
  }

  function listFiles(path = '/sdcard') {
    send({ type: 'file_list', path })
  }

  function tapScreen(x, y) {
    send({ type: 'tap', x, y })
  }

  function sendInput(text) {
    send({ type: 'input_text', text })
  }

  function launchApp(pkg) {
    send({ type: 'launch_app', pkg })
  }

  function fetchSms(limit = 20) {
    send({ type: 'sms_list', limit })
  }

  function fetchPhotos(limit = 50) {
    send({ type: 'photo_list', limit })
  }

  function fetchPhoto(path) {
    send({ type: 'photo_get', path })
  }

  function fetchScreenshot() {
    send({ type: 'screenshot' })
  }

  function startScreenStream(interval = 1500) {
    send({ type: 'screen_stream_start', interval })
    screenStreaming.value = true
  }

  function stopScreenStream() {
    send({ type: 'screen_stream_stop' })
    screenStreaming.value = false
  }

  function swipe(x1, y1, x2, y2, duration = 300) {
    send({ type: 'swipe', x1, y1, x2, y2, duration })
  }

  function keyEvent(keyCode) {
    send({ type: 'key_event', keyCode })
  }

  return {
    connected, devices, selectedDevice, cmdOutput, fileList, logs,
    smsList, photoList, screenshot, photoPreview,
    screenFrame, screenStreaming,
    connect, selectDevice, sendShell, listFiles, tapScreen, sendInput, launchApp,
    fetchSms, fetchPhotos, fetchPhoto, fetchScreenshot,
    startScreenStream, stopScreenStream, swipe, keyEvent
  }
})
