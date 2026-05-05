<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="logo">📱 Android Remote</div>
      <h2>登录控制台</h2>
      <input v-model="username" placeholder="用户名" @keyup.enter="login"/>
      <input v-model="password" type="password" placeholder="密码" @keyup.enter="login"/>
      <button :disabled="loading" @click="login">
        {{ loading ? '登录中...' : '登 录' }}
      </button>
      <p class="err" v-if="error">{{ error }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
const router = useRouter()
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function login() {
  loading.value = true; error.value = ''
  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error)
    localStorage.setItem('token', data.token)
    router.push('/dashboard')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap { display:flex; align-items:center; justify-content:center; min-height:100vh; background:#0f1117; }
.login-card { background:#1a1d27; border:1px solid #2d3148; border-radius:12px; padding:40px; width:360px; display:flex; flex-direction:column; gap:16px; }
.logo { font-size:24px; text-align:center; }
h2 { text-align:center; color:#a5b4fc; font-weight:500; }
input { background:#0f1117; border:1px solid #2d3148; border-radius:8px; padding:10px 14px; color:#e2e8f0; font-size:14px; outline:none; }
input:focus { border-color:#6366f1; }
button { background:#6366f1; color:#fff; border:none; border-radius:8px; padding:12px; font-size:15px; cursor:pointer; transition:background .2s; }
button:hover:not(:disabled) { background:#4f46e5; }
button:disabled { opacity:.6; cursor:not-allowed; }
.err { color:#f87171; font-size:13px; text-align:center; }
</style>
