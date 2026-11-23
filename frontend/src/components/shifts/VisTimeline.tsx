/**
 * @file VisTimeline.tsx
 * @brief Imperative React wrapper around the vis-timeline library.
 *
 * @details
 * ## Imperative architecture
 * vis-timeline is a DOM-imperative library: it creates and manages the DOM directly.
 * It is not compatible with React's declarative rendering.
 * This component uses `useRef` to keep the Timeline instance and `DataSet`
 * as mutable references outside the React rendering cycle.
 *
 * ## Data updates
 * Items and groups are updated through `DataSet.clear()` + `DataSet.add()`
 * (not with `update()`, because full replacement is simpler and safer).
 *
 * ## Time window
 * Changes to `options.start`/`options.end` update the visible window with
 * `timeline.setWindow()` without animation to avoid lag.
 *
 * ## Exposed events
 * - `onItemClick` — click on a shift (item)
 * - `onCanvasClick` — click on an empty area (background) — used to add shifts
 * - `onContextMenu` — right click on an item or background — opens ContextMenu
 */

import { useRef, useEffect, useState } from 'react'
// IMPORTANT: use the "peer" build (not "esnext"): esnext EMBEDS a private English-only copy
// of moment, so locales registered on our moment instance have no effect on the axis.
// The peer build uses external moment (ours, with it/fr/es/de locales registered below).
import { Timeline } from 'vis-timeline/peer'
import { DataSet } from 'vis-data/peer'
import type { TimelineOptions, DataItem, DataGroup } from 'vis-timeline/peer'
import type { MomentInput } from 'moment'
import 'vis-timeline/styles/vis-timeline-graph2d.min.css'
import './VisTimeline.css'
import moment from 'moment'
// Register locales for the time axis (en is built into moment).
// IMPORTANT: use ESM locales from dist/locale, NOT 'moment/locale/*' (CJS):
// Vite resolves 'moment' to the ESM build (jsnext:main → dist/moment.js), while CJS locales
// they would register on the unused CJS copy → the axis would always be in English.
import 'moment/dist/locale/it'
import 'moment/dist/locale/fr'
import 'moment/dist/locale/es'
import 'moment/dist/locale/de'
// Simplified Chinese: the UI code is 'zh', the moment locale is 'zh-cn'.
import 'moment/dist/locale/zh-cn'

/** i18n codes that differ from the moment locale code. */
const LOCALE_ALIASES: Record<string, string> = { zh: 'zh-cn' }
const SUPPORTED_LOCALES = new Set(['it', 'en', 'fr', 'es', 'de', 'zh-cn'])

function supportedLocale(locale?: string): string {
  const resolved = locale ? (LOCALE_ALIASES[locale] ?? locale) : ''
  return SUPPORTED_LOCALES.has(resolved) ? resolved : 'en'
}

function localizedMoment(locale: string) {
  return (date?: MomentInput) => moment(date).locale(locale)
}

// Timeline constructors accept exactly DataItem/DataGroup. Keep
// the public aliases already used by pages to avoid unrelated changes.
export type TimelineItem = DataItem
export type TimelineGroup = DataGroup

/**
 * @brief Properties of the vis-timeline click/contextmenu event.
 */
interface ClickProps {
  item: string | number | null
  group: string | number | null
  time: Date
  event: Event
  what: string
}

/**
 * @brief VisTimeline component props.
 */
interface Props {
  items: TimelineItem[]
  groups: TimelineGroup[]
  options: TimelineOptions
  /** @brief Callback when an item (shift) is clicked. */
  onItemClick?: (itemId: string | number, groupId: string | number | null) => void
  /** @brief Callback on empty-area click. `groupId` = ID of the clicked group (location/employee). */
  onCanvasClick?: (time: Date, groupId: string | number | null) => void
  /** @brief Right-click callback. Viewport x/y coordinates used to position the ContextMenu. */
  onContextMenu?: (itemId: string | number | null, groupId: string | number | null, time: Date, x: number, y: number) => void
  /** @brief Locale for formatting the time axis (e.g. "it", "en"). Default "it". */
  locale?: string
  /** True when the timeline container is visible and can be redrawn. */
  visible?: boolean
  /** Called after redrawing a timeline that has just become visible. */
  onVisibleReady?: () => void
}

export default function VisTimeline({ items, groups, options, onItemClick, onCanvasClick, onContextMenu, locale, visible = true, onVisibleReady }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const timelineRef = useRef<Timeline | null>(null)
  const [itemsDs] = useState(() => new DataSet<DataItem>())
  const [groupsDs] = useState(() => new DataSet<DataGroup>())
  const callbacksRef = useRef({ onItemClick, onCanvasClick, onContextMenu })
  const initialOptionsRef = useRef(options)
  const initialLocaleRef = useRef(supportedLocale(locale))

  // vis-timeline handlers are registered only once. Keep React callbacks up to date,
  // however, to avoid stale closures over schedule/tab.
  useEffect(() => {
    callbacksRef.current = { onItemClick, onCanvasClick, onContextMenu }
  }, [onItemClick, onCanvasClick, onContextMenu])

  // Create the timeline only once on mount
  useEffect(() => {
    if (!containerRef.current) return

    itemsDs.clear()
    groupsDs.clear()

    // Localize the time axis: vis-timeline formats labels with
    // this.moment(date).format(...), where this.moment comes from the `moment` option.
    // Pass a moment instance with the current locale (locales are registered above via imports).
    const lc = initialLocaleRef.current
    moment.locale(lc) // also set globally for safety in any code paths that use moment directly
    const opts = {
      ...initialOptionsRef.current,
      moment: localizedMoment(lc),
      locale: lc,
    }
    const tl = new Timeline(containerRef.current, itemsDs, groupsDs, opts)
    timelineRef.current = tl

    tl.on('click', (props: ClickProps) => {
      if (props.what === 'item' && props.item != null) {
        callbacksRef.current.onItemClick?.(props.item, props.group)
      } else if (props.what === 'background') {
        // Only clicks on an empty timeline area, not on a group label
        callbacksRef.current.onCanvasClick?.(props.time, props.group)
      }
    })

    tl.on('contextmenu', (props: ClickProps & { pageX?: number; pageY?: number }) => {
      props.event.preventDefault()
      const pointerEvent = props.event as MouseEvent
      const x = pointerEvent.clientX
      const y = pointerEvent.clientY
      callbacksRef.current.onContextMenu?.(props.item ?? null, props.group ?? null, props.time, x, y)
    })

    return () => {
      tl.destroy()
      timelineRef.current = null
    }
  }, [groupsDs, itemsDs])

  // Apply all changed options without recreating the timeline or DataSets.
  useEffect(() => {
    const lc = supportedLocale(locale)
    moment.locale(lc)
    timelineRef.current?.setOptions({ ...options, locale: lc, moment: localizedMoment(lc) })
    timelineRef.current?.redraw()
  }, [locale, options])

  // Replace groups and items in ONE pass, then redraw.
  //
  // This used to be two separate effects and the caller remounted the whole timeline on every
  // view switch (`key={activeTab}`). That made each switch depend on a freshly constructed
  // instance measuring a container correctly before its first paint — and when it did not, the
  // new timeline painted nothing and only a later setWindow (changing the period) repaired it.
  // Reusing the instance removes that whole failure mode, and it is much cheaper: switching
  // views no longer tears down and rebuilds the DOM skeleton of 500 operator rows.
  //
  // Order matters: groups first, because an item is positioned inside its group.
  useEffect(() => {
    groupsDs.clear()
    if (groups.length > 0) groupsDs.add(groups)
    itemsDs.clear()
    if (items.length > 0) itemsDs.add(items)
    timelineRef.current?.redraw()
  }, [groups, items, groupsDs, itemsDs])

  // Update the window when options.start/end change
  useEffect(() => {
    if (timelineRef.current && options.start && options.end) {
      timelineRef.current.setWindow(options.start, options.end, { animation: false })
    }
  }, [options.start, options.end])

  // A timeline initialized inside display:none does not know its width, so it is redrawn once
  // the browser has applied layout.
  //
  // IMPORTANT: the READY signal must NOT ride on requestAnimationFrame. In a background tab, an
  // off-screen container or a throttled frame budget the callback is deferred indefinitely; the
  // caller (ShiftsPage) then keeps its tab-switch overlay up, and the freshly built timeline
  // looks completely blank until some unrelated change (switching the period) re-runs this
  // effect. Timers are not deferred the same way, and the notification is also sent when the
  // instance is not ready yet — a missed notification is far worse than a slightly early one.
  useEffect(() => {
    if (!visible) return
    const redraw = () => {
      const tl = timelineRef.current
      if (!tl) return
      tl.redraw()
      if (options.start && options.end) tl.setWindow(options.start, options.end, { animation: false })
    }
    const frame = requestAnimationFrame(redraw) // best effort: paint as early as possible
    const settle = window.setTimeout(() => {
      redraw()
      onVisibleReady?.()
    }, 60)
    return () => {
      cancelAnimationFrame(frame)
      window.clearTimeout(settle)
    }
  }, [visible, options.start, options.end, onVisibleReady])

  return <div ref={containerRef} />
}
