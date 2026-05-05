import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import LoginPage from './pages/LoginPage.vue'
import DashboardPage from './pages/DashboardPage.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', component: LoginPage },
  {
    path: '/dashboard',
    component: DashboardPage,
    beforeEnter: (to, from, next) => {
      if (!localStorage.getItem('token')) next('/login')
      else next()
    }
  }
]

const router = createRouter({ history: createWebHashHistory(), routes })
const pinia = createPinia()

createApp(App).use(pinia).use(router).mount('#app')
