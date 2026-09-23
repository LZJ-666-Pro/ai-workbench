import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

// Element Plus 仅 /admin 后台使用；主色在 .admin-layout 作用域内覆盖为企业蓝，
// 聊天页不受影响（无需全局变量改动）
createApp(App).use(router).use(ElementPlus).mount('#app')
