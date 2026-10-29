<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import { ticketApi } from '../api/ticket'
import { CATEGORY_OPTIONS, PRIORITY_OPTIONS } from '../constants/ticket'

const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)

/**
 * 本次填写的幂等键：进入页面时生成一个，创建成功后才重置。
 * 为什么不是"每次点击都生成"：网络超时后用户再点一次，必须复用同一个 key 才能被后端识别成重放；
 * 每次换 key 等于放弃了这层保护。
 */
const idempotencyKey = ref(newIdempotencyKey())

function newIdempotencyKey() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID()
  }
  // 旧浏览器兜底：时间戳 + 随机串，长度远小于后端 64 字符上限
  return `t${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}

const form = reactive({
  title: '',
  description: '',
  category: 'IT_FAULT',
  priority: 'MEDIUM'
})

const rules = {
  title: [
    { required: true, message: '请填写工单标题', trigger: 'blur' },
    { max: 128, message: '标题不能超过 128 个字符', trigger: 'blur' }
  ],
  description: [{ max: 2000, message: '描述不能超过 2000 个字符', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  priority: [{ required: true, message: '请选择优先级', trigger: 'change' }]
}

function submit() {
  formRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    submitting.value = true
    try {
      const created = await ticketApi.create({ ...form }, idempotencyKey.value)
      // 成功后换新 key，避免"再建一单"被当成重放
      idempotencyKey.value = newIdempotencyKey()
      ElMessage.success(`工单 ${created.ticketNo} 已创建`)
      router.replace({ name: 'ticket-detail', params: { id: created.id } })
    } catch (error) {
      console.error('创建工单失败', error)
    } finally {
      submitting.value = false
    }
  })
}
</script>

<template>
  <div>
    <div class="mb-4">
      <el-button text :icon="ArrowLeft" @click="router.push({ name: 'tickets' })">返回列表</el-button>
    </div>

    <PageHeader title="新建工单" description="描述越具体，技术支持定位越快" />

    <div class="isd-card mx-auto max-w-[640px] p-6">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="一句话说明问题，例如：3 楼打印机无法连接" />
        </el-form-item>

        <div class="grid grid-cols-2 gap-4">
          <el-form-item label="分类" prop="category">
            <el-select v-model="form.category" class="w-full">
              <el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>

          <el-form-item label="优先级" prop="priority">
            <el-select v-model="form.priority" class="w-full">
              <el-option v-for="item in PRIORITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="问题描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="7"
            maxlength="2000"
            show-word-limit
            placeholder="补充现象、影响范围、已经尝试过的操作。例如：今早开始 3 楼东侧打印机面板显示离线，重启打印机无效，影响 12 人打印。"
          />
        </el-form-item>

        <div class="mt-2 flex items-center justify-end gap-2">
          <el-button @click="router.push({ name: 'tickets' })">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submit">提交工单</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>
