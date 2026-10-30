import { expect } from '@playwright/test'

/** 与 e2e/seed.sql、README「演示账号」保持一致 */
export const ACCOUNTS = {
  employee: { username: 'demo_emp', password: 'employee123' },
  support: { username: 'demo_sup', password: 'support123' },
  admin: { username: 'demo_admin', password: 'admin12345' }
}

/**
 * UI 登录：不预置 storageState。
 * 会话 Cookie 是 Secure + HttpOnly，API 预登录还要手工拼 CSRF 双提交；
 * 三次 UI 登录只多几秒，换来用例可读、也更接近真实用户路径。
 */
export async function login(page, account) {
  await page.goto('/login')
  await page.getByTestId('login-username').fill(account.username)
  await page.getByTestId('login-password').fill(account.password)
  await page.getByTestId('login-submit').click()

  // 登录成功会 replace 到工作台；等侧栏出现，避免后续 goto 与登录跳转互相打断
  await expect(page.getByRole('link', { name: '工作台' })).toBeVisible()
  await expect(page).toHaveURL((url) => url.pathname === '/')
}

/** 退出登录：顶栏用户下拉 → 退出登录。会话清掉后停在登录页。 */
export async function logout(page) {
  await page.locator('header .el-dropdown').click()
  await page.getByRole('menuitem', { name: '退出登录' }).click()
  await expect(page.getByTestId('login-submit')).toBeVisible()
  await expect(page).toHaveURL((url) => url.pathname === '/login')
}
