<script setup lang="ts">
import InvitationResult,{type Invitation} from '@/features/account/InvitationResult.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElAlert, ElButton, ElInput, ElSelect, ElOption, ElTable, ElTableColumn, ElPagination, ElCard, ElDialog, ElCheckbox, ElCheckboxGroup } from 'element-plus'
import { httpClient } from '@/shared/http/client'
import type { ApiResponse, Page } from '@/shared/http/create-client'
import type { Role, User } from '@/shared/auth/session'
const users = ref<User[]>([]), total = ref(0), page = ref(1), error = ref(''), message = ref(''), busy = ref(false)
const create = reactive({ username: '', displayName: '', password: '', role: 'CUSTOMER_SERVICE' as Role, shopId: '', ownerPhone: '' })
const contactOpen=ref(false),contactId=ref(0),contactPhone=ref(''),contactReason=ref('')
function openContact(user:User){contactId.value=user.id;contactPhone.value=user.ownerPhone??'';contactReason.value='';contactOpen.value=true}
async function saveContact(){await run(async()=>{await httpClient.put(`/admin/users/${contactId.value}/owner-contact`,{phone:contactPhone.value.trim(),reason:contactReason.value.trim()});contactOpen.value=false;message.value='手机号已维护，历史绑定未改变';await load()})}
const resetId = ref(''), resetPassword = ref('')
const invitation=ref<Invitation>()
async function invite(user:User){await run(async()=>{invitation.value=undefined;invitation.value=(await httpClient.post(`/admin/account-invitations/users/${user.id}`)).data.data;await load()})}
async function inviteStatus(user:User){await run(async()=>{const result=(await httpClient.get(`/admin/account-invitations/users/${user.id}`)).data.data;message.value=`账号状态：${result.state}；邀请：${result.invitationState??'未签发'}`})}
const assignmentOpen=ref(false),assignmentId=ref(0),assignmentRoles=ref<Role[]>([]),assignmentShopId=ref('')
const roleOptions:Role[]=['ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER']
function openAssignment(user:User){assignmentId.value=user.id;assignmentRoles.value=[...user.roles];assignmentShopId.value=user.shopId?String(user.shopId):'';assignmentOpen.value=true}
async function saveAssignment(){await run(async()=>{if(!assignmentRoles.value.length)throw Error('至少选择一个角色');await httpClient.put(`/admin/users/${assignmentId.value}/assignment`,{roles:assignmentRoles.value,shopId:assignmentRoles.value.includes('REPAIR_SHOP')?Number(assignmentShopId.value):null});assignmentOpen.value=false;message.value='角色与网点归属已更新，旧会话失效';await load()})}
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
      invitation.value=(await httpClient.post('/admin/account-invitations/accounts',{username:create.username,displayName:create.displayName,role:create.role,shopId:create.role==='REPAIR_SHOP'?Number(create.shopId):null})).data.data
      message.value = '待激活账号已准备，请手动发送邀请'; await load()
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
  <main><header class="module-page-head"><p>ADMIN / USERS</p><h1>用户与角色</h1><span>仅管理员可创建账号、调整现有角色及网点归属；权限以服务端校验为准。</span></header>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElAlert v-if="message" :title="message" type="success" :closable="false" />
    <ElCard shadow="never"><h2>创建账号</h2><form @submit.prevent="submitCreate">
      <label>账号<ElInput v-model="create.username" autocomplete="off" /></label><label>显示名<ElInput v-model="create.displayName" /></label>
      <p>本人通过邀请设置密码；车主请从案件详情开通。</p>
      <label>角色<ElSelect v-model="create.role"><ElOption v-for="role in ['ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP']" :key="role" :label="role" :value="role" /></ElSelect></label>
      <label v-if="create.role === 'REPAIR_SHOP'">网点 ID<ElInput v-model="create.shopId" inputmode="numeric" /></label>
      <label v-if="create.role === 'OWNER'">车主手机号（用于工单精确匹配）<ElInput v-model="create.ownerPhone" maxlength="32" /></label>
      <ElButton type="primary" native-type="submit" :disabled="busy">创建</ElButton>
    </form></ElCard>
    <section class="admin-surface"><h2>账号列表</h2><ElTable :data="users"><ElTableColumn prop="id" label="ID" /><ElTableColumn prop="username" label="账号" />
      <ElTableColumn prop="displayName" label="显示名" /><ElTableColumn label="角色"><template #default="scope">{{ scope.row.roles.join('、') }}</template></ElTableColumn>
      <ElTableColumn label="状态"><template #default="scope">{{ !scope.row.enabled ? '停用' : scope.row.activated===false ? '待激活' : '已激活且启用' }}</template></ElTableColumn>
      <ElTableColumn label="操作" min-width="180"><template #default="scope"><ElButton v-if="scope.row.activated===false&&scope.row.enabled" :disabled="busy" @click="invite(scope.row as User)">生成/重发邀请</ElButton><ElButton :disabled="busy" @click="inviteStatus(scope.row as User)">邀请状态</ElButton><ElButton :disabled="busy" @click="toggle(scope.row as User)">{{ scope.row.enabled ? '停用' : '启用' }}</ElButton><ElButton :disabled="busy" @click="openAssignment(scope.row as User)">角色与归属</ElButton><ElButton v-if="scope.row.roles.includes('OWNER')" :disabled="busy" @click="openContact(scope.row as User)">车主手机号</ElButton></template></ElTableColumn>
    </ElTable><ElPagination v-model:current-page="page" :page-size="20" :total="total" layout="prev,pager,next" @current-change="run(load)" /></section>
    <ElCard shadow="never"><h2>重置密码</h2><form @submit.prevent="reset"><label>用户 ID<ElInput v-model="resetId" inputmode="numeric" /></label>
      <label>新密码<ElInput v-model="resetPassword" type="password" autocomplete="new-password" /></label><ElButton native-type="submit" :disabled="busy">重置</ElButton>
    </form></ElCard>
    <ElDialog v-model="contactOpen" title="维护车主手机号"><p>仅影响后续精确匹配；不转移已有案件绑定。</p><label>手机号<ElInput v-model="contactPhone" maxlength="32"/></label><label>变更原因<ElInput v-model="contactReason" type="textarea" maxlength="2000"/></label><ElAlert v-if="error" :title="error" type="error" :closable="false"/><template #footer><ElButton @click="contactOpen=false">取消</ElButton><ElButton type="primary" :loading="busy" @click="saveContact">保存并审计</ElButton></template></ElDialog>
    <ElDialog v-model="assignmentOpen" title="调整角色与网点归属" width="min(520px,calc(100vw - 32px))"><p>更改后该用户原有会话失效；角色范围仍仅限现有四类。</p><ElCheckboxGroup v-model="assignmentRoles"><ElCheckbox v-for="role in roleOptions" :key="role" :value="role">{{ role }}</ElCheckbox></ElCheckboxGroup><label v-if="assignmentRoles.includes('REPAIR_SHOP')">所属网点 ID<ElInput v-model="assignmentShopId" inputmode="numeric"/></label><ElAlert v-if="error" :title="error" type="error" :closable="false"/><template #footer><ElButton @click="assignmentOpen=false">取消</ElButton><ElButton type="primary" :loading="busy" @click="saveAssignment">保存并审计</ElButton></template></ElDialog>
    <InvitationResult :invitation="invitation" @close="invitation=undefined"/>
  </main>
</template>
<style scoped>form { display: flex; flex-wrap: wrap; align-items: end; gap: 16px; } label:not(.el-checkbox) { display:grid;gap:6px;width:220px;max-width:100%; } .el-card { margin: 24px 0; } .admin-surface h2{margin-top:0}.admin-surface :deep(.el-pagination){justify-content:flex-end;margin-top:16px}@media(max-width:640px){form{align-items:stretch}label:not(.el-checkbox){width:100%}}</style>
