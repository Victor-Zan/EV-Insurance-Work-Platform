<script setup lang="ts">
import { Cell, CellGroup, NavBar, Tag } from 'vant'
</script>

<template>
  <main class="mobile-shell">
    <NavBar title="保险维修协同平台" safe-area-inset-top />
    <section class="intro">
      <Tag type="success" size="medium">工程骨架已就绪</Tag>
      <h1>移动 H5</h1>
      <p>供维修网点和车主使用。当前阶段仅建立可启动工程，不包含业务功能。</p>
    </section>
    <CellGroup inset title="技术基线">
      <Cell title="框架" value="Vue 3 + TypeScript" />
      <Cell title="移动组件" value="Vant" />
    </CellGroup>
  </main>
</template>

