<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import AccountPage from '../../components/AccountPage.vue'
import { activateInvitation, holdInvitationEntry, inspectInvitation, returnToLogin, takeInvitationEntry, type InvitationPreview } from '../../services/auth'
import { useAccountFeedback } from '../../services/account-feedback'
import { RequestError } from '../../services/client'
const token = ref(''), password = ref(''), confirmPassword = ref(''), confirmed = ref(false)
const preview = ref<InvitationPreview | null>(null), inspecting = ref(false)
const { error, remaining, busy, failed } = useAccountFeedback()
let revision = 0, alive = true
onLoad(options => {
  if (typeof options?.token === 'string') {
    holdInvitationEntry(options.token)
    uni.redirectTo({ url: '/pages/activate/index' })
    return
  }
  token.value = takeInvitationEntry()
  if (token.value) void inspect()
})
onUnload(() => { alive = false; revision++; token.value = ''; password.value = ''; confirmPassword.value = ''; preview.value = null })
function changed() { revision++; preview.value = null; confirmed.value = false; error.value = '' }
async function inspect() {
  if (inspecting.value || busy.value || remaining.value) return
  const current = ++revision, value = token.value.trim()
  preview.value = null; confirmed.value = false; error.value = ''; inspecting.value = true
  try { const result = await inspectInvitation(value); if (alive && current === revision) preview.value = result }
  catch (cause) { if (alive && current === revision) failed(cause) }
  finally { if (alive) inspecting.value = false }
}
async function submit() {
  if (!preview.value || busy.value || inspecting.value || remaining.value) return
  if (Date.parse(preview.value.expiresAt) <= Date.now()) { preview.value = null; failed(new RequestError('INVITATION_INVALID', '邀请已过期，请联系邀请人员。')); return }
  error.value = ''
  try { await activateInvitation(token.value.trim(), password.value, confirmPassword.value, confirmed.value); token.value = ''; password.value = ''; confirmPassword.value = ''; uni.reLaunch({ url: '/pages/index/index' }) }
  catch (cause) { failed(cause); if (cause instanceof RequestError && cause.code === 'INVITATION_INVALID') preview.value = null }
}
</script>
<template>
  <AccountPage compact title="激活并绑定微信" description="先核对邀请身份，再设置密码并绑定当前微信。">
    <text class="account-label">邀请凭证</text><input v-model="token" class="account-input invitation-field" password maxlength="43" :disabled="busy || inspecting" placeholder="填写工作人员提供的邀请凭证" @input="changed" />
    <button class="account-secondary inspect-invitation" :loading="inspecting" :disabled="busy || inspecting || remaining > 0" @tap="inspect">{{ remaining ? `请稍候 ${remaining} 秒` : inspecting ? '正在核对…' : '核对邀请身份' }}</button>
    <view v-if="preview" class="account-preview"><text>受邀账号：{{ preview.usernameMasked }}</text><text>姓名：{{ preview.displayNameMasked }}</text><text>服务身份：{{ preview.role === 'OWNER' ? '车主' : '维修网点' }}</text><text v-if="preview.shopNameMasked">网点：{{ preview.shopNameMasked }}</text></view>
    <template v-if="preview">
      <text class="account-label">设置账号密码</text><input v-model="password" class="account-input new-password" password maxlength="72" :disabled="busy" placeholder="以后也可用于网页版登录" />
      <text class="account-label">再次确认密码</text><input v-model="confirmPassword" class="account-input confirm-password" password maxlength="72" :disabled="busy" placeholder="请再次输入相同密码" />
      <checkbox-group @change="confirmed = $event.detail.value.includes('confirmed')"><label class="account-agreement"><checkbox value="confirmed" :checked="confirmed" :disabled="busy" color="#35383b" /><text>我已核实受邀身份，确认设置密码并绑定当前微信。</text></label></checkbox-group>
      <button class="account-primary activate-submit" :loading="busy" :disabled="busy || remaining > 0" @tap="submit">{{ busy ? '正在激活…' : '确认激活并绑定' }}</button>
    </template>
    <text v-if="error" class="account-error">{{ error }}</text>
    <text class="account-help">核对邀请不会激活账号。邀请只能办理一次，身份不符请联系工作人员。</text>
    <text class="account-help">若提交结果未能确认，请先返回微信登录核对，避免重复办理。</text>
    <text class="account-link" @tap="!busy && returnToLogin()">返回微信登录</text>
  </AccountPage>
</template>
