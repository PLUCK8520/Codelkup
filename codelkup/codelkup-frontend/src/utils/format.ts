import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

/**
 * 相对时间：刚刚 / 5 分钟前 / 3 小时前 / 2 天前，超过 30 天显示具体日期。
 *
 * ⚠️ 后端 Jackson 配置的输出格式是 `yyyy-MM-dd HH:mm:ss`（无时区标记）。
 *    Safari 对 `2026-09-24 23:45:00` 这种格式解析会失败（返回 Invalid Date），
 *    因此这里统一把空格替换成 `T`，转成 ISO 8601 再交给 dayjs。
 */
export function formatRelativeTime(input?: string | null): string {
  if (!input) return ''
  const normalized = input.replace(' ', 'T')
  const d = dayjs(normalized)
  if (!d.isValid()) return input

  const diffDays = dayjs().diff(d, 'day')
  if (diffDays > 30) {
    return d.format('YYYY-MM-DD')
  }
  return d.fromNow()
}

/** 绝对时间（详情页、hover 提示用） */
export function formatDateTime(input?: string | null): string {
  if (!input) return ''
  const d = dayjs(input.replace(' ', 'T'))
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : input
}

/**
 * 大数字缩写：1234 → 1.2k，1234567 → 1.2m
 * 中文社区更习惯「万」，但计数一般不会那么大，用 k 更紧凑。
 */
export function formatCount(n?: number | null): string {
  const num = n ?? 0
  if (num < 1000) return String(num)
  if (num < 1_000_000) {
    const v = num / 1000
    return (v >= 10 ? Math.round(v) : v.toFixed(1).replace(/\.0$/, '')) + 'k'
  }
  const v = num / 1_000_000
  return (v >= 10 ? Math.round(v) : v.toFixed(1).replace(/\.0$/, '')) + 'm'
}
