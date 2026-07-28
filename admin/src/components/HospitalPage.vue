<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { hospitalApi, type Hospital, type HospitalInput } from '../api/organization'

const rows = ref<Hospital[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const emptyForm = (): HospitalInput => ({ name: '', level: '', address: '', longitude: 0, latitude: 0 })
const form = reactive<HospitalInput>(emptyForm())

async function load() { rows.value = await hospitalApi.list() }
function openCreate() { editingId.value = null; Object.assign(form, emptyForm()); dialogVisible.value = true }
function openEdit(row: Hospital) { editingId.value = row.id; Object.assign(form, row); dialogVisible.value = true }
async function save() {
  if (editingId.value) await hospitalApi.update(editingId.value, form)
  else await hospitalApi.create(form)
  ElMessage.success('保存成功'); dialogVisible.value = false; await load()
}
async function remove(row: Hospital) {
  await ElMessageBox.confirm(`确认删除“${row.name}”？`, '删除医院', { type: 'warning' })
  await hospitalApi.remove(row.id); ElMessage.success('已删除'); await load()
}
onMounted(load)
</script>

<template>
  <section>
    <div class="page-heading"><div><h2>医院管理</h2><p>维护医院基础信息和地图坐标</p></div><el-button type="primary" @click="openCreate">新增医院</el-button></div>
    <el-table :data="rows" stripe border>
      <el-table-column prop="name" label="医院" min-width="160" /><el-table-column prop="level" label="等级" width="110" />
      <el-table-column prop="address" label="地址" min-width="180" /><el-table-column prop="longitude" label="经度" width="100" />
      <el-table-column prop="latitude" label="纬度" width="100" /><el-table-column label="操作" width="150">
        <template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑医院' : '新增医院'" width="560">
      <el-form label-width="80"><el-form-item label="名称"><el-input v-model="form.name" /></el-form-item><el-form-item label="等级"><el-input v-model="form.level" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item><el-form-item label="经度"><el-input-number v-model="form.longitude" :precision="6" /></el-form-item><el-form-item label="纬度"><el-input-number v-model="form.latitude" :precision="6" /></el-form-item></el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
