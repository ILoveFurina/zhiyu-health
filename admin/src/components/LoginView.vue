<script setup lang="ts">
import { reactive, ref } from 'vue'

import { login } from '../api/auth'
import { setToken } from '../api/client'

const emit = defineEmits<{ success: [] }>()
const form = reactive({ username: 'admin', password: 'admin123' })
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    const result = await login(form.username, form.password)
    setToken(result.access_token)
    emit('success')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <el-card class="login-card">
      <h1>智愈 B 端</h1>
      <p class="muted">医院组织与医生资源管理</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-button class="full-button" type="primary" :loading="loading" @click="submit">登录</el-button>
      </el-form>
    </el-card>
  </main>
</template>
