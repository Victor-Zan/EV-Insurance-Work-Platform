<script setup lang="ts">
import { useRouter } from 'vue-router'
import { authSession, currentUser } from '@/shared/http/client'
import { Button, Cell, CellGroup, NavBar } from 'vant'
const router = useRouter()
function logout() { authSession.clear(); void router.replace('/login') }
</script>

<template>
  <main class="mobile-shell"><NavBar title="身份首页" /><section class="intro"><h1>{{ currentUser?.displayName }}</h1></section>
    <CellGroup inset><Cell title="账号" :value="currentUser?.username" /><Cell title="角色" :value="currentUser?.roles.join('、')" /></CellGroup>
    <div class="actions"><Button block @click="logout">退出登录</Button></div>
  </main>
</template>
