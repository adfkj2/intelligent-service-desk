/**
 * 复制文本到剪贴板。
 * 优先用异步 Clipboard API（localhost/HTTPS 可用），否则退回 textarea + execCommand。
 * 返回 Promise，调用方统一 then/catch，避免把复制失败静默掉。
 */
export function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(text)
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  document.body.appendChild(textarea)
  textarea.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(textarea)
  return ok ? Promise.resolve() : Promise.reject(new Error('浏览器不支持复制'))
}
