/**
 * 登录后回跳地址的校验。
 *
 * 为什么必须集中在一处：redirect 参数有三个生产者（路由守卫、axios 401 拦截器、登录页），
 * 只要有一处漏校验，/change-password 就会被反复写进参数里，换任何账号登录都落回改密页。
 * 所以三处统一调用这个函数，而不是各写一套 if。
 */
const BLOCKED_PATHS = ['/login', '/change-password']

/**
 * 返回一个可信的站内回跳地址，任何可疑输入都回落到 fallback。
 *
 * 拒绝三类输入：
 * 1. 非字符串、不以单个 "/" 开头（绝对 URL）、以 "//" 开头（协议相对地址，会被当成外站）；
 * 2. 站内的登录页 / 改密页：登录成功后再回改密页等于把用户锁死在单向门里；
 * 3. 归一化后（去尾斜杠、转小写）命中 1、2 的变体，避免 /LOGIN/ 这类绕过。
 */
export function safeRedirect(raw, fallback = '/') {
  if (typeof raw !== 'string') {
    return fallback
  }
  const value = raw.trim()
  if (!value.startsWith('/') || value.startsWith('//')) {
    return fallback
  }

  const path = value.split('?')[0].split('#')[0]
  const normalized = path.toLowerCase().replace(/\/+$/, '') || '/'
  if (BLOCKED_PATHS.includes(normalized)) {
    return fallback
  }
  return value
}

export { BLOCKED_PATHS }
