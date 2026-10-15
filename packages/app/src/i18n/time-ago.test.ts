import { describe, expect, it } from 'vitest'
import { compactAge, compactCount, formatTimeAgo } from './time-ago'

describe('formatTimeAgo', () => {
  const now = Date.UTC(2026, 0, 10, 12)

  it('picks the largest whole unit', () => {
    expect(formatTimeAgo(new Date(now - 3 * 60 * 60 * 1000), 'en', now)).toBe('3 hours ago')
    expect(formatTimeAgo(new Date(now - 2 * 24 * 60 * 60 * 1000), 'en', now)).toBe('2 days ago')
    expect(formatTimeAgo(new Date(now - 10_000), 'en', now)).toBe('now')
  })

  it('follows the locale', () => {
    expect(formatTimeAgo(new Date(now - 3 * 60 * 60 * 1000), 'zh-CN', now)).toBe('3小时前')
  })
})

// The same cases as the phone's InboxTest, so the inbox reads the same on both.
describe('compactAge', () => {
  const now = Date.UTC(2026, 0, 10, 12)
  const ago = (seconds: number) => compactAge(new Date(now - seconds * 1000), now)

  it('steps through the units the phone uses', () => {
    expect(ago(30)).toBe('now')
    expect(ago(5 * 60)).toBe('5m')
    expect(ago(3 * 3_600)).toBe('3h')
    expect(ago(2 * 86_400)).toBe('2d')
    expect(ago(14 * 86_400)).toBe('2w')
    expect(ago(90 * 86_400)).toBe('3mo')
    expect(ago(800 * 86_400)).toBe('2y')
    expect(compactAge(new Date(now + 60_000), now)).toBe('now')
  })
})

describe('compactCount', () => {
  it('fits the diff column', () => {
    expect(compactCount(640)).toBe('640')
    expect(compactCount(1_234)).toBe('1.2k')
    expect(compactCount(12_345)).toBe('12k')
  })
})
