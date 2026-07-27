<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchHealth, type HealthResponse, type ServiceName } from './api/health'

const serviceLabels: Record<ServiceName, string> = {
  postgres: 'PostgreSQL + pgvector',
  redis: 'Redis',
  neo4j: 'Neo4j',
}

const health = ref<HealthResponse | null>(null)
const loading = ref(false)
const errorMessage = ref('')

async function refreshHealth() {
  loading.value = true
  errorMessage.value = ''
  try {
    health.value = await fetchHealth()
  } catch {
    errorMessage.value = '无法连接后端 health 接口，请确认 FastAPI 已启动。'
  } finally {
    loading.value = false
  }
}

onMounted(refreshHealth)
</script>

<template>
  <el-container class="app-shell">
    <el-header class="header">智愈 B 端</el-header>
    <el-main>
      <el-card class="health-card">
        <template #header>
          <div class="card-header">
            <span>基础设施状态</span>
            <el-button :loading="loading" type="primary" @click="refreshHealth">刷新</el-button>
          </div>
        </template>

        <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
        <el-skeleton v-else-if="!health" :rows="3" animated />
        <el-descriptions v-else :column="1" border>
          <el-descriptions-item
            v-for="(service, name) in health.services"
            :key="name"
            :label="serviceLabels[name as ServiceName]"
          >
            <el-tag :type="service.status === 'ok' ? 'success' : 'danger'">
              {{ service.status === 'ok' ? '正常' : '异常' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-main>
  </el-container>
</template>
