import { describe, expect, it } from 'vitest'
import { BLOCKED_PATHS, safeRedirect } from '../../src/utils/redirect'

/**
 * 回跳地址是「登录后被带到哪」的唯一开关，也是典型的开放重定向入口。
 * 这里用表驱动把三类拒绝写清楚：外站、登录/改密页、以及归一化后的变体。
 */
describe('safeRedirect', () => {
  it('放行站内路径并原样返回（含 query 与 hash）', () => {
    expect(safeRedirect('/tickets/12?tab=log#top')).toBe('/tickets/12?tab=log#top')
    expect(safeRedirect('/')).toBe('/')
  })

  it('拒绝绝对 URL 与协议相对地址', () => {
    expect(safeRedirect('https://evil.example.com')).toBe('/')
    expect(safeRedirect('//evil.example.com/login')).toBe('/')
    expect(safeRedirect('javascript:alert(1)')).toBe('/')
  })

  it('拒绝非字符串输入', () => {
    expect(safeRedirect(undefined)).toBe('/')
    expect(safeRedirect(null)).toBe('/')
    expect(safeRedirect(123)).toBe('/')
    expect(safeRedirect({})).toBe('/')
  })

  it('拒绝登录页与改密页，避免登录后又被送回单向门', () => {
    BLOCKED_PATHS.forEach((path) => {
      expect(safeRedirect(path)).toBe('/')
      expect(safeRedirect(`${path}?redirect=/tickets`)).toBe('/')
    })
  })

  it('拒绝归一化后命中的变体（大小写、尾斜杠）', () => {
    expect(safeRedirect('/LOGIN/')).toBe('/')
    expect(safeRedirect('//Change-Password///')).toBe('/')
  })

  it('前缀相似但不同的路径不算命中', () => {
    expect(safeRedirect('/login-help')).toBe('/login-help')
    expect(safeRedirect('/change-password-tip')).toBe('/change-password-tip')
  })

  it('支持自定义 fallback：401 跳登录时不允许把空串当成落点', () => {
    expect(safeRedirect('/login', '')).toBe('')
    expect(safeRedirect('https://evil.example.com', '')).toBe('')
  })
})
