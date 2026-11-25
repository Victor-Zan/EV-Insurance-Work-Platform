<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onHide, onShow, onPullDownRefresh, onUnload } from '@dcloudio/uni-app'
import BrandHeader from '../../components/BrandHeader.vue'
import OwnerHome from '../../components/OwnerHome.vue'
import ShopHome from '../../components/ShopHome.vue'
import { currentSession, revalidateSession, returnToLogin, session, logout } from '../../services/auth'
import { onSessionCleared, sameSession, sessionGeneration } from '../../services/session'
import { appVisible } from '../../services/lifecycle'
const pageReady = ref(false), pageVisible = ref(false), renderedUser = ref<number | null>(null), error = ref('')
const ownerHome = ref<InstanceType<typeof OwnerHome> | null>(null), shopHome = ref<InstanceType<typeof ShopHome> | null>(null)
const role = computed(() => session.value?.user.role)
const active = computed(() => pageReady.value && pageVisible.value && appVisible.value)
let sequence = 0
function clear() { sequence++; pageReady.value = false; renderedUser.value = null; error.value = '' }
const stop = onSessionCleared(clear)
onUnload(() => { clear(); stop() })
async function checkAccount() {
  pageReady.value = false; error.value = ''
  const account = currentSession(), stamp = sessionGeneration(), current = ++sequence
  if (!account) { returnToLogin(); return }
  try { await revalidateSession() }
  catch (cause) { if (current !== sequence) return; if (!currentSession()) returnToLogin(); else error.value = cause instanceof Error ? cause.message : '账号暂未核实，请重试。'; return }
  if (current !== sequence || !sameSession(stamp) || !pageVisible.value || !appVisible.value) return
  renderedUser.value = account.user.id; pageReady.value = true
}
onShow(() => { pageVisible.value = true; void checkAccount() })
onHide(() => { pageVisible.value = false; sequence++; pageReady.value = false })
watch(appVisible, value => { if (!value) { sequence++; pageReady.value = false } else if (pageVisible.value) void checkAccount() })
watch(session, value => { if (!value) returnToLogin() })
onPullDownRefresh(async () => {
  try { if (!pageReady.value) await checkAccount(); else if (role.value === 'OWNER') await ownerHome.value?.refresh(); else await shopHome.value?.refresh() }
  finally { uni.stopPullDownRefresh() }
})
</script>
<template>
  <OwnerHome v-if="renderedUser && role === 'OWNER'" ref="ownerHome" :key="renderedUser" :active="active" />
  <ShopHome v-if="renderedUser && role === 'REPAIR_SHOP'" ref="shopHome" :key="renderedUser" :active="active" />
  <view v-if="!pageReady" class="shop-page"><BrandHeader /><view class="shop-card"><text class="shop-muted">{{ error || '正在核实账号…' }}</text><button v-if="error" class="shop-button" @tap="checkAccount">重新核实</button><button v-if="error" class="shop-button" @tap="logout">退出登录</button></view></view>
</template>
