/**
 * @file MapPage.tsx
 * @brief 审核员分布地图：中国地图总览 → 城市 → 审核员 → 个人信息。
 *
 * @details
 * - 点位是**城市**（`locations` 中活跃且有 GCJ-02 坐标的行），不是个人位置，
 *   个人明细只在选中城市后于侧栏展示。
 * - 在岗状态由后端判定（当前时刻有班次覆盖），前端不自行推导。
 * - 底图用高德 JS API 2.0；密钥未配置时显示引导面板而不是空白地图。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, ButtonGroup } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faArrowUpRightFromSquare, faRotateRight } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'

import {
  MAP_GROUPS,
  MAP_SCOPES,
  mapOverviewApi,
  type MapCity,
  type MapGroup,
  type MapOverview,
  type MapPerson,
  type MapScope,
} from '../api/mapOverview'
import { AMAP_APPROVAL_NO, AMAP_NOT_CONFIGURED, loadAmap } from '../map/amap'
import { useAppStore } from '../store/useAppStore'
import './MapPage.css'

/** 未归属城市的伪城市 id（侧栏里可点击查看）。 */
const UNASSIGNED = -1

/** @brief 从 URL 读口径：非法值回落 now，与后端对未知 scope 的处理保持一致。 */
function parseScope(raw: string | null): MapScope {
  return MAP_SCOPES.find((value) => value === raw) ?? 'now'
}

/** @brief 从 URL 读打点口径：非法值回落 home，与后端对未知 group 的处理保持一致。 */
function parseGroup(raw: string | null): MapGroup {
  return MAP_GROUPS.find((value) => value === raw) ?? 'home'
}

/** @brief 从 URL 读城市 id：-1 表示「未归属」，其余非正整数视为未选中。 */
function parseCity(raw: string | null): number | null {
  if (raw === null || raw === '') {
    return null
  }
  const id = Number(raw)
  return Number.isInteger(id) && (id === UNASSIGNED || id > 0) ? id : null
}

/**
 * 每个口径对应的一组文案。
 * 「在岗」只适用于 now：选「今日有班」时，一个下午才开工的人并不"在岗"，
 * 文案必须跟着口径变，否则用户会读到自相矛盾的信息。
 */
const SCOPE_TEXT: Record<MapScope, {
  tab: string; on: string; off: string; total: string; shift: string; head: string
}> = {
  now: {
    tab: 'map.scopeNow', on: 'map.onDuty', off: 'map.idle', total: 'map.onDutyNow',
    shift: 'map.shiftNow', head: 'map.headNow',
  },
  today: {
    tab: 'map.scopeToday', on: 'map.todayOn', off: 'map.todayOff', total: 'map.todayTotal',
    shift: 'map.shiftToday', head: 'map.headToday',
  },
  next7: {
    tab: 'map.scopeNext7', on: 'map.weekOn', off: 'map.weekOff', total: 'map.weekTotal',
    shift: 'map.shiftNext', head: 'map.headNext',
  },
}

type MapState = 'loading' | 'ready' | 'missing' | 'error'

/** 打点口径按钮文案：两者读的是同一个数字，但含义完全不同，必须写清楚。 */
const GROUP_TEXT: Record<MapGroup, { tab: string; fallback: string }> = {
  home: { tab: 'map.groupHome', fallback: 'By home base' },
  onsite: { tab: 'map.groupOnsite', fallback: 'By work site' },
}

/** 口径按钮的英文兜底：后端文案缺失时不要退化成 now/today/next7 这种原始取值。 */
const SCOPE_FALLBACK: Record<MapScope, string> = {
  now: 'Now',
  today: 'Today',
  next7: 'Next 7 days',
}

/** @brief 详情里的时间只保留到分钟：秒对"当前在岗"没有意义，却会把一行撑爆。 */
function minute(text: string | null): string {
  return (text ?? '').slice(0, 16)
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export default function MapPage() {
  const { t } = useTranslation()
  const structureId = useAppStore((s) => s.currentStructure?.id ?? 0)
  const [searchParams, setSearchParams] = useSearchParams()

  const [data, setData] = useState<MapOverview | null>(null)
  // 初始值取自 URL：/map?scope=next7&city=12 打开即是同一视图，可分享、刷新不丢。
  const [scope, setScope] = useState<MapScope>(() => parseScope(searchParams.get('scope')))
  // 打点口径：home = 按常驻地（圈内数字是常驻人数）/ onsite = 按班次所在地（圈内数字是现场人数）
  const [group, setGroup] = useState<MapGroup>(() => parseGroup(searchParams.get('group')))
  const text = SCOPE_TEXT[scope]
  const [loading, setLoading] = useState(true)
  const [selectedCity, setSelectedCity] = useState<number | null>(() => parseCity(searchParams.get('city')))
  const [selectedPerson, setSelectedPerson] = useState<MapPerson | null>(null)
  const [mapState, setMapState] = useState<MapState>('loading')

  const containerRef = useRef<HTMLDivElement | null>(null)
  // 高德 SDK 的对象（Map / Marker）没有类型声明，用 any 持有；调用点都在本文件内。
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const mapRef = useRef<any>(null)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const markersRef = useRef<any[]>([])
  const lastStructure = useRef(0)
  /** 底图是否已获准初始化：没有部门时不加载 SDK，避免出现一张无从解释的空地图。 */
  const [mapBooted, setMapBooted] = useState(false)
  /** 结构列表由导航栏异步加载：留一小段宽限期，避免自动选部门时闪一下「未选择」。 */
  const [structureWaitExpired, setStructureWaitExpired] = useState(false)

  const load = useCallback(async () => {
    if (!structureId) {
      // 部门尚未选定（或结构列表加载失败）：结束加载态并交给侧栏提示，
      // 否则页面会永久停在「加载中」，用户看不出是缺数据还是在等。
      setData(null)
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      setData(await mapOverviewApi.get(structureId, scope, group))
    } catch {
      toast.error(t('map.loadError', 'Failed to load map data.'))
    } finally {
      setLoading(false)
    }
  }, [structureId, scope, group, t])

  useEffect(() => {
    void load()
  }, [load])

  // 口径与选中城市写回 URL（replace，不污染浏览器历史）。
  useEffect(() => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (scope === 'now') next.delete('scope')
      else next.set('scope', scope)
      if (group === 'home') next.delete('group')
      else next.set('group', group)
      if (selectedCity === null) next.delete('city')
      else next.set('city', String(selectedCity))
      return next
    }, { replace: true })
  }, [scope, group, selectedCity, setSearchParams])

  // 切换部门时清空选中：旧城市 id 在新部门里通常不存在，留着会让侧栏静默退回列表。
  // 首次由 0 → 首个部门不算切换，否则会把 URL 深链接带进来的选择抹掉。
  useEffect(() => {
    if (!structureId) {
      return
    }
    if (lastStructure.current && lastStructure.current !== structureId) {
      setSelectedCity(null)
      setSelectedPerson(null)
    }
    lastStructure.current = structureId
  }, [structureId])

  // 没有部门时不加载底图：既省一次 SDK 请求，也避免渲染出一张无法解释的空地图。
  useEffect(() => {
    if (structureId && !mapBooted) {
      setMapBooted(true)
    }
  }, [structureId, mapBooted])

  // 自动选第一个部门通常几百毫秒内完成；超过宽限期仍未选中，才认定「确实没有可选部门」，
  // 否则用户会看到一闪而过的错误提示，误以为系统坏了。
  useEffect(() => {
    if (structureId) {
      setStructureWaitExpired(false)
      return
    }
    const timer = window.setTimeout(() => setStructureWaitExpired(true), 2000)
    return () => window.clearTimeout(timer)
  }, [structureId])

  // 初始化底图（只做一次）。配置缺失时 SDK 根本不加载，直接给出申请引导。
  useEffect(() => {
    if (!mapBooted) {
      return
    }
    let disposed = false
    setMapState('loading')
    loadAmap()
      .then(() => {
        if (disposed || !containerRef.current) {
          return
        }
        const AMap = window.AMap
        mapRef.current = new AMap.Map(containerRef.current, {
          zoom: 4.4,
          center: [104.2, 35.9],
          viewMode: '2D',
          resizeEnable: true,
        })
        setMapState('ready')
      })
      .catch((error: unknown) => {
        if (disposed) {
          return
        }
        setMapState(error instanceof Error && error.message === AMAP_NOT_CONFIGURED ? 'missing' : 'error')
      })
    return () => {
      disposed = true
      mapRef.current?.destroy?.()
      mapRef.current = null
      markersRef.current = []
    }
  }, [mapBooted])

  // 数据/选中变化时重建打点。30 个城市规模下整体重建最简单也最不容易出现"幽灵点位"。
  useEffect(() => {
    const map = mapRef.current
    if (!map || mapState !== 'ready' || !data) {
      return
    }
    const AMap = window.AMap
    for (const marker of markersRef.current) {
      map.remove(marker)
    }
    markersRef.current = []

    for (const city of data.cities) {
      const diameter = Math.min(56, Math.max(26, 22 + Math.round(Math.sqrt(city.auditors) * 4)))
      const duty = city.inScope > 0
      const safeName = escapeHtml(city.name)
      // 常驻视图：圈内是常驻总数，角标补在岗数；现场视图：圈内已是现场人数，角标只留城市名。
      const caption = group === 'onsite' ? safeName
        : duty ? `${safeName}<em> · ${city.inScope}</em>` : safeName
      const selected = selectedCity === city.locationId ? ' map-pin--selected' : ''
      const content = `<div class="map-pin ${duty ? 'map-pin--duty' : 'map-pin--idle'}${selected}">`
        + `<div class="map-pin-dot" style="width:${diameter}px;height:${diameter}px">${city.auditors}</div>`
        + `<div class="map-pin-caption">${caption}</div>`
        + `</div>`
      const marker = new AMap.Marker({
        position: [city.longitude, city.latitude],
        content,
        anchor: 'center',
        zIndex: 100 + city.auditors,
        title: `${city.name} · ${city.auditors}`,
      })
      marker.on('click', () => {
        setSelectedCity(city.locationId)
        setSelectedPerson(null)
      })
      map.add(marker)
      markersRef.current.push(marker)
    }
  }, [data, mapState, selectedCity, group])

  // 固定引用：否则每次渲染都是新数组，下面的 activeCity 会跟着重算。
  const cities = useMemo(() => data?.cities ?? [], [data])
  const activeCity: MapCity | null = useMemo(
    () => (selectedCity === null ? null : cities.find((c) => c.locationId === selectedCity) ?? null),
    [cities, selectedCity],
  )
  const unassigned = data?.unassigned ?? []
  const showingUnassigned = selectedCity === UNASSIGNED

  function renderPersonRow(person: MapPerson) {
    return (
      <div
        key={person.id}
        className={`map-person${selectedPerson?.id === person.id ? ' is-active' : ''}`}
        onClick={() => setSelectedPerson(person)}
        role="button"
        tabIndex={0}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            setSelectedPerson(person)
          }
        }}
      >
        <span className="map-person-name">
          {person.name}
          <span className="map-person-code">{person.code}</span>
        </span>
        <span className={`map-chip${person.inScope ? ' map-chip--duty' : ''}`}>
          {person.inScope ? t(text.on, 'On duty') : t(text.off, 'Idle')}
        </span>
      </div>
    )
  }

  function renderSide() {
    if (!structureId) {
      return <p className="map-hint">{t('map.selectStructure', 'Select a structure in the top-right corner first.')}</p>
    }
    if (loading) {
      return <p className="map-hint">{t('map.loading', 'Loading…')}</p>
    }
    if (!data) {
      return <p className="map-hint">{t('map.loadError', 'Failed to load map data.')}</p>
    }
    if (selectedPerson) {
      return (
        <>
          <button type="button" className="map-back" onClick={() => setSelectedPerson(null)}>
            {t('map.back', 'Back')}
          </button>
          <div className="map-side-head">
            <h6>{selectedPerson.name}</h6>
            <span className={`map-chip${selectedPerson.inScope ? ' map-chip--duty' : ''}`}>
              {selectedPerson.inScope ? t(text.on, 'On duty') : t(text.off, 'Idle')}
            </span>
          </div>
          <div className="map-detail">
            <dl>
              <dt>{t('map.code', 'Code')}</dt>
              <dd>{selectedPerson.code}</dd>
              <dt>{t('map.email', 'Email')}</dt>
              <dd>{selectedPerson.email || '—'}</dd>
              {/* 常驻与任务地分开写：两种打点口径下都能看出"人从哪来、现在在哪" */}
              <dt>{t('map.homeCity', 'Home city')}</dt>
              <dd>{selectedPerson.homeCity || '—'}</dd>
              {selectedPerson.taskCity && (
                <>
                  <dt>{t('map.taskCity', 'Task city')}</dt>
                  <dd className={selectedPerson.taskCity !== selectedPerson.homeCity ? 'text-warning fw-semibold' : undefined}>
                    {selectedPerson.taskCity}
                    {selectedPerson.taskCity !== selectedPerson.homeCity && (
                      <span className="ms-1 small">{t('map.crossCity', '(cross-city)')}</span>
                    )}
                  </dd>
                </>
              )}
              <dt>{t(text.shift, 'Shift')}</dt>
              <dd>
                {selectedPerson.inScope
                  ? `${minute(selectedPerson.shiftStart)} → ${minute(selectedPerson.shiftEnd)}`
                  : t('map.noShift', 'No shift in this range')}
              </dd>
              {selectedPerson.inScope && selectedPerson.orderCode ? (
                <>
                  <dt>{t('map.order', 'Order')}</dt>
                  <dd>
                    <Link
                      className="map-jump"
                      to={`/workflow?tab=orders&order=${encodeURIComponent(selectedPerson.orderCode)}`}
                      title={t('map.openOrder', 'Open this order in the scheduling workflow')}
                    >
                      {selectedPerson.orderCode}
                      <FontAwesomeIcon icon={faArrowUpRightFromSquare} className="ms-1" />
                    </Link>
                    {selectedPerson.stage ? ` · ${selectedPerson.stage}` : ''}
                  </dd>
                </>
              ) : null}
            </dl>
            <Link className="map-jump map-jump--block" to={`/employees?employee=${selectedPerson.id}`}>
              {t('map.openPerson', 'Open auditor details')}
              <FontAwesomeIcon icon={faArrowUpRightFromSquare} className="ms-1" />
            </Link>
          </div>
        </>
      )
    }
    if (activeCity && selectedCity !== null) {
      return (
        <>
          <div className="map-side-head">
            <h6>{activeCity.name}</h6>
            <span className="map-side-sub">
              {group === 'onsite'
                ? t('map.onsiteCount', { n: activeCity.inScope, defaultValue: '{{n}} on site' })
                : <>{activeCity.auditors} {t('map.auditor', 'auditors')} ·{' '}
                  {t(text.head, { n: activeCity.inScope, defaultValue: '{{n}} in range' })}</>}
            </span>
          </div>
          {activeCity.people.length === 0 ? (
            <p className="map-hint">{t('map.none', 'No auditors.')}</p>
          ) : (
            activeCity.people.map(renderPersonRow)
          )}
        </>
      )
    }
    if (showingUnassigned) {
      return (
        <>
          <div className="map-side-head">
            <h6>{t('map.unassigned', 'No city assigned')}</h6>
            <span className="map-side-sub">{unassigned.length}</span>
          </div>
          {unassigned.map(renderPersonRow)}
        </>
      )
    }
    return (
      <>
        <div className="map-side-head">
          <h6>{t('map.cityList', 'Cities')}</h6>
          <span className="map-side-sub">{cities.length}</span>
        </div>
        <p className="map-hint">{t('map.pickHint', 'Click a city on the map to see its auditors.')}</p>
        {cities.map((city) => (
          <div
            key={city.locationId}
            className="map-city-row"
            onClick={() => {
              setSelectedCity(city.locationId)
              setSelectedPerson(null)
            }}
            role="button"
            tabIndex={0}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                setSelectedCity(city.locationId)
              }
            }}
          >
            <span className="map-city-name">{city.name}</span>
            <span className={`map-chip${city.inScope > 0 ? ' map-chip--duty' : ''}`}>
              {group === 'onsite' ? city.auditors : `${city.inScope}/${city.auditors}`}
            </span>
          </div>
        ))}
        {unassigned.length > 0 ? (
          <div
            className="map-city-row"
            onClick={() => {
              setSelectedCity(UNASSIGNED)
              setSelectedPerson(null)
            }}
            role="button"
            tabIndex={0}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                setSelectedCity(UNASSIGNED)
              }
            }}
          >
            <span className="map-city-name">{t('map.unassigned', 'No city assigned')}</span>
            <span className="map-chip">{unassigned.length}</span>
          </div>
        ) : null}
      </>
    )
  }

  return (
    <div className="map-page">
      <div className="map-toolbar">
        <h5>{t('map.title', 'Auditor Distribution')}</h5>
        {/* 口径切换：改变"有班"的判定窗口，图例与标签文案随口径变化 */}
        <ButtonGroup size="sm" className="map-scope" aria-label={t('map.scopeLabel', 'Time range')}>
          {MAP_SCOPES.map((value) => (
            <Button
              key={value}
              variant={scope === value ? 'dark' : 'outline-secondary'}
              onClick={() => setScope(value)}
              aria-pressed={scope === value}
            >
              {t(SCOPE_TEXT[value].tab, SCOPE_FALLBACK[value])}
            </Button>
          ))}
        </ButtonGroup>
        {/* 打点口径切换：同一个圈里的数字，按常驻看是"储备"，按现场看是"兵力" */}
        <ButtonGroup size="sm" className="map-scope" aria-label={t('map.groupLabel', 'Grouping')}>
          {MAP_GROUPS.map((value) => (
            <Button
              key={value}
              variant={group === value ? 'dark' : 'outline-secondary'}
              onClick={() => setGroup(value)}
              aria-pressed={group === value}
            >
              {t(GROUP_TEXT[value].tab, GROUP_TEXT[value].fallback)}
            </Button>
          ))}
        </ButtonGroup>
        <div className="map-legend">
          <span>
            <i style={{ background: 'var(--map-accent)' }} />
            {t(text.on, 'On duty')}
          </span>
          <span>
            <i style={{ background: 'var(--map-idle)' }} />
            {t(text.off, 'Idle')}
          </span>
        </div>
        <div className="map-stats">
          <div className="map-stat">
            <b>{data?.totalAuditors ?? 0}</b>
            <span>{t('map.totalAuditors', 'Auditors')}</span>
          </div>
          <div className="map-stat">
            <b>{data?.totalInScope ?? 0}</b>
            <span>{t(text.total, 'On duty now')}</span>
          </div>
          <div className="map-stat">
            <b>{data?.totalCities ?? 0}</b>
            <span>{t('map.cities', 'Cities')}</span>
          </div>
          {data ? (
            <span className="map-stamp">
              {t('map.dataTime', 'Data as of')} {data.generatedAt}
            </span>
          ) : null}
          <Button size="sm" variant="outline-secondary" onClick={() => void load()} disabled={loading}>
            <FontAwesomeIcon icon={faRotateRight} className="me-1" />
            {t('map.refresh', 'Refresh')}
          </Button>
        </div>
      </div>

      {!structureId ? (
        // 没有部门时不渲染地图容器（底图也未初始化），只给一块说明。
        <div className="map-canvas-empty">
          {structureWaitExpired ? (
            <div>
              <h6>{t('map.noStructureTitle', 'No structure selected')}</h6>
              <p className="map-hint mb-0">
                {t('map.selectStructure', 'Select a structure in the top-right corner first.')}
              </p>
            </div>
          ) : (
            <p className="map-hint mb-0">{t('map.loading', 'Loading…')}</p>
          )}
        </div>
      ) : (
        <>
          <div className="map-body">
            {mapState === 'missing' ? (
              <div className="map-canvas-empty">
                <div>
                  <h6>{t('map.keyMissingTitle', 'Map key not configured')}</h6>
                  <p className="map-hint mb-2">
                    {t(
                      'map.keyMissingBody',
                      'This page uses AMap JS API. Apply for a Web (JS API) key and its security code at console.amap.com, then set AMAP_JS_KEY and AMAP_JS_SECURITY_CODE (or app.map.amap-js-key / app.map.amap-js-security-code in config/application.properties next to the jar) and restart. No rebuild is needed.',
                    )}
                  </p>
                  <p className="map-hint mb-0">
                    <code>app.map.amap-js-key</code> · <code>app.map.amap-js-security-code</code>
                  </p>
                </div>
              </div>
            ) : (
              <div ref={containerRef} className="map-canvas" id="map-canvas" />
            )}
            <div className="map-side">{renderSide()}</div>
          </div>
          {mapState === 'error' ? (
            <p className="map-hint mt-2">{t('map.mapError', 'Map failed to load. Check the key and Referer whitelist.')}</p>
          ) : null}
          {/* 中国地图合规：公开展示必须标注审图号。底图未加载时无从谈起，故只在 ready 时显示。 */}
          {mapState === 'ready' ? (
            <p className="map-attribution">
              {t('map.approvalNo', { no: AMAP_APPROVAL_NO, defaultValue: 'Map approval no. {{no}}' })}
            </p>
          ) : null}
        </>
      )}
    </div>
  )
}
