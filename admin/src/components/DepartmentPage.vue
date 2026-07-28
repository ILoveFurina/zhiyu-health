<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { departmentApi, hospitalApi, type Department, type DepartmentInput, type Hospital } from '../api/organization'

const rows = ref<Department[]>([])
const hospitals = ref<Hospital[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const emptyForm = (): DepartmentInput => ({ hospital_id: 0, name: '', floor: '', location: '' })
const form = reactive<DepartmentInput>(emptyForm())
const hospitalNames = computed(() => Object.fromEntries(hospitals.value.map(item => [item.id, item.name])))

async function load() { [rows.value, hospitals.value] = await Promise.all([departmentApi.list(), hospitalApi.list()]) }
function openCreate() { editingId.value = null; Object.assign(form, emptyForm(), { hospital_id: hospitals.value[0]?.id ?? 0 }); dialogVisible.value = true }
function openEdit(row: Department) { editingId.value = row.id; Object.assign(form, row); dialogVisible.value = true }
async function save() {
  if (editingId.value) await departmentApi.update(editingId.value, form)
  else await departmentApi.create(form)
  ElMessage.success('保存成功'); dialogVisible.value = false; await load()
}
async function remove(row: Department) {
  await ElMessageBox.confirm(`确认删除“${row.name}”？`, '删除科室', { type: 'warning' })
  await departmentApi.remove(row.id); ElMessage.success('已删除'); await load()
}
onMounted(load)
</script>

<template>
  <section>
    <div class="page-heading"><div><h2>科室管理</h2><p>楼层与位置将用于 C 端就诊指引卡</p></div><el-button type="primary" @click="openCreate">新增科室</el-button></div>
    <el-table :data="rows" stripe border><el-table-column label="医院" min-width="180"><template #default="scope">{{ hospitalNames[scope.row.hospital_id] }}</template></el-table-column>
      <el-table-column prop="name" label="科室" width="150" /><el-table-column prop="floor" label="楼层" width="150" /><el-table-column prop="location" label="位置" min-width="180" />
      <el-table-column label="操作" width="150"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑科室' : '新增科室'" width="560"><el-form label-width="80">
      <el-form-item label="医院"><el-select v-model="form.hospital_id" class="full-width"><el-option v-for="item in hospitals" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
      <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item><el-form-item label="楼层"><el-input v-model="form.floor" placeholder="如：门诊楼 3 层" /></el-form-item><el-form-item label="位置"><el-input v-model="form.location" placeholder="如：东区 301 室" /></el-form-item></el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog>
  </section>
</template>
