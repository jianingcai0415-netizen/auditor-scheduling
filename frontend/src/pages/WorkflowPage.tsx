import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Accordion, Alert, Badge, Button, Card, Col, Form, Modal, Nav, ProgressBar, Row, Spinner, Table } from 'react-bootstrap'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { tx } from '../i18n/uiText'
import toast from 'react-hot-toast'
import { useAppStore } from '../store/useAppStore'
import { useAuth } from '../auth/AuthContext'
import { errorBody, errorCode, errorStatus } from '../api/client'
import { locationsApi, type Location } from '../api/locations'
import { skillsApi, type Skill } from '../api/skills'
import {
  workflowApi, saveBlob, type AuditEvent, type AuditOrder, type AuditOrderInput, type AuditPhaseInput,
  type AuditPhase, type Candidate, type CandidateAiReview, type CandidateAiStatus,
  type CandidateWeightInput, type CandidateWeightProfile,
  type EmployeeProfile, type EmployeeShift, type ExclusionList,
  type ImportPreview, type OrderCandidateResult, type PlanAssignment, type SchedulePlan,
  type SupersedePreview,
} from '../api/workflow'

type Tab = 'candidates' | 'orders' | 'plans' | 'imports' | 'audit'

/** 深链接可用的标签页；顺序与界面上的 Nav 一致。 */
const TAB_KEYS: Tab[] = ['candidates', 'orders', 'plans', 'imports', 'audit']

const nowLocal = () => {
  const value = new Date(Date.now() - new Date().getTimezoneOffset() * 60000)
  return value.toISOString().slice(0, 16)
}
const laterLocal = (hours: number) => {
  const value = new Date(Date.now() + hours * 3600000 - new Date().getTimezoneOffset() * 60000)
  return value.toISOString().slice(0, 16)
}
const emptyOrder = (): AuditOrderInput => ({ orderCode: '', customerName: '', projectName: '', status: 'DRAFT', priority: 3, ownerName: '', notes: '' })
const emptyPhase = (): AuditPhaseInput => ({
  phaseCode: '', phaseName: '', startTime: nowLocal(), endTime: laterLocal(8), locationId: 0,
  requiredHeadcount: 1, leadRequired: false, continuityGroup: '', deliveryMode: 'ONSITE', status: 'READY', requiredSkills: [],
})
const defaultWeightForm = (): CandidateWeightInput => ({
  ability: 40, location: 30, workload: 15, continuity: 10, age: 5, changeReason: '',
})

function statusColor(status: string): string {
  if (['EXECUTED', 'APPROVED', 'ACTIVE', 'CONFIRMED'].includes(status)) return 'success'
  if (['SUBMITTED', 'REVIEW', 'PREVIEW'].includes(status)) return 'warning'
  if (['CANCELLED', 'SUPERSEDED', 'REJECTED'].includes(status)) return 'secondary'
  return 'primary'
}

/**
 * 共用状态表。注意 `DRAFT` 在两个对象上含义完全不同：
 * **订单**的 DRAFT 是"还没提交审批"，**方案**的 DRAFT 是"人排好了但还没定稿"。
 * 两个都叫「草案」时，订单列表和方案列表读起来一模一样，所以各自用 {@link orderStatusLabel}
 * / {@link planStatusLabel} 覆盖成「草稿」与「待定稿」。
 */
const statusLabels: Record<string, string> = {
  DRAFT: '草案', SUBMITTED: '待审批', APPROVED: '已批准', REJECTED: '已退回',
  EXECUTED: '已定稿', SUPERSEDED: '已被新版本替代',
  ACTIVE: '进行中', CANCELLED: '已取消', COMPLETED: '已完成',
  READY: '就绪', CONFIRMED: '已确认', PREVIEW: '待确认', IMPORTED: '已导入',
  FAILED: '失败', PLANNED: '待就绪', REVIEW: '待审批', PUBLISHED: '已发布',
}

/** 订单级排班进度：把各阶段的「已分配 / 名额」加总。
 *  没有这个汇总，已批准订单在列表里看起来和"还没排人"完全一样——实际很可能早已满员。 */
function assignmentProgress(order: AuditOrder) {
  const slots = order.phases.reduce((sum, phase) => sum + (phase.requiredHeadcount || 0), 0)
  const assigned = order.phases.reduce((sum, phase) => sum + (phase.assignedCount ?? 0), 0)
  return { slots, assigned }
}

/** 折叠头里的排班进度徽标。草稿/已退回的订单还没生成班次，显示 0/0 只会误导，直接不显示。 */
function AssignmentBadge({ order }: { order: AuditOrder }) {
  const { slots, assigned } = assignmentProgress(order)
  if (slots === 0 || order.status === 'DRAFT' || order.status === 'REJECTED') return null
  if (assigned >= slots) {
    return <Badge bg="success" className="ms-2" title={tx('全部名额都已分配审核员')}>{tx("已排 {0}/{1}", [assigned, slots])}</Badge>
  }
  if (assigned === 0) {
    return <Badge bg="secondary" className="ms-2" title={tx('还没有任何名额分配审核员')}>{tx("未排 0/{0}", [slots])}</Badge>
  }
  return <Badge bg="warning" text="dark" className="ms-2" title={tx('还有名额空着')}>{tx("缺 {0}人（{1}/{2}）", [slots - assigned, assigned, slots])}</Badge>
}

const roleLabels: Record<string, string> = { LEAD_AUDITOR: '主审核员', AUDITOR: '审核员' }
/** Live-dispatch status shown under every candidate name; raw English codes confuse
 *  dispatchers, so they are translated with the operational meaning attached. */
const operationalStatusLabels: Record<string, string> = {
  AVAILABLE: '可调度', ASSIGNED: '已分配任务', TRAVELLING: '转场中', UNAVAILABLE: '不可用',
}
const operationalStatusHints: Record<string, string> = {
  AVAILABLE: '当前没有已确认任务，可参与本次排程',
  ASSIGNED: '已在其他任务中占用，系统会按时间冲突规则处理',
  TRAVELLING: '正在前往其他地点，转场缓冲可能不足',
  UNAVAILABLE: '已登记不可用时间或暂停调度，不会出现在合格候选内',
}
const operationalStatusLabel = (status?: string) =>
  status ? tx(operationalStatusLabels[status] ?? status) : '—'
const planSourceLabels: Record<string, string> = { WINDOW: '时间窗快照', ORDER: '按订单生成', REVISION: '修订版' }
const planSourceHints: Record<string, string> = {
  WINDOW: '快照：原样复制排班表在窗口内的现有人员，不重查资格；不合格人员不会被移除，只在风险列标出',
  ORDER: '按订单生成：从全部未分配开始，不沿用测试数据里的模拟人员，需逐个名额锁定',
  REVISION: '修订版：从已有方案复制而来，可在其上继续调整后重新定稿',
}
/** A draft whose scheduling window has fully ended is dead weight: it can never be
 *  submitted meaningfully, so the list de-emphasises it and the OPEN filter hides it. */
const planExpired = (plan: SchedulePlan) =>
  plan.windowEnd.replace('T', ' ').slice(0, 16) < nowLocal().replace('T', ' ')
/** 方案只有两步：草案 → 定稿。审批已上移到订单，方案不再有"提交/批准/退回"。 */
const planStepOrder = ['DRAFT', 'EXECUTED']
const planStepLabels: Record<string, string> = { DRAFT: '待定稿', EXECUTED: '已定稿' }
/** Covers every action the backend writes to audit_events (17 kinds), so the plan
 *  history panel and the global audit tab share one translation table. */
const planActionLabels: Record<string, string> = {
  SCHEDULE_PLAN_CREATED: '创建草案（时间窗快照）',
  SCHEDULE_PLAN_CREATED_FROM_ORDER: '按订单创建草案',
  SCHEDULE_PLAN_REVISED: '新建修订',
  SCHEDULE_PLAN_FINALIZED: '定稿（分配已写入班次）',
  // 旧流程留下的审计记录：状态机已收敛，但历史事件必须仍然读得懂，故标注「旧流程」而非直接丢弃。
  SCHEDULE_PLAN_SUBMITTED: '提交审批（旧流程）',
  SCHEDULE_PLAN_APPROVED: '批准（旧流程）',
  SCHEDULE_PLAN_REJECTED: '退回（旧流程）',
  SCHEDULE_PLAN_PUBLISHED: '发布（旧流程）',
  SCHEDULE_PLAN_DELETED: '删除草案',
  SCHEDULE_PLAN_EXPORTED: '导出 Excel',
  SCHEDULE_ASSIGNMENT_CHANGED: '修改人员分配',
  SCHEDULE_SOLVE_STARTED: '启动求解器',
  SHIFT_ASSIGNMENTS_SAVED: '保存解算结果',
  IMPORT_PREVIEWED: '导入预检查',
  IMPORT_CONFIRMED: '确认导入',
  AUDIT_ORDER_CREATED: '创建订单',
  AUDIT_ORDER_SUBMITTED: '提交订单审批',
  AUDIT_ORDER_APPROVED: '批准订单',
  AUDIT_ORDER_REJECTED: '退回订单',
  AUDIT_PHASE_CREATED: '创建阶段',
  AI_CANDIDATE_REVIEW_COMPLETED: 'AI 初筛完成',
}
const auditEntityLabels: Record<string, string> = {
  SCHEDULE_PLAN: '排程方案', SCHEDULE_PLAN_ASSIGNMENT: '人员分配', SOLVER_JOB: '求解任务',
  SHIFT_ASSIGNMENTS: '班次分配', IMPORT_JOB: '导入任务', AUDIT_ORDER: '审核订单',
  AUDIT_PHASE: '订单阶段', EMPLOYEE: '审核员',
}
const planDetailKeyLabels: Record<string, string> = {
  name: '名称', assignments: '名额数', status: '状态', orderId: '订单ID', orderCode: '订单号',
  parentPlanId: '父方案', revision: '版本', superseded: '替代方案', reason: '原因', format: '格式', rows: '行数',
  planId: '方案', employeeId: '人员ID', model: '模型', provider: '服务商', recommendations: '建议数',
  responseId: '响应ID', headcount: '人数', skills: '技能数', employees: '人员数', locations: '地点数',
  phases: '阶段数', warnings: '警告数', fileName: '文件名', errors: '错误数', maxSolveSeconds: '求解时限（秒）',
}
function planEventDetail(details: string): string {
  if (!details) return '—'
  try {
    const parsed = JSON.parse(details) as Record<string, unknown>
    const text = Object.entries(parsed)
      .map(([key, value]) => `${tx(planDetailKeyLabels[key] ?? key)} ${Array.isArray(value) ? value.join('、') : String(value)}`)
      .join(' · ')
    return text || '—'
  } catch { return details }
}
const statusLabel = (status: string) => tx(statusLabels[status] ?? status)
/** 订单的 DRAFT = 草稿订单（还没提交审批），与方案无关。 */
const orderStatusLabel = (status: string) => status === 'DRAFT' ? tx('草稿') : statusLabel(status)
/** 方案的 DRAFT = 待定稿（人员已锁定但未写入班次），与订单的草稿是两回事。 */
const planStatusLabel = (status: string) => status === 'DRAFT' ? tx('待定稿') : statusLabel(status)
const roleLabel = (role: string) => tx(roleLabels[role] ?? role)
const riskLabel = (risk: string) => risk === 'UNASSIGNED' ? tx('未分配') : tx(riskLabels[risk] ?? risk)
/**
 * @brief 把后端给的评分理由渲染成当前语言的句子。
 * @details 后端同时给 `reasons`（成品中文）和 `reasonItems`（结构化）。有结构化数据时按占位符
 *          逐条拼，数字与分支都来自后端；只有老数据没有 reasonItems 时才退回中文原文。
 */
function candidateReasonLines(candidate: Candidate): string[] {
  const items = candidate.reasonItems
  if (!items || items.length === 0) return candidate.reasons ?? []
  return items.map(item => {
    switch (item.code) {
      case 'ability':
        return tx("能力 {0}：有效资格 {1}，命中必需资格 {2}/{3}",
          [item.score, item.validCount, item.matched, item.required])
      case 'location': {
        if (item.noHome)
          return tx("位置 {0}：未设置常驻地且无近期任务，转场按 0 分钟估算（补录位置可得更准确的转场评估）",
            [item.score])
        const origin = item.fromPreviousTask
          ? tx("从上一任务所在地 {0} 出发（该任务 {1} 结束，{2} 小时前，仍在 {3} 小时返程空窗期内）",
            [item.origin ?? tx('未知'), item.previousEnd, item.gapHours, item.returnToBaseHours])
          : tx("从常驻地 {0} 出发", [item.origin ?? tx('未设置')])
        if (item.sameCity) return tx("位置 {0}：{1}，与任务地点同城，转场 0 分钟", [item.score, origin])
        if (item.missingCoords)
          return tx("位置 {0}：{1} → 任务地点 {2}，坐标缺失按 120 分钟保守估算", [item.score, origin, item.task])
        const suffix = item.overBaseline
          ? tx("（已超过 {0} 小时基准，位置分按 0 计）", [item.baselineHours]) : ''
        return tx("位置 {0}：{1} → 任务地点 {2}，预计转场 {3} 分钟{4}",
          [item.score, origin, item.task, item.minutes, suffix])
      }
      case 'workload': {
        let line = tx("空闲度 {0}：排班表已占用 {1} 小时（含历史与其他方案）", [item.score, item.hours])
        if (item.ownHours) line += tx("，其中本方案 {0} 小时", [item.ownHours])
        if (item.saturated) line += tx("，已达 80 小时基准按满载计")
        return line
      }
      case 'continuity':
        return tx("连续性 {0}：{1}", [item.score,
          item.state === 'NONE' ? tx("无可继承人员，按中性分")
            : item.state === 'SAME' ? tx("延续同组人员") : tx("未延续同组人员")])
      default:
        return tx("年龄 {0}：尚未采集，按中性分且仅占5%", [item.score])
    }
  })
}

const formatDateTime = (value?: string) => value ? value.replace('T', ' ').slice(0, 16) : '—'

/** A superseded plan is not simply "gone": publication only overwrote the shifts inside the
 *  window overlap, and whatever the old plan placed outside the overlap keeps running. The note
 *  spells out which part survives instead of letting "superseded" read as "inactive everywhere". */
const supersededNote = (plan: SchedulePlan, plans: SchedulePlan[]): string => {
  const successor = plan.supersededByPlanId
    ? plans.find(candidate => candidate.id === plan.supersededByPlanId) : undefined
  if (!successor) return tx('已被更新版本替代；仅两个方案时间窗重叠部分的分配被覆盖，未重叠时段如需核对请对照班次表。')
  const norm = (value: string) => value.replace('T', ' ').slice(0, 16)
  const label = `《${successor.name}》v${successor.revision}`
  if (norm(successor.windowStart) <= norm(plan.windowStart) && norm(successor.windowEnd) >= norm(plan.windowEnd))
    return tx("本方案时间窗内的分配已被{0}全部覆盖，仅作历史留档。", [label])
  const overlapStart = norm(successor.windowStart) > norm(plan.windowStart) ? successor.windowStart : plan.windowStart
  const overlapEnd = norm(successor.windowEnd) < norm(plan.windowEnd) ? successor.windowEnd : plan.windowEnd
  const surviving = [
    norm(successor.windowStart) > norm(plan.windowStart)
      ? `${formatDateTime(plan.windowStart)} ～ ${formatDateTime(successor.windowStart)}` : '',
    norm(successor.windowEnd) < norm(plan.windowEnd)
      ? `${formatDateTime(successor.windowEnd)} ～ ${formatDateTime(plan.windowEnd)}` : '',
  ].filter(Boolean).join(tx(' 与 '))
  return tx("与{0}仅在 {1} ～ {2} 重叠：", [label, formatDateTime(overlapStart), formatDateTime(overlapEnd)])
    + tx("重叠时段的分配已由新方案覆盖；{0} 时段的班次安排仍按本方案执行。", [surviving])
}

function download(blob: Promise<Blob>, name: string) {
  void blob.then(value => saveBlob(value, name)).catch(() => toast.error(tx('下载失败')))
}

const riskLabels: Record<string, string> = {
  EMPLOYEE_INACTIVE: '人员已停用', EMPLOYEE_BUSY: '外派或忙碌', EMPLOYEE_UNAVAILABLE: '处于不可用时间',
  QUALIFICATION_MISSING: '缺少所需资格', QUALIFICATION_EXPIRED: '资格无效或过期',
  LEAD_QUALIFICATION_REQUIRED: '缺少主审核员资质', TIME_OVERLAP: '已有任务冲突',
  TRANSFER_BUFFER_INSUFFICIENT: '转场或缓冲时间不足',
  SCHEDULE_PLAN_STALE: '订单阶段已被修改', EMPLOYEE_STATUS_CHANGED: '人员状态已更新，需重新确认',
  EMPLOYEE_PROFILE_CHANGED: '人员资料已变更，需重新确认',
}
const riskHints: Record<string, string> = {
  EMPLOYEE_INACTIVE: '人员已停用：不会出现在新候选里。换人，或先恢复其在职状态',
  EMPLOYEE_BUSY: '外派或忙碌：忙碌期未结束。等状态恢复，或换人',
  EMPLOYEE_UNAVAILABLE: '处于不可用时间：与该人员登记的不可用时段冲突。调整任务时间，或换人',
  QUALIFICATION_MISSING: '缺少所需资格：补齐资格记录，或改派具备资格的人员',
  QUALIFICATION_EXPIRED: '资格无效或过期：更新证书有效期，或换人',
  LEAD_QUALIFICATION_REQUIRED: '缺少主审核员资质：安排有主审资格的人，或调整阶段的组长要求',
  TIME_OVERLAP: '已有任务冲突：与其他任务时间重叠。调整时间，或换人',
  TRANSFER_BUFFER_INSUFFICIENT: '转场或缓冲时间不足：拉长任务间隔，或换更近的人员',
  SCHEDULE_PLAN_STALE: '订单阶段已被修改：阶段的时间或要求已变化，请重新核对后再提交',
  EMPLOYEE_STATUS_CHANGED: '人员状态已更新：打开候选人列表重新确认或换人',
  EMPLOYEE_PROFILE_CHANGED: '人员资料已变更：重新核对该人员的资格与状态后再提交',
}

/** Backend machine codes from workflow actions, translated to actionable guidance. Errors arrive
 *  as {"error": "CODE"} bodies (see ApiErrors); this table is the single translation point for
 *  everything funneled through run() and the load helpers. The AI branch keeps its own table. */
const workflowErrorMessages: Record<string, string> = {
  SCHEDULE_PLAN_NOT_FOUND: '方案不存在或已被删除，请刷新后重试。',
  SCHEDULE_PLAN_NAME_REQUIRED: '请填写方案名称。',
  SCHEDULE_PLAN_WINDOW_INVALID: '时间窗无效：请检查开始与结束时间。',
  SCHEDULE_PLAN_EMPTY: '所选时间窗内没有可排班的任务，请调整时间窗后重试。',
  SCHEDULE_PLAN_PARENT_INVALID: '源方案不存在，无法创建修订。',
  SCHEDULE_PLAN_NOT_EDITABLE: '当前状态下不能修改人员：只有「待定稿」的方案可以编辑。',
  SCHEDULE_PLAN_HAS_GAPS: '方案还有名额未分配，请先为每个名额选择人员。',
  SCHEDULE_PLAN_STALE: '订单或排班数据在方案创建后被修改，请刷新后重新核对名额。',
  SCHEDULE_PLAN_INVALID: '方案中有名额不满足硬约束，请处理风险项后重试。',
  SCHEDULE_PLAN_TRANSITION_INVALID: '方案状态刚刚发生了变化（可能被其他人操作），请刷新后重试。',
  SCHEDULE_PLAN_DELETE_NOT_ALLOWED: '只有「待定稿」的方案可以删除；已定稿或已被替代的方案需保留记录。',
  SCHEDULE_PLAN_EXPORT_FAILED: '导出失败，请稍后重试。',
  SCHEDULE_ASSIGNMENT_NOT_FOUND: '该名额不存在，请刷新后重试。',
  SCHEDULE_CHANGE_REASON_REQUIRED: '请选择「选择原因」后再确认。',
  SCHEDULE_EMPLOYEE_INVALID: '该人员无效（可能已停用或不属于本部门），请换人。',
  SCHEDULE_ASSIGNMENT_INVALID: '该人员不满足硬约束，请换人。',
  SCHEDULE_REJECTION_REASON_REQUIRED: '请填写退回原因。',
  EMPLOYEE_STATUS_CHANGED: '有人员状态已更新，请打开候选人列表重新确认或换人。',
  AUDIT_ORDER_NOT_FOUND: '未找到该订单，请检查订单号。',
  AUDIT_ORDER_NOT_APPROVED: '该订单尚未批准，无法排班。请先提交审批并批准后再查候选或生成方案。',
  AUDIT_ORDER_INVALID_TRANSITION: '订单当前状态不允许该操作（可能已被别人处理），请刷新后重试。',
  AUDIT_ORDER_NOT_ACTIVE: '该订单不是「进行中」状态，无法生成方案。',
  AUDIT_ORDER_NO_READY_PHASES: '该订单没有「就绪」的阶段或可排班任务，请先配置阶段与名额。',
  AUDIT_ORDER_NO_PHASES: '该订单还没有任何阶段。订单至少要有一个阶段才能提交审批。',
  AUDIT_PHASE_SKILL_REQUIRED: '有阶段没有填写「所需资格」。资格空着的阶段在排班时不会做资质筛选，请补上再提交。',
  AUDIT_PHASE_HEADCOUNT_INVALID: '有阶段的人数为 0，请至少填 1 人。',
  AUDIT_PHASE_DELIVERY_MODE_INVALID: '阶段的「执行方式」只能是现场 / 远程 / 混合。',
  AUDIT_ORDER_CODE_REQUIRED: '请输入订单号。',
  AUDIT_ORDER_CUSTOMER_REQUIRED: '请填写客户名称。',
  AUDIT_ORDER_PROJECT_REQUIRED: '请填写项目名称。',
  CANDIDATE_SCORING_INVALID: '候选评分参数无效，请检查权重设置。',
  SHIFT_NOT_FOUND: '任务数据不存在，请刷新后重试。',
  SAFETY_BACKUP_BUSY: '系统正在执行自动备份，请稍等片刻后重试。',
  SAFETY_BACKUP_CLIENT_TOOLS_MISSING: '服务器缺少备份工具，操作已被安全中止，请联系管理员。',
  SAFETY_BACKUP_FAILED: '定稿前自动备份失败，操作已中止，请联系管理员检查磁盘与备份日志。',
}

/** Turns any thrown error into a user-facing sentence: known code → guidance (appending the
 *  underlying risk when the body carries one), unknown code → code kept for support, HTTP
 *  failures → status-specific wording, local throws → shown as-is. Raw JSON never reaches a toast. */
function workflowErrorText(error: unknown): string {
  const code = errorCode(error)
  const body = errorBody(error)
  const risk = typeof body?.risk === 'string' && body.risk !== 'UNASSIGNED' ? riskLabel(body.risk) : null
  // 提交门禁会带回具体是哪个阶段不合格——指出来，比让用户自己翻三行强。
  const phase = typeof body?.phaseCode === 'string' && body.phaseCode ? tx("（阶段 {0}）", [body.phaseCode]) : ''
  if (code) {
    const text = workflowErrorMessages[code]
    if (text) return (risk ? tx("{0}具体原因：{1}。", [tx(text), risk]) : text) + phase
    return tx("操作失败（{0}），请重试；若持续出现请联系管理员。", [code])
  }
  const status = errorStatus(error)
  if (status === 401) return tx('登录状态已过期，请重新登录。')
  if (status === 403) return tx('当前账号没有执行该操作的权限，请联系管理员。')
  if (status) return tx("请求失败（HTTP {0}），请稍后重试。", [status])
  const message = error instanceof Error ? error.message : ''
  if (!message || /fetch|network|load failed/i.test(message)) return tx('网络连接失败，请检查网络后重试。')
  return message
}

export default function WorkflowPage() {
  // 只为一件事而调用：订阅语言变化。页面文案走 tx()（直接读 i18n.language），
  // 若不在组件里订阅，切换语言时这里不会重渲染，文案会停在旧语言。
  useTranslation()
  const structureId = useAppStore(state => state.currentStructure?.id)
  const { isDispatcher, isApprover } = useAuth()
  const [searchParams] = useSearchParams()
  // 深链接：从地图点订单号过来时落到「订单」页并预填搜索词（/workflow?tab=orders&q=XXX）。
  const [tab, setTab] = useState<Tab>(() => {
    const requested = searchParams.get('tab')
    return TAB_KEYS.find(key => key === requested) ?? 'candidates'
  })
  const [orders, setOrders] = useState<AuditOrder[]>([])
  const [plans, setPlans] = useState<SchedulePlan[]>([])
  const [imports, setImports] = useState<Awaited<ReturnType<typeof workflowApi.imports>>>([])
  const [events, setEvents] = useState<AuditEvent[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [skills, setSkills] = useState<Skill[]>([])
  const [loading, setLoading] = useState(false)
  const [orderForm, setOrderForm] = useState(emptyOrder)
  const [editingOrder, setEditingOrder] = useState<AuditOrder | null>(null)
  const [orderEditForm, setOrderEditForm] = useState(emptyOrder)
  const [phaseOrder, setPhaseOrder] = useState<AuditOrder | null>(null)
  const [phaseForm, setPhaseForm] = useState(emptyPhase)
  const [editingPhaseId, setEditingPhaseId] = useState<number | null>(null)
  // 编辑阶段时记录"打开那一刻"的地点/时间/已分配数，用于检测"改地点/时间会覆盖已分配"并提示。
  const [phaseOriginal, setPhaseOriginal] = useState<{ locationId?: number; startTime?: string; endTime?: string; assignedCount?: number }>({})
  // 改地点或改时间，且该阶段已有分配 → 提示"这些安排会随阶段一起被覆盖，但不会自动重新核对人员是否还合适"。
  const phaseLocationChanged = phaseOriginal.locationId !== undefined
    && phaseOriginal.locationId !== phaseForm.locationId
  const phaseTimeChanged = phaseOriginal.startTime !== undefined && phaseOriginal.endTime !== undefined
    && (phaseOriginal.startTime.replace(' ', 'T').slice(0, 16) !== phaseForm.startTime
      || phaseOriginal.endTime.replace(' ', 'T').slice(0, 16) !== phaseForm.endTime)
  const phaseAffectsAssigned = (phaseOriginal.assignedCount ?? 0) > 0
    && (phaseLocationChanged || phaseTimeChanged)
  const [planForm, setPlanForm] = useState({ name: tx("排程方案 {0}", [new Date().toLocaleDateString()]), windowStart: nowLocal(), windowEnd: laterLocal(24 * 7) })
  const [selectedPlan, setSelectedPlan] = useState<SchedulePlan | null>(null)
  const [candidateAssignment, setCandidateAssignment] = useState<PlanAssignment | null>(null)
  const [candidates, setCandidates] = useState<Candidate[]>([])
  const [candidateLimit, setCandidateLimit] = useState(5)
  const [orderCodeSearch, setOrderCodeSearch] = useState('')
  /** 候选名单里点「选择」后待确认的派工目标。派工会立即写入班次，所以要点一下再落库。 */
  const [assignTarget, setAssignTarget] = useState<null | {
    orderId: number; orderCode: string; shiftId: number; slotIndex: number
    phaseCode: string; phaseName: string; startTime: string; endTime: string; locationName: string
    employeeId: number; code: string; name: string; rank: number; totalScore: number
  }>(null)
  const [orderListSearch, setOrderListSearch] = useState(() => searchParams.get('q') ?? '')
  /** 受控展开的订单（手风琴）。深链接进来要能把目标订单直接摊开，而不是让用户自己找。 */
  const [expandedOrderIds, setExpandedOrderIds] = useState<string[]>([])
  // 深链接：排班页「去选人」→ /workflow?tab=candidates&order=CODE，进来就把候选查好，
  // 不用再让用户把订单号抄一遍。只在首次进入时触发一次。
  // 按"上次处理过的订单号"去重，而不是布尔标记：从地图/排班页再次跳同一个页面时，
  // 路由只在 query 上变化、组件不会重新挂载，布尔标记会导致第二次跳转完全不生效。
  const lastDeepLinkOrder = useRef<string | null>(null)
  const deepLinkOrder = searchParams.get('order')
  const [planListSearch, setPlanListSearch] = useState('')
  const [planStatusFilter, setPlanStatusFilter] = useState('OPEN')
  const [orderCandidateResult, setOrderCandidateResult] = useState<OrderCandidateResult | null>(null)
  const [orderSearchError, setOrderSearchError] = useState('')
  /**
   * 已排人员的实时风险：orderId → shiftId → 风险码。
   * 只在展开某张订单时按需拉取——整页 600 多条分配一次全算会明显拖慢列表。
   */
  const [orderRisks, setOrderRisks] = useState<Record<number, Record<number, string>>>({})
  /** 已经拉过风险的订单，避免每次折叠/展开都重复请求。 */
  const riskLoadedOrders = useRef<Set<number>>(new Set())
  const [weightProfile, setWeightProfile] = useState<CandidateWeightProfile | null>(null)
  const [weightForm, setWeightForm] = useState<CandidateWeightInput>(defaultWeightForm)
  const [showWeightEditor, setShowWeightEditor] = useState(false)
  const [aiStatus, setAiStatus] = useState<CandidateAiStatus | null>(null)
  const [aiReview, setAiReview] = useState<CandidateAiReview | null>(null)
  const [aiBusy, setAiBusy] = useState(false)
  const [changeReasonCode, setChangeReasonCode] = useState(tx('负责人确认'))
  const [changeReason, setChangeReason] = useState('')
  const [emergencyReplacement, setEmergencyReplacement] = useState(false)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [busy, setBusy] = useState(false)
  const [publishConfirmOpen, setPublishConfirmOpen] = useState(false)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)
  /** 订单退回：审批在订单层，方案不再有退回动作。 */
  const [rejectOrder, setRejectOrder] = useState<AuditOrder | null>(null)
  const [rejectOrderReason, setRejectOrderReason] = useState('')
  const [exclusionsView, setExclusionsView] = useState<{ orderCode: string; phaseId: number; slotIndex: number; risk: string } | null>(null)
  const [exclusions, setExclusions] = useState<ExclusionList | null>(null)
  const [exclusionsBusy, setExclusionsBusy] = useState(false)
  const [profileEmployeeId, setProfileEmployeeId] = useState<number | null>(null)
  const [profile, setProfile] = useState<EmployeeProfile | null>(null)
  const [profileShifts, setProfileShifts] = useState<EmployeeShift[]>([])
  const [profileBusy, setProfileBusy] = useState(false)
  const selectedPlanId = selectedPlan?.id
  // Every selectedPlan write bumps this token. The SSE listener re-reads the plan by id, and a
  // response that arrives after the user switched to another plan (or created a revision) is
  // stale: applying it would silently snap the modal back to the previous plan.
  const planLoadToken = useRef(0)
  const selectedPlanIdRef = useRef<number | undefined>(undefined)
  selectedPlanIdRef.current = selectedPlanId
  const setPlan = useCallback((plan: SchedulePlan | null) => {
    planLoadToken.current += 1
    setSelectedPlan(plan)
  }, [])

  // Audit events are only readable by ADMIN/APPROVER; dispatcher-only sessions simply
  // get an empty history instead of a failing request on every plan open.
  const [planHistory, setPlanHistory] = useState<AuditEvent[]>([])
  const loadPlanHistory = useCallback(async (planId: number) => {
    if (!structureId || !isApprover) { setPlanHistory([]); return }
    try {
      const rows = await workflowApi.auditEvents(structureId)
      setPlanHistory(rows
        .filter(event => event.entityType === 'SCHEDULE_PLAN' && Number(event.entityId) === planId)
        .sort((left, right) => left.id - right.id))
    } catch { setPlanHistory([]) }
  }, [structureId, isApprover])
  const visiblePlanHistory = useMemo(
    () => selectedPlan ? planHistory.filter(event => Number(event.entityId) === selectedPlan.id) : [],
    [planHistory, selectedPlan])

  /** 定稿影响预览（后端算）。以前前端自己按"时间窗重叠"估算，后端改成"只取代同一张订单的方案"后
   *  两边口径就分叉了：弹窗吓唬说"4 个方案将被替代"，实际一个都不会动。现在统一走后端同一个方法。 */
  const navigate = useNavigate()
  const [publishImpact, setPublishImpact] = useState<SupersedePreview | null>(null)
  const openPublishConfirm = useCallback(async (planId: number) => {
    setPublishImpact(null)
    setPublishConfirmOpen(true)
    try { setPublishImpact(await workflowApi.supersedePreview(structureId!, planId)) }
    catch { setPublishImpact(null) }  // 预览失败不挡定稿：真定稿时后端仍会执行同一套判定
  }, [structureId])
  const supersededOnPublish = publishImpact?.targets ?? []

  /** 拉取单张订单的风险；失败不打断主流程（列表照常可用）。 */
  const loadOrderRisks = useCallback(async (orderId: number) => {
    if (!structureId) return
    try {
      const data = await workflowApi.orderRisks(structureId, orderId)
      const byShift: Record<number, string> = {}
      for (const row of data.risks ?? []) byShift[row.shiftId] = row.risk
      setOrderRisks(previous => ({ ...previous, [orderId]: byShift }))
      riskLoadedOrders.current.add(orderId)
    } catch {
      riskLoadedOrders.current.delete(orderId)
    }
  }, [structureId])

  const reload = useCallback(async (silent = false) => {
    if (!structureId) return
    if (!silent) setLoading(true)
    try {
      const [orderRows, planRows, importRows, currentWeights, currentAiStatus] = await Promise.all([
        workflowApi.orders(structureId), workflowApi.plans(structureId),
        isDispatcher ? workflowApi.imports(structureId) : Promise.resolve([]),
        workflowApi.candidateWeights(structureId),
        workflowApi.candidateAiStatus(),
      ])
      setOrders(orderRows); setPlans(planRows); setImports(importRows)
      // 数据可能刚被改过（换人/改阶段/审批），已展开的订单要重新算风险，否则显示的是旧结论。
      for (const orderId of Array.from(riskLoadedOrders.current)) void loadOrderRisks(orderId)
      setWeightProfile(currentWeights)
      setAiStatus(currentAiStatus)
      setWeightForm({ ability: currentWeights.ability, location: currentWeights.location,
        workload: currentWeights.workload, continuity: currentWeights.continuity,
        age: currentWeights.age, changeReason: '' })
      if (isDispatcher) {
        const [locationRows, skillRows] = await Promise.all([locationsApi.list(structureId), skillsApi.list(structureId)])
        setLocations(locationRows); setSkills(skillRows)
      }
      if (isApprover) setEvents(await workflowApi.auditEvents(structureId))
    } catch (error) { if (!silent) toast.error(workflowErrorText(error)) }
    finally { if (!silent) setLoading(false) }
  }, [structureId, isDispatcher, isApprover, loadOrderRisks])

  useEffect(() => { void reload() }, [reload])
  useEffect(() => {
    if (!structureId) return
    const source = new EventSource('/events/dispatch')
    const refresh = (event: Event) => {
      try {
        const payload = JSON.parse((event as MessageEvent<string>).data) as { structureId?: number }
        if (!payload.structureId || payload.structureId === structureId) {
          void reload(true)
          // Read the id at call time (not from the effect closure) and drop the response if the
          // user moved on while it was in flight — otherwise creating a revision immediately
          // overwrites the fresh modal with the parent plan it was derived from.
          const openPlanId = selectedPlanIdRef.current
          if (openPlanId) {
            const token = planLoadToken.current
            void workflowApi.plan(structureId, openPlanId)
              .then(plan => { if (planLoadToken.current === token) setSelectedPlan(plan) })
              .catch(() => undefined)
            // Keep the operation log in sync: every plan mutation emits a schedule-plan event.
            void loadPlanHistory(openPlanId)
          }
          if (orderCandidateResult) void workflowApi.orderCandidates(
            structureId, orderCandidateResult.orderCode, candidateLimit,
          ).then(setOrderCandidateResult).catch(() => setOrderSearchError(tx('人员状态已变化，请重新查询。')))
        }
      } catch { void reload(true) }
    }
    const names = ['audit-order', 'audit-phase', 'schedule-plan', 'candidate-weights', 'employee.status.changed', 'import', 'solver.started']
    names.forEach(name => source.addEventListener(name, refresh))
    return () => { names.forEach(name => source.removeEventListener(name, refresh)); source.close() }
    // loadPlanHistory 每次渲染都是新函数，放进来会让 SSE 反复重连；这里只按业务关键值重订阅。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [structureId, reload, orderCandidateResult, candidateLimit])

  const locationNames = useMemo(() => new Map(locations.map(location => [location.id, location.name])), [locations])
  // 方案详情打开后要先回答"这单要去哪几个城市"：从分配快照里取地点去重，按出现顺序列出。
  const planLocations = useMemo(() => {
    const ids: number[] = []
    for (const assignment of selectedPlan?.assignments ?? []) {
      if (assignment.locationId && !ids.includes(assignment.locationId)) ids.push(assignment.locationId)
    }
    return ids.map(id => locationNames.get(id) ?? tx("地点 #{0}", [id]))
  }, [selectedPlan, locationNames])
  const filteredOrders = useMemo(() => {
    const query = orderListSearch.trim().toLocaleLowerCase()
    if (!query) return orders
    return orders.filter(order => [order.orderCode, order.customerName, order.projectName, order.ownerName]
      .some(value => value?.toLocaleLowerCase().includes(query)))
  }, [orders, orderListSearch])
  const filteredPlans = useMemo(() => {
    const query = planListSearch.trim().toLocaleLowerCase()
    return plans.filter(plan => {
      const statusMatches = planStatusFilter === 'ALL'
        || (planStatusFilter === 'OPEN' && plan.status === 'DRAFT' && !planExpired(plan))
        || (planStatusFilter === 'EXPIRED' && planExpired(plan))
        || plan.status === planStatusFilter
      return statusMatches && (!query || plan.name.toLocaleLowerCase().includes(query))
    })
  }, [plans, planListSearch, planStatusFilter])
  /**
   * 第一个还没排人的班次。卡片上的「去处理」要能直达它，否则用户跳过去还要自己翻时间轴找。
   * 用 shiftIds 减 assignees 算，比 requiredHeadcount - assignedCount 更接近真相（班次行才是实体）。
   */
  const firstUnassigned = useMemo(() => {
    let best: { shiftId: number; orderCode: string; startTime: string } | null = null
    for (const order of orders) {
      for (const phase of order.phases) {
        if (phase.status !== 'READY') continue
        const taken = new Set((phase.assignees ?? []).map(row => row.shiftId))
        const free = (phase.shiftIds ?? []).filter(id => !taken.has(id))
        if (free.length === 0) continue
        // 取**最早**的那条，而不是遍历到的第一条：订单列表按更新时间排序，
        // 直接取第一条会跳到将来很远的活，用户点"去处理"时预期是先干眼前这条。
        if (!best || phase.startTime < best.startTime) {
          best = { shiftId: free[0], orderCode: order.orderCode, startTime: phase.startTime }
        }
      }
    }
    return best
  }, [orders])
  const approvedOrderCount = orders.filter(order => order.status === 'APPROVED').length
  const draftPlanCount = plans.filter(plan => plan.status === 'DRAFT').length
  /**
   * 未排班次 = 还有几个名额空着。**按班次算而不是按方案算**：
   * 订单即使一张方案都没有，只要阶段是「就绪」，它的班次就已经物化进排班表了（employee_id 为空），
   * 这些才是真正待处理的活。「待排班草案」只数方案，草稿订单在这条口径里是隐形的。
   */
  const unassignedShiftCount = orders.reduce((orderSum, order) => orderSum + order.phases
    .filter(phase => phase.status === 'READY')
    .reduce((sum, phase) => sum + Math.max(0, phase.requiredHeadcount - (phase.assignedCount ?? 0)), 0), 0)
  const pendingOrderCount = orders.filter(order => order.status === 'SUBMITTED').length
  const executedPlanCount = plans.filter(plan => plan.status === 'EXECUTED').length
  const weightTotal = weightForm.ability + weightForm.location + weightForm.workload
    + weightForm.continuity + weightForm.age
  const candidateLookup = useMemo(() => {
    const result = new Map<string, Candidate>()
    orderCandidateResult?.phases.forEach(phase => phase.slots.forEach(slot =>
      slot.candidates.forEach(candidate => result.set(`${slot.shiftId}:${candidate.employeeId}`, candidate))))
    return result
  }, [orderCandidateResult])
  const candidateSlotLookup = useMemo(() => {
    const result = new Map<number, { phaseName: string; slotIndex: number; requiredRole: string }>()
    orderCandidateResult?.phases.forEach(phase => phase.slots.forEach(slot => result.set(slot.shiftId, {
      phaseName: `${phase.phaseCode} · ${phase.phaseName}`,
      slotIndex: slot.slotIndex,
      requiredRole: slot.requiredRole,
    })))
    return result
  }, [orderCandidateResult])
  useEffect(() => {
    const code = deepLinkOrder?.trim()
    if (!code || !structureId || lastDeepLinkOrder.current === code) return
    lastDeepLinkOrder.current = code
    setOrderCodeSearch(code)
    // 候选页：直接查好这一单的候选人；订单页：把列表收到这一单，再靠下面的 effect 展开。
    if (tab === 'candidates') void searchOrderCandidates(code)
    else setOrderListSearch(code)
    // 深链接只应在 URL 的订单号/页签变化时触发一次，函数本身不进依赖。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deepLinkOrder, tab, structureId])

  // 订单页深链接：orders 到位后自动展开目标订单，用户不用再点开找。
  useEffect(() => {
    const code = deepLinkOrder?.trim()
    if (!code || tab !== 'orders' || orders.length === 0) return
    const target = orders.find(order => order.orderCode === code)
    if (!target) return
    setExpandedOrderIds(prev => prev.includes(String(target.id)) ? prev : [String(target.id)])
  }, [deepLinkOrder, tab, orders])

  if (!structureId) return <Alert variant="warning">{tx("请先选择部门。")}</Alert>

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    try { await action(); toast.success(success); await reload(true) }
    catch (error) { toast.error(workflowErrorText(error)) }
    finally { setBusy(false) }
  }

  async function openPlan(plan: SchedulePlan) {
    setBusy(true)
    try { const detail = await workflowApi.plan(structureId!, plan.id); setPlan(detail); void loadPlanHistory(plan.id) }
    catch (error) { toast.error(workflowErrorText(error)) }
    finally { setBusy(false) }
  }

  async function openCandidates(assignment: PlanAssignment) {
    if (!selectedPlan) return
    setEmergencyReplacement(false)
    setBusy(true); setCandidateAssignment(assignment)
    try { setCandidates(await workflowApi.candidates(structureId!, selectedPlan.id, assignment.id, candidateLimit)) }
    catch (error) { setCandidateAssignment(null); toast.error(workflowErrorText(error)) }
    finally { setBusy(false) }
  }

  async function openExclusions(orderCode: string, phaseId: number, slotIndex: number, risk: string) {
    if (!structureId) return
    setExclusionsView({ orderCode, phaseId, slotIndex, risk })
    setExclusions(null)
    setExclusionsBusy(true)
    try { setExclusions(await workflowApi.orderCandidateExclusions(structureId, orderCode, phaseId, slotIndex, risk)) }
    catch (error) { setExclusionsView(null); toast.error(workflowErrorText(error)) }
    finally { setExclusionsBusy(false) }
  }

  async function openProfile(employeeId: number) {
    if (!structureId) return
    setProfileEmployeeId(employeeId)
    setProfile(null)
    setProfileShifts([])
    setProfileBusy(true)
    try {
      const [profileData, shiftData] = await Promise.all([
        workflowApi.employeeProfile(structureId, employeeId),
        workflowApi.employeeShifts(structureId, employeeId),
      ])
      setProfile(profileData)
      setProfileShifts(shiftData.shifts)
    }
    catch (error) { setProfileEmployeeId(null); toast.error(workflowErrorText(error)) }
    finally { setProfileBusy(false) }
  }

  async function startEmergencyReplacement(assignment: PlanAssignment) {
    if (!selectedPlan || selectedPlan.status !== 'EXECUTED') return
    setBusy(true)
    try {
      const revision = await workflowApi.revise(structureId!, selectedPlan.id)
      const target = revision.assignments?.find(row => row.shiftId === assignment.shiftId)
      if (!target) throw new Error(tx('修订版本中未找到对应名额'))
      setPlan(revision); setCandidateAssignment(target)
      setChangeReasonCode(tx('临时外派变化')); setChangeReason(''); setEmergencyReplacement(true)
      setCandidates(await workflowApi.candidates(structureId!, revision.id, target.id, candidateLimit))
      toast.success(tx('已创建新版本，请选择替补人员'))
      await reload(true)
    } catch (error) {
      setCandidateAssignment(null); setEmergencyReplacement(false)
      toast.error(workflowErrorText(error))
    } finally { setBusy(false) }
  }

  function startAddPhase(order: AuditOrder) {
    const value = emptyPhase(); value.locationId = locations[0]?.id ?? 0
    setEditingPhaseId(null); setPhaseForm(value); setPhaseOrder(order); setPhaseOriginal({})
  }

  function startEditPhase(order: AuditOrder, phase: AuditPhase) {
    setEditingPhaseId(phase.id)
    setPhaseOriginal({ locationId: phase.locationId, startTime: phase.startTime,
      endTime: phase.endTime, assignedCount: phase.assignedCount ?? 0 })
    setPhaseForm({
      phaseCode: phase.phaseCode, phaseName: phase.phaseName,
      startTime: phase.startTime.replace(' ', 'T').slice(0, 16),
      endTime: phase.endTime.replace(' ', 'T').slice(0, 16), locationId: phase.locationId,
      requiredHeadcount: phase.requiredHeadcount, leadRequired: phase.leadRequired,
      continuityGroup: phase.continuityGroup || '', deliveryMode: phase.deliveryMode,
      status: phase.status, requiredSkills: phase.requiredSkills,
    })
    setPhaseOrder(order)
  }

  function closePhaseModal() {
    setPhaseOrder(null); setEditingPhaseId(null); setPhaseForm(emptyPhase())
  }

  function startEditOrder(order: AuditOrder) {
    setOrderEditForm({
      orderCode: order.orderCode, customerName: order.customerName, projectName: order.projectName,
      status: order.status, priority: order.priority, ownerName: order.ownerName, notes: order.notes,
    })
    setEditingOrder(order)
  }

  /** 候选名单直接派工：只改一个班次，服务端会重算硬约束（名单不作数）。 */
  async function confirmAssignSlot() {
    if (!assignTarget) return
    const target = assignTarget
    setBusy(true)
    try {
      await workflowApi.assignSlot(structureId!, target.orderId, {
        shiftId: target.shiftId,
        employeeId: target.employeeId,
        reason: tx("候选名单第 {0} 名", [target.rank]),
      })
      toast.success(tx("{0} 已派到「{1} · 名额 {2}」，分配已生效", [target.name, target.phaseName, target.slotIndex]))
      setAssignTarget(null)
      void searchOrderCandidates(target.orderCode)
      void reload(true)
    } catch (error) { toast.error(workflowErrorText(error)) }
    finally { setBusy(false) }
  }


  async function searchOrderCandidates(code = orderCodeSearch) {
    const orderCode = code.trim()
    if (!orderCode) return
    setBusy(true); setOrderSearchError(''); setOrderCandidateResult(null); setAiReview(null)
    try { setOrderCandidateResult(await workflowApi.orderCandidates(structureId!, orderCode, candidateLimit)) }
    catch (error) {
      const status = (error as { status?: number }).status
      setOrderSearchError(status === 404 ? tx("未找到订单号“{0}”，请核对编号和当前部门。", [orderCode])
        : status === 409 ? tx('该订单已取消/完成，或没有处于“就绪”状态的阶段。请先在“订单与阶段”中检查状态。')
          : tx('候选人查询失败，请稍后重试。'))
    } finally { setBusy(false) }
  }

  async function runAiCandidateReview() {
    if (!orderCandidateResult || !aiStatus?.configured) return
    setAiBusy(true); setAiReview(null)
    try {
      setAiReview(await workflowApi.candidateAiReview(
        structureId!, orderCandidateResult.orderCode, candidateLimit,
      ))
      toast.success(tx('AI 初筛复核已完成，请结合规则评分人工确认'))
    } catch (error) {
      const code = errorCode(error)
      const message = code === 'AI_NOT_CONFIGURED' ? tx('AI 尚未配置，请由管理员在服务端设置环境变量并重启。')
        : code === 'AI_RATE_LIMITED' ? tx('AI 服务当前繁忙，请稍后重试。')
          : code === 'AI_PROVIDER_TIMEOUT' ? tx('AI 服务响应超时，请稍后重试。')
            : code === 'AI_RESPONSE_OUT_OF_SCOPE' ? tx('AI 返回了候选范围外的数据，系统已拒绝本次结果。')
              : tx('AI 初筛失败，原有规则候选结果不受影响。')
      toast.error(message)
    } finally { setAiBusy(false) }
  }

  async function createOrderPlan() {
    if (!orderCandidateResult) return
    setBusy(true)
    try {
      const plan = await workflowApi.createPlanFromOrder(
        structureId!, orderCandidateResult.orderId, tx("{0} 排程方案", [orderCandidateResult.orderCode]),
      )
      setPlan(plan); setTab('plans'); toast.success(tx('已生成仅包含该订单的排程方案（待定稿）'))
      await reload(true)
    } catch (error) { toast.error(workflowErrorText(error)) }
    finally { setBusy(false) }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-start mb-3">
        <div><h4 className="mb-1">{tx("审核员调配工作台")}</h4><div className="text-muted small">{tx("填写订单草案 → 提交审批 → 批准后排班求解 → 保存结果即定稿")}</div></div>
        <Badge bg="dark">{tx("部门 #{0}", [structureId])}</Badge>
      </div>
      <Row className="g-2 mb-3">
        <Col sm={6} lg={3}><Card className="h-100"><Card.Body className="py-2"><div className="text-muted small">{tx("已批准订单")}</div><div className="d-flex align-items-end justify-content-between"><strong className="fs-4">{approvedOrderCount}</strong><Button size="sm" variant="link" className="p-0" onClick={() => setTab('orders')}>{tx("查看订单")}</Button></div></Card.Body></Card></Col>
        {/* 主数字必须是"还有几个名额空着"：草稿订单没有方案，按方案数会显示 0 却仍有活没干。 */}
        <Col sm={6} lg={3}><Card className={`h-100 ${unassignedShiftCount ? 'border-warning' : ''}`}><Card.Body className="py-2"><div className="text-muted small">{tx("未排班次")}</div><div className="d-flex align-items-end justify-content-between"><strong className="fs-4">{unassignedShiftCount}</strong><Button size="sm" variant="link" className="p-0" title={firstUnassigned ? tx("直接打开 {0} 的待排名额", [firstUnassigned.orderCode]) : undefined} onClick={() => navigate(firstUnassigned ? `/shifts?shiftId=${firstUnassigned.shiftId}` : '/shifts')}>{tx("去处理")}</Button></div><div className="small text-muted">{draftPlanCount > 0 && <Button size="sm" variant="link" className="p-0 small" title={tx('方案是排人的工作台：人员锁定但还没定稿时停在这里')} onClick={() => { setPlanStatusFilter('DRAFT'); setTab('plans') }}>{tx("另有 {0}份方案待定稿", [draftPlanCount])}</Button>}</div></Card.Body></Card></Col>
        <Col sm={6} lg={3}><Card className={`h-100 ${pendingOrderCount ? 'border-warning' : ''}`}><Card.Body className="py-2"><div className="text-muted small">{tx("待审批订单")}</div><div className="d-flex align-items-end justify-content-between"><strong className="fs-4">{pendingOrderCount}</strong><Button size="sm" variant="link" className="p-0" onClick={() => setTab('orders')}>{tx("去审批")}</Button></div></Card.Body></Card></Col>
        <Col sm={6} lg={3}><Card className="h-100"><Card.Body className="py-2"><div className="text-muted small">{tx("已定稿方案")}</div><div className="d-flex align-items-end justify-content-between"><strong className="fs-4">{executedPlanCount}</strong><Button size="sm" variant="link" className="p-0" onClick={() => { setPlanStatusFilter('EXECUTED'); setTab('plans') }}>{tx("查看结果")}</Button></div></Card.Body></Card></Col>
      </Row>
      <Nav variant="tabs" activeKey={tab} onSelect={key => key && setTab(key as Tab)} className="mb-3">
        <Nav.Item><Nav.Link eventKey="candidates">{tx("按单号找候选")}</Nav.Link></Nav.Item>
        <Nav.Item><Nav.Link eventKey="orders">{tx("订单与阶段")}</Nav.Link></Nav.Item>
        <Nav.Item><Nav.Link eventKey="plans">{tx("排程方案")}</Nav.Link></Nav.Item>
        {isDispatcher && <Nav.Item><Nav.Link eventKey="imports">{tx("Excel 导入")}</Nav.Link></Nav.Item>}
        {isApprover && <Nav.Item><Nav.Link eventKey="audit">{tx("操作日志")}</Nav.Link></Nav.Item>}
      </Nav>
      {loading ? <div className="text-center py-5"><Spinner /></div> : null}

      {!loading && tab === 'candidates' && <>
        <Card className="mb-3 border-primary"><Card.Body>
          <Card.Title>{tx("输入订单号，直接查询候选人")}</Card.Title>
          <div className="text-muted mb-3">{tx("无需进入“排班管理”点 Solve。系统会针对订单的每个阶段和人员名额，实时过滤硬约束并按模拟权重排序。")}</div>
          <Row className="g-2 align-items-end">
            <Col md={7}><Form.Group><Form.Label>{tx("订单号")}</Form.Label><Form.Control autoFocus placeholder={tx('例如：ORD-TEST-0117')} value={orderCodeSearch}
              onChange={event => setOrderCodeSearch(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') void searchOrderCandidates() }} /></Form.Group></Col>
            <Col md={2}><Form.Group><Form.Label>{tx("每个名额显示")}</Form.Label><Form.Select value={candidateLimit} onChange={event => setCandidateLimit(Number(event.target.value))}>
              <option value={3}>Top 3</option><option value={5}>Top 5</option><option value={10}>Top 10</option><option value={20}>Top 20</option>
            </Form.Select></Form.Group></Col>
            <Col md={3}><Button className="w-100" disabled={busy || !orderCodeSearch.trim()} onClick={() => void searchOrderCandidates()}>{busy ? <><Spinner size="sm" className="me-2" />{tx("查询中")}</> : tx('查询最优候选人')}</Button></Col>
          </Row>
        </Card.Body></Card>
        <Card className="mb-3"><Card.Body className="py-2">
          <div className="d-flex justify-content-between align-items-center">
            <div><strong>{tx("部门评分规则 {0}", [weightProfile?.version ? `v${weightProfile.version}` : tx('（系统默认）')])}</strong>
              <span className="text-muted ms-3 small">{tx("能力 {0}% · 位置 {1}% · 空闲度 {2}% · 连续性 {3}% · 年龄 {4}%", [weightProfile?.ability ?? 40, weightProfile?.location ?? 30, weightProfile?.workload ?? 15, weightProfile?.continuity ?? 10, weightProfile?.age ?? 5])}</span>
            </div>
            {isDispatcher && <Button size="sm" variant="outline-secondary" onClick={() => setShowWeightEditor(value => !value)}>{showWeightEditor ? tx('收起') : tx('调整权重')}</Button>}
          </div>
          {weightProfile?.version ? <div className="text-muted small mt-1">{tx("最近调整：{0}· {1}· {2}", [weightProfile.changeReason, weightProfile.createdBy, formatDateTime(weightProfile.createdAt)])}</div> : <div className="text-muted small mt-1">{tx("尚未保存部门规则，当前使用可追溯的系统默认版本 v0。")}</div>}
          <div className="small mt-1">
            <Badge bg={aiStatus?.configured ? 'success' : 'secondary'} className="me-2">AI {aiStatus?.configured ? tx('已连接') : tx('未配置')}</Badge>
            {aiStatus?.configured
              ? tx("{0} / {1}，仅用于合格候选的二次初筛，不会自动分配人员。", [aiStatus.provider, aiStatus.model])
              : tx('规则匹配仍可正常使用；如需 AI 初筛，请由管理员在服务端配置 DISPATCH_AI_ENABLED 和 DISPATCH_AI_API_KEY。')}
          </div>
          {showWeightEditor && <Row className="g-2 align-items-end mt-1">
            {([['ability', tx('能力')], ['location', tx('位置')], ['workload', tx('空闲度')], ['continuity', tx('连续性')], ['age', tx('年龄')]] as const).map(([field, label]) => <Col md={1} key={field}><Form.Label>{label}</Form.Label><Form.Control type="number" min={0} max={100} value={weightForm[field]} onChange={event => setWeightForm({ ...weightForm, [field]: Number(event.target.value) })} /></Col>)}
            <Col md={5}><Form.Label>{tx("调整依据（必填）")}</Form.Label><Form.Control value={weightForm.changeReason} onChange={event => setWeightForm({ ...weightForm, changeReason: event.target.value })} placeholder={tx('例如：试点阶段提高跨城成本权重')} /></Col>
            <Col md={2}><Button className="w-100" disabled={busy || weightTotal !== 100 || !weightForm.changeReason.trim()} onClick={() => run(async () => { const updated = await workflowApi.updateCandidateWeights(structureId, weightForm); setWeightProfile(updated); setShowWeightEditor(false); if (orderCodeSearch.trim()) await searchOrderCandidates() }, tx('评分规则已保存为新版本'))}>{tx("保存新版本")}</Button></Col>
            <Col md={12}><span className={weightTotal === 100 ? 'text-success small' : 'text-danger small'}>{tx("当前合计 {0}%，必须等于 100%。保存后只影响新的候选查询，不会改写已有方案。", [weightTotal])}</span></Col>
          </Row>}
        </Card.Body></Card>
        {orderSearchError && <Alert variant="danger">{orderSearchError}</Alert>}
        {orderCandidateResult && <>
          <Alert variant="info">
            <strong>{orderCandidateResult.orderCode}</strong> {tx("· {0}· 优先级 {1}· {2}个阶段 · {3}个人员名额。评分规则 {4}：能力 {5}%、位置 {6}%、空闲度 {7}%、连续性 {8}%、年龄 {9}%。", [orderCandidateResult.projectName || orderCandidateResult.customerName || tx('未命名项目'), orderCandidateResult.priority, orderCandidateResult.phases.length, orderCandidateResult.phases.reduce((total, phase) => total + phase.slots.length, 0), orderCandidateResult.weightVersion ? ` v${orderCandidateResult.weightVersion}` : tx(' v0（系统默认）'), orderCandidateResult.weights.ability, orderCandidateResult.weights.location, orderCandidateResult.weights.workload, orderCandidateResult.weights.continuity, orderCandidateResult.weights.age])}{orderCandidateResult.phases.length > 0 && <span className="float-end d-flex gap-2">
              <Button size="sm" variant="outline-primary" disabled={!aiStatus?.configured || aiBusy}
                title={aiStatus?.configured ? tx('用 AI 对规则筛出的合格候选进行二次复核') : tx('需由管理员先配置服务端 AI 环境变量')}
                onClick={() => void runAiCandidateReview()}>
                {aiBusy ? <><Spinner size="sm" className="me-1" />{tx("AI 分析中")}</> : tx('AI 初筛复核')}
              </Button>
              {isDispatcher && <Button size="sm" disabled={busy} onClick={() => void createOrderPlan()}>{tx("生成该订单排程方案")}</Button>}
            </span>}
          </Alert>
          {aiReview && <Card className="mb-3 border-success"><Card.Header className="d-flex justify-content-between align-items-center">
            <strong>{tx("AI 初筛建议（仅供人工复核）")}</strong>
            <span className="text-muted small">{aiReview.provider} / {aiReview.model} · {formatDateTime(aiReview.generatedAt)}</span>
          </Card.Header><Card.Body>
            <Alert variant="success" className="py-2">{aiReview.overallSummary}</Alert>
            <Table responsive hover size="sm"><thead><tr><th>{tx("阶段 / 名额")}</th><th>{tx("AI 排名")}</th><th>{tx("候选人")}</th><th>{tx("置信度")}</th><th>{tx("AI 判断依据")}</th><th>{tx("注意事项")}</th></tr></thead><tbody>
              {aiReview.recommendations.map(recommendation => {
                const candidate = candidateLookup.get(`${recommendation.shiftId}:${recommendation.employeeId}`)
                const slot = candidateSlotLookup.get(recommendation.shiftId)
                return <tr key={`${recommendation.shiftId}:${recommendation.aiRank}`}>
                  <td>{slot?.phaseName ?? tx("名额 #{0}", [recommendation.shiftId])}<br/><small className="text-muted">{tx("名额 {0}· {1}", [slot?.slotIndex ?? '—', roleLabel(slot?.requiredRole ?? '')])}</small></td>
                  <td className="fw-bold">#{recommendation.aiRank}</td>
                  <td>{candidate ? `${candidate.code} · ${candidate.name}` : tx("审核员 #{0}", [recommendation.employeeId])}<br/><small className="text-muted">{tx("规则排名 #{0}· 综合分 {1}", [candidate?.rank ?? '—', candidate?.totalScore ?? '—'])}</small></td>
                  <td style={{ minWidth: 120 }}><ProgressBar now={recommendation.confidence} label={`${recommendation.confidence}%`} /></td>
                  <td>{recommendation.rationale}</td>
                  <td>{recommendation.cautions.length ? recommendation.cautions.join('；') : '—'}</td>
                </tr>
              })}
            </tbody></Table>
            {aiReview.warnings.length > 0 && <Alert variant="warning" className="mb-0 py-2">{aiReview.warnings.join('；')}</Alert>}
            <div className="text-muted small mt-2">{tx("AI 不能增加规则候选范围外的人员，也不能自动锁人、审批或定稿。最终选择仍须由调度员完成并通过定稿前硬约束复检。")}</div>
          </Card.Body></Card>}
          {orderCandidateResult.phases.length === 0 && <Alert variant="warning">{tx("该订单还没有阶段，先到“订单与阶段”添加时间、地点、人数和资格要求。")}</Alert>}
          {orderCandidateResult.phases.map(phase => <Card className="mb-3" key={phase.phaseId}><Card.Header>
            <strong>{phase.phaseCode} · {phase.phaseName}</strong><span className="text-muted ms-3">{tx("{0}至 {1}· {2}· {3}人", [formatDateTime(phase.startTime), formatDateTime(phase.endTime), locationNames.get(phase.locationId) ?? tx("地点 #{0}", [phase.locationId]), phase.requiredHeadcount])}</span>
          </Card.Header><Card.Body>
            {phase.slots.map(slot => <div key={slot.shiftId} className="mb-4">
              <h6>{tx("名额 {0}· {1}", [slot.slotIndex, slot.requiredRole === 'LEAD_AUDITOR' ? tx('主审核员') : tx('审核员')])}</h6>
              {slot.currentAssignee && <div className="small text-muted mb-1">{tx("当前人员（已定稿方案）：")}<Badge bg="info">{tx("当前人员")}</Badge> <Button variant="link" className="p-0 align-baseline" onClick={() => openProfile(slot.currentAssignee!.employeeId)}>{slot.currentAssignee.code} · {slot.currentAssignee.name}</Button>{!slot.candidates.some(candidate => candidate.employeeId === slot.currentAssignee!.employeeId) && tx('（因已有占用排分靠后，未进入下方推荐列表）')}</div>}
              <div className="small mb-2">{tx("已检查")}<strong>{slot.evaluatedCount}</strong> {tx("人，符合全部硬约束")}<strong className="text-success">{slot.eligibleCount}</strong> {tx("人。")}{Object.entries(slot.excludedCounts).map(([risk, count]) => <Button variant="outline-secondary" size="sm" className="ms-2 py-0" key={risk} title={tx("{0}（点击查看名单）", [tx(riskHints[risk] ?? '')])} onClick={() => openExclusions(orderCandidateResult.orderCode, phase.phaseId, slot.slotIndex, risk)}>{tx(riskLabels[risk] ?? risk)} {count} »</Button>)}
              </div>
              {slot.candidates.length === 0 ? <Alert variant="warning" className="py-2">{tx("当前没有同时满足资格、时间、状态、缓冲和转场要求的人员。")}</Alert> :
                <Table responsive hover size="sm"><thead><tr><th>{tx("排名")}</th><th>{tx("审核员")}</th><th>{tx("综合分")}</th><th>{tx("能力 / 位置 / 空闲度 / 连续 / 年龄")}</th><th>{tx("预计转场")}</th><th>{tx("为什么入选")}</th><th>{tx("操作")}</th></tr></thead><tbody>
                  {slot.candidates.map(candidate => <tr key={candidate.employeeId} className={candidate.siblingAssignment ? 'opacity-75' : undefined}><td className="fw-bold">#{candidate.rank}</td><td><Button variant="link" className="p-0 align-baseline" onClick={() => openProfile(candidate.employeeId)}>{candidate.code} · {candidate.name}</Button>{candidate.currentAssignee && <Badge bg="info" className="ms-1" title={tx('该名额当前由他承担（来自已定稿方案）')}>{tx("当前人员")}</Badge>}<br/><small className="text-muted" title={tx(operationalStatusHints[candidate.operationalStatus ?? ''] ?? '')} style={{ cursor: 'help' }}>{operationalStatusLabel(candidate.operationalStatus)}</small>{/* 常驻城市：与右侧「预计转场」配合，一眼看出是从哪调过来的。 */}<br/><small className="text-muted">{candidate.currentLocationName ? tx("常驻 {0}", [candidate.currentLocationName]) : tx('常驻地未登记')}</small>{/* 出发地与常驻地不同：说明这次是按「上一任务所在地」算的转场（返程空窗期内）。 */}{candidate.departureFromPreviousTask && candidate.departureLocationName && <><br/><small className="text-info">{tx("从 {0}出发", [candidate.departureLocationName])}</small></>}{/* 被其他草案锁定占用：只提示不拦截，由调度员判断换人还是先去处理那份草案。 */}{candidate.preemptedBy && <><br/><small className="text-warning" title={tx("时间段 {0} ~ {1}", [candidate.preemptedBy.startTime, candidate.preemptedBy.endTime])}>{tx("⚠ 已被「{0}」预占", [candidate.preemptedBy.planName])}</small></>}{candidate.siblingAssignment && <><br/><small className="text-secondary" title={tx("该班次 {0} ~ {1}", [candidate.siblingAssignment.startTime, candidate.siblingAssignment.endTime])}>{tx("已在本单「{0}· 名额 {1}」", [candidate.siblingAssignment.phaseName, candidate.siblingAssignment.slotIndex])}</small></>}</td><td><Badge bg="primary">{candidate.totalScore}</Badge></td><td>{candidate.abilityScore} / {candidate.locationScore} / {candidate.workloadScore} / {candidate.continuityScore} / {candidate.ageScore}</td><td>{tx("{0}分钟", [candidate.travelMinutes])}</td><td><small>{candidateReasonLines(candidate).map(reason => <div key={reason}>{reason}</div>)}</small></td><td><Button size="sm" variant="outline-primary" disabled={busy} title={tx('直接派到这个名额（立即写入班次）')} onClick={() => setAssignTarget({ orderId: orderCandidateResult.orderId, orderCode: orderCandidateResult.orderCode, shiftId: slot.shiftId, slotIndex: slot.slotIndex, phaseCode: phase.phaseCode, phaseName: phase.phaseName, startTime: phase.startTime, endTime: phase.endTime, locationName: locationNames.get(phase.locationId) ?? '', employeeId: candidate.employeeId, code: candidate.code, name: candidate.name, rank: candidate.rank, totalScore: candidate.totalScore })}>{tx("选择")}</Button></td></tr>)}
                </tbody></Table>}
            </div>)}
          </Card.Body></Card>)}
          <Alert variant="secondary" className="small">{tx("这些是供人工审核的实时推荐，不会自动占用人员。“生成该订单排程方案”会从全部未分配开始，不沿用测试工作簿中的模拟既有人员；请逐个名额从当前合格候选中锁定，定稿前系统会再次校验。")}</Alert>
        </>}
      </>}

      {!loading && tab === 'orders' && <>
        {isDispatcher && <Card className="mb-3"><Card.Body>
          <Card.Title className="fs-6">{tx("新建审核订单")}</Card.Title>
          <Row className="g-2">
            <Col md={2}><Form.Control placeholder={tx('订单编号*')} title={tx('必填')} value={orderForm.orderCode} onChange={e => setOrderForm({ ...orderForm, orderCode: e.target.value })} /></Col>
            <Col md={2}><Form.Control placeholder={tx('客户*')} title={tx('必填')} value={orderForm.customerName} onChange={e => setOrderForm({ ...orderForm, customerName: e.target.value })} /></Col>
            <Col md={3}><Form.Control placeholder={tx('项目名称*')} title={tx('必填')} value={orderForm.projectName} onChange={e => setOrderForm({ ...orderForm, projectName: e.target.value })} /></Col>
            <Col md={1}><Form.Control placeholder={tx('负责人')} value={orderForm.ownerName} onChange={e => setOrderForm({ ...orderForm, ownerName: e.target.value })} /></Col>
            <Col md={2}><Form.Select value={orderForm.priority} title={tx('优先级：1 最高，5 最低（仅作标记，暂不参与排序）')} onChange={e => setOrderForm({ ...orderForm, priority: Number(e.target.value) })}>{[[1, tx('1 最高')], [2, tx('2 高')], [3, tx('3 中')], [4, tx('4 低')], [5, tx('5 最低')]].map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Form.Select></Col>
            <Col md={2}><Button className="w-100" disabled={busy || !orderForm.orderCode.trim() || !orderForm.customerName.trim() || !orderForm.projectName.trim()} title={!orderForm.orderCode.trim() ? tx('请填写订单编号') : !orderForm.customerName.trim() ? tx('请填写客户') : !orderForm.projectName.trim() ? tx('请填写项目名称') : undefined} onClick={() => run(async () => { await workflowApi.createOrder(structureId, orderForm); setOrderForm(emptyOrder()) }, tx('订单已创建'))}>{tx("创建订单")}</Button></Col>
          </Row>
        </Card.Body></Card>}
        <Card className="mb-3"><Card.Body className="py-2"><Row className="align-items-center g-2">
          <Col md={8}><Form.Control aria-label={tx('搜索订单')} placeholder={tx('搜索订单号、客户、项目或负责人')} value={orderListSearch} onChange={event => setOrderListSearch(event.target.value)} /></Col>
          <Col md={4} className="text-md-end text-muted small">{tx("显示 {0}/ {1}个订单", [filteredOrders.length, orders.length])}</Col>
        </Row></Card.Body></Card>
        {orders.length === 0 ? <Alert variant="light">{tx("暂无订单，可先通过 Excel 导入或手工创建。")}</Alert> :
          filteredOrders.length === 0 ? <Alert variant="light">{tx("没有匹配“{0}”的订单。", [orderListSearch])}</Alert> :
          <Accordion alwaysOpen activeKey={expandedOrderIds} onSelect={key => {
            // alwaysOpen 下 key 可能是数组；重复的 key 由 riskLoadedOrders 去重。
            const keys = Array.isArray(key) ? key : key ? [key] : []
            setExpandedOrderIds(keys as string[])
            for (const one of keys) void loadOrderRisks(Number(one))
          }}>{filteredOrders.map(order => <Accordion.Item eventKey={String(order.id)} key={order.id}>
            <Accordion.Header><span className="fw-semibold me-2">{order.orderCode}</span>{order.projectName || order.customerName}<Badge bg={statusColor(order.status)} className="ms-2">{orderStatusLabel(order.status)}</Badge><AssignmentBadge order={order} /><span className="text-muted ms-3 small">{order.phases.length === 1 ? tx("1 个阶段") : tx("{0}个阶段", [order.phases.length])}</span></Accordion.Header>
            <Accordion.Body>
              <div className="d-flex justify-content-between mb-2"><span className="text-muted">{tx("客户：{0}· 负责人：{1}· 优先级：{2}", [order.customerName || '—', order.ownerName || '—', order.priority])}{order.submittedAt && <span className="ms-2">{tx("· 提交于 {0}", [formatDateTime(order.submittedAt)])}</span>}
                {order.approvedAt && <span className="ms-2">{tx("· {0}批准于 {1}", [order.approvedBy || '—', formatDateTime(order.approvedAt)])}</span>}
                {order.status === 'REJECTED' && order.rejectReason && <span className="ms-2 text-danger">{tx("· 退回原因：{0}", [order.rejectReason])}</span>}
              </span><div>
                {/* 审批在订单上：草稿/退回可提交，待审批可批准或退回；只有已批准才能排班 */}
                {isDispatcher && ['DRAFT', 'REJECTED'].includes(order.status) && <Button size="sm" variant="warning" className="me-2" disabled={busy}
                  onClick={() => run(async () => { await workflowApi.submitOrder(structureId, order.id) }, tx('订单已提交审批'))}>{tx("提交审批")}</Button>}
                {isApprover && order.status === 'SUBMITTED' && <Button size="sm" variant="success" className="me-2" disabled={busy}
                  onClick={() => run(async () => { await workflowApi.approveOrder(structureId, order.id) }, tx('订单已批准'))}>{tx("批准")}</Button>}
                {isApprover && order.status === 'SUBMITTED' && <Button size="sm" variant="outline-danger" className="me-2" disabled={busy}
                  onClick={() => { setRejectOrder(order); setRejectOrderReason('') }}>{tx("退回")}</Button>}
                <Button size="sm" variant="outline-primary" className="me-2"
                  disabled={order.status !== 'APPROVED' || !order.phases.some(phase => phase.status === 'READY')}
                  title={order.status !== 'APPROVED' ? tx('只有已批准的订单可以查候选') : tx('订单至少需要一个就绪阶段')}
                  onClick={() => { setOrderCodeSearch(order.orderCode); setTab('candidates'); void searchOrderCandidates(order.orderCode) }}>{tx("查候选")}</Button>
                {isDispatcher && <Button size="sm" variant="outline-secondary" className="me-2" onClick={() => startEditOrder(order)}>{tx("编辑订单")}</Button>}
                {isDispatcher && <Button size="sm" onClick={() => startAddPhase(order)}>{tx("添加阶段")}</Button>}
              </div></div>
              <Table responsive size="sm" bordered><thead><tr><th>{tx("阶段")}</th><th>{tx("时间")}</th><th>{tx("地点")}</th><th title={tx('左边是已分配人数，右边是名额')}>{tx("已排 / 名额")}</th><th>{tx("审核员")}</th><th>{tx("组长")}</th><th>{tx("资格")}</th><th>{tx("状态")}</th><th>{tx("操作")}</th></tr></thead><tbody>
                {order.phases.map(phase => <tr key={phase.id}><td>{phase.phaseCode}<br/><span className="text-muted">{phase.phaseName}</span></td><td>{formatDateTime(phase.startTime)}<br/>{formatDateTime(phase.endTime)}</td><td>{locationNames.get(phase.locationId) ?? `#${phase.locationId}`}</td><td className={!phase.assignedCount ? 'text-muted' : phase.assignedCount >= phase.requiredHeadcount ? 'text-success' : 'text-warning'}><span className="fw-semibold">{phase.assignedCount ?? 0}</span> / {phase.requiredHeadcount}</td><td>{phase.assignees && phase.assignees.length > 0
                  ? <>{phase.assignees.map(assignee => {
                    // 名单是静态的，风险是现在算的：改阶段、证书过期、临时请假都会让人"在位但不合格"。
                    const risk = orderRisks[order.id]?.[assignee.shiftId]
                    return <div key={assignee.shiftId} className="text-nowrap">
                      <Button size="sm" variant="link" className={`p-0 align-baseline${risk ? ' text-danger' : ''}`} title={assignee.code ? tx("工号 {0}", [assignee.code]) : undefined} onClick={() => void openProfile(assignee.employeeId)}>{assignee.name || assignee.code || `#${assignee.employeeId}`}</Button>
                      {risk && <><br/><small className="text-danger" title={tx(riskHints[risk] ?? '')}>{riskLabel(risk)}</small></>}
                    </div>
                  })}
                    {(phase.assignedCount ?? 0) < phase.requiredHeadcount && <div className="text-warning small">{tx("还缺 {0}个名额", [phase.requiredHeadcount - (phase.assignedCount ?? 0)])}</div>}</>
                  : <span className="text-muted">{tx("未分配")}</span>}</td><td>{phase.leadRequired ? tx('需要') : tx('否')}</td><td>{phase.requiredSkills.map(required => skills.find(skill => skill.id === required.skillId)?.name ?? `#${required.skillId}`).join('、') || '—'}</td><td><Badge bg={statusColor(phase.status)}>{statusLabel(phase.status)}</Badge></td><td>{isDispatcher && <Button size="sm" variant="outline-secondary" onClick={() => startEditPhase(order, phase)}>{tx("编辑")}</Button>}</td></tr>)}
              </tbody></Table>
            </Accordion.Body>
          </Accordion.Item>)}</Accordion>}
      </>}

      {!loading && tab === 'plans' && <>
        {isDispatcher && <Card className="mb-3"><Card.Body>
          <Card.Title className="fs-6 mb-1">{tx("把当前排班存为快照")}</Card.Title>
          <div className="text-muted small mb-2">
            {tx("不改变任何班次，只把所选窗口内的现有安排（含人员）复制成一份可追溯的方案版本。 与「按订单生成草案」相反：那个会清空人员等你逐个锁定。")}</div>
          {/* 窗口取的是"有交集"的班次，不是完全落在窗内的：跨边界的多天现场任务也会被收进来，
              所以条数常常大于窗口内完整班次的数量——这里说明一句，避免对不上数时误以为是 bug。 */}
          <Row className="g-2">
          <Col md={4}><Form.Control value={planForm.name} onChange={e => setPlanForm({ ...planForm, name: e.target.value })} /></Col>
          <Col md={3}><Form.Control type="datetime-local" value={planForm.windowStart} onChange={e => setPlanForm({ ...planForm, windowStart: e.target.value })} /></Col>
          <Col md={3}><Form.Control type="datetime-local" value={planForm.windowEnd} onChange={e => setPlanForm({ ...planForm, windowEnd: e.target.value })} /></Col>
          <Col md={2}><Button className="w-100" disabled={busy} onClick={() => run(async () => {
            const plan = await workflowApi.createPlan(structureId, planForm)
            // A window snapshot copies today's assignments as-is; surface immediately how many
            // of them are currently unusable instead of letting the dispatcher discover it row by row.
            if (plan.riskCount > 0) toast(tx("这份快照含 {0} 个有风险的名额（不可用/资格/冲突等），已在风险列标出——这是当前排班的真实状态，定稿前需逐项处理", [plan.riskCount]), { icon: '⚠️', duration: 6000 })
          }, tx('快照已生成（同一窗口再次点击会打开已存在的快照，不会重复创建）'))}>{tx("存为快照")}</Button></Col>
        </Row></Card.Body></Card>}
        <Card className="mb-3"><Card.Body className="py-2"><Row className="g-2">
          <Col md={8}><Form.Control aria-label={tx('搜索排程方案')} placeholder={tx('搜索方案名称')} value={planListSearch} onChange={event => setPlanListSearch(event.target.value)} /></Col>
          <Col md={4}><Form.Select aria-label={tx('按状态筛选排程方案')} value={planStatusFilter} onChange={event => setPlanStatusFilter(event.target.value)}>
            <option value="OPEN">{tx("待处理（仅草案）")}</option><option value="DRAFT">{tx("仅草案")}</option><option value="EXECUTED">{tx("仅已定稿")}</option><option value="SUPERSEDED">{tx("仅已被替代")}</option><option value="EXPIRED">{tx("已过期（窗口已结束）")}</option><option value="ALL">{tx("全部状态")}</option>
          </Form.Select></Col>
        </Row></Card.Body></Card>
        <Table responsive hover bordered size="sm"><thead><tr><th>{tx("方案")}</th><th>{tx("来源")}</th><th>{tx("创建时间")}</th><th>{tx("窗口")}</th><th>{tx("版本")}</th><th>{tx("状态")}</th><th>{tx("名额/缺口/风险")}</th><th>{tx("操作")}</th></tr></thead><tbody>
          {filteredPlans.map(plan => <tr key={plan.id} className={planExpired(plan) ? 'table-light' : undefined}><td>{plan.name}<br/><small className="text-muted">{plan.createdBy}</small></td><td title={tx(planSourceHints[plan.source ?? 'WINDOW'] ?? '')} style={{ cursor: 'help', textDecoration: 'underline dotted' }}>{tx(planSourceLabels[plan.source ?? 'WINDOW'] ?? plan.source)}</td><td>{formatDateTime(plan.createdAt)}</td><td>{formatDateTime(plan.windowStart)}<br/>{formatDateTime(plan.windowEnd)}</td><td>v{plan.revision}</td><td><Badge bg={statusColor(plan.status)} title={plan.status === 'SUPERSEDED' ? supersededNote(plan, plans) : undefined}>{planStatusLabel(plan.status)}</Badge>{planExpired(plan) && <Badge bg="secondary" className="ms-1" title={tx('排班窗口结束时间已过，该方案不会再进入后续流程')}>{tx("已过期")}</Badge>}</td><td>{plan.assignmentCount} / <span className="text-danger">{plan.gapCount}</span> / <span className="text-warning">{plan.riskCount}</span>{plan.source === 'ORDER' && plan.gapCount > 0 && plan.gapCount === plan.assignmentCount && <><br/><small className="text-muted">{tx("尚未分配人员")}</small></>}</td><td><Button size="sm" variant="outline-primary" onClick={() => openPlan(plan)}>{tx("查看")}</Button></td></tr>)}
          {filteredPlans.length === 0 && <tr><td colSpan={8} className="text-center text-muted py-4">{tx("没有符合当前筛选条件的排程方案。")}</td></tr>}
          </tbody></Table>
          {isApprover && visiblePlanHistory.length > 0 && <Card className="mt-3" body>
            <div className="fw-bold mb-2">{tx("方案操作记录")}</div>
            <Table responsive size="sm" className="mb-0"><thead><tr><th>{tx("时间")}</th><th>{tx("操作")}</th><th>{tx("操作人")}</th><th>{tx("详情")}</th></tr></thead><tbody>
              {visiblePlanHistory.map(event => <tr key={event.id}>
                <td className="text-nowrap">{formatDateTime(event.createdAt)}</td>
                <td className="text-nowrap">{tx(planActionLabels[event.action] ?? event.action)}</td>
                <td className="text-nowrap">{event.actor}</td>
                <td><small className="text-muted">{planEventDetail(event.details)}</small></td>
              </tr>)}
            </tbody></Table>
          </Card>}
        </>}

      {!loading && tab === 'imports' && isDispatcher && <>
        <Card className="mb-3"><Card.Body><Row className="align-items-end g-3"><Col md={3}><Button variant="outline-secondary" onClick={() => download(workflowApi.downloadTemplate(), tx('审核员调配导入模板.xlsx'))}>{tx("下载固定模板")}</Button></Col><Col md={6}><Form.Group><Form.Label>{tx("上传 XLSX 预检查")}</Form.Label><Form.Control type="file" accept=".xlsx" onChange={async event => { const file = (event.currentTarget as HTMLInputElement).files?.[0]; if (!file) return; setBusy(true); try { setPreview(await workflowApi.uploadWorkbook(structureId, file)) } catch { toast.error(tx('预检查失败')) } finally { setBusy(false) } }} /></Form.Group></Col><Col md={3}>{busy && <Spinner size="sm" />}</Col></Row></Card.Body></Card>
        {preview && <Card className="mb-3"><Card.Body><div className="d-flex justify-content-between"><div><strong>{preview.fileName}</strong> {tx("· {0}行 ·", [preview.rowCount])}<span className="text-danger">{tx("{0}错误", [preview.errorCount])}</span> · <span className="text-warning">{tx("{0}警告", [preview.warningCount])}</span></div><div><Button size="sm" variant="outline-secondary" className="me-2" onClick={() => download(workflowApi.downloadErrors(structureId, preview.token), tx('导入错误.csv'))}>{tx("下载错误")}</Button><Button size="sm" variant="success" disabled={!preview.confirmable || busy} onClick={() => run(async () => { await workflowApi.confirmImport(structureId, preview.token); setPreview(null) }, tx('导入已确认'))}>{tx("确认导入")}</Button></div></div>
          {preview.issues.length > 0 && <Table responsive size="sm" className="mt-3"><thead><tr><th>{tx("工作表")}</th><th>{tx("行")}</th><th>{tx("字段")}</th><th>{tx("级别")}</th><th>{tx("问题")}</th></tr></thead><tbody>{preview.issues.slice(0, 100).map((issue, index) => <tr key={`${issue.sheet}-${issue.row}-${index}`}><td>{issue.sheet}</td><td>{issue.row}</td><td>{issue.field}</td><td><Badge bg={issue.severity === 'ERROR' ? 'danger' : 'warning'}>{issue.severity === 'ERROR' ? tx('错误') : tx('警告')}</Badge></td><td>{issue.message} ({issue.code})</td></tr>)}</tbody></Table>}
        </Card.Body></Card>}
        <Table responsive bordered size="sm"><thead><tr><th>{tx("文件")}</th><th>{tx("状态")}</th><th>{tx("行数")}</th><th>{tx("错误/警告")}</th><th>{tx("操作者")}</th><th>{tx("时间")}</th></tr></thead><tbody>{imports.map(job => <tr key={job.token}><td>{job.fileName}</td><td><Badge bg={statusColor(job.status)}>{statusLabel(job.status)}</Badge></td><td>{job.rowCount}</td><td>{job.errorCount}/{job.warningCount}</td><td>{job.createdBy}</td><td>{formatDateTime(job.createdAt)}</td></tr>)}</tbody></Table>
      </>}

      {!loading && tab === 'audit' && isApprover && <Card><Card.Body className="py-2"><div className="d-flex justify-content-between align-items-center mb-2"><strong>{tx("系统操作日志")}</strong><span className="text-muted small">{tx("仅显示最近 300 条记录，按时间正序排列")}</span></div><Table responsive hover bordered size="sm" className="mb-0"><thead><tr><th>{tx("时间")}</th><th>{tx("操作人")}</th><th>{tx("动作")}</th><th>{tx("对象")}</th><th>{tx("详情")}</th></tr></thead><tbody>{events.map(event => <tr key={event.id}><td className="text-nowrap">{formatDateTime(event.createdAt)}</td><td className="text-nowrap">{event.actor}</td><td className="text-nowrap">{tx(planActionLabels[event.action] ?? event.action)}</td><td className="text-nowrap">{auditEntityLabels[event.entityType] ?? event.entityType} #{event.entityId}</td><td><small className="text-muted">{planEventDetail(event.details)}</small></td></tr>)}{events.length === 0 && <tr><td colSpan={5} className="text-center text-muted py-4">{tx("暂无操作记录。")}</td></tr>}</tbody></Table></Card.Body></Card>}

      <Modal show={editingOrder !== null} onHide={() => setEditingOrder(null)} size="lg"><Modal.Header closeButton><Modal.Title>{tx("编辑订单 · {0}", [editingOrder?.orderCode])}</Modal.Title></Modal.Header><Modal.Body><Row className="g-3">
        <Col md={4}><Form.Label>{tx("订单号")}</Form.Label><Form.Control value={orderEditForm.orderCode} onChange={e => setOrderEditForm({ ...orderEditForm, orderCode: e.target.value })} /></Col>
        <Col md={4}><Form.Label>{tx("客户")}<span className="text-danger">*</span></Form.Label><Form.Control value={orderEditForm.customerName} onChange={e => setOrderEditForm({ ...orderEditForm, customerName: e.target.value })} /></Col>
        <Col md={4}><Form.Label>{tx("负责人")}</Form.Label><Form.Control value={orderEditForm.ownerName} onChange={e => setOrderEditForm({ ...orderEditForm, ownerName: e.target.value })} /></Col>
        <Col md={6}><Form.Label>{tx("项目名称")}<span className="text-danger">*</span></Form.Label><Form.Control value={orderEditForm.projectName} onChange={e => setOrderEditForm({ ...orderEditForm, projectName: e.target.value })} /></Col>
        <Col md={3}><Form.Label>{tx("优先级")}</Form.Label><Form.Select value={orderEditForm.priority} title={tx('优先级：1 最高，5 最低（仅作标记，暂不参与排序）')} onChange={e => setOrderEditForm({ ...orderEditForm, priority: Number(e.target.value) })}>{[[1, tx('1 最高')], [2, tx('2 高')], [3, tx('3 中')], [4, tx('4 低')], [5, tx('5 最低')]].map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Form.Select></Col>
        <Col md={3}><Form.Label>{tx("订单状态")}</Form.Label><Form.Select value={orderEditForm.status} onChange={e => setOrderEditForm({ ...orderEditForm, status: e.target.value })}><option value="DRAFT">{tx("草稿")}</option><option value="ACTIVE">{tx("进行中")}</option><option value="COMPLETED">{tx("已完成")}</option><option value="CANCELLED">{tx("已取消")}</option></Form.Select></Col>
        <Col md={12}><Form.Label>{tx("备注")}</Form.Label><Form.Control as="textarea" rows={2} value={orderEditForm.notes} onChange={e => setOrderEditForm({ ...orderEditForm, notes: e.target.value })} /></Col>
        {orderEditForm.status === 'CANCELLED' && <Col md={12}><Alert variant="warning" className="mb-0 py-2">{tx("取消后，该订单不能再查询候选人或生成新排程；历史方案和操作记录仍会保留。")}</Alert></Col>}
      </Row></Modal.Body><Modal.Footer><Button variant="secondary" onClick={() => setEditingOrder(null)}>{tx("放弃修改")}</Button><Button disabled={busy || !editingOrder || !orderEditForm.orderCode.trim() || !orderEditForm.customerName.trim() || !orderEditForm.projectName.trim()} title={!orderEditForm.orderCode.trim() ? tx('请填写订单号') : !orderEditForm.customerName.trim() ? tx('请填写客户') : !orderEditForm.projectName.trim() ? tx('请填写项目名称') : undefined} onClick={() => run(async () => { await workflowApi.updateOrder(structureId, editingOrder!.id, orderEditForm); setEditingOrder(null); await reload(true) }, tx('订单已更新'))}>{tx("保存订单")}</Button></Modal.Footer></Modal>

      <Modal show={phaseOrder !== null} onHide={closePhaseModal} size="lg"><Modal.Header closeButton><Modal.Title>{editingPhaseId ? tx('编辑订单阶段') : tx('添加订单阶段')} · {phaseOrder?.orderCode}</Modal.Title></Modal.Header><Modal.Body>
        {phaseAffectsAssigned && (
          <Alert variant="warning" className="py-2 small mb-3">
            {tx("⚠ 该阶段已有 {0}个名额分配了人员。修改{1}{2}{3}会把这些班次一起改掉，但", [phaseOriginal.assignedCount, phaseLocationChanged ? tx('地点') : '', phaseLocationChanged && phaseTimeChanged ? tx('或') : '', phaseTimeChanged ? tx('时间') : ''])}<b>{tx("不会自动重新核对")}</b>{tx("这些人是否还适合新地点/新时段。请先确认是否需要重新调整分配。")}</Alert>
        )}
        <Row className="g-3">
        <Col md={4}><Form.Label>{tx("阶段编号")}</Form.Label><Form.Control value={phaseForm.phaseCode} onChange={e => setPhaseForm({ ...phaseForm, phaseCode: e.target.value })} /></Col><Col md={8}><Form.Label>{tx("阶段名称")}</Form.Label><Form.Control value={phaseForm.phaseName} onChange={e => setPhaseForm({ ...phaseForm, phaseName: e.target.value })} /></Col>
        <Col md={6}><Form.Label>{tx("开始")}</Form.Label><Form.Control type="datetime-local" value={phaseForm.startTime} onChange={e => setPhaseForm({ ...phaseForm, startTime: e.target.value })} /></Col><Col md={6}><Form.Label>{tx("结束")}</Form.Label><Form.Control type="datetime-local" value={phaseForm.endTime} onChange={e => setPhaseForm({ ...phaseForm, endTime: e.target.value })} /></Col>
        <Col md={5}><Form.Label>{tx("地点")}</Form.Label><Form.Select value={phaseForm.locationId} onChange={e => setPhaseForm({ ...phaseForm, locationId: Number(e.target.value) })}>{locations.map(location => <option value={location.id} key={location.id}>{location.name}</option>)}</Form.Select></Col><Col md={3}><Form.Label>{tx("人数")}</Form.Label><Form.Control type="number" min={1} max={100} value={phaseForm.requiredHeadcount} onChange={e => setPhaseForm({ ...phaseForm, requiredHeadcount: Number(e.target.value) })} /></Col><Col md={4} className="pt-4"><Form.Check label={tx('首个名额要求主审核员')} checked={phaseForm.leadRequired} onChange={e => setPhaseForm({ ...phaseForm, leadRequired: e.target.checked })} /></Col>
        <Col md={4}><Form.Label>{tx("连续阶段组")}</Form.Label><Form.Control value={phaseForm.continuityGroup} onChange={e => setPhaseForm({ ...phaseForm, continuityGroup: e.target.value })} placeholder={tx('可留空；如 A1')} /><Form.Text muted>{tx("同一订单内填了相同组名的阶段：同一名额序号会保持同一名审核员（保证审核团队连续）。留空则各阶段独立分配。")}</Form.Text></Col>
        <Col md={4}><Form.Label>{tx("执行方式")}</Form.Label><Form.Select value={phaseForm.deliveryMode} onChange={e => setPhaseForm({ ...phaseForm, deliveryMode: e.target.value })}><option value="ONSITE">{tx("现场")}</option><option value="REMOTE">{tx("远程")}</option><option value="HYBRID">{tx("混合")}</option></Form.Select></Col>
        <Col md={4}><Form.Label>{tx("阶段状态")}</Form.Label><Form.Select value={phaseForm.status} onChange={e => setPhaseForm({ ...phaseForm, status: e.target.value })}><option value="PLANNED">{tx("待就绪")}</option><option value="READY">{tx("就绪")}</option><option value="COMPLETED">{tx("已完成")}</option><option value="CANCELLED">{tx("已取消")}</option></Form.Select></Col>
        <Col md={12}><Form.Label>{tx("所需资格（点击勾选，可多选）")}</Form.Label>
          <div className="border rounded p-2 d-flex flex-wrap gap-2" style={{ maxHeight: 150, overflowY: 'auto' }}>
            {skills.filter(skill => skill.active).map(skill => {
              const checked = phaseForm.requiredSkills.some(item => item.skillId === skill.id)
              return <Form.Check key={skill.id} type="checkbox" id={`phase-skill-${skill.id}`} label={skill.name} checked={checked} className="me-3"
                onChange={e => setPhaseForm({ ...phaseForm, requiredSkills: e.target.checked
                  ? [...phaseForm.requiredSkills, { skillId: skill.id, minimumCount: phaseForm.requiredHeadcount, leadOnly: false }]
                  : phaseForm.requiredSkills.filter(item => item.skillId !== skill.id) })} />
            })}
            {skills.filter(skill => skill.active).length === 0 && <span className="text-muted small">{tx("暂无可用资格，请先在基础数据中维护。")}</span>}
          </div>
        </Col>
        {phaseForm.status === 'CANCELLED' && <Col md={12}><Alert variant="warning" className="mb-0 py-2">{tx("取消后，该阶段不再进入候选查询和新排程，历史方案仍会保留。")}</Alert></Col>}
      </Row></Modal.Body><Modal.Footer><Button variant="secondary" onClick={closePhaseModal}>{tx("放弃修改")}</Button><Button disabled={busy || !phaseOrder || !phaseForm.phaseCode || !phaseForm.locationId} onClick={() => run(async () => { if (editingPhaseId) await workflowApi.updatePhase(structureId, editingPhaseId, phaseForm); else await workflowApi.createPhase(structureId, phaseOrder!.id, phaseForm); closePhaseModal(); await reload(true) }, editingPhaseId ? tx('阶段已更新') : tx('阶段已创建'))}>{tx("保存阶段")}</Button></Modal.Footer></Modal>

      <Modal show={selectedPlan !== null} onHide={() => setPlan(null)} size="xl"><Modal.Header closeButton><Modal.Title>{selectedPlan?.name} · v{selectedPlan?.revision} <Badge bg={statusColor(selectedPlan?.status ?? '')}>{planStatusLabel(selectedPlan?.status ?? '')}</Badge></Modal.Title></Modal.Header><Modal.Body>
        {selectedPlan && <>
          <div className="d-flex align-items-center gap-2 mb-3 flex-wrap" aria-label={tx('方案流程进度')}>
            {planStepOrder.map((step, index) => {
              const currentIndex = planStepOrder.indexOf(selectedPlan.status)
              const superseded = selectedPlan.status === 'SUPERSEDED'
              const active = selectedPlan.status === step
              const done = !superseded && currentIndex > index
              return <Fragment key={step}>{index > 0 && <span className="text-muted">→</span>}
                <Badge bg={active ? 'primary' : done ? 'success' : 'light'} text={active || done ? undefined : 'dark'}
                  className={active || done ? 'px-3 py-2' : 'px-3 py-2 opacity-50'}>{tx(planStepLabels[step] ?? step)}</Badge>
              </Fragment>
            })}
            {selectedPlan.status === 'SUPERSEDED' && <Badge bg="secondary" className="px-3 py-2">{tx("已被新版本替代")}</Badge>}
          </div>
          {selectedPlan.status === 'SUPERSEDED' && <Alert variant="secondary" className="py-2 small">{supersededNote(selectedPlan, plans)}</Alert>}
          <Alert variant={selectedPlan.gapCount || selectedPlan.riskCount ? 'warning' : 'success'}>
            <div className="d-flex justify-content-between mb-2"><strong>{tx("人员完成度")}</strong><span>{tx("已分配 {0}/ {1}· 风险 {2}", [selectedPlan.assignmentCount - selectedPlan.gapCount, selectedPlan.assignmentCount, selectedPlan.riskCount])}</span></div>
            <ProgressBar now={selectedPlan.assignmentCount ? (selectedPlan.assignmentCount - selectedPlan.gapCount) * 100 / selectedPlan.assignmentCount : 0} variant={selectedPlan.gapCount || selectedPlan.riskCount ? 'warning' : 'success'} />
            {/* 方案标题里只有订单号，光看表格不知道这单要去哪；把涉及的城市先列出来。 */}
            <div className="small mt-2">
              {planLocations.length > 0
                ? <><span className="text-muted">{tx("涉及地点：")}</span>{planLocations.join('、')}</>
                : <span className="text-muted">{tx("涉及地点：待分配")}</span>}
            </div>
            <div className="small mt-1">{tx("定稿时会重新校验资格、人员状态、时间冲突、缓冲和版本；有缺口或风险时不能定稿。")}</div>
          </Alert>
          {planExpired(selectedPlan) && selectedPlan.status === 'DRAFT' && <Alert variant="secondary" className="py-2 small">{tx("窗口已于 {0}结束，不能再定稿；如需继续请新建草案。", [formatDateTime(selectedPlan.windowEnd)])}</Alert>}
          {selectedPlan.rejectionReason && <Alert variant="secondary" className="py-2 small"><strong>{tx("历史退回记录：")}</strong>{tx("{0}（旧流程遗留，仅供参考）", [selectedPlan.rejectionReason])}</Alert>}
          <Table responsive bordered size="sm"><thead><tr><th>{tx("订单/阶段")}</th><th>{tx("时间")}</th><th>{tx("地点")}</th><th>{tx("名额")}</th><th>{tx("审核员")}</th><th>{tx("角色")}</th><th>{tx("风险")}</th><th>{tx("操作")}</th></tr></thead><tbody>{selectedPlan.assignments?.map(assignment => <tr key={assignment.id} className={!assignment.employeeId || (assignment.riskCode && assignment.riskCode !== 'UNASSIGNED') ? 'table-warning' : ''}><td>{assignment.auditOrderCode}<br/>{assignment.auditStage}</td><td>{formatDateTime(assignment.startTime)}<br/>{formatDateTime(assignment.endTime)}</td><td className="text-nowrap">{locationNames.get(assignment.locationId) ?? tx("地点 #{0}", [assignment.locationId])}</td><td>{assignment.slotIndex}</td><td>{assignment.employeeName || <span className="text-danger">{tx("未分配")}</span>}</td><td>{roleLabel(assignment.assignmentRole)}{assignment.locked ? ' 🔒' : ''}</td><td title={assignment.riskCode && assignment.riskCode !== 'UNASSIGNED' ? tx(riskHints[assignment.riskCode] ?? '') : ''} style={{ cursor: assignment.riskCode && assignment.riskCode !== 'UNASSIGNED' ? 'help' : undefined }}>{assignment.riskCode ? riskLabel(assignment.riskCode) : '—'}</td><td>{isDispatcher && selectedPlan.status === 'DRAFT' && <Button size="sm" variant="outline-primary" onClick={() => openCandidates(assignment)}>{assignment.employeeId ? tx('换人/重新锁定') : tx('选择并锁定')}</Button>}{isDispatcher && selectedPlan.status === 'EXECUTED' && <Button size="sm" variant="outline-danger" disabled={busy} onClick={() => void startEmergencyReplacement(assignment)}>{tx("紧急换人")}</Button>}</td></tr>)}</tbody></Table>
        </>}
      </Modal.Body><Modal.Footer>{selectedPlan && <>
        <Button variant="outline-secondary" onClick={() => download(workflowApi.exportPlanExcel(structureId, selectedPlan.id), tx("排程方案-{0}-v{1}.xlsx", [selectedPlan.id, selectedPlan.revision]))}>{tx("导出 Excel")}</Button>
        {isDispatcher && selectedPlan.status === 'DRAFT' && <Button variant="primary" disabled={busy || planExpired(selectedPlan) || selectedPlan.gapCount > 0 || selectedPlan.riskCount > 0} title={planExpired(selectedPlan) ? tx('窗口已结束：该草案无法定稿，请新建草案') : selectedPlan.gapCount ? tx("还有 {0} 个名额未分配", [selectedPlan.gapCount]) : selectedPlan.riskCount ? tx("还有 {0} 项风险未解决", [selectedPlan.riskCount]) : undefined} onClick={() => void openPublishConfirm(selectedPlan.id)}>{tx("定稿")}</Button>}
        {isDispatcher && ['EXECUTED', 'SUPERSEDED'].includes(selectedPlan.status) && <Button variant="outline-primary" disabled={busy} onClick={() => run(async () => { setPlan(await workflowApi.revise(structureId, selectedPlan.id)) }, tx("已创建新版本 v{0}，可在该版本上继续调整", [selectedPlan.revision + 1]))}>{tx("新建修订")}</Button>}
        {isDispatcher && selectedPlan.status === 'DRAFT' && <Button variant="outline-danger" disabled={busy} onClick={() => setDeleteConfirmOpen(true)} title={tx('删除该草案及其分配快照；已定稿的方案不可删除')}>{tx("删除草案")}</Button>}
        {selectedPlan.status === 'EXECUTED' && <span className="text-muted small me-auto">{tx("PDF 请在“Report”按正式排程导出")}</span>}
      </>}<Button variant="secondary" onClick={() => setPlan(null)}>{tx("关闭")}</Button></Modal.Footer></Modal>

      <Modal show={candidateAssignment !== null} onHide={() => { setCandidateAssignment(null); setEmergencyReplacement(false) }} size="xl"><Modal.Header closeButton><Modal.Title>{emergencyReplacement ? tx('紧急换人') : tx('选择合格候选人')}{candidateAssignment ? tx(" · {0} / {1} / 名额 {2} / {3}", [candidateAssignment.auditOrderCode, candidateAssignment.auditStage, candidateAssignment.slotIndex, locationNames.get(candidateAssignment.locationId) ?? tx("地点 #{0}", [candidateAssignment.locationId])]) : ''}</Modal.Title></Modal.Header><Modal.Body>{emergencyReplacement && <Alert variant="warning">{tx("系统已从已定稿方案创建可编辑的新版本。选定替补后，改完需重新定稿；原版本在新版本定稿前继续有效。")}</Alert>}<div className="d-flex gap-3 align-items-end mb-3"><Form.Group><Form.Label>{tx("候选人数")}</Form.Label><Form.Select value={candidateLimit} onChange={async e => { const value = Number(e.target.value); setCandidateLimit(value); if (candidateAssignment) setCandidates(await workflowApi.candidates(structureId!, selectedPlan!.id, candidateAssignment.id, value)) }}><option value={3}>Top 3</option><option value={5}>Top 5</option><option value={10}>Top 10</option></Form.Select></Form.Group><Form.Group><Form.Label>{tx("选择原因（必填）")}</Form.Label><Form.Select value={changeReasonCode} onChange={e => setChangeReasonCode(e.target.value)}><option>{tx("负责人确认")}</option><option>{tx("客户或项目连续性")}</option><option>{tx("行业经验更匹配")}</option><option>{tx("语言能力匹配")}</option><option>{tx("转场更方便")}</option><option>{tx("工作量平衡")}</option><option>{tx("临时外派变化")}</option><option>{tx("候选人本人不可参加")}</option><option>{tx("系统评分不符合实际")}</option><option>{tx("其他")}</option></Form.Select></Form.Group><Form.Group className="flex-grow-1"><Form.Label>{tx("补充说明（可选）")}</Form.Label><Form.Control value={changeReason} onChange={e => setChangeReason(e.target.value)} placeholder={tx('补充具体业务依据')} /></Form.Group></div><Alert variant="info" className="py-2">{tx("只显示已通过资格、状态、时间冲突、缓冲和转场硬约束的人员。当前部门评分规则{0}：能力 {1}%、位置 {2}%、空闲度 {3}%、连续性 {4}%、年龄 {5}%；空闲度按排班表全局占用计算（含历史、其他方案与本方案已排小时），分数越高越空闲，年龄尚未采集时统一按中性50分处理。", [weightProfile?.version ? ` v${weightProfile.version}` : tx(' v0（系统默认）'), weightProfile?.ability ?? 40, weightProfile?.location ?? 30, weightProfile?.workload ?? 15, weightProfile?.continuity ?? 10, weightProfile?.age ?? 5])}</Alert><Table responsive hover size="sm"><thead><tr><th>{tx("排名")}</th><th>{tx("审核员")}</th><th>{tx("综合分")}</th><th>{tx("能力/位置/空闲度/连续/年龄")}</th><th>{tx("预计转场")}</th><th>{tx("评分说明")}</th><th></th></tr></thead><tbody>{candidates.map(candidate => <tr key={candidate.employeeId}><td>#{candidate.rank}</td><td><Button variant="link" className="p-0 align-baseline" onClick={() => openProfile(candidate.employeeId)}>{candidate.code} · {candidate.name}</Button>{candidate.currentAssignee && <Badge bg="info" className="ms-1" title={tx('该名额当前由他承担')}>{tx("当前人员")}</Badge>}<br/><small className="text-muted" title={tx(operationalStatusHints[candidate.operationalStatus ?? ''] ?? '')} style={{ cursor: 'help' }}>{operationalStatusLabel(candidate.operationalStatus)}</small>{/* 常驻城市：与右侧「预计转场」配合，一眼看出是从哪调过来的。 */}<br/><small className="text-muted">{candidate.currentLocationName ? tx("常驻 {0}", [candidate.currentLocationName]) : tx('常驻地未登记')}</small>{/* 出发地与常驻地不同：说明这次是按「上一任务所在地」算的转场（返程空窗期内）。 */}{candidate.departureFromPreviousTask && candidate.departureLocationName && <><br/><small className="text-info">{tx("从 {0}出发", [candidate.departureLocationName])}</small></>}{/* 被其他草案锁定占用：只提示不拦截，由调度员判断换人还是先去处理那份草案。 */}{candidate.preemptedBy && <><br/><small className="text-warning" title={tx("时间段 {0} ~ {1}", [candidate.preemptedBy.startTime, candidate.preemptedBy.endTime])}>{tx("⚠ 已被「{0}」预占", [candidate.preemptedBy.planName])}</small></>}{candidate.siblingAssignment && <><br/><small className="text-secondary" title={tx("该班次 {0} ~ {1}", [candidate.siblingAssignment.startTime, candidate.siblingAssignment.endTime])}>{tx("已在本单「{0}· 名额 {1}」", [candidate.siblingAssignment.phaseName, candidate.siblingAssignment.slotIndex])}</small></>}</td><td><Badge bg="primary">{candidate.totalScore}</Badge></td><td>{candidate.abilityScore} / {candidate.locationScore} / {candidate.workloadScore} / {candidate.continuityScore} / {candidate.ageScore}</td><td>{tx("{0}分钟", [candidate.travelMinutes])}</td><td><small>{candidateReasonLines(candidate).map(reason => <div key={reason}>{reason}</div>)}</small></td><td><Button size="sm" disabled={!changeReasonCode || busy} onClick={() => run(async () => { const reason = changeReason.trim() ? `${changeReasonCode}：${changeReason.trim()}` : changeReasonCode; await workflowApi.updateAssignment(structureId, selectedPlan!.id, candidateAssignment!.id, { employeeId: candidate.employeeId, locked: true, assignmentRole: candidateAssignment!.assignmentRole, changeReason: reason }); setPlan(await workflowApi.plan(structureId, selectedPlan!.id)); setCandidateAssignment(null); setEmergencyReplacement(false); setChangeReason('') }, emergencyReplacement ? tx('替补人员已锁定，请重新定稿') : tx('人员已替换并锁定'))}>{tx("选择")}</Button></td></tr>)}</tbody></Table>{candidates.length === 0 && <Alert variant="warning">{tx("没有满足全部硬约束的候选人。请返回订单检查资格要求、人员不可用时间、地点或任务时间。")}</Alert>}</Modal.Body></Modal>

      {/* 候选名单「选择」的确认：派工立即写入班次，所以把对象与后果写清楚再落库。 */}
      <Modal show={assignTarget !== null} onHide={() => setAssignTarget(null)} centered>
        <Modal.Header closeButton><Modal.Title>{tx("确认派工")}</Modal.Title></Modal.Header>
        <Modal.Body>{assignTarget && <>
          <p className="mb-2">{tx("即将把")}<strong>{assignTarget.name}</strong>{tx("（{0}）派到：", [assignTarget.code])}</p>
          <ul className="mb-3">
            <li>{tx("订单：{0}", [assignTarget.orderCode])}</li>
            <li>{tx("阶段：{0}· {1}{2}", [assignTarget.phaseCode, assignTarget.phaseName, assignTarget.locationName ? ` · ${assignTarget.locationName}` : ''])}</li>
            <li>{tx("时间：{0}～ {1}", [formatDateTime(assignTarget.startTime), formatDateTime(assignTarget.endTime)])}</li>
            <li>{tx("名额：{0}· 候选排名 #{1}· 综合分 {2}", [assignTarget.slotIndex, assignTarget.rank, assignTarget.totalScore])}</li>
          </ul>
          <Alert variant="info" className="py-2 mb-0 small">
            {tx("写入后立即生效：这个人马上出现在排班表上，并计入他的负荷与后续冲突检查。 服务端会再核对一遍资格、不可用时间、时间冲突与转场缓冲。")}</Alert>
        </>}</Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setAssignTarget(null)}>{tx("取消")}</Button>
          <Button variant="primary" disabled={busy} onClick={() => void confirmAssignSlot()}>{tx("确认派工")}</Button>
        </Modal.Footer>
      </Modal>

      <Modal show={publishConfirmOpen} onHide={() => setPublishConfirmOpen(false)} centered>
        <Modal.Header closeButton><Modal.Title>{tx("确认定稿方案")}</Modal.Title></Modal.Header>
        <Modal.Body>{selectedPlan && <>
          <p className="mb-2">{tx("即将定稿：")}<strong>{selectedPlan.name}</strong> · v{selectedPlan.revision}</p>
          <ul className="mb-3">
            <li>{tx("时间窗：{0}～ {1}", [formatDateTime(selectedPlan.windowStart), formatDateTime(selectedPlan.windowEnd)])}</li>
            {publishImpact && publishImpact.orderCodes.length > 0 && <li>{tx("覆盖订单：{0}", [publishImpact.orderCodes.join('、')])}</li>}
            <li>{tx("将写入 {0}个名额的人员分配（写入后即刻生效）", [selectedPlan.assignmentCount])}</li>
            {/* 只有「分配到同一个班次」的旧方案才会被取代——不同订单在同一时间由不同的人干活是常态，
                各写各的班次，谈不上覆盖。列表由后端 supersede-preview 给出，前端不再自己按时间窗估算。 */}
            {supersededOnPublish.length > 0 && <li className="text-danger">
              {tx("本方案的部分班次已由 {0}个已定稿方案写过（{1}）。定稿后它们将标记为「已被新版本替代」；本方案只改写这些重叠的班次，它们在其余班次上的安排继续有效", [supersededOnPublish.length, supersededOnPublish.map(p => p.name).join('、')])}</li>}
          </ul>
          <Alert variant="warning" className="py-2 mb-0 small">{tx("定稿前系统会自动完成一次安全备份；定稿后方案不可再编辑，如需调整请使用「新建修订」。")}</Alert>
        </>}</Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setPublishConfirmOpen(false)}>{tx("取消")}</Button>
          <Button variant="primary" disabled={busy || !selectedPlan} onClick={() => {
            if (!selectedPlan) return
            const planId = selectedPlan.id
            const supersededCount = supersededOnPublish.length
            setPublishConfirmOpen(false)
            void run(async () => { setPlan(await workflowApi.publish(structureId, planId)) },
              supersededCount > 0 ? tx("方案已定稿；{0} 个重叠旧方案已标记为「已被新版本替代」", [supersededCount]) : tx('方案已定稿'))
          }}>{tx("确认定稿")}</Button>
        </Modal.Footer>
      </Modal>

      <Modal show={rejectOrder !== null} onHide={() => setRejectOrder(null)} centered>
        <Modal.Header closeButton><Modal.Title>{tx("退回订单")}</Modal.Title></Modal.Header>
        <Modal.Body>
          <p className="mb-2">{tx("即将退回：")}<strong>{rejectOrder?.orderCode}</strong></p>
          <div className="small text-muted mb-2">{tx("退回后订单进入「已退回」状态，可修改后重新提交；原因会记入操作日志。")}</div>
          <Form.Control as="textarea" rows={3} value={rejectOrderReason} onChange={e => setRejectOrderReason(e.target.value)} placeholder={tx('请填写退回原因（必填），例如：时间窗与客户需求不符')} autoFocus />
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setRejectOrder(null)}>{tx("取消")}</Button>
          <Button variant="danger" disabled={busy || !rejectOrderReason.trim()} onClick={() => {
            const target = rejectOrder
            const reason = rejectOrderReason.trim()
            setRejectOrder(null)
            if (!target) return
            void run(async () => { await workflowApi.rejectOrder(structureId, target.id, reason) }, tx('订单已退回'))
          }}>{tx("确认退回")}</Button>
        </Modal.Footer>
      </Modal>

      <Modal show={deleteConfirmOpen} onHide={() => setDeleteConfirmOpen(false)} centered>
        <Modal.Header closeButton><Modal.Title>{tx("删除草案")}</Modal.Title></Modal.Header>
        <Modal.Body>{selectedPlan && <>
          <p className="mb-2">{tx("即将删除：")}<strong>{selectedPlan.name}</strong> · v{selectedPlan.revision}</p>
          <ul className="mb-3">
            <li>{tx("共 {0}个名额的分配快照将一并删除", [selectedPlan.assignmentCount])}</li>
            <li>{tx("删除动作会记入操作日志")}</li>
          </ul>
          <Alert variant="danger" className="py-2 mb-0 small">{tx("删除后无法恢复；如需留档请先导出 Excel。仅「草案」可以删除，已定稿或已被替代的方案需保留记录。")}</Alert>
        </>}</Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setDeleteConfirmOpen(false)}>{tx("取消")}</Button>
          <Button variant="danger" disabled={busy || !selectedPlan} onClick={() => {
            if (!selectedPlan) return
            const planId = selectedPlan.id
            const planName = selectedPlan.name
            setDeleteConfirmOpen(false)
            void run(async () => { await workflowApi.removePlan(structureId, planId); setPlan(null) }, tx("草案「{0}」已删除", [planName]))
          }}>{tx("确认删除")}</Button>
        </Modal.Footer>
      </Modal>

      <Modal show={exclusionsView !== null} onHide={() => setExclusionsView(null)} size="lg" scrollable>
        <Modal.Header closeButton><Modal.Title>{tx("不满足人员明细 · {0}", [exclusionsView ? tx(riskLabels[exclusionsView.risk] ?? exclusionsView.risk) : ''])}</Modal.Title></Modal.Header>
        <Modal.Body>
          {exclusionsBusy && <div className="text-muted py-3"><Spinner size="sm" animation="border" className="me-2" />{tx("正在计算...")}</div>}
          {!exclusionsBusy && exclusions && <>
            <div className="small text-muted mb-2">{tx("共 {0}人被此项硬约束排除。点击姓名可查看个人档案。", [exclusions.count])}</div>
            {exclusions.employees.length === 0 ? <Alert variant="warning" className="py-2">{tx("没有记录（人员状态可能刚刚变化，请重试）。")}</Alert> :
              <Table responsive hover size="sm"><thead><tr><th style={{ width: 180 }}>{tx("审核员")}</th><th>{tx("具体原因")}</th></tr></thead><tbody>
                {exclusions.employees.map(employee => <tr key={employee.employeeId}>
                  <td><Button variant="link" className="p-0 align-baseline" onClick={() => openProfile(employee.employeeId)}>{employee.code} · {employee.name}</Button></td>
                  <td className="small">{employee.detail}</td>
                </tr>)}
              </tbody></Table>}
          </>}
        </Modal.Body>
        <Modal.Footer><Button variant="secondary" onClick={() => setExclusionsView(null)}>{tx("关闭")}</Button></Modal.Footer>
      </Modal>

      <Modal show={profileEmployeeId !== null} onHide={() => setProfileEmployeeId(null)} size="lg" scrollable>
        <Modal.Header closeButton><Modal.Title>{tx("审核员档案{0}", [profile ? ` · ${profile.code}` : ''])}</Modal.Title></Modal.Header>
        <Modal.Body>
          {profileBusy && <div className="text-muted py-3"><Spinner size="sm" animation="border" className="me-2" />{tx("正在加载档案...")}</div>}
          {!profileBusy && profile && <>
            <div className="d-flex gap-3 align-items-end mb-3 flex-wrap">
              <div><div className="text-muted small">{tx("姓名")}</div><strong>{profile.firstName} {profile.lastName}</strong></div>
              <div><div className="text-muted small">{tx("编号")}</div>{profile.code}</div>
              <div><div className="text-muted small">{tx("状态")}</div>{operationalStatusLabel(profile.operationalStatus)}{profile.active ? '' : tx('（已停用）')}</div>
              <div><div className="text-muted small">{tx("当前所在地")}</div>{profile.currentLocationId ? locationNames.get(profile.currentLocationId) ?? tx("地点 #{0}", [profile.currentLocationId]) : tx('未设置')}</div>
              <div><div className="text-muted small">{tx("外派/忙碌至")}</div>{profile.busyUntil ?? '—'}</div>
              <div><div className="text-muted small">{tx("转场缓冲")}</div>{tx("前 {0}分 / 后 {1}分", [profile.bufferBeforeMinutes, profile.bufferAfterMinutes])}</div>
            </div>
            <h6 className="mt-3">{tx("资格证书（{0}）", [profile.qualifications.length])}</h6>
            {profile.qualifications.length === 0 ? <div className="text-muted small">{tx("暂无资格记录")}</div> :
              <Table responsive hover size="sm"><thead><tr><th>{tx("资格")}</th><th>{tx("证书编号")}</th><th>{tx("有效期")}</th><th>{tx("状态")}</th></tr></thead><tbody>
                {profile.qualifications.map((qualification, index) => <tr key={index}>
                  <td>{skills.find(skill => skill.id === qualification.skillId)?.name ?? tx("资格 #{0}", [qualification.skillId])}</td>
                  <td>{qualification.certificateNumber ?? '—'}</td>
                  <td>{qualification.validFrom ?? '?'} ~ {qualification.validUntil ?? '?'}</td>
                  <td>{qualification.status ?? '—'}</td>
                </tr>)}
              </tbody></Table>}
            <Row className="mt-3 g-3">
              {([[tx('期望日期'), profile.desiredDates, 'success'], [tx('不希望日期'), profile.undesiredDates, 'warning'], [tx('不可用日期'), profile.unavailableDates, 'danger']] as const).map(([label, dates, variant]) => <Col md={4} key={label}>
                <h6>{label}（{dates.length}）</h6>
                {dates.length === 0 ? <div className="text-muted small">{tx("无")}</div> :
                  dates.map(date => <Badge bg={variant} className="me-1 mb-1 d-inline-block" key={date.id}>{date.dateStart.slice(0, 16)} ~ {date.dateEnd.slice(0, 16)}</Badge>)}
              </Col>)}
            </Row>
            <h6 className="mt-4">{tx("已承担任务（{0}）", [profileShifts.length])}<span className="text-muted fw-normal small ms-2">{tx("来自已定稿方案的实际排班，选人时按“时间冲突”硬约束处理")}</span></h6>
            {profileShifts.length === 0 ? <div className="text-muted small">{tx("当前没有已排定的任务")}</div> :
              <Table responsive hover size="sm"><thead><tr><th>{tx("时间段")}</th><th>{tx("订单 / 阶段")}</th><th>{tx("地点")}</th><th>{tx("时长")}</th><th>{tx("状态")}</th></tr></thead><tbody>
                {profileShifts.map(shift => {
                  const start = new Date(shift.startTime.replace(' ', 'T'))
                  const end = new Date(shift.endTime.replace(' ', 'T'))
                  const now = new Date()
                  const state = now < start ? [tx('未开始'), 'secondary'] : now > end ? [tx('已结束'), 'light'] : [tx('进行中'), 'success']
                  const hours = Math.round((end.getTime() - start.getTime()) / 36000) / 100
                  return <tr key={shift.shiftId}>
                    <td>{shift.startTime.slice(0, 16)} ~ {shift.endTime.slice(0, 16)}</td>
                    <td>{shift.auditOrderCode} · {shift.auditStage}</td>
                    <td>{locationNames.get(shift.locationId) ?? tx("地点 #{0}", [shift.locationId])}</td>
                    <td>{tx("{0}小时", [hours])}</td>
                    <td><Badge bg={state[1]} text={state[1] === 'light' ? 'dark' : undefined}>{state[0]}</Badge></td>
                  </tr>
                })}
              </tbody></Table>}
            <h6 className="mt-4">{tx("简历")}</h6>
            <Alert variant="secondary" className="py-2 small mb-0">{tx("简历暂未上传。后续版本将支持上传与预览审核员简历（PDF/图片），此处为预留入口。")}</Alert>
          </>}
        </Modal.Body>
        <Modal.Footer><Button variant="secondary" onClick={() => setProfileEmployeeId(null)}>{tx("关闭")}</Button></Modal.Footer>
      </Modal>
    </div>
  )
}
