<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, KeyRound, LifeBuoy, LogOut, ShieldCheck } from 'lucide-vue-next'
import { authApi } from '../api/auth'
import { useUserStore } from '../stores/user'
import AdminContactDialog from '../components/AdminContactDialog.vue'

const router = useRouter()
const store = useUserStore()
const formRef = ref(null)
const loading = ref(false)
const loggingOut = ref(false)
const contactVisible = ref(false)
const oldPasswordError = ref('')

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const forced = computed(() => store.mustChangePassword)

/**
 * 逃生通道：忘记临时密码时退出当前账号，换别的账号或等管理员重新重置。
 * 不携带 redirect 参数——登录页是终点，不能成为下一个账号的回跳来源。
 * 后端 PasswordChangeInterceptor 已放行 /api/auth/logout，强制改密态下同样可用。
 */
async function handleLogout() {
  loggingOut.value = true
  try {
    await store.logout()
    ElMessage.success('已退出登录')
  } catch (error) {
    // 退出接口本身失败（例如会话已过期）不应阻塞用户离开，store.logout 的 finally 已清空本地用户态
    console.error('退出登录失败', error)
  } finally {
    loggingOut.value = false
    router.replace({ name: 'login' })
  }
}

function backToWorkbench() {
  router.replace({ name: 'workbench' })
}

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '新密码长度需在 8-64 之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

function submit() {
  formRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    oldPasswordError.value = ''
    loading.value = true
    try {
      const updated = await authApi.changePassword({
        oldPassword: form.oldPassword,
        newPassword: form.newPassword
      })
      store.setUser(updated)
      ElMessage.success('密码修改成功，请使用新密码继续')
      router.replace({ name: 'workbench' })
    } catch (error) {
      // 原密码输错是字段级问题，留在表单里提示，不要被全局拦截器踢回登录页
      if (error.response?.status === 400) {
        oldPasswordError.value = error.response.data?.message || '原密码不正确'
      }
      console.error('修改密码失败', error)
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="flex min-h-screen">
    <section class="relative hidden w-[55%] flex-col justify-between overflow-hidden bg-[#111827] p-12 lg:flex">
      <div class="absolute -right-24 -top-24 h-96 w-96 rounded-full bg-brand-600/20 blur-3xl" />
      <div class="absolute -bottom-32 -left-16 h-96 w-96 rounded-full bg-brand-500/10 blur-3xl" />

      <div class="relative flex items-center gap-3">
        <div class="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-600 font-semibold text-white">IS</div>
        <div>
          <p class="m-0 text-[15px] font-medium text-white">ISD 智能服务台</p>
          <p class="m-0 text-[10px] tracking-widest text-gray-500">IT SERVICE DESK</p>
        </div>
      </div>

      <div class="relative max-w-md">
        <h1 class="m-0 text-[30px] font-semibold leading-tight text-white">
          先改密码，<br />再开始一天的工作
        </h1>
        <p class="mt-4 text-[14px] leading-7 text-gray-400">
          管理员为你生成的是一次性临时密码。修改完成后，临时密码立即失效，其他设备上的登录也会被退出。
        </p>

        <div class="mt-10 space-y-3">
          <div class="flex items-start gap-3 text-[13px] text-gray-300">
            <KeyRound :size="17" class="mt-0.5 shrink-0 text-brand-500" />
            新密码长度 8-64 位，且不能与原密码相同
          </div>
          <div class="flex items-start gap-3 text-[13px] text-gray-300">
            <ShieldCheck :size="17" class="mt-0.5 shrink-0 text-brand-500" />
            提交后立即生效，并失效该账号的其他会话
          </div>
        </div>
      </div>

      <p class="relative m-0 text-[11px] text-gray-600">Spring Boot 3.5 · MyBatis · MySQL · Spring AI</p>
    </section>

    <section class="flex w-full items-center justify-center bg-canvas px-6 lg:w-[45%]">
      <div class="w-full max-w-[400px]">
        <p class="m-0 text-[12px] font-medium tracking-wide text-brand-600">
          {{ forced ? '首次登录安全校验' : '账号安全' }}
        </p>
        <h2 class="m-0 mt-1 text-[22px] font-semibold text-ink-900">
          {{ forced ? '请修改初始密码' : '修改登录密码' }}
        </h2>
        <p class="mt-1.5 mb-7 text-[13px] leading-6 text-ink-600">
          {{ forced ? '当前账号使用管理员重置的临时密码，修改后才能进入业务页面。' : '修改后需要重新使用新密码登录。' }}
        </p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <el-form-item label="当前密码" prop="oldPassword" :error="oldPasswordError">
            <el-input
              v-model="form.oldPassword"
              type="password"
              size="large"
              show-password
              placeholder="管理员提供的临时密码或当前密码"
            />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input v-model="form.newPassword" type="password" size="large" show-password placeholder="8-64 位" />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              size="large"
              show-password
              placeholder="再次输入新密码"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-button type="primary" size="large" class="w-full" :loading="loading" @click="submit">
            确认修改
          </el-button>
        </el-form>

        <div class="mt-3 flex items-center justify-between gap-3">
          <el-button link type="primary" :icon="LogOut" :loading="loggingOut" @click="handleLogout">
            {{ forced ? '退出登录，换个账号' : '退出登录' }}
          </el-button>
          <el-button v-if="!forced" link :icon="ArrowLeft" @click="backToWorkbench">
            返回工作台
          </el-button>
        </div>

        <div v-if="forced" class="mt-4 rounded-lg border border-line bg-white px-3.5 py-3">
          <p class="m-0 text-[12px] font-medium text-ink-900">忘记临时密码了？</p>
          <p class="m-0 mt-1 text-[11px] leading-5 text-ink-600">
            临时密码只在重置弹窗里展示一次。可以退出登录请管理员重新重置，或直接查看管理员联系方式。
          </p>
          <el-button link type="primary" size="small" :icon="LifeBuoy" class="mt-1.5" @click="contactVisible = true">
            联系管理员
          </el-button>
        </div>

        <p class="mt-3 text-[11px] leading-5 text-ink-400">
          当前账号：{{ store.user?.username }}。
          {{ forced ? '未完成改密前只能停留在本页。' : '修改后需要用新密码重新登录。' }}
        </p>
      </div>
    </section>

    <AdminContactDialog v-model="contactVisible" />
  </div>
</template>
