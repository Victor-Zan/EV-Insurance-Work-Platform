<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElInput, ElSelect, ElOption, ElTable, ElTableColumn, ElPagination, ElCard } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import type { Role, User } from '@/shared/auth/session'
const router = useRouter()
const users = ref<User[]>([]), total = ref(0), page = ref(1), error = ref(''), message = ref(''), busy = ref(false)
const create = reactive({ username: '', displayName: '', password: '', role: 'CUSTOMER_SERVICE' as Role, shopId: '' })
const resetId = ref(''), resetPassword = ref('')
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { await action() } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败' }
  finally { busy.value = false }
}
async function load() {
  const response = await httpClient.get<ApiResponse<Page<User>>>('/admin/users', { params: { page: page.value, size: 20 } })
  users.value = response.data.data.records; total.value = response.data.data.total
}
async function submitCreate() {
  await run(async () => {
    try {
      await httpClient.post('/admin/users', { username: create.username, displayName: create.displayName, password: create.password,
        roles: [create.role], shopId: create.role === 'REPAIR_SHOP' ? Number(create.shopId) : null })
      message.value = '账号已创建'; await load()
    } finally { create.password = '' }
  })
}
async function toggle(user: User) { await run(async () => { await httpClient.patch('/admin/users/' + user.id + '/enabled', { enabled: !user.enabled }); await load() }) }
async function reset() {
  await run(async () => {
    try { await httpClient.post('/admin/users/' + Number(resetId.value) + '/password-reset', { password: resetPassword.value }); message.value = '密码已重置，原会话已失效' }
    finally { resetPassword.value = '' }
  })
}
onMounted(() => { void run(load) })
</script>
<template>
  <main style="padding: 32px"><ElButton @click="router.push('/')">返回首页</ElButton><h1>用户管理验证</h1>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElAlert v-if="message" :title="message" type="success" :closable="false" />
    <ElCard><h2>创建账号</h2><form @submit.prevent="submitCreate">
      <label>账号<ElInput v-model="create.username" autocomplete="off" /></label><label>显示名<ElInput v-model="create.displayName" /></label>
      <label>初始密码<ElInput v-model="create.password" type="password" autocomplete="new-password" /></label>
      <label>角色<ElSelect v-model="create.role"><ElOption v-for="role in ['ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER']" :key="role" :label="role" :value="role" /></ElSelect></label>
      <label v-if="create.role === 'REPAIR_SHOP'">网点 ID<ElInput v-model="create.shopId" inputmode="numeric" /></label>
      <ElButton native-type="submit" :disabled="busy">创建</ElButton>
    </form></ElCard>
    <ElTable :data="users"><ElTableColumn prop="id" label="ID" /><ElTableColumn prop="username" label="账号" />
      <ElTableColumn prop="displayName" label="显示名" /><ElTableColumn label="角色"><template #default="scope">{{ scope.row.roles.join('、') }}</template></ElTableColumn>
      <ElTableColumn label="状态"><template #default="scope">{{ scope.row.enabled ? '启用' : '停用' }}</template></ElTableColumn>
      <ElTableColumn label="操作"><template #default="scope"><ElButton :disabled="busy" @click="toggle(scope.row as User)">{{ scope.row.enabled ? '停用' : '启用' }}</ElButton></template></ElTableColumn>
    </ElTable><ElPagination v-model:current-page="page" :page-size="20" :total="total" layout="prev,pager,next" @current-change="run(load)" />
    <ElCard><h2>重置密码</h2><form @submit.prevent="reset"><label>用户 ID<ElInput v-model="resetId" inputmode="numeric" /></label>
      <label>新密码<ElInput v-model="resetPassword" type="password" autocomplete="new-password" /></label><ElButton native-type="submit" :disabled="busy">重置</ElButton>
    </form></ElCard>
  </main>
</template>
<style scoped>form { display: flex; flex-wrap: wrap; align-items: end; gap: 16px; } label { max-width: 220px; } .el-card { margin: 24px 0; }</style>
