<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CheckCircle2, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { useUserStore } from '../stores/user'
import { authApi } from '../api/auth'
import { safeRedirect } from '../utils/redirect'
import AdminContactDialog from '../components/AdminContactDialog.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const loginFormRef = ref(null)
const registerFormRef = ref(null)
const loading = ref(false)
const registerVisible = ref(false)
const forgotVisible = ref(false)

const form = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', password: '', fullName: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 50, message: '用户名长度需在 4-50 之间', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: '只能包含字母、数字和下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需在 8-64 之间', trigger: 'blur' }
  ]
}

function doLogin() {
  loginFormRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    loading.value = true
    try {
      const data = await store.login({ username: form.username, password: form.password })
      if (data.mustChangePassword) {
        ElMessage.warning('该账号使用临时密码，请先修改密码')
        router.replace({ name: 'change-password' })
        return
      }
      ElMessage.success('登录成功')
      // 只回跳到受控的站内业务页：登录页 / 改密页会被 safeRedirect 过滤成工作台，
      // 否则任一账号登录后都可能被历史 redirect 参数拖回改密页。
      router.replace(safeRedirect(route.query.redirect))
    } catch (error) {
      console.error('登录失败', error)
    } finally {
      loading.value = false
    }
  })
}

function doRegister() {
  registerFormRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    try {
      await authApi.register({ ...registerForm })
      ElMessage.success('注册成功，请使用新账号登录')
      form.username = registerForm.username
      form.password = ''
      registerVisible.value = false
    } catch (error) {
      console.error('注册失败', error)
    }
  })
}

function fillDemo() {
  form.username = 'demo_emp'
  form.password = 'employee123'
}
</script>

<template>
  <div class="flex min-h-screen">
    <section class="relative hidden w-[55%] flex-col justify-between overflow-hidden bg-[#111827] p-12 lg:flex">
      <div class="absolute -right-24 -top-24 h-96 w-96 rounded-full bg-brand-600/20 blur-3xl" />
      <div class="absolute -bottom-32 -left-16 h-96 w-96 rounded-full bg-brand-500/10 blur-3xl" />

      <div class="relative">
        <div class="flex items-center gap-3">
          <div class="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-600 font-semibold text-white">IS</div>
          <div>
            <p class="m-0 text-[15px] font-medium text-white">ISD 智能服务台</p>
            <p class="m-0 text-[10px] tracking-widest text-gray-500">IT SERVICE DESK</p>
          </div>
        </div>
      </div>

      <div class="relative max-w-md">
        <h1 class="m-0 text-[30px] font-semibold leading-tight text-white">
          报障、接单、沉淀知识，<br />一站式 IT 服务闭环
        </h1>
        <p class="mt-4 text-[14px] leading-7 text-gray-400">
          工单状态流转带乐观锁保护，知识库与 AI 助手基于真实数据作答，员工只会看到自己的工单。
        </p>

        <div class="mt-10 space-y-3">
          <div class="flex items-start gap-3 text-[13px] text-gray-300">
            <CheckCircle2 :size="17" class="mt-0.5 shrink-0 text-brand-500" />
            三角色权限隔离：员工 / 技术支持 / 管理员各司其职
          </div>
          <div class="flex items-start gap-3 text-[13px] text-gray-300">
            <ShieldCheck :size="17" class="mt-0.5 shrink-0 text-brand-500" />
            并发接单乐观锁，抢单冲突有明确提示而非报错页
          </div>
          <div class="flex items-start gap-3 text-[13px] text-gray-300">
            <Sparkles :size="17" class="mt-0.5 shrink-0 text-brand-500" />
            AI 只读助手：查工单进展、检索知识库，不执行写操作
          </div>
        </div>
      </div>

      <p class="relative m-0 text-[11px] text-gray-600">Spring Boot 3.5 · MyBatis · MySQL · Spring AI</p>
    </section>

    <section class="flex w-full items-center justify-center bg-canvas px-6 lg:w-[45%]">
      <div class="w-full max-w-[380px]">
        <h2 class="m-0 text-[22px] font-semibold text-ink-900">登录</h2>
        <p class="mt-1.5 mb-7 text-[13px] text-ink-600">使用企业账号进入服务台</p>

        <el-form ref="loginFormRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              data-testid="login-username"
              size="large"
              placeholder="请输入用户名"
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              data-testid="login-password"
              type="password"
              size="large"
              show-password
              placeholder="请输入密码"
              @keyup.enter="doLogin"
            />
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            class="w-full"
            data-testid="login-submit"
            :loading="loading"
            @click="doLogin"
          >
            登录
          </el-button>
        </el-form>

        <div class="mt-4 flex items-center justify-between text-[12px]">
          <button type="button" class="cursor-pointer text-brand-600 hover:text-brand-700" @click="registerVisible = true">
            注册新账号
          </button>
          <div class="flex items-center gap-3">
            <button type="button" class="cursor-pointer text-brand-600 hover:text-brand-700" @click="forgotVisible = true">
              忘记密码？
            </button>
            <button type="button" class="cursor-pointer text-ink-400 hover:text-ink-600" @click="fillDemo">
              填入演示员工账号
            </button>
          </div>
        </div>

        <div class="mt-8 rounded-lg border border-line bg-white p-3.5">
          <p class="m-0 text-[12px] font-medium text-ink-900">演示账号</p>
          <p class="m-0 mt-1 text-[11px] leading-5 text-ink-600">
            员工 demo_emp / employee123 · 技术支持 demo_sup / support123 · 管理员 demo_admin / admin12345
          </p>
        </div>
      </div>
    </section>

    <AdminContactDialog v-model="forgotVisible" />

    <el-dialog v-model="registerVisible" title="注册新账号" width="420px">
      <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="registerForm.username" placeholder="4-50 位字母、数字或下划线" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="registerForm.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="registerForm.fullName" placeholder="选填，默认与用户名相同" />
        </el-form-item>
      </el-form>
      <p class="m-0 text-[11px] text-ink-400">新注册账号默认为员工角色，需要技术支持或管理员权限请联系管理员调整。</p>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" @click="doRegister">注册</el-button>
      </template>
    </el-dialog>
  </div>
</template>
