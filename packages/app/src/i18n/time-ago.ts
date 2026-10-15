const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 60 * 60 * 1000],
  ['month', 30 * 24 * 60 * 60 * 1000],
  ['week', 7 * 24 * 60 * 60 * 1000],
  ['day', 24 * 60 * 60 * 1000],
  ['hour', 60 * 60 * 1000],
  ['minute', 60 * 1000],
]

/** "3 hours ago" in the given language; anything under a minute reads as "now". */
export function formatTimeAgo(date: Date, locale: string, now = Date.now()): string {
  const format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' })
  const elapsed = date.getTime() - now
  for (const [unit, ms] of UNITS) {
    if (Math.abs(elapsed) >= ms)
      return format.format(Math.round(elapsed / ms), unit)
  }
  return format.format(0, 'second')
}

const DAY = 86_400

/** The phone's `compactAge`: `now`, `5m`, `3h`, `2d`, `1w`, `4mo`, `1y`, for a column that fits four characters. */
export function compactAge(date: Date, now = Date.now()): string {
  const seconds = Math.max(0, Math.floor((now - date.getTime()) / 1000))
  if (seconds < 60)
    return 'now'
  if (seconds < 3_600)
    return `${Math.floor(seconds / 60)}m`
  if (seconds < DAY)
    return `${Math.floor(seconds / 3_600)}h`
  if (seconds < 7 * DAY)
    return `${Math.floor(seconds / DAY)}d`
  if (seconds < 30 * DAY)
    return `${Math.floor(seconds / (7 * DAY))}w`
  if (seconds < 365 * DAY)
    return `${Math.floor(seconds / (30 * DAY))}mo`
  return `${Math.floor(seconds / (365 * DAY))}y`
}

/** The phone's `compactCount`: a line count that fits the diff column, `640`, `1.2k`, `12k`. */
export function compactCount(n: number): string {
  if (n < 1_000)
    return `${n}`
  if (n < 10_000)
    return `${Math.floor(n / 1_000)}.${Math.floor(n % 1_000 / 100)}k`
  return `${Math.floor(n / 1_000)}k`
}
