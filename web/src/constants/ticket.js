/**
 * 工单枚举的单一数据源：状态、分类、优先级的 label 与配色都在这里，
 * 页面只引用不重复定义，避免"三个页面三种叫法"。
 */
export const TICKET_STATUS = {
  OPEN: { label: '待接单', type: 'primary', color: '#2563EB' },
  IN_PROGRESS: { label: '处理中', type: 'warning', color: '#F59E0B' },
  WAITING_CUSTOMER: { label: '待补充信息', type: 'warning', color: '#B45309' },
  RESOLVED: { label: '已解决', type: 'success', color: '#10B981' },
  CLOSED: { label: '已关闭', type: 'info', color: '#9CA3AF' }
}

export const STATUS_OPTIONS = Object.entries(TICKET_STATUS).map(([value, meta]) => ({
  value,
  label: meta.label
}))

export const TICKET_CATEGORY = {
  IT_FAULT: 'IT 故障',
  PERMISSION: '权限申请',
  DEVICE: '设备维护',
  OTHER: '其他'
}

export const CATEGORY_OPTIONS = Object.entries(TICKET_CATEGORY).map(([value, label]) => ({
  value,
  label
}))

export const TICKET_PRIORITY = {
  LOW: { label: '低', color: '#9CA3AF' },
  MEDIUM: { label: '中', color: '#3B82F6' },
  HIGH: { label: '高', color: '#F59E0B' },
  URGENT: { label: '紧急', color: '#EF4444' }
}

export const PRIORITY_OPTIONS = Object.entries(TICKET_PRIORITY).map(([value, meta]) => ({
  value,
  label: meta.label
}))

/** 操作历史里动作的中文名 */
export const ACTION_LABEL = {
  CREATE: '创建工单',
  CLAIM: '接单',
  ASSIGN: '指派',
  RESOLVE: '标记解决',
  CLOSE: '确认关闭',
  REQUEST_INFO: '请求补充信息',
  PROVIDE_INFO: '已补充信息',
  REOPEN: '重新处理',
  TRANSFER: '转派',
  REPLY: '回复',
  ATTACHMENT: '添加附件',
  INTERNAL_NOTE: '内部备注',
  INTERNAL_ATTACHMENT: '内部附件'
}

export const ROLE_LABEL = {
  EMPLOYEE: '员工',
  SUPPORT: '技术支持',
  ADMIN: '管理员'
}

export const ROLE_TAG_TYPE = {
  EMPLOYEE: 'info',
  SUPPORT: 'warning',
  ADMIN: 'danger'
}
