/**
 * @file ShiftsPage.tsx
 * @brief Main shift-management page with a vis-timeline timeline.
 *
 * @details
 * ## Layout
 * The page is organized into two tabs:
 * - **Operators** — timeline with one row per employee; colored background bands
 *   indicate availability ranges (green/orange/red)
 * - **Locations** — timeline with one row per location
 *
 * ## Features
 * - Click a shift → ShiftModal in edit mode
 * - Click an empty area → ShiftModal in add mode (prepopulated date/time)
 * - Right click → ContextMenu with contextual actions
 * - Click group-label icons (left column) → related modals
 * - Solve button → starts the Timefold solver with a blocking overlay
 * - When solving finishes → SolveResultModal with constraint analysis
 *
 * ## Integrazione Timefold Solver
 * 1. `handleSolve()` chiama `shiftsApi.solve()` → ottiene `jobId`
 * 2. Poll `shiftsApi.getJob(jobId)` every 2s until `solverStatus !== "SOLVING_ACTIVE"`
 * 3. `handleStopSolve()` calls `shiftsApi.stopJob()` and clears polling
 * 4. On completion: `shiftsApi.analyze()` → opens `SolveResultModal`
 *
 * ## Left column (group HTML)
 * vis-timeline groups use injected HTML to display clickable icons.
 * Icon clicks are intercepted through event delegation on the container
 * (`data-action` and `data-id` attributes).
 */

import { useEffect, useState, useCallback, useMemo, useRef } from 'react'
import { Spinner, Button, Modal } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faRotate, faPlay, faStop, faLocationCrosshairs, faChevronLeft, faChevronRight, faUserGroup, faLocationDot, faMagnifyingGlass } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import i18n from '../i18n'
import { shiftsApi, getShiftColor, type ScheduleData, type Shift } from '../api/shifts'
import { workflowApi } from '../api/workflow'
import { tx } from '../i18n/uiText'
import { errorCode } from '../api/client'
import { useAppStore } from '../store/useAppStore'
import emptySchedule from '../assets/empty-schedule.svg'
import ShiftModal from '../components/shifts/ShiftModal'
import EmployeeDatesModal from '../components/employees/EmployeeDatesModal'
import LocationShiftsModal from '../components/locations/LocationShiftsModal'
import ConfirmModal from '../components/ConfirmModal'
import VisTimeline, { type TimelineItem, type TimelineGroup } from '../components/shifts/VisTimeline'
import LocationDayGrid from '../components/shifts/LocationDayGrid'
import { SAFE_TIMELINE_XSS } from '../components/shifts/timelineXss'
import ContextMenu, { type ContextMenuAction } from '../components/ContextMenu'
import SolveResultModal from '../components/shifts/SolveResultModal'
import type { ScoreAnalysis } from '../api/shifts'
import './ShiftsPage.css'

// ─── Availability colors ─────────────────────────────────────────────────────
/** 订单状态的中文名。只在「未分配」引导弹窗里用，避免把整个 statusLabels 表搬过来。 */
const ORDER_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '待审批', APPROVED: '已批准',
  REJECTED: '已退回', CANCELLED: '已取消', COMPLETED: '已完成',
}

const AVAIL_COLORS: Record<string, string> = {
  desired:     'rgba(0,200,0,0.18)',
  undesired:   'rgba(255,165,0,0.22)',
  unavailable: 'rgba(220,0,0,0.22)',
}

function escHtml(s: string): string {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')
}

/** @brief Translation function (key, fallback) → localized string. */
type TFn = (key: string, fallback: string) => string

// ─── Operator left-column HTML ───────────────────────────────────────────────
// UX: calendar icon only (date management). Editing/deletion is done
// from the Employees page.
function employeeGroupHtml(emp: ScheduleData['employees'][0], hasDates: boolean, t: TFn): string {
  const calColor = hasDates ? '#006400' : '#aaaaaa'
  const calTitle = hasDates
    ? t('tooltip.employeeDatesWithData', 'Manage dates (with dates assigned)')
    : t('tooltip.employeeDatesEmpty', 'Manage dates (no dates)')
  const id = emp.id
  return `
    <table style="width:240px;border-collapse:collapse;border:none;padding:2px 0">
      <tr>
        <td style="width:100%;vertical-align:middle;border:none;overflow-wrap:anywhere;font-size:0.88rem;font-weight:500">
          ${escHtml(emp.fullName ?? '')}
        </td>
        <td style="white-space:nowrap;text-align:right;vertical-align:middle;border:none;padding-left:4px">
          <i class="fas fa-calendar-alt"
             data-action="dates-emp" data-id="${id}"
             style="cursor:pointer;color:${calColor};font-size:0.85rem"
             title="${escHtml(calTitle)}"></i>
        </td>
      </tr>
    </table>`
}

// ─── Location left-column HTML ───────────────────────────────────────────────
// UX: calendar icon only (location-shift management). Editing/deletion is done
// from the Locations page.
function locationGroupHtml(loc: ScheduleData['locations'][0], hasShifts: boolean, t: TFn): string {
  const calColor = hasShifts ? '#006400' : '#aaaaaa'
  const calTitle = hasShifts
    ? t('tooltip.locationShiftsWithData', 'Manage shift dates (with shifts)')
    : t('tooltip.locationShiftsEmpty', 'Manage shift dates (no shifts)')
  const id = loc.id
  return `
    <table style="width:240px;border-collapse:collapse;border:none;padding:2px 0">
      <tr>
        <td style="width:100%;vertical-align:middle;border:none;overflow-wrap:anywhere;font-size:0.88rem;font-weight:500">
          ${escHtml(loc.id ? i18n.t('location.' + loc.id, loc.name ?? '') : (loc.name ?? ''))}
        </td>
        <td style="white-space:nowrap;text-align:right;vertical-align:middle;border:none;padding-left:4px">
          <i class="fas fa-calendar-alt"
             data-action="dates-loc" data-id="${id}"
             style="cursor:pointer;color:${calColor};font-size:0.85rem"
             title="${escHtml(calTitle)}"></i>
        </td>
      </tr>
    </table>`
}

/** Localized skill name for DISPLAY (never for comparisons, which keep using the base name). */
function skillLabel(s: { id?: number; name?: string }): string {
  return s.id ? i18n.t('skill.' + s.id, s.name ?? '') : (s.name ?? '')
}

function skillBadges(skills: { id?: number; name?: string }[]): string {
  return skills.map(s =>
    `<span class="badge me-1" style="background-color:#4a90d9;font-size:0.7rem">${escHtml(skillLabel(s))}</span>`
  ).join('')
}

// ─── Professional tooltip (card) ──────────────────────────────────────────────
// vis-timeline renders an item's `title` field as innerHTML inside `.vis-tooltip`.
// We make that container transparent (VisTimeline.css) and draw a dark card with
// an accent bar, icon, title, time, and skills.

/** "HH:mm" time from a local Date. */
function fmtHm(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

// Inline SVG icons (currentColor). FontAwesome is SVG-per-component here: `fa-*`
// classes have no effect in raw tooltip HTML, so use SVG directly.
const ICON_USER = '<svg viewBox="0 0 448 512" width="11" height="11" fill="currentColor" style="vertical-align:-1px"><path d="M224 256A128 128 0 1 0 224 0a128 128 0 1 0 0 256zm-45.7 48C79.8 304 0 383.8 0 482.3 0 498.7 13.3 512 29.7 512l388.6 0c16.4 0 29.7-13.3 29.7-29.7C448 383.8 368.2 304 269.7 304l-91.4 0z"/></svg>'
const ICON_PIN = '<svg viewBox="0 0 384 512" width="11" height="11" fill="currentColor" style="vertical-align:-1px"><path d="M215.7 499.2C267 435 384 279.4 384 192 384 86 298 0 192 0S0 86 0 192c0 87.4 117 243 168.3 307.2 12.3 15.3 35.1 15.3 47.4 0zM192 128a64 64 0 1 1 0 128 64 64 0 1 1 0-128z"/></svg>'

/** Pill-shaped skill badge for the tooltip card (light on a dark background). */
function tipSkillBadges(skills: { id?: number; name?: string }[]): string {
  return skills.map(s => `<span class="tl-tip-badge">${escHtml(skillLabel(s))}</span>`).join('')
}

/**
 * Tooltip card. `title`/`subtitle` are escaped here; `badgesHtml` is already safe HTML.
 */
function tipCard(opts: { accent: string; icon: string; title: string; subtitle?: string; badgesHtml?: string }): string {
  return `<div class="tl-tip" style="border-left-color:${opts.accent}">`
    + `<div class="tl-tip-head"><span class="tl-tip-ico" style="color:${opts.accent}">${opts.icon}</span>`
    + `<span class="tl-tip-title">${escHtml(opts.title)}</span></div>`
    + (opts.subtitle ? `<div class="tl-tip-sub">${escHtml(opts.subtitle)}</div>` : '')
    + (opts.badgesHtml ? `<div class="tl-tip-badges">${opts.badgesHtml}</div>` : '')
    + `</div>`
}

// ─── Data for the "by operator" tab ───────────────────────────────────────────
/**
 * @param window Only the "unavailable/desired" background ranges intersecting this window are
 *   emitted. They come from the backend as full-year ranges (3.1k for 500 operators); keeping the
 *   ones outside the visible period costs nothing on screen but makes every redraw and every
 *   view switch walk thousands of irrelevant items.
 */
function buildEmployeeData(schedule: ScheduleData, t: TFn, window?: { start: Date; end: Date }) {
  const windowStart = window ? toDbString(window.start).slice(0, 10) : null
  const windowEnd = window ? toDbString(window.end).slice(0, 10) : null
  const intersects = (from: string, to: string) =>
    !windowStart || !windowEnd || (to >= windowStart && from <= windowEnd)
  const groups: TimelineGroup[] = schedule.employees.map(emp => {
    const hasDates = [emp.unavailableDates, emp.undesiredDates, emp.desiredDates]
      .some(arr => (arr ?? []).some(d => d.dateStart && d.dateEnd))
    return { id: emp.id, content: employeeGroupHtml(emp, hasDates, t) }
  })

  const items: TimelineItem[] = []

  schedule.shifts.forEach((shift, i) => {
    if (!shift.start || !shift.end || !shift.employee) return
    const color = getShiftColor(shift, schedule.employees)
    const reqSkills = (shift.requiredSkills ?? []).filter(s => s.used)
    items.push({
      id: `emp-shift-${i}`,
      group: shift.employee.id,
      // 单行 + 类名：两行内容（姓名 + 徽章）在标准行高里放不下，会压到下一行；
      // strong 又不接受 class（时间轴白名单），所以统一用 span。
      content: `<div class="tl-item">
        <span class="tl-item-name">${escHtml(shift.location_id ? i18n.t('location.' + shift.location_id, shift.location_desc ?? '') : (shift.location_desc ?? ''))}</span>
        ${reqSkills.length > 0 ? `<span class="tl-item-badges">${skillBadges(reqSkills)}</span>` : ''}
      </div>`,
      start: new Date(shift.start),
      end: new Date(shift.end),
      style: `background-color:${color};border-color:${color};border-radius:4px`,
      // Tooltip card in By operator view: the row already identifies the operator → show the location.
      title: tipCard({
        accent: color,
        icon: ICON_PIN,
        title: shift.location_id ? i18n.t('location.' + shift.location_id, shift.location_desc ?? '') : (shift.location_desc ?? ''),
        subtitle: `${fmtHm(new Date(shift.start))} – ${fmtHm(new Date(shift.end))}`,
        badgesHtml: reqSkills.length > 0 ? tipSkillBadges(reqSkills) : undefined,
      }),
      editable: false,
    })
  })

  // Availability ranges as backgrounds
  schedule.employees.forEach(emp => {
    const allDates = [
      { arr: emp.unavailableDates ?? [], type: 'unavailable' },
      { arr: emp.undesiredDates ?? [],   type: 'undesired' },
      { arr: emp.desiredDates ?? [],     type: 'desired' },
    ]
    allDates.forEach(({ arr, type }) => {
      arr.forEach((d, di) => {
        if (!d.dateStart || !d.dateEnd) return
        if (!intersects(d.dateStart, d.dateEnd)) return
        items.push({
          id: `avail-${type}-${emp.id}-${di}`,
          group: emp.id,
          start: new Date(d.dateStart),
          end: new Date(d.dateEnd),
          content: '',
          type: 'background',
          style: `background-color:${AVAIL_COLORS[type]}`,
        })
      })
    })
  })

  return { groups, items }
}

// ─── Data for the "by location" tab ───────────────────────────────────────────
function buildLocationData(schedule: ScheduleData, t: TFn) {
  const locationsWithShifts = new Set(schedule.shifts.map(s => s.location_id))
  const groups: TimelineGroup[] = schedule.locations.map(loc => {
    const hasShifts = locationsWithShifts.has(loc.id)
    return { id: loc.id, content: locationGroupHtml(loc, hasShifts, t) }
  })

  const items: TimelineItem[] = schedule.shifts
    .filter(s => s.start && s.end)
    .map<TimelineItem>((shift, i) => {
      const reqSkills = (shift.requiredSkills ?? []).filter(s => s.used)
      if (shift.employee == null) {
        return {
          id: `loc-shift-${i}`,
          group: shift.location_id,
          content: reqSkills.length > 0
            ? `<div class="tl-item"><span class="tl-item-badges"><span class="badge" style="background-color:#aaa;font-size:0.62rem">${escHtml(reqSkills.map(s => skillLabel(s)).join(', '))}</span></span></div>`
            : '',
          start: new Date(shift.start),
          end: new Date(shift.end),
          style: 'background-color:#f1948a;border-color:#e74c3c;border-radius:4px',
          // Tooltip card: unassigned shift → show status + required skills.
          title: tipCard({
            accent: '#e74c3c',
            icon: ICON_USER,
            title: i18n.t('msg.unassigned', 'Unassigned'),
            subtitle: `${fmtHm(new Date(shift.start))} – ${fmtHm(new Date(shift.end))}`,
            badgesHtml: reqSkills.length > 0 ? tipSkillBadges(reqSkills) : undefined,
          }),
          editable: false,
        }
      }
      // Owned skills = only those with the `used` flag (the full catalog includes everything);
      // compare by ID because names may be localized.
      const empSkillIds = new Set((shift.employee.skills ?? []).filter(s => s.used).map(s => s.id))
      const hasSkills = reqSkills.length === 0 || reqSkills.every(rs => empSkillIds.has(rs.id))
      const skillColor = hasSkills ? '#5cb85c' : '#d9534f'
      return {
        id: `loc-shift-${i}`,
        group: shift.location_id,
        content: `<div class="tl-item">
          <span class="tl-item-name">${escHtml(shift.employee.fullName ?? '')}</span>
          <span class="tl-item-badges"><span class="badge" style="background-color:${skillColor};font-size:0.62rem">${escHtml(reqSkills.map(s => skillLabel(s)).join(', '))}</span></span>
        </div>`,
        start: new Date(shift.start),
        end: new Date(shift.end),
        style: 'background-color:#a9dfbf;border-color:#27ae60;border-radius:4px',
        // Tooltip card in By location view: the row already identifies the location → show the full name;
        // use a green/red accent depending on whether the operator has the required skills.
        title: tipCard({
          accent: skillColor,
          icon: ICON_USER,
          title: shift.employee.fullName ?? '',
          subtitle: `${fmtHm(new Date(shift.start))} – ${fmtHm(new Date(shift.end))}`,
          badgesHtml: reqSkills.length > 0 ? tipSkillBadges(reqSkills) : undefined,
        }),
        editable: false,
      }
    })

  return { groups, items }
}

/** Formats a local Date as "yyyy-MM-dd HH:mm:ss" (format expected by the backend). */
function toDbString(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

type WindowMode = 'week' | 'month'

/** Monday 00:00 of the week containing `d` (ISO, consistent with the Italian locale). */
function startOfWeekMonday(d: Date): Date {
  const diff = (d.getDay() + 6) % 7 // getDay: 0=Sun..6=Sat → 0=Mon..6=Sun
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - diff)
}

/** Normalizes a date to the start of its period (Monday for week, first day for month). Idempotent. */
function normalizeViewStart(d: Date, mode: WindowMode): Date {
  return mode === 'week'
    ? startOfWeekMonday(d)
    : new Date(d.getFullYear(), d.getMonth(), 1)
}

/** Right-exclusive [start, end) window for the selected granularity. */
function windowFor(viewStart: Date, mode: WindowMode): { start: Date; end: Date } {
  const start = normalizeViewStart(viewStart, mode)
  const end = mode === 'week'
    ? new Date(start.getFullYear(), start.getMonth(), start.getDate() + 7)
    : new Date(start.getFullYear(), start.getMonth() + 1, 1)
  return { start, end }
}

/** Clamps a date to the [min, max] range (already normalized); null = no bound on that side. */
function clampPeriod(d: Date, mode: WindowMode, min: Date | null, max: Date | null): Date {
  let v = normalizeViewStart(d, mode)
  if (min && v.getTime() < min.getTime()) v = min
  if (max && v.getTime() > max.getTime()) v = max
  return v
}

/**
 * First scrollable ancestor of `el` — the timeline's group column is its own scroll viewport
 * (created by vis-timeline), and it must not be confused with the page scroller.
 *
 * Using `el.scrollIntoView()` here would also scroll the document, which drags the navbar out of
 * view every time a search hit lands off-screen.
 */
function findScrollableAncestor(el: HTMLElement | null): HTMLElement | null {
  for (let node = el?.parentElement ?? null; node; node = node.parentElement) {
    const style = window.getComputedStyle(node)
    if (/(auto|scroll)/.test(style.overflowY) && node.scrollHeight > node.clientHeight) return node
  }
  return null
}

function shiftIdFromItemId(itemId: string | number, schedule: ScheduleData): number | null {
  const m = String(itemId).match(/shift-(\d+)$/)
  if (!m) return null
  return schedule.shifts[parseInt(m[1])]?.id ?? null
}

// ─── Main component ───────────────────────────────────────────────────────────

type CtxMenu = { x: number; y: number; actions: ContextMenuAction[] }

const TL_OPTIONS_BASE = {
  // 不按 content 排序：content 是一段 HTML 字符串，500 行排序 = 数千次长字符串比较，
  // 而且按中文字符的编码序排出来是乱的。用默认顺序（后端按 id）更稳定也更快。
  selectable: false,
  moveable: true,
  zoomable: true,
  // vis-timeline writes its root height as an INLINE style on every redraw
  // (asSize(options.height, autoHeight+'px')) — CSS alone can never constrain it.
  // Passing height here is what actually pins the viewport and enables the
  // internal vertical scroll with the time axis fixed on top.
  height: '100%' as const,
  // Wheel = scroll the employee/location list vertically; Ctrl+wheel = zoom.
  // Without zoomKey the wheel would zoom, making the page feel broken (no vertical scroll).
  verticalScroll: true,
  zoomKey: 'ctrlKey' as const,
  zoomMin: 1 * 24 * 60 * 60 * 1000,
  zoomMax: 367 * 24 * 60 * 60 * 1000,
  // 两个字段都要写：vis 里缺 `item` 时 `orientation.item !== 'top'` 成立，第一次
  // `_updateScrollTop` 会把整个内容高度当成偏移加到 scrollTop 上 —— 表现为打开就停在
  // 列表最底部（0001 那一批人完全看不到），而且之后怎么看都像"没有数据"。
  orientation: { axis: 'top' as const, item: 'top' as const },
  showCurrentTime: true,
  stack: true,
  xss: SAFE_TIMELINE_XSS,
}

export default function ShiftsPage() {
  const { t } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const mode = useAppStore(s => s.shiftWindowMode)
  const language = useAppStore(s => s.language)
  const [schedule, setSchedule] = useState<ScheduleData | null>(null)
  const [loading, setLoading] = useState(false)
  const [activeTab, setActiveTab] = useState<'employee' | 'location'>('employee')
  // 行检索：只做"定位 + 高亮"，不改数据、不重排列表（过滤会让人失去上下文）。
  const [searchTerm, setSearchTerm] = useState('')
  const [searchCursor, setSearchCursor] = useState(0)
  const timelineViewportRef = useRef<HTMLDivElement>(null)

  // ShiftModal
  const [shiftModalOpen, setShiftModalOpen] = useState(false)
  // 页面里已有同名函数 navigate(±1 周/月)，路由跳转单独取名，避免遮蔽。
  const goToRoute = useNavigate()
  const [searchParams] = useSearchParams()
  /**
   * 点「未分配」班次时的派工引导。以前直接弹「编辑班次」——而那个弹窗**没有审核员字段**，
   * 用户点完发现无处选人，还以为功能坏了。现在先判断订单能不能派工，再给下一步。
   */
  const [assignGuide, setAssignGuide] = useState<null | {
    shift: Shift; status: string | null; loading: boolean
  }>(null)
  const [editShiftId, setEditShiftId] = useState<number | null>(null)
  const [prefillStart, setPrefillStart] = useState<string | undefined>()
  const [prefillLocationId, setPrefillLocationId] = useState<number | undefined>()

  // EmployeeDatesModal
  const [datesModalEmpId, setDatesModalEmpId] = useState<number | null>(null)
  const [datesModalEmpName, setDatesModalEmpName] = useState('')
  const [datesModalOpen, setDatesModalOpen] = useState(false)

  // LocationShiftsModal
  const [locShiftsModalId, setLocShiftsModalId] = useState<number | null>(null)
  const [locShiftsModalName, setLocShiftsModalName] = useState('')
  const [locShiftsModalOpen, setLocShiftsModalOpen] = useState(false)

  // ConfirmModal (delete)
  const [confirm, setConfirm] = useState<{ message: string; onConfirm: () => void } | null>(null)

  // Context menu
  const [ctxMenu, setCtxMenu] = useState<CtxMenu | null>(null)

  // Period navigation (week/month)
  const [viewStart, setViewStart] = useState<Date | null>(null)
  // Structure data range (minimum/maximum shift date), used for arrow constraints
  const [dataRange, setDataRange] = useState<{ min: Date | null; max: Date | null }>({ min: null, max: null })
  // Race prevention: identifies the latest fetch and discards older responses
  const reqIdRef = useRef(0)

  // Navigation bounds (normalized period starts):
  // - periodMin = period of the first shift (backward navigation limited by history).
  // - periodMax = null: no forward limit, so the head nurse can prepare shifts for
  //   subsequent weeks/months (optionally populating them from a template).
  const { periodMin, periodMax } = useMemo(() => {
    const min = dataRange.min ? normalizeViewStart(dataRange.min, mode) : null
    return { periodMin: min, periodMax: null as Date | null }
  }, [dataRange, mode])

  // Solver
  const [solving, setSolving] = useState(false)
  const solverJobId = useRef<string | null>(null)
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [solveResult, setSolveResult] = useState<ScoreAnalysis | null>(null)
  const [savingAssignments, setSavingAssignments] = useState(false)

  const stopPolling = useCallback(() => {
    if (pollTimer.current) { clearTimeout(pollTimer.current); pollTimer.current = null }
  }, [])

  // WINDOWED loading: download only shifts in the visible period (week/month).
  // Load nothing without a structure or selected period (avoids unnecessary fetches).
  // Depends on viewStart and mode: navigating or changing granularity fetches again.
  // Race guard: if multiple responses arrive, only the latest request wins.
  const load = useCallback(() => {
    if (!structureId || !viewStart) { setSchedule(null); return }
    const { start, end } = windowFor(viewStart, mode)
    const myReq = ++reqIdRef.current
    setLoading(true)
    shiftsApi.schedule(structureId, toDbString(start), toDbString(end), true) // activeOnly: exclude disabled shifts
      .then(data => { if (myReq === reqIdRef.current) setSchedule(data) })
      .catch(() => { if (myReq === reqIdRef.current) toast.error(i18n.t('toast.errorLoadCalendar', 'Error loading the calendar.')) })
      .finally(() => { if (myReq === reqIdRef.current) setLoading(false) })
  }, [structureId, viewStart, mode])

  useEffect(() => { load() }, [load])

  // When the structure changes, ask the backend for the first shift date (lightweight endpoint)
  // and move to its month WITHOUT downloading all shifts. If the structure has no shifts,
  // fall back to the current month. This replaces the old viewStart calculation from loaded
  // data (which now includes only the month's shifts, not all shifts).
  useEffect(() => {
    setSchedule(null)
    setSolveResult(null)
    if (solving) {
      const jobId = solverJobId.current
      if (jobId) {
        shiftsApi.stopJob(jobId).catch(() => {})
        solverJobId.current = null
      }
      stopPolling()
      setSolving(false)
    }
    if (!structureId) { setViewStart(null); setDataRange({ min: null, max: null }); return }
    let cancelled = false
    shiftsApi.dateRange(structureId)
      .then(r => {
        if (cancelled) return
        const min = r.min ? new Date(r.min.replace(' ', 'T')) : null
        const max = r.max ? new Date(r.max.replace(' ', 'T')) : null
        setDataRange({ min, max })
        setViewStart(normalizeViewStart(min ?? new Date(), mode))
      })
      .catch(() => {
        if (cancelled) return
        setDataRange({ min: null, max: null })
        setViewStart(normalizeViewStart(new Date(), mode))
      })
    return () => { cancelled = true }
  }, [structureId]) // eslint-disable-line react-hooks/exhaustive-deps -- only on structure change; mode is handled by the effect below

  // When granularity changes (week↔month), re-normalize the current period and clamp it
  // to the range again. Depend only on [mode] to prevent fetch loops.
  useEffect(() => {
    setViewStart(prev => {
      if (!prev) return prev
      const next = clampPeriod(prev, mode, periodMin, periodMax)
      return prev.getTime() === next.getTime() ? prev : next
    })
  }, [mode]) // eslint-disable-line react-hooks/exhaustive-deps

  // Clean up polling on unmount
  useEffect(() => stopPolling, [stopPolling])

  async function handleSolve() {
    const solvedStructureId = structureId
    const solvedViewStart = viewStart
    setSolving(true)
    try {
      // The solver operates on the same window as the timeline (visible period).
      const win = solvedViewStart ? windowFor(solvedViewStart, mode) : null
      const jobId = win
        ? await shiftsApi.solve(solvedStructureId, toDbString(win.start), toDbString(win.end))
        : await shiftsApi.solve(solvedStructureId)
      solverJobId.current = jobId
      const poll = async () => {
        try {
          const result = await shiftsApi.getJob(jobId)
          // Structure changed while the solver was in flight: discard the result.
          if (solvedStructureId !== useAppStore.getState().currentStructure?.id) return
          // Stop may occur while the request is in flight: ignore the response
          // and do not restart polling for a job that is already closed.
          if (solverJobId.current !== jobId) return
          // Context shifts (outside the window, pinned for boundary constraints) must
          // neither appear in the timeline nor be saved by handleSaveAssignments.
          setSchedule({ ...result, shifts: result.shifts.filter(s => !s.context) })
          if (result.solverStatus === 'NOT_SOLVING') {
            stopPolling()
            setSolving(false)
            // Constraint analysis
            try {
              const analysis = await shiftsApi.analyze(jobId)
              setSolveResult(analysis)
            } catch {
              toast.success(t('toast.solveCompleted', 'Solve completed!'))
            }
            solverJobId.current = null
          } else {
            // Schedule the next check only after the current response:
            // no overlap if the backend takes more than two seconds.
            pollTimer.current = setTimeout(poll, 2000)
          }
        } catch {
          stopPolling()
          setSolving(false)
          solverJobId.current = null
          toast.error(t('toast.errorSolverPolling', 'Error while polling the solver.'))
        }
      }
      pollTimer.current = setTimeout(poll, 2000)
    } catch {
      setSolving(false)
      toast.error(t('toast.errorSolverStart', "Error starting the solver."))
    }
  }

  /** @brief Persists the displayed solution's assignments (current schedule) to the database. */
  async function handleSaveAssignments() {
    if (!schedule) return
    setSavingAssignments(true)
    const savedStructureId = structureId
    const savedViewStart = viewStart
    // Restrict server-side saving to the displayed window: exclude context shifts
    // from adjacent weeks even if the payload is malformed.
    const win = savedViewStart ? windowFor(savedViewStart, mode) : null
    try {
      await shiftsApi.saveAssignments(
        // `version` travels with each assignment and is the value read by the solver. If a shift
        // was modified in the meantime, the server rejects the entire save operation.
        schedule.shifts.map(s => ({
          shift_id: s.id,
          employee_id: s.employee?.id ?? null,
          version: s.version,
          employee_status_updated_at: s.employee?.statusUpdatedAt ?? '',
        })),
        savedStructureId,
        win ? toDbString(win.start) : undefined,
        win ? toDbString(win.end) : undefined,
      )
      toast.success(t('toast.assignmentsSaved', 'Assignments saved!'))
      setSolveResult(null)
      load()
    } catch (err) {
      if (errorCode(err) === 'SHIFTS_CHANGED') {
        toast.error(t('toast.shiftsChanged',
          'The shifts changed after the solve: nothing was saved. Reload and run the solve again.'))
        setSolveResult(null)
        load()
      } else if (errorCode(err) === 'EMPLOYEE_STATUS_CHANGED') {
        toast.error(t('toast.employeeStatusChanged',
          'An operator\'s status changed during solving: run the solver again.'))
        setSolveResult(null)
        load()
      } else if (errorCode(err) === 'EMPLOYEE_DOUBLE_BOOKED') {
        // 同一个人被安排进两个时间重叠的班次（最常见：同一订单的两个名额点了同一个人）。
        // 整份保存被拒绝，数据库没有写入任何一行。
        toast.error(t('toast.employeeDoubleBooked',
          'The same auditor was assigned to two overlapping shifts: change the person or the times, then save again.'))
      } else {
        toast.error(t('toast.errorSave', 'Error while saving.'))
      }
    } finally {
      setSavingAssignments(false)
    }
  }

  /** @brief Discards the solution: the view returns to the state persisted in the database. */
  function handleDiscardSolution() {
    setSolveResult(null)
    load()
    toast(t('toast.solutionDiscarded', 'Solution discarded.'))
  }

  async function handleStopSolve() {
    const jobId = solverJobId.current
    if (!jobId) return
    try {
      await shiftsApi.stopJob(jobId)
    } catch { /* ignore */ }
    stopPolling()
    setSolving(false)
    solverJobId.current = null
    load()
  }

  // Prepare data only for the active view: with large schedules, building both
  // representations simultaneously doubled work and allocations.
  // 依赖故意用 language 而不是 t：t 只是取文案，语言变了才需要重算；这样任何与语言无关的
  // 重渲染（搜索框输入、弹窗开关）都不会触发 500 行 group + 1300 条 item 的重建。
  const timelineData = useMemo(() => {
    if (!schedule) return { groups: [], items: [] }
    // 按地点 + 周视图用「每天一格」的方块表，不需要时间轴数据（省一次 30 行 + 237 item 的构建）。
    if (mode === 'week' && activeTab === 'location') return { groups: [], items: [] }
    if (activeTab !== 'employee') return buildLocationData(schedule, t)
    // 按审核员视图要把"不可用/期望"日期条裁到当前窗口：后端返回的是全年区间，
    // 10 月窗口下全量塞进去是 3100+ 条 item（真正有班次的只有 223 条），
    // 每次重绘和每次切换都要遍历它们。裁完只剩窗口内的那部分。
    const w = windowFor(viewStart ?? new Date(), mode)
    return buildEmployeeData(schedule, t, { start: w.start, end: new Date(w.end.getTime() - 1) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schedule, activeTab, language, viewStart, mode])

  // 搜索命中：按当前视图在显示名上做不区分大小写的包含匹配。
  // 刻意不参与 timelineData —— 命中只用于滚动定位，否则每敲一个字都要重建几百行 DOM。
  const searchHits = useMemo(() => {
    const term = searchTerm.trim().toLowerCase()
    if (!term || !schedule) return []
    return activeTab === 'employee'
      ? schedule.employees.filter(e => (e.fullName ?? '').toLowerCase().includes(term)).map(e => e.id)
      : schedule.locations.filter(l => (l.name ?? '').toLowerCase().includes(term)).map(l => l.id)
  }, [searchTerm, schedule, activeTab])

  // 命中变了就把那一行滚进视野并保持高亮（直到换词、换视图或清空）。
  // vis 会为每个 group 建 label DOM（不是虚拟列表），所以可以直接按 group id 查到对应行。
  const searchHitId = searchHits.length > 0 ? searchHits[Math.min(searchCursor, searchHits.length - 1)] : null
  // 周视图的「按地点」不走时间轴，而是每天一格的方块表（LocationDayGrid）。
  const dayGridView = mode === 'week' && activeTab === 'location'
  useEffect(() => {
    const root = timelineViewportRef.current
    if (!root) return
    // 时间轴的高亮是手动加的 class；天格子那边由 React 按 highlightLocationId 渲染，不用清。
    if (!dayGridView) {
      root.querySelectorAll('.vis-label.search-hit').forEach(el => el.classList.remove('search-hit'))
    }
    if (searchHitId == null) return
    const target = dayGridView
      ? root.querySelector<HTMLElement>(`[data-row-id="${searchHitId}"]`)
      : root.querySelector<HTMLElement>(`.vis-label [data-id="${searchHitId}"]`)?.closest<HTMLElement>('.vis-label') ?? null
    if (!target) return
    if (!dayGridView) target.classList.add('search-hit')
    // 只滚动视图自己的竖向视口，把命中行放到可视区中间；不用 scrollIntoView，
    // 否则命中行在屏幕外时整个页面也会被滚走（导航栏消失）。
    const scroller = findScrollableAncestor(target)
    if (scroller) {
      const targetRect = target.getBoundingClientRect()
      const boxRect = scroller.getBoundingClientRect()
      scroller.scrollTop += targetRect.top - boxRect.top - (boxRect.height - targetRect.height) / 2
    }
  }, [searchHitId, dayGridView])

  // 首次加载后把列表定位到第一行：vis 在数据分两批写入（groups 先、items 后）时会按
  // 中间态算一次滚动范围，加载完常常停在列表底部——打开就看不到 0001 那一批人。
  // 500 行渲染比 150ms 慢，所以等在 DOM 里看到行之后再做，并且只做一次。
  const initialScrollDone = useRef(false)
  useEffect(() => {
    if (initialScrollDone.current || !schedule) return
    let timer = 0
    let tries = 0
    const tick = () => {
      const root = timelineViewportRef.current
      const rows = root ? root.querySelectorAll('[data-row-id], .vis-label').length : 0
      if (rows === 0 && tries++ < 30) { timer = window.setTimeout(tick, 60); return }
      const scroller = findScrollableAncestor(root?.querySelector<HTMLElement>('[data-row-id], .vis-label') ?? null)
      if (scroller) scroller.scrollTop = 0
      initialScrollDone.current = true
    }
    timer = window.setTimeout(tick, 60)
    return () => window.clearTimeout(timer)
  }, [schedule])

  // Timeline window = period (week/month) of the selected date.
  const tlWindow = useMemo(() => {
    const w = windowFor(viewStart ?? new Date(), mode)
    return { start: w.start, end: new Date(w.end.getTime() - 1) } // exclusive → inclusive
  }, [viewStart, mode])

  const tlOptions = useMemo(() => ({
    ...TL_OPTIONS_BASE,
    start: tlWindow.start,
    end: tlWindow.end,
    // 按地点 + 月视图仍然走时间轴，必须保留堆叠：关掉的话同一城市时间重叠的班次会互相压住。
    // 周视图的按地点改用「每天一格 + 方块」视图（LocationDayGrid），不再走这里。
    stack: true,
  }), [tlWindow])

  const MONTHS = [
    t('month.january','January'), t('month.february','February'), t('month.march','March'),
    t('month.april','April'), t('month.may','May'), t('month.june','June'),
    t('month.july','July'), t('month.august','August'), t('month.september','September'),
    t('month.october','October'), t('month.november','November'), t('month.december','December'),
  ]
  const MONTHS_SHORT = [
    t('monthShort.jan','Jan'), t('monthShort.feb','Feb'), t('monthShort.mar','Mar'),
    t('monthShort.apr','Apr'), t('monthShort.may','May'), t('monthShort.jun','Jun'),
    t('monthShort.jul','Jul'), t('monthShort.aug','Aug'), t('monthShort.sep','Sep'),
    t('monthShort.oct','Oct'), t('monthShort.nov','Nov'), t('monthShort.dec','Dec'),
  ]

  // Period label: month ("December 2024") or week ("16–22 Dec 2024").
  const viewLabel = !viewStart ? '' : mode === 'month'
    ? `${MONTHS[viewStart.getMonth()]} ${viewStart.getFullYear()}`
    : (() => {
        const s = startOfWeekMonday(viewStart)
        const e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 6) // inclusive Sunday
        if (s.getMonth() === e.getMonth())
          return `${s.getDate()}–${e.getDate()} ${MONTHS_SHORT[s.getMonth()]} ${s.getFullYear()}`
        if (s.getFullYear() === e.getFullYear())
          return `${s.getDate()} ${MONTHS_SHORT[s.getMonth()]} – ${e.getDate()} ${MONTHS_SHORT[e.getMonth()]} ${e.getFullYear()}`
        return `${s.getDate()} ${MONTHS_SHORT[s.getMonth()]} ${s.getFullYear()} – ${e.getDate()} ${MONTHS_SHORT[e.getMonth()]} ${e.getFullYear()}`
      })()

  // Backward navigation is limited by the first shift; forward navigation is unlimited (preparing future shifts).
  const canPrev = !!viewStart && !!periodMin && viewStart.getTime() > periodMin.getTime()
  const canNext = !!viewStart && (!periodMax || viewStart.getTime() < periodMax.getTime())

  // Navigate by ±1 period (week or month), clamped to the range. Return prev if unchanged
  // (avoids unnecessary fetches when the normalized period is identical).
  // Note: ALWAYS return a new Date (no "if equal, return prev" short circuit).
  // This makes every click reattach the timeline window even when the period does not change,
  // e.g. after manual timeline pan/zoom caused it to drift out of sync with state.
  function navigate(delta: number) {
    setViewStart(prev => {
      const base = normalizeViewStart(prev ?? new Date(), mode)
      const raw = mode === 'week'
        ? new Date(base.getFullYear(), base.getMonth(), base.getDate() + 7 * delta)
        : new Date(base.getFullYear(), base.getMonth() + delta, 1)
      return clampPeriod(raw, mode, periodMin, periodMax)
    })
  }

  // Switching views no longer remounts the timeline (the `key` no longer carries activeTab),
  // so there is nothing to wait for: the data is swapped in place and painted immediately.
  // No overlay, no readiness callback, no timeout fallback — all of that existed only to cover
  // up a freshly created instance that initially measured itself as zero-sized.
  function changeTab(tab: 'employee' | 'location') {
    if (tab === activeTab) return
    setActiveTab(tab)
    setSearchTerm('')
    setSearchCursor(0)
    // 两个视图行数差一个数量级（500 人 vs 30 城）。沿用旧的垂直偏移会让短的那个列表
    // 停在末尾之外、看起来像空的。等 vis 按新数据排完版再归零（两帧之后）。
    window.requestAnimationFrame(() => {
      window.requestAnimationFrame(() => {
        const root = timelineViewportRef.current
        const anchor = root?.querySelector<HTMLElement>('[data-row-id], .vis-label') ?? null
        const scroller = findScrollableAncestor(anchor)
        if (scroller) scroller.scrollTop = 0
      })
    })
  }

  // "Today" button: always moves to the current period (clamped to the navigable range).
  function goToday() {
    setViewStart(clampPeriod(new Date(), mode, periodMin, periodMax))
  }

  // ─── Clicks on group icons (event delegation) ──────────────────────────────
  function handleGroupIconClick(e: React.MouseEvent<HTMLDivElement>) {
    const icon = (e.target as HTMLElement).closest<HTMLElement>('[data-action]')
    if (!icon) return
    e.stopPropagation()

    const action = icon.dataset.action!
    const id = parseInt(icon.dataset.id!)

    // Only the calendar icon appears in groups: operators and locations are edited/deleted
    // from their respective pages.
    switch (action) {
      case 'dates-emp': {
        const emp = schedule?.employees.find(e => e.id === id)
        setDatesModalEmpId(id)
        setDatesModalEmpName(emp?.fullName ?? '')
        setDatesModalOpen(true)
        break
      }
      case 'dates-loc': {
        openLocationDates(id)
        break
      }
    }
  }

  /** Opens the "shift dates for this location" modal. Shared by the timeline and the day grid. */
  function openLocationDates(locationId: number) {
    const loc = schedule?.locations.find(l => l.id === locationId)
    setLocShiftsModalId(locationId)
    setLocShiftsModalName(loc?.name ?? '')
    setLocShiftsModalOpen(true)
  }

  // ─── 深链接：直接定位到某个班次 ────────────────────────────────────────────
  // 工作台「未排班次 → 去处理」带 ?shiftId=xxx 过来。两段式处理，缺一不可：
  //   ① 先问后端"这条班次在哪一周"，把窗口挪过去；
  //   ② 等它出现在当前窗口的 schedule 里，再弹派工引导。
  // 曾经的写法是"在当前 schedule 里找不到就 return"，但初始窗口是「最早班次所在周」，
  // 目标班次通常不在那一周 → 找不到就退出，而 schedule 也不会再变，effect 永远等不到重试。
  // 实测症状：跳到 11/3 的班次，时间轴仍停在 10/26 那一周，也不弹窗。
  const pendingShiftId = useRef<number | null>(null)
  const lastShiftParam = useRef<string | null>(null)

  useEffect(() => {
    const raw = searchParams.get('shiftId')
    if (!raw || !structureId || raw === lastShiftParam.current) return
    const shiftId = Number(raw)
    if (!Number.isFinite(shiftId) || shiftId <= 0) return
    lastShiftParam.current = raw
    pendingShiftId.current = shiftId
    shiftsApi.get(shiftId, structureId)
      .then(({ shift }) => {
        const start = new Date(shift.start)
        if (!Number.isNaN(start.getTime())) setViewStart(normalizeViewStart(start, mode))
      })
      .catch(() => { pendingShiftId.current = null })
  }, [searchParams, structureId, mode])

  // 目标班次进入窗口后再弹引导：load() 完成会换掉 schedule 对象，这里自然重试。
  useEffect(() => {
    const target = pendingShiftId.current
    if (target == null || !schedule) return
    if (!schedule.shifts.some(row => row.id === target)) return
    pendingShiftId.current = null
    void openShiftOrGuide(target)
  }, [schedule])

  // ─── Click on a shift item ─────────────────────────────────────────────────
  function handleItemClick(itemId: string | number) {
    if (!schedule) return
    const shiftId = shiftIdFromItemId(itemId, schedule)
    if (shiftId == null) return
    void openShiftOrGuide(shiftId)
  }

  /** 打开「编辑班次」（时间/地点/资格）。注意这里没有审核员字段。 */
  function openShiftEditor(shiftId: number) {
    setAssignGuide(null)
    setEditShiftId(shiftId)
    setPrefillStart(undefined)
    setPrefillLocationId(undefined)
    setShiftModalOpen(true)
  }

  /**
   * 已排人的班次 → 直接编辑；未排人的 → 先看订单能不能派工。
   * 派工本身只能在「工作台」做（候选名单要按资格/冲突/转场筛），所以给的是跳转入口。
   */
  async function openShiftOrGuide(shiftId: number) {
    if (!schedule) return
    const shift = schedule.shifts.find(row => row.id === shiftId)
    if (!shift || shift.employee) { openShiftEditor(shiftId); return }
    setAssignGuide({ shift, status: null, loading: true })
    try {
      const found = await workflowApi.lookupOrder(structureId!, shift.auditOrderCode ?? '')
      setAssignGuide({ shift, status: found.status, loading: false })
    } catch {
      setAssignGuide({ shift, status: 'UNKNOWN', loading: false })
    }
  }

  /** 订单状态的中文名（只用于这句话里的人话表达）。 */
  function orderStatusText(status: string): string {
    return tx(ORDER_STATUS_TEXT[status] ?? status)
  }

  /** 班次地点名（地点表里查，查不到退回脱敏描述）。 */
  function shiftLocationName(shift: Shift): string {
    return schedule?.locations.find(row => row.id === shift.location_id)?.name ?? shift.location_desc ?? ''
  }

  // ─── Click on an empty area ────────────────────────────────────────────────
  function handleCanvasClick(time: Date, groupId: string | number | null) {
    setEditShiftId(null)
    setPrefillStart(time.toISOString())
    setPrefillLocationId(activeTab === 'location' && groupId != null ? Number(groupId) : undefined)
    setShiftModalOpen(true)
  }

  // ─── Context menu ──────────────────────────────────────────────────────────
  /** Right-click menu for a shift. Shared by the timeline (via item id) and the day grid (direct). */
  function openShiftContextMenu(shiftId: number, x: number, y: number) {
    setCtxMenu({
      x, y,
      actions: [
        {
          label: t('ctx.editShift', 'Edit shift'),
          icon: 'fas fa-pencil-alt',
          onClick: () => { setEditShiftId(shiftId); setPrefillStart(undefined); setPrefillLocationId(undefined); setShiftModalOpen(true) },
        },
        {
          label: t('ctx.deleteShift', 'Delete shift'),
          icon: 'fas fa-trash',
          variant: 'danger',
          onClick: () => setConfirm({
            message: t('confirm.deleteShiftMessage', 'Delete this shift?'),
            onConfirm: async () => {
              try { await shiftsApi.delete(shiftId, structureId); load() }
              catch { toast.error(t('toast.errorDelete', "Error while deleting.")) }
            },
          }),
        },
      ],
    })
  }

  function handleContextMenu(
    itemId: string | number | null,
    groupId: string | number | null,
    time: Date,
    x: number, y: number,
  ) {
    if (!schedule) return
    if (itemId != null) {
      const shiftId = shiftIdFromItemId(itemId, schedule)
      if (shiftId == null) return
      openShiftContextMenu(shiftId, x, y)
    } else {
      setCtxMenu({
        x, y,
        actions: [{
          label: t('ctx.addShift', 'Add shift'),
          icon: 'fas fa-plus',
          variant: 'primary',
          onClick: () => {
            setEditShiftId(null)
            setPrefillStart(time.toISOString())
            setPrefillLocationId(activeTab === 'location' && groupId != null ? Number(groupId) : undefined)
            setShiftModalOpen(true)
          },
        }],
      })
    }
  }

  // ─── Legenda ───────────────────────────────────────────────────────────────
  // 图例色值必须与画布一致：班次是实色块，日期申报是 18~22% 透明的背景带。
  // 之前图例用纯色 #00cc00/#FFA500/#FF4444，和画布上的浅色带对不上，用户根本联系不起来。
  const LEGEND = [
    { color: '#aaaaaa', label: t('msg.unassigned', 'Unassigned'), faint: false },
    { color: '#729fcf', label: t('msg.assigned', 'Assigned'), faint: false },
    { color: AVAIL_COLORS.desired, label: t('dateType.desired', 'Desired'), faint: true },
    { color: AVAIL_COLORS.undesired, label: t('dateType.undesired', 'Undesired'), faint: true },
    { color: AVAIL_COLORS.unavailable, label: t('dateType.unavailable', 'Unavailable'), faint: true },
  ]

  return (
    <div>
      {/* Header */}
      <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
        <div className="d-flex align-items-center gap-3">
          <h5 className="mb-0">{t('nav.shiftManagement', 'Shift Management')}</h5>
        </div>
        {/* 图例与说明分两行：英文文案比中文长，挤在同一行会把「Faint band = …」顶出屏幕。 */}
        <div className="d-flex flex-column align-items-end gap-1" style={{ minWidth: 0 }}>
          <div className="d-flex gap-3 align-items-center flex-wrap justify-content-end">
            {LEGEND.map(({ color, label, faint }) => (
              <span key={label} className="d-flex align-items-center gap-1 small" title={faint ? t('msg.legendFaintHint', 'Background band = an auditor\'s declared dates, not a shift') : undefined}>
                <span style={{ width: 12, height: 12, borderRadius: 2, background: color, display: 'inline-block', flexShrink: 0, border: faint ? '1px solid rgba(0,0,0,0.18)' : 'none' }} />
                {label}
              </span>
            ))}
          </div>
          <span className="text-muted small text-end" style={{ maxWidth: 520, whiteSpace: 'normal' }}>{t('msg.legendFaintHint', 'Background band = an auditor\'s declared dates, not a shift')}</span>
        </div>
      </div>

      {/* Period navigation (week / month) */}
      <div className="d-flex align-items-center justify-content-center mb-2 gap-1">
        <Button
          variant="outline-primary"
          size="sm"
          className="d-inline-flex align-items-center justify-content-center"
          style={{ width: 36, height: 32 }}
          onClick={() => navigate(-1)}
          disabled={!canPrev}
          title={t('tooltip.prevPeriod', 'Previous period')}
          aria-label={t('tooltip.prevPeriod', 'Previous period')}
        >
          <FontAwesomeIcon icon={faChevronLeft} />
        </Button>
        <span className="fw-semibold mx-2" style={{ minWidth: 210, textAlign: 'center', fontSize: '1rem' }}>
          {viewLabel}
        </span>
        <Button
          variant="outline-primary"
          size="sm"
          className="d-inline-flex align-items-center justify-content-center"
          style={{ width: 36, height: 32 }}
          onClick={() => navigate(1)}
          disabled={!canNext}
          title={t('tooltip.nextPeriod', 'Next period')}
          aria-label={t('tooltip.nextPeriod', 'Next period')}
        >
          <FontAwesomeIcon icon={faChevronRight} />
        </Button>
        <Button
          variant="outline-primary"
          size="sm"
          className="ms-3"
          onClick={goToday}
          title={t('tooltip.today', 'Go to today')}
        >
          <FontAwesomeIcon icon={faLocationCrosshairs} className="me-1" />{t('btn.today', 'Today')}
        </Button>
        {solving ? (
          <Button variant="danger" size="sm" className="ms-3" onClick={handleStopSolve}>
            <FontAwesomeIcon icon={faStop} className="me-1" />Stop
          </Button>
        ) : (
          <Button variant="success" size="sm" className="ms-3" onClick={handleSolve} disabled={loading || !schedule}>
            <FontAwesomeIcon icon={faPlay} className="me-1" />Solve
          </Button>
        )}
        <Button variant="outline-secondary" size="sm" className="ms-1" onClick={load} disabled={loading || solving}>
          <FontAwesomeIcon icon={faRotate} spin={loading} />
        </Button>
      </div>

      {/* Tabs */}
      <div className="shift-view-tabs" role="tablist">
        {([['employee', t('tab.byEmployee', 'By operator'), faUserGroup], ['location', t('tab.byLocation', 'By location'), faLocationDot]] as const).map(([key, label, icon]) => {
          const active = activeTab === key
          return (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={active}
              className={`shift-view-tab${active ? ' active' : ''}`}
              onClick={() => changeTab(key)}
            >
              <FontAwesomeIcon icon={icon} className="me-2" />
              {label}
            </button>
          )
        })}
        {/* 行检索：只定位不高亮过滤，列表顺序保持原样，便于对照上下文。 */}
        <div className="shift-view-search">
          <FontAwesomeIcon icon={faMagnifyingGlass} className="shift-view-search__icon" />
          <input
            type="search"
            value={searchTerm}
            onChange={e => { setSearchTerm(e.target.value); setSearchCursor(0) }}
            onKeyDown={e => {
              if (e.key !== 'Enter') return
              e.preventDefault()
              if (searchHits.length > 1) setSearchCursor(c => (c + 1) % searchHits.length)
            }}
            placeholder={activeTab === 'employee'
              ? t('search.operator', 'Search operator…')
              : t('search.location', 'Search city…')}
            aria-label={activeTab === 'employee'
              ? t('search.operator', 'Search operator…')
              : t('search.location', 'Search city…')}
          />
          {searchTerm.trim() !== '' && (
            searchHits.length === 0
              ? <span className="shift-view-search__count text-danger">{t('search.none', 'No match')}</span>
              : <span className="shift-view-search__count">
                  {Math.min(searchCursor + 1, searchHits.length)}/{searchHits.length}
                </span>
          )}
          {searchHits.length > 1 && (
            <>
              <button
                type="button"
                className="shift-view-search__step"
                title={t('search.prev', 'Previous match')}
                onClick={() => setSearchCursor(c => (c - 1 + searchHits.length) % searchHits.length)}
              >↑</button>
              <button
                type="button"
                className="shift-view-search__step"
                title={t('search.next', 'Next match')}
                onClick={() => setSearchCursor(c => (c + 1) % searchHits.length)}
              >↓</button>
            </>
          )}
        </div>
      </div>

      <div className="timeline-stage border border-top-0" ref={timelineViewportRef} style={{ minHeight: 400 }}>
        {loading && !schedule ? (
          <div className="text-center py-5"><Spinner /></div>
        ) : !schedule ? (
          <div className="empty-schedule">
            <img src={emptySchedule} alt="" className="empty-schedule__art" />
            <div className="empty-schedule__text">
              <h5>{t('empty.title', 'No shift planned')}</h5>
              <p className="mb-1">{t('empty.body', 'This view fills up as soon as you create the first shift.')}</p>
              <p className="text-muted mb-0">{t('empty.hint', 'Choose a facility and add a shift to get started.')}</p>
            </div>
          </div>
        ) : (dayGridView ? schedule.locations.length === 0 : timelineData.groups.length === 0) ? (
          <div className="empty-schedule">
            <img src={emptySchedule} alt="" className="empty-schedule__art" />
            <div className="empty-schedule__text">
              <h5>{t('empty.title', 'No shift planned')}</h5>
              <p className="mb-1">{t('empty.body', 'This view fills up as soon as you create the first shift.')}</p>
              <p className="text-muted mb-0">{t('empty.hint', 'Choose a facility and add a shift to get started.')}</p>
            </div>
          </div>
        ) : dayGridView ? (
          /* 按地点 · 周视图：每天一列，班次画成小方块。时间轴按小时会把班次拉成长条，
             同一城市时间重叠的班次就会叠在一起；方块与时长无关，重叠不再互相遮挡。
             月视图仍用下面的时间轴（30 列放不下方块），堆叠已打开。 */
          <LocationDayGrid
            locations={schedule.locations}
            shifts={schedule.shifts}
            windowStart={tlWindow.start}
            days={7}
            t={t}
            highlightLocationId={searchHitId}
            onShiftClick={shiftId => void openShiftOrGuide(shiftId)}
            onShiftContextMenu={openShiftContextMenu}
            onCellClick={(locationId, day) => handleCanvasClick(day, locationId)}
            onCellContextMenu={(locationId, day, x, y) => handleContextMenu(null, locationId, day, x, y)}
            onManageDates={openLocationDates}
          />
        ) : (
          /* Wrapper with event delegation for group icons.
             Fixed height + .timeline-viewport (CSS makes the vis root fill it) →
             vis-timeline's verticalScroll takes over: the wheel scrolls groups inside
             this viewport while the time axis stays pinned on top. */
          <div
            className="timeline-viewport"
            onClick={handleGroupIconClick}
            style={{ height: 'calc(100vh - 250px)', minHeight: 420 }}
          >
            <VisTimeline
              /* 只在语言变化时重建（moment locale 绑在构造期）。视图切换不再重建：
                 重建要等新实例完成测量，那正是"切过去空白"的来源，且 500 行骨架重建很贵。 */
              key={language}
              locale={language}
              groups={timelineData.groups}
              items={timelineData.items}
              options={tlOptions}
              visible
              onItemClick={handleItemClick}
              onCanvasClick={handleCanvasClick}
              onContextMenu={handleContextMenu}
            />
          </div>
        )}
      </div>

      {/* Modals */}
      {/* 未分配班次的派工引导：说明为什么这里选不了人，以及下一步去哪。 */}
      <Modal show={assignGuide !== null} onHide={() => setAssignGuide(null)} centered>
        <Modal.Header closeButton><Modal.Title>{tx('这个名额还没排人')}</Modal.Title></Modal.Header>
        <Modal.Body>{assignGuide && <>
          <ul className="mb-3 small">
            <li>{tx('订单：{0}', [assignGuide.shift.auditOrderCode ?? '—'])}</li>
            <li>{tx('阶段：{0}', [assignGuide.shift.auditStage ?? '—'])}</li>
            <li>{tx('时间：{0}～ {1}', [assignGuide.shift.start.replace('T', ' ').slice(0, 16), assignGuide.shift.end.replace('T', ' ').slice(0, 16)])}</li>
            <li>{tx('地点：{0}', [shiftLocationName(assignGuide.shift) || '—'])}</li>
          </ul>
          {assignGuide.loading
            ? <div className="text-muted small">{tx('正在检查订单状态…')}</div>
            : assignGuide.status === 'APPROVED'
              ? <div className="alert alert-info py-2 mb-0 small">{tx('订单已批准，可以去候选名单选人：候选人已按资格、时间冲突、转场缓冲筛过，并按评分排序。')}</div>
              : <div className="alert alert-warning py-2 mb-0 small">
                  {tx('订单当前状态是「{0}」，还没批准，所以查不了候选、也派不了人。先去订单提交审批。', [orderStatusText(assignGuide.status ?? '')])}
                </div>}
          <div className="text-muted small mt-2">
            {tx('这里的「编辑班次」只能改时间、地点和资格要求——审核员要在工作台的候选名单里选。')}
          </div>
        </>}</Modal.Body>
        <Modal.Footer>
          {assignGuide && assignGuide.status === 'APPROVED' && <Button variant="primary" onClick={() => goToRoute(`/workflow?tab=candidates&order=${encodeURIComponent(assignGuide.shift.auditOrderCode ?? '')}`)}>{tx('去选人')}</Button>}
          {assignGuide && assignGuide.status !== 'APPROVED' && !assignGuide.loading && <Button variant="primary" onClick={() => goToRoute(`/workflow?tab=orders&order=${encodeURIComponent(assignGuide.shift.auditOrderCode ?? '')}`)}>{tx('去订单处理')}</Button>}
          <Button variant="outline-secondary" onClick={() => assignGuide && openShiftEditor(assignGuide.shift.id)}>{tx('只改班次信息')}</Button>
          <Button variant="secondary" onClick={() => setAssignGuide(null)}>{tx('关闭')}</Button>
        </Modal.Footer>
      </Modal>

      <ShiftModal
        show={shiftModalOpen}
        shiftId={editShiftId}
        prefillStart={prefillStart}
        prefillLocationId={prefillLocationId}
        structureId={structureId}
        onClose={() => setShiftModalOpen(false)}
        onSaved={load}
        onDeleted={load}
      />

      <LocationShiftsModal
        show={locShiftsModalOpen}
        locationId={locShiftsModalId}
        locationName={locShiftsModalName}
        shifts={schedule?.shifts ?? []}
        structureId={structureId}
        onClose={() => setLocShiftsModalOpen(false)}
        onChanged={() => { load() }}
      />

      <EmployeeDatesModal
        show={datesModalOpen}
        employeeId={datesModalEmpId}
        employeeName={datesModalEmpName}
        structureId={structureId}
        onClose={() => { setDatesModalOpen(false); load() }}
      />

      <ConfirmModal
        show={confirm !== null}
        message={confirm?.message ?? ''}
        onConfirm={() => { confirm?.onConfirm(); setConfirm(null) }}
        onClose={() => setConfirm(null)}
      />

      {ctxMenu && (
        <ContextMenu
          x={ctxMenu.x}
          y={ctxMenu.y}
          actions={ctxMenu.actions}
          onClose={() => setCtxMenu(null)}
        />
      )}

      <SolveResultModal
        show={solveResult !== null}
        analysis={solveResult}
        onSave={handleSaveAssignments}
        onDiscard={handleDiscardSolution}
        saving={savingAssignments}
      />

      {/* Solver-in-progress overlay */}
      {solving && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 9999,
          background: 'rgba(0,0,0,0.55)',
          display: 'flex', flexDirection: 'column',
          alignItems: 'center', justifyContent: 'center',
          gap: 20,
        }}>
          <Spinner animation="border" variant="light" style={{ width: 64, height: 64, borderWidth: 6 }} />
          <div style={{ color: '#fff', fontSize: '1.2rem', fontWeight: 500 }}>
            {t('msg.solvingInProgress', 'Solving in progress…')}
          </div>
          <Button variant="danger" onClick={handleStopSolve}>
            <FontAwesomeIcon icon={faStop} className="me-2" />{t('btn.interrupt', 'Stop')}
          </Button>
        </div>
      )}
    </div>
  )
}
