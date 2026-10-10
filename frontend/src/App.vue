<script setup lang="ts">
import {ref} from 'vue'
import {RouterView} from 'vue-router'
import router from './router'
const ready=ref(false)
void router.isReady().then(()=>{ready.value=true})
</script>
<template>
 <main v-if="!ready" class="route-loading" role="status" aria-live="polite">
  <span class="loading-mark">C</span><p>正在恢复你的登录状态…</p>
 </main>
 <RouterView v-else />
</template>
