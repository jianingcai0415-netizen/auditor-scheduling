/**
 * @file LocationDayGrid.tsx
 * @brief "By location" view in weekly mode: one column per day, one row per location, and every
 *        shift drawn as a small chip instead of a bar stretched over its duration.
 *
 * @details
 * Why this is not vis-timeline: a timeline item is proportional to its duration, so two shifts
 * that overlap in time inside the same city are drawn on top of each other — exactly what this
 * view must avoid. Chips also make "+N more" possible, which a timeline cannot express.
 *
 * A shift that spans several days appears once per covered day: solid on the day it starts,
 * faded on the following days ("continuation").
 */

import { Fragment, useMemo, useState } from 'react'
import type { ScheduleData, Shift } from '../../api/shifts'
import { tx } from '../../i18n/uiText'
import './LocationDayGrid.css'

/** Same shape as the one in ShiftsPage: i18next's TFunction is assignable to it, a looser
 *  signature (optional fallback) is not. */
type TFn = (key: string, fallback: string) => string

/** One chip: which shift, and whether this day is the day it starts. */
interface ChipEntry {
  shift: Shift
  isStart: boolean
}

interface Props {
  locations: ScheduleData['locations']
  shifts: Shift[]
  /** First day of the period (Monday for week mode). */
  windowStart: Date
  days: number
  t: TFn
  /** Location id highlighted by the search box, if any. */
  highlightLocationId?: number | null
  onShiftClick: (shiftId: number) => void
  onShiftContextMenu: (shiftId: number, x: number, y: number) => void
  onCellClick: (locationId: number, day: Date) => void
  onCellContextMenu: (locationId: number, day: Date, x: number, y: number) => void
  onManageDates: (locationId: number) => void
}

const MAX_CHIPS = 3

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function addDays(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n)
}

function dayKey(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 星期表头：键走 TSV 的 day.*.s（已有 5 语言），中文兜底保证键缺失时不至于露出原始 key。 */
const WEEKDAY_KEYS = ['day.sun.s', 'day.mon.s', 'day.tue.s', 'day.wed.s', 'day.thu.s', 'day.fri.s', 'day.sat.s']
const WEEKDAY_ZH = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** Last 3-4 digits of the operator's display name — the part that actually distinguishes them. */
function chipLabel(shift: Shift): string {
  const name = shift.employee?.fullName ?? ''
  // 模块级函数拿不到 t 钩子，用 tx() 读当前语言（组件重渲染时自然重算）。
  if (!shift.employee) return tx('待分配')
  const digits = name.match(/(\d{3,})/)
  if (digits) return digits[1].slice(-4)
  return name.slice(0, 4)
}

/** "08:00 → 12:00" (or with the date when the shift crosses midnight). */
function timeRange(shift: Shift): string {
  const s = new Date(shift.start)
  const e = new Date(shift.end)
  const hm = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const sameDay = dayKey(s) === dayKey(e)
  return sameDay ? `${hm(s)} → ${hm(e)}` : `${dayKey(s)} ${hm(s)} → ${dayKey(e)} ${hm(e)}`
}

export default function LocationDayGrid({
  locations, shifts, windowStart, days, t, highlightLocationId,
  onShiftClick, onShiftContextMenu, onCellClick, onCellContextMenu, onManageDates,
}: Props) {
  // Expanded cells ("+N" opened). Keyed by location + day so only one cell per corner.
  const [expanded, setExpanded] = useState<string | null>(null)

  const daysArr = useMemo(
    () => Array.from({ length: days }, (_, i) => addDays(startOfDay(windowStart), i)),
    [windowStart, days],
  )

  /**
   * locationId|yyyy-mm-dd → chips of that day, sorted by start time.
   * A shift is added to every day it covers, so a 3-day shift yields 3 chips.
   */
  const index = useMemo(() => {
    const map = new Map<string, ChipEntry[]>()
    for (const shift of shifts) {
      if (!shift.start || !shift.end) continue
      const start = new Date(shift.start)
      const end = new Date(shift.end)
      if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) continue
      for (const day of daysArr) {
        const dayFrom = day
        const dayTo = addDays(day, 1)
        if (start >= dayTo || end <= dayFrom) continue
        const key = `${shift.location_id}|${dayKey(day)}`
        const bucket = map.get(key)
        const entry: ChipEntry = { shift, isStart: start >= dayFrom && start < dayTo }
        if (bucket) bucket.push(entry)
        else map.set(key, [entry])
      }
    }
    for (const bucket of map.values()) {
      bucket.sort((a, b) => a.shift.start.localeCompare(b.shift.start))
    }
    return map
  }, [shifts, daysArr])

  return (
    <div className="daygrid">
      <div className="daygrid__scroll">
        <div
          className="daygrid__grid"
          style={{ gridTemplateColumns: `184px repeat(${days}, minmax(118px, 1fr))` }}
        >
          <div className="daygrid__corner">{t('col.location', '地点')}</div>
          {daysArr.map(day => {
            const isToday = dayKey(day) === dayKey(new Date())
            return (
              <div key={dayKey(day)} className={`daygrid__head${isToday ? ' is-today' : ''}`}>
                <span className="daygrid__head-dow">{t(WEEKDAY_KEYS[day.getDay()], WEEKDAY_ZH[day.getDay()])}</span>
                <span className="daygrid__head-date">{day.getDate()}</span>
              </div>
            )
          })}

          {locations.map(location => {
            const rowHit = highlightLocationId === location.id
            return (
              <Fragment key={location.id}>
                <div className={`daygrid__loc${rowHit ? ' search-hit' : ''}`} data-row-id={location.id}>
                  <span className="daygrid__loc-name" title={location.name}>{location.name}</span>
                  <i
                    className="fas fa-calendar-alt daygrid__loc-cal"
                    role="button"
                    tabIndex={0}
                    data-action="dates-loc"
                    data-id={location.id}
                    title={t('tooltip.locationShiftsEmpty', '管理地点日期')}
                    onClick={e => { e.stopPropagation(); onManageDates(location.id) }}
                    onKeyDown={e => { if (e.key === 'Enter') { e.stopPropagation(); onManageDates(location.id) } }}
                  />
                </div>

                {daysArr.map(day => {
                  const cellKey = `${location.id}|${dayKey(day)}`
                  const entries = index.get(cellKey) ?? []
                  const isOpen = expanded === cellKey
                  const visible = isOpen ? entries : entries.slice(0, MAX_CHIPS)
                  const hidden = entries.length - visible.length
                  return (
                    <div
                      key={cellKey}
                      className={`daygrid__cell${entries.length === 0 ? ' is-empty' : ''}`}
                      onClick={e => { if (e.target === e.currentTarget) onCellClick(location.id, day) }}
                      onContextMenu={e => {
                        if (e.target !== e.currentTarget) return
                        e.preventDefault()
                        onCellContextMenu(location.id, day, e.clientX, e.clientY)
                      }}
                    >
                      {visible.map(({ shift, isStart }) => {
                        const unassigned = !shift.employee
                        const missingSkill = !unassigned && !hasRequiredSkills(shift)
                        const kind = unassigned ? 'unassigned' : missingSkill ? 'warn' : (isStart ? 'start' : 'cont')
                        return (
                          <button
                            key={`${shift.id}-${dayKey(day)}`}
                            type="button"
                            className={`daygrid__chip daygrid__chip--${kind}`}
                            title={chipTitle(shift, location.name, isStart, t)}
                            onClick={e => { e.stopPropagation(); onShiftClick(shift.id) }}
                            onContextMenu={e => {
                              e.preventDefault()
                              e.stopPropagation()
                              onShiftContextMenu(shift.id, e.clientX, e.clientY)
                            }}
                          >
                            <span className="daygrid__chip-id">{chipLabel(shift)}</span>
                            {missingSkill && <span className="daygrid__chip-flag">!</span>}
                            {!isStart && !unassigned && !missingSkill && (
                              <span className="daygrid__chip-cont" aria-hidden="true">›</span>
                            )}
                          </button>
                        )
                      })}
                      {hidden > 0 && (
                        <button
                          type="button"
                          className="daygrid__more"
                          onClick={e => { e.stopPropagation(); setExpanded(cellKey) }}
                        >
                          +{hidden}
                        </button>
                      )}
                      {isOpen && entries.length > MAX_CHIPS && (
                        <button
                          type="button"
                          className="daygrid__more daygrid__more--collapse"
                          onClick={e => { e.stopPropagation(); setExpanded(null) }}
                        >
                          {t('daygrid.collapse', '收起')}
                        </button>
                      )}
                    </div>
                  )
                })}
              </Fragment>
            )
          })}
        </div>
      </div>
    </div>
  )
}

/** Assigned operator holds every skill this shift requires. */
function hasRequiredSkills(shift: Shift): boolean {
  const required = (shift.requiredSkills ?? []).filter(s => s.used)
  if (required.length === 0) return true
  const owned = new Set((shift.employee?.skills ?? []).filter(s => s.used).map(s => s.id))
  return required.every(s => owned.has(s.id))
}

function chipTitle(shift: Shift, locationName: string, isStart: boolean, t: TFn): string {
  const unassigned = !shift.employee
  const who = shift.employee?.fullName ?? t('daygrid.unassigned', '未分配')
  const parts = [
    `${locationName} · ${who}`,
    timeRange(shift),
    isStart ? '' : t('daygrid.continuation', '（跨天延续）'),
    shift.auditOrderCode ? `${shift.auditOrderCode}${shift.auditStage ? ' · ' + shift.auditStage : ''}` : '',
    // 未分配时没人可"缺资格"，这条提示只对已分配的班次有意义。
    unassigned ? '' : hasRequiredSkills(shift) ? '' : t('daygrid.missingSkill', '⚠ 缺少所需资格'),
  ]
  return parts.filter(Boolean).join('\n')
}
