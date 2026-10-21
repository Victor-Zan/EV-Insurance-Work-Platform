<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Cell, CellGroup, Collapse, CollapseItem, NavBar } from 'vant'
import { currentUser } from '@/shared/http/client'
const route=useRoute(),router=useRouter(),expanded=ref<string[]>(['cases'])
const guide=computed(()=>route.path==='/guide')
const shop=computed(()=>currentUser.value?.roles.includes('REPAIR_SHOP')===true)
</script>
<template><div class="mobile-page"><NavBar :title="guide?'使用说明':'帮助中心'" left-arrow @click-left="router.push('/me')"/>
<section v-if="guide" class="help-content"><h2>{{ shop?'处理维修案件':'查看我的案件' }}</h2>
<ol v-if="shop"><li>在“案件”中查看派给本网点的工单，确认接单和车辆到店。</li><li>进入案件上传材料、提交报价。获得维修授权后更新进度。</li><li>上传完工照片并提交完工，等待收车确认。</li><li>在案件的“结算”中查看本店结算记录。</li></ol>
<ol v-else><li>在“案件”中选择车辆，查看维修进度和照片。</li><li>车辆维修完成后，进入案件核对情况，实际收车后点击“确认已收车”。</li><li>确认收车后，可在案件的“评价”中填写文字或评分，也可以跳过。</li><li>在“通知”中查看案件消息。</li></ol><p>评价提交后无法自行修改，如有错误请联系办理案件的客服。</p></section>
<template v-else><Collapse v-model="expanded"><CollapseItem title="为什么找不到我的案件？" name="cases"><template v-if="!shop">请确认登录的是办理案件时登记的车主账号。案件正式提交后，按登记手机号关联账号；仅保存草稿时不会显示。若仍找不到，请联系办理案件的客服核对手机号和绑定状态。</template><template v-else>仅显示派给本网点的案件。请联系办理案件的客服核对派单网点。</template></CollapseItem><CollapseItem title="如何确认收车和评价？" name="review">维修完成后，车主可在案件内确认收车；案件完成后可自愿提交一次文字评价或评分。</CollapseItem><CollapseItem title="信息有误或需要帮助怎么办？" name="support">请联系办理该案件的客服，提供工单号和需要核对的内容。不要在评价中填写证件号、银行卡等敏感信息。</CollapseItem></Collapse>
<CellGroup inset class="help-links"><Cell title="使用说明" is-link to="/guide"/><Cell v-if="!shop" title="服务反馈记录" label="查看与案件相关的反馈及处理进度" is-link to="/complaints"/></CellGroup></template></div></template>
<style scoped>.help-content{padding:20px}.help-content h2{font-size:20px}.help-content li{margin-bottom:18px;line-height:1.7}.help-content p{color:var(--color-text-secondary);line-height:1.7}.help-links{margin-top:24px}</style>
