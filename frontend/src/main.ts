import {createPinia} from 'pinia'
import {createApp} from 'vue'
import App from './App.vue'
import router from './router'
import {useIdentityStore} from './stores/identity'
import {installAuthenticationLifecycle} from './app/authLifecycle'
import './styles/main.css'
const app=createApp(App)
const pinia=createPinia()
app.use(pinia)
installAuthenticationLifecycle(useIdentityStore(pinia),router)
app.use(router)
app.mount('#app')
