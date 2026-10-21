<script setup lang="ts">
import {ref} from 'vue'
import {ElDialog,ElButton} from 'element-plus'
export interface Invitation {userId:number;username:string;url:string;expiresAt:string|null;alreadyActive?:boolean}
defineProps<{invitation?:Invitation}>();const emit=defineEmits<{close:[]}>(),message=ref('')
async function copy(value:string){try{await navigator.clipboard.writeText(value);message.value='已复制，请通过已核实联系方式手动发给本人'}catch{message.value='无法访问剪贴板，请手动选择并复制链接'}}
</script>
<template><ElDialog :model-value="Boolean(invitation)" title="手动发送账号开通邀请" width="min(560px,94vw)" @update:model-value="emit('close')"><template v-if="invitation"><p>账号：{{ invitation.username }}<span v-if="invitation.expiresAt">；到期：{{ new Date(invitation.expiresAt).toLocaleString() }}</span></p><p v-if="invitation.alreadyActive">已有账号已激活，本次复用原账号；不生成激活邀请，不修改原密码。请本人使用原密码登录。</p><p>请通过已经核实的联系方式发给本人。未发送短信或邮件；链接是敏感凭据，不要截图、公开或转发给其他人。</p><label>{{ invitation.alreadyActive?"H5登录地址":"邀请链接（仅本次显示）" }}<input :value="invitation.url" readonly style="width:100%;min-height:44px;box-sizing:border-box"></label><p role="status">{{ message }}</p><ElButton @click="copy(invitation.url)">{{ invitation.alreadyActive?"复制登录地址":"复制邀请链接" }}</ElButton><ElButton @click="emit('close')">关闭并清除显示</ElButton></template></ElDialog></template>
