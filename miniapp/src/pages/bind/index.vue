<script setup lang="ts">
import { ref } from 'vue'
import { onUnload } from '@dcloudio/uni-app'
import AccountPage from '../../components/AccountPage.vue'
import { bindExisting, returnToLogin } from '../../services/auth'
import { useAccountFeedback } from '../../services/account-feedback'
const username = ref(''), password = ref(''), confirmed = ref(false)
const { error, remaining, busy, failed } = useAccountFeedback()
onUnload(() => { password.value = ''; confirmed.value = false })
async function submit() {
  if (busy.value || remaining.value) return
  error.value = ''
  try { await bindExisting(username.value, password.value, confirmed.value); password.value = ''; uni.reLaunch({ url: '/pages/index/index' }) }
  catch (cause) { failed(cause) }
}
</script>
<template>
  <AccountPage compact title="绑定服务账号" description="使用已开通的账号验证一次，以后通过微信登录。">
    <text class="account-label">服务账号</text><input v-model="username" class="account-input username-field" maxlength="64" :disabled="busy" placeholder="请输入原平台账号" />
    <text class="account-label">账号密码</text><input v-model="password" class="account-input password-field" password maxlength="72" :disabled="busy" placeholder="请输入原平台密码" />
    <checkbox-group @change="confirmed = $event.detail.value.includes('confirmed')"><label class="account-agreement"><checkbox value="confirmed" :checked="confirmed" :disabled="busy" color="#35383b" /><text>我已核实上述账号属于本人，确认绑定当前微信。</text></label></checkbox-group>
    <button class="account-primary bind-submit" :disabled="busy || remaining > 0" :loading="busy" @tap="submit">{{ remaining ? `请稍候 ${remaining} 秒` : busy ? '正在绑定…' : '确认绑定微信' }}</button>
    <text v-if="error" class="account-error">{{ error }}</text>
    <text class="account-help">尚未激活请使用邀请开通。忘记密码或更换微信，请联系工作人员核实恢复。</text>
    <text class="account-help">若提交结果未能确认，请先返回微信登录核对，无需重复领取邀请。</text>
    <text class="account-link" @tap="!busy && returnToLogin()">返回微信登录</text>
  </AccountPage>
</template>
