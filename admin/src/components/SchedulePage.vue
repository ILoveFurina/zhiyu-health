<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { doctorApi, type Doctor } from '../api/organization'
import { scheduleApi, type Schedule, type ScheduleInput } from '../api/schedule'

const rows = ref<Schedule[]>([])
const doctors = ref<Doctor[]>([])
const dialogVisible = ref(false)
const emptyForm = (): ScheduleInput => ({
  doctor_id: 0,
  schedule_date: '',
  time_slot: '上午',
  total_slots: 20,
})
const form = reactive<ScheduleInput>(emptyForm())
const doctorNames = computed(() =>
  Object.fromEntries(doctors.value.map((doctor) => [doctor.id, doctor.name])),
)

async function load() {
  ;[rows.value, doctors.value] = await Promise.all([scheduleApi.list(), doctorApi.list()])
}

function openCreate() {
  Object.assign(form, emptyForm(), { doctor_id: doctors.value[0]?.id ?? 0 })
  dialogVisible.value = true
}

async function save() {
  await scheduleApi.create(form)
  ElMessage.success('排班创建成功')
  dialogVisible.value = false
  await load()
}

async function disable(row: Schedule) {
  await ElMessageBox.confirm(
    `确认停用 ${doctorNames.value[row.doctor_id]} ${row.schedule_date} ${row.time_slot} 的排班？`,
    '停用排班',
    { type: 'warning' },
  )
  await scheduleApi.disable(row.id)
  ElMessage.success('排班已停用')
  await load()
}

onMounted(load)
</script>

<template>
  <section>
    <div class="page-heading">
      <div><h2>排班管理</h2><p>维护医生出诊时段与号源池</p></div>
      <el-button type="primary" @click="openCreate">创建排班</el-button>
    </div>
    <el-table :data="rows" stripe border>
      <el-table-column label="医生" min-width="130">
        <template #default="scope">{{ doctorNames[scope.row.doctor_id] }}</template>
      </el-table-column>
      <el-table-column prop="schedule_date" label="日期" width="130" />
      <el-table-column prop="time_slot" label="时段" width="110" />
      <el-table-column prop="total_slots" label="总号源" width="100" />
      <el-table-column prop="remaining_slots" label="剩余号源" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.is_active ? 'success' : 'info'">
            {{ scope.row.is_active ? '启用' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="scope">
          <el-button
            v-if="scope.row.is_active"
            link
            type="danger"
            @click="disable(scope.row)"
          >停用</el-button>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialogVisible" title="创建排班" width="520">
      <el-form label-width="90px">
        <el-form-item label="医生">
          <el-select v-model="form.doctor_id" class="full-width">
            <el-option
              v-for="doctor in doctors"
              :key="doctor.id"
              :label="`${doctor.name} · ${doctor.title}`"
              :value="doctor.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker
            v-model="form.schedule_date"
            type="date"
            value-format="YYYY-MM-DD"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="时段">
          <el-select v-model="form.time_slot" class="full-width">
            <el-option label="上午" value="上午" />
            <el-option label="下午" value="下午" />
            <el-option label="晚上" value="晚上" />
          </el-select>
        </el-form-item>
        <el-form-item label="号源总数">
          <el-input-number v-model="form.total_slots" :min="1" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>
