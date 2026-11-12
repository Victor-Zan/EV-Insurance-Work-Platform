<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { currentUser } from '@/shared/http/client'
</script>

<template>
  <nav class="funds-navigation" aria-label="资金页面">
    <RouterLink to="/funds/analytics">经营看板</RouterLink>
    <RouterLink to="/funds/workbench">案件收支</RouterLink>
    <RouterLink v-if="currentUser?.roles.includes('CUSTOMER_SERVICE')" to="/funds/imports">资金导入</RouterLink>
  </nav>
</template>

<style scoped>
.funds-navigation {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 16px;
  border-bottom: 1px solid #deddd7;
  margin-bottom: 16px;
}
.funds-navigation a {
  border: 1px solid #d8d8d2;
  background: white;
  border-radius: 7px;
  padding: 9px 22px;
  color: inherit;
  text-decoration: none;
}
.funds-navigation a[aria-current='page'] {
  background: #303638;
  color: white;
  border-color: #303638;
}
.funds-navigation a:focus-visible {
  outline: 2px solid #7395ad;
  outline-offset: 2px;
}
</style>
