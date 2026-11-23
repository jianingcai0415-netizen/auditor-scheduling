/**
 * @file mapOverview.ts
 * @brief 审核员分布地图的数据接口与类型。
 *
 * @details
 * 一次请求拿回「城市 → 审核员 → 工作状态」的完整快照：几百人、几十个城市的体量下，
 * 让点击城市时侧栏零延迟，也避免地图打点触发几十个并发请求。
 *
 * **状态口径由 `scope` 决定**（后端判定，前端不自行推导——否则前后端会各有一套定义）：
 * - `now`   班次覆盖当前时刻 → 文案用「在岗 / 空闲」
 * - `today` 班次与今天有交集 → 文案用「今日有班 / 今日无班」
 * - `next7` 班次与未来 7 天有交集 → 文案用「7天内有班 / 7天内无班」
 *
 * 字段叫 `inScope` 而不是 `onDuty`：选了"今日有班"之后，"有"并不等于"此刻在岗"。
 */

import { api } from './client'

/** @brief 统计口径（时间维度）。 */
export type MapScope = 'now' | 'today' | 'next7'

export const MAP_SCOPES: MapScope[] = ['now', 'today', 'next7']

/**
 * @brief 打点口径（空间维度）。
 *
 * - `home`   按**常驻地**归堆：圈内数字 = 该城常驻人数，看人力储备；
 * - `onsite` 按**当前班次所在地**归堆：圈内数字 = 此刻在该城干活的人数，看现场兵力。
 *
 * 同一个数字在两种口径下含义完全不同（"深圳 38"是常驻，不是此刻有 38 人在深圳），
 * 所以必须由用户显式选择，后端不会替用户猜。
 */
export type MapGroup = 'home' | 'onsite'

export const MAP_GROUPS: MapGroup[] = ['home', 'onsite']

/** @brief 单个审核员。`shiftStart` 等字段仅在 `inScope` 为真时有值。 */
export interface MapPerson {
  id: number
  code: string
  name: string
  email: string
  /** @brief 是否符合当前口径（有覆盖该窗口的班次）。 */
  inScope: boolean
  shiftStart: string | null
  shiftEnd: string | null
  orderCode: string | null
  stage: string | null
  /**
   * 当前班次的任务地点。地图打点用的是**常驻城市**（人要出发的地方），
   * 与本字段不同说明这趟是跨城出差——侧栏据此提示，避免"在南京却在上海干活"的误解。
   */
  taskCity?: string | null
  /** @brief 常驻城市名。按现场视图打点时，点位是任务地点，靠这个字段看出人是从哪来的。 */
  homeCity?: string | null
}

/** @brief 一个城市点位（坐标是 GCJ-02，来自 locations 表）。 */
export interface MapCity {
  locationId: number
  name: string
  latitude: number
  longitude: number
  auditors: number
  /** @brief 其中符合当前口径的人数。 */
  inScope: number
  people: MapPerson[]
}

/** @brief 地图快照。 */
export interface MapOverview {
  generatedAt: string
  scope: MapScope
  /** @brief 本次打点口径：home / onsite。 */
  group: MapGroup
  totalAuditors: number
  totalInScope: number
  totalCities: number
  cities: MapCity[]
  /** @brief 没有归属到任何有坐标城市的审核员——单独成桶，否则总数对不上。 */
  unassigned: MapPerson[]
}

export const mapOverviewApi = {
  /** @brief 拉取指定部门、指定时间口径与打点口径的分布快照。 */
  get(structureId: number, scope: MapScope, group: MapGroup = 'home'): Promise<MapOverview> {
    return api.get<MapOverview>(`/map-overview?structureId=${structureId}&scope=${scope}&group=${group}`)
  },
}
