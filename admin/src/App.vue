<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Loading } from '@element-plus/icons-vue'

import { fetchProfile, type StaffProfile } from './api/auth'
import { clearToken, getToken } from './api/client'
import DepartmentPage from './components/DepartmentPage.vue'
import DoctorPage from './components/DoctorPage.vue'
import HospitalPage from './components/HospitalPage.vue'
import InfrastructurePage from './components/InfrastructurePage.vue'
import LoginView from './components/LoginView.vue'

const profile = ref<StaffProfile | null>(null)
const activePage = ref('hospitals')
const loading = ref(Boolean(getToken()))

async function loadProfile() {
  loading.value = true
  try { profile.value = await fetchProfile() }
  catch { clearToken(); profile.value = null }
  finally { loading.value = false }
}
function logout() { clearToken(); profile.value = null }
onMounted(() => { if (getToken()) loadProfile() })
</script>

<template>
  <LoginView v-if="!profile && !loading" @success="loadProfile" />
  <div v-else-if="loading" class="center-loading"><el-icon class="is-loading" :size="36"><Loading /></el-icon></div>
  <el-container v-else-if="profile" class="app-shell">
    <el-aside width="220px" class="sidebar"><div class="brand">智愈 B 端</div><el-menu v-model="activePage" :default-active="activePage" @select="activePage = $event">
      <el-menu-item index="health">基础设施状态</el-menu-item><el-menu-item index="hospitals">医院管理</el-menu-item><el-menu-item index="departments">科室管理</el-menu-item><el-menu-item index="doctors">医生管理</el-menu-item></el-menu></el-aside>
    <el-container><el-header class="header"><span>组织资源中心</span><div><el-tag>{{ profile.role === 'admin' ? '管理员' : '医生' }}</el-tag><span class="username">{{ profile.username }}</span><el-button link @click="logout">退出</el-button></div></el-header>
      <el-main><InfrastructurePage v-if="activePage === 'health'" /><el-alert v-else-if="profile.role !== 'admin'" title="医生账号无组织管理权限" type="info" show-icon />
        <template v-else><HospitalPage v-if="activePage === 'hospitals'" /><DepartmentPage v-else-if="activePage === 'departments'" /><DoctorPage v-else /></template>
      </el-main></el-container>
  </el-container>
</template>
