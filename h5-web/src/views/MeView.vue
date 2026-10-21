<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Button, Cell, CellGroup, NavBar, Tag } from 'vant'
import { authSession, currentUser } from '@/shared/http/client'
const router=useRouter()
const isShop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
function logout(){authSession.clear();void router.replace('/login')}
</script>
<template><div class="mobile-page"><NavBar title="我的"/><section class="profile-head"><h1>{{ currentUser?.displayName }}</h1><Tag plain type="primary">{{ isShop?'维修网点':'车主' }}</Tag></section><CellGroup inset><Cell title="登录账号" :value="currentUser?.username"/><Cell v-if="isShop" title="所属网点" :value="currentUser?.shopId?`网点 ${currentUser.shopId}`:'未关联网点'"/></CellGroup><CellGroup inset class="support-links"><Cell title="帮助中心" icon="question-o" is-link to="/help"/><Cell title="使用说明" icon="guide-o" is-link to="/guide"/></CellGroup><div class="page-actions"><Button block plain type="primary" @click="logout">退出登录</Button></div></div></template>
<style scoped>.support-links{margin-top:20px}.profile-head{padding:28px 20px 20px}.profile-head h1{margin:4px 0 10px;font-size:24px}.me-note{margin:24px 20px;padding-top:18px;border-top:1px solid var(--color-border)}.me-note h2{font-size:16px}.me-note p{color:var(--color-text-secondary);line-height:1.7}.page-actions{padding:8px 20px}</style>
