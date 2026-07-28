<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { departmentApi, doctorApi, type Department, type Doctor, type DoctorInput } from '../api/organization'

const rows = ref<Doctor[]>([])
const departments = ref<Department[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const emptyForm = (): DoctorInput => ({ department_id: 0, name: '', title: '', specialty: '', photo_url: '' })
const form = reactive<DoctorInput>(emptyForm())
const departmentNames = computed(() => Object.fromEntries(departments.value.map(item => [item.id, item.name])))

async function load() { [rows.value, departments.value] = await Promise.all([doctorApi.list(), departmentApi.list()]) }
function openCreate() { editingId.value = null; Object.assign(form, emptyForm(), { department_id: departments.value[0]?.id ?? 0 }); dialogVisible.value = true }
function openEdit(row: Doctor) { editingId.value = row.id; Object.assign(form, row); dialogVisible.value = true }
async function save() {
  if (editingId.value) await doctorApi.update(editingId.value, form)
  else await doctorApi.create(form)
  ElMessage.success('保存成功'); dialogVisible.value = false; await load()
}
async function remove(row: Doctor) {
  await ElMessageBox.confirm(`确认删除“${row.name}”？`, '删除医生', { type: 'warning' })
  await doctorApi.remove(row.id); ElMessage.success('已删除'); await load()
}
onMounted(load)
</script>

<template>
  <section>
    <div class="page-heading"><div><h2>医生管理</h2><p>维护医生专业资料与展示照片</p></div><el-button type="primary" @click="openCreate">新增医生</el-button></div>
    <el-table :data="rows" stripe border><el-table-column label="照片" width="76"><template #default="scope"><el-avatar :src="scope.row.photo_url">{{ scope.row.name[0] }}</el-avatar></template></el-table-column>
      <el-table-column prop="name" label="姓名" width="110" /><el-table-column label="科室" width="140"><template #default="scope">{{ departmentNames[scope.row.department_id] }}</template></el-table-column>
      <el-table-column prop="title" label="职称" width="130" /><el-table-column prop="specialty" label="擅长" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="150"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button link type="danger" @click="remove(scope.row)">删除</el-button></template></el-table-column></el-table>
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑医生' : '新增医生'" width="600"><el-form label-width="80">
      <el-form-item label="科室"><el-select v-model="form.department_id" class="full-width"><el-option v-for="item in departments" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
      <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item><el-form-item label="职称"><el-input v-model="form.title" /></el-form-item><el-form-item label="擅长"><el-input v-model="form.specialty" type="textarea" :rows="3" /></el-form-item><el-form-item label="照片 URL"><el-input v-model="form.photo_url" /></el-form-item></el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog>
  </section>
</template>
