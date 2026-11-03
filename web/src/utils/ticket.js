export function formatDuration(seconds) {
  if (seconds === null || seconds === undefined || !Number.isFinite(Number(seconds))) return '暂无数据'
  const minutes = Math.round(Number(seconds) / 60)
  if (minutes < 1) return '不到 1 分钟'
  if (minutes < 60) return `${minutes} 分钟`
  if (minutes < 1440) return `${(minutes / 60).toFixed(1)} 小时`
  return `${(minutes / 1440).toFixed(1)} 天`
}

export function deadlineLabel(value, status) {
  if (status === 'WAITING_CUSTOMER') return '等待补充，解决计时暂停'
  if (status === 'RESOLVED' || status === 'CLOSED') return '处理已完成'
  if (!value) return '未设置处理时限'
  const difference = new Date(value).getTime() - Date.now()
  if (!Number.isFinite(difference)) return value
  return difference < 0 ? `已超时 ${formatDuration(-difference / 1000)}` : `剩余 ${formatDuration(difference / 1000)}`
}

export function knowledgeLink(id) {
  return { name: 'kb', query: { itemId: Number(id) } }
}
