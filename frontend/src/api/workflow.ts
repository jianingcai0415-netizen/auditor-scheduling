import { api, downloadBlob, rawFetch } from './client'

export interface SkillRequirement { skillId: number; minimumCount: number; leadOnly: boolean }
export interface AuditPhase {
  id: number; auditOrderId: number; phaseCode: string; phaseName: string; startTime: string; endTime: string
  locationId: number; requiredHeadcount: number; leadRequired: boolean; continuityGroup: string
  deliveryMode: string; status: string; requiredSkills: SkillRequirement[]; shiftIds: number[]
  /** 已分配名额数；改地点/改时间时用它提示"这些安排会被覆盖"。 */
  assignedCount?: number
  /** 已分配到本阶段名额的审核员（未分配就是空数组）。只给人数看不出"排了谁"，所以连名单一起返回。 */
  assignees?: { shiftId: number; employeeId: number; code: string | null; name: string | null }[]
}
export interface AuditOrder {
  id: number; structureId: number; orderCode: string; customerName: string; projectName: string
  status: string; priority: number; ownerName: string; notes: string; updatedAt: string; phases: AuditPhase[]
  /** 审批轨迹：审批单元是订单，排班方案不再承载审批。 */
  submittedAt?: string | null; approvedAt?: string | null; approvedBy?: string | null; rejectReason?: string
}
export interface AuditOrderInput {
  orderCode: string; customerName: string; projectName: string; status: string
  priority: number; ownerName: string; notes: string
}
export interface AuditPhaseInput {
  phaseCode: string; phaseName: string; startTime: string; endTime: string; locationId: number
  requiredHeadcount: number; leadRequired: boolean; continuityGroup: string; deliveryMode: string
  status: string; requiredSkills: SkillRequirement[]
}
export interface PlanAssignment {
  id: number; shiftId: number; phaseId?: number; slotIndex: number; employeeId?: number
  employeeName?: string; locked: boolean; assignmentRole: string; riskCode: string; changeReason: string
  startTime: string; endTime: string; locationId: number; auditOrderCode: string; auditStage: string
}
export interface SchedulePlan {
  id: number; structureId: number; name: string; windowStart: string; windowEnd: string; status: string
  revision: number; parentPlanId?: number; solverScore?: string; createdBy: string; submittedBy?: string
  approvedBy?: string; publishedBy?: string; rejectionReason: string; createdAt: string; updatedAt: string
  publishedAt?: string; source?: string; supersededByPlanId?: number
  assignmentCount: number; gapCount: number; riskCount: number; assignments?: PlanAssignment[]
}
/**
 * @brief 一条评分理由。
 * @details code 决定用哪条文案模板，其余字段是模板参数；分支（同城/坐标缺失/超基准）
 *          由后端判定，前端只负责按占位符拼句子，避免两边各算一遍算出不同结论。
 */
export interface CandidateReasonItem {
  code: 'ability' | 'location' | 'workload' | 'continuity' | 'age'
  score: number
  validCount?: number; matched?: number; required?: number
  noHome?: boolean; fromPreviousTask?: boolean; origin?: string | null; task?: string | null
  minutes?: number; sameCity?: boolean; missingCoords?: boolean; overBaseline?: boolean
  baselineHours?: number; gapHours?: number; previousEnd?: string | null; returnToBaseHours?: number
  hours?: number; ownHours?: number | null; saturated?: boolean
  state?: 'NONE' | 'SAME' | 'OTHER'
}

export interface Candidate {
  employeeId: number; code: string; name: string; eligible: boolean; risk: string
  currentAssignee?: boolean
  operationalStatus: string; travelMinutes: number; rank: number; totalScore: number
  abilityScore: number; locationScore: number; workloadScore: number
  continuityScore: number; ageScore: number; ageDataAvailable: boolean
  plannedHours: number; reasons: string[]
  /** 结构化评分理由（后端算好的分支与数字），前端按语言渲染；老接口只有 reasons 时退回纯文本。 */
  reasonItems?: CandidateReasonItem[]
  /** 审核员的**常驻地**（不是本次出发地；出发地见 departureLocationName）。null = 未登记。 */
  currentLocationName?: string | null
  /** 本名额的任务地点，与 currentLocationName 配合可判断跨城调度。 */
  taskLocationName?: string | null
  /**
   * 已被其他「草案」锁定占用（时间与本名额重叠）。只做提示、不拦截：
   * 草案是半成品，调度员需要自己判断是换人还是先去处理那份草案。
   */
  preemptedBy?: { planId: number; planName: string; startTime: string; endTime: string } | null
  /**
   * 本订单**其他名额**已经排了他。候选只对已落库的班次判冲突，同一订单里还没保存的空名额
   * 互相看不见，所以同一个人会同时出现在几个名额的候选里。前端据此置灰提示，避免重复点同一人。
   */
  siblingAssignment?: {
    shiftId: number; phaseCode: string; phaseName: string; slotIndex: number
    startTime: string; endTime: string
  } | null
  /**
   * 本次计算的**出发地**（不是常驻地）。上一任务结束未满「返程空窗期」时取其所在地，
   * 否则取常驻地。与后端转场硬约束同源，两处口径一致。
   */
  departureLocationName?: string | null
  /** true = 出发地来自上一任务所在地；false = 来自常驻地。 */
  departureFromPreviousTask?: boolean
}
export interface OrderCandidateSlot {
  shiftId: number; slotIndex: number; requiredRole: string; requiredSkillIds: number[]
  evaluatedCount: number; eligibleCount: number; excludedCounts: Record<string, number>; candidates: Candidate[]
  currentAssignee?: { employeeId: number; code: string; name: string } | null
}
export interface OrderCandidatePhase {
  phaseId: number; phaseCode: string; phaseName: string; startTime: string; endTime: string
  locationId: number; requiredHeadcount: number; leadRequired: boolean; slots: OrderCandidateSlot[]
}
export interface OrderCandidateResult {
  orderId: number; orderCode: string; customerName: string; projectName: string; status: string
  priority: number; topK: number; weights: Record<string, number>; weightVersion: number
  weightSource: 'DEFAULT' | 'PROFILE' | 'QUERY_OVERRIDE'; phases: OrderCandidatePhase[]
}
export interface ExcludedEmployee { employeeId: number; code: string; name: string; detail: string }
export interface ExclusionList { risk: string; count: number; employees: ExcludedEmployee[] }
/** 定稿影响预览：本方案覆盖的订单 + 会被标记为「已被新版本替代」的其他方案。 */
export interface SupersedeTarget {
  id: number; name: string; windowStart: string; windowEnd: string; orderCodes: string[]
}
export interface SupersedePreview {
  planId: number; status: string; orderCodes: string[]
  /**
   * 会被本次定稿真正覆盖的已定稿方案。判据是**分配到同一个班次**（不是时间窗重叠、
   * 也不是同一张订单）：不同订单在同一时间由不同的人同时干活是常态，各写各的班次，互不覆盖。
   * 空数组 = 本次定稿不影响任何其他方案。
   */
  targets: SupersedeTarget[]
}
export interface EmployeeShift {
  shiftId: number; startTime: string; endTime: string; locationId: number
  auditOrderCode: string; auditStage: string; publishedPlanId?: number | null
}
export interface EmployeeShiftList { employeeId: number; count: number; shifts: EmployeeShift[] }
export interface EmployeeProfileDate { id: number; employeeId: number; dateTypeId: number; dateStart: string; dateEnd: string }
export interface EmployeeQualification {
  skillId: number; certificateNumber?: string; qualificationLevel?: string
  validFrom?: string | null; validUntil?: string | null; status?: string
}
export interface EmployeeProfile {
  id: number; code: string; firstName: string; lastName: string; email?: string; active: boolean
  operationalStatus: string; busyUntil?: string | null; statusUpdatedAt?: string | null
  currentLocationId?: number | null; bufferBeforeMinutes: number; bufferAfterMinutes: number
  desiredDates: EmployeeProfileDate[]; undesiredDates: EmployeeProfileDate[]; unavailableDates: EmployeeProfileDate[]
  qualifications: EmployeeQualification[]
}
export interface CandidateWeightProfile {
  version: number; ability: number; location: number; workload: number; continuity: number; age: number
  source: 'DEFAULT' | 'PROFILE'; changeReason: string; createdBy: string; createdAt: string
}
export interface CandidateWeightInput {
  ability: number; location: number; workload: number; continuity: number; age: number; changeReason: string
}
export interface CandidateAiStatus {
  configured: boolean; provider: string; model: string; mode: 'ADVISORY_ONLY'
}
export interface CandidateAiRecommendation {
  shiftId: number; employeeId: number; aiRank: number; confidence: number
  rationale: string; cautions: string[]
}
export interface CandidateAiReview {
  orderId: number; orderCode: string; provider: string; model: string; responseId: string
  generatedAt: string; advisoryOnly: boolean; overallSummary: string
  recommendations: CandidateAiRecommendation[]; warnings: string[]
}
export interface ImportIssue { sheet: string; row: number; field: string; severity: string; code: string; message: string }
export interface ImportPreview {
  token: string; fileName: string; rowCount: number; errorCount: number; warningCount: number
  summary: Record<string, number>; issues: ImportIssue[]; confirmable: boolean
}
export interface ImportJob {
  token: string; fileName: string; status: string; rowCount: number; errorCount: number
  warningCount: number; createdBy: string; createdAt: string
}
export interface AuditEvent {
  id: number; actor: string; action: string; entityType: string; entityId: string; details: string; createdAt: string
}

async function uploadWorkbook(structureId: number, file: File): Promise<ImportPreview> {
  const query = new URLSearchParams({ structureId: String(structureId), fileName: file.name })
  const response = await rawFetch(`/imports/preview?${query}`, {
    method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: await file.arrayBuffer(),
  })
  if (!response.ok) throw Object.assign(new Error(await response.text()), { status: response.status })
  return response.json() as Promise<ImportPreview>
}

/** 已排人员的实时风险；只含有风险的行，缺失即正常。 */
export interface OrderAssignmentRisk { shiftId: number; phaseId: number; employeeId: number; risk: string }

export const workflowApi = {
  orders: (structureId: number) => api.get<AuditOrder[]>(`/audit-orders?structureId=${structureId}`),
  /** 展开订单时才调用：整页 600 多条分配全算会明显拖慢列表。 */
  orderRisks: (structureId: number, id: number) =>
    api.get<{ risks: OrderAssignmentRisk[] }>(`/audit-orders/${id}/assignment-risks?structureId=${structureId}`),
  createOrder: (structureId: number, input: AuditOrderInput) =>
    api.post<AuditOrder>(`/audit-orders?structureId=${structureId}`, input),
  updateOrder: (structureId: number, id: number, input: AuditOrderInput) =>
    api.put<AuditOrder>(`/audit-orders/${id}?structureId=${structureId}`, input),
  createPhase: (structureId: number, orderId: number, input: AuditPhaseInput) =>
    api.post<AuditPhase>(`/audit-orders/${orderId}/phases?structureId=${structureId}`, input),
  updatePhase: (structureId: number, phaseId: number, input: AuditPhaseInput) =>
    api.put<AuditPhase>(`/audit-orders/phases/${phaseId}?structureId=${structureId}`, input),
  /** 订单审批：三态流转都在订单上，方案层不再有提交/批准/退回。 */
  submitOrder: (structureId: number, id: number) =>
    api.post<AuditOrder>(`/audit-orders/${id}/submit?structureId=${structureId}`, {}),
  approveOrder: (structureId: number, id: number) =>
    api.post<AuditOrder>(`/audit-orders/${id}/approve?structureId=${structureId}`, {}),
  rejectOrder: (structureId: number, id: number, reason: string) =>
    api.post<AuditOrder>(`/audit-orders/${id}/reject?structureId=${structureId}&reason=${encodeURIComponent(reason)}`, {}),
  plans: (structureId: number) => api.get<SchedulePlan[]>(`/schedule-plans?structureId=${structureId}`),
  plan: (structureId: number, id: number) => api.get<SchedulePlan>(`/schedule-plans/${id}?structureId=${structureId}`),
  createPlan: (structureId: number, input: { name: string; windowStart: string; windowEnd: string }) =>
    api.post<SchedulePlan>(`/schedule-plans?structureId=${structureId}`, input),
  createPlanFromOrder: (structureId: number, orderId: number, name: string) =>
    api.post<SchedulePlan>(`/schedule-plans/from-order/${orderId}?structureId=${structureId}`, { name }),
  revise: (structureId: number, id: number) => api.post<SchedulePlan>(`/schedule-plans/${id}/revise?structureId=${structureId}`, {}),
  removePlan: (structureId: number, id: number) =>
    api.delete<{ deleted: boolean }>(`/schedule-plans/${id}?structureId=${structureId}`),
  /** 按订单号取最小状态（排班页「待分配」块要判断"能不能派工"，不能为一句提示拉全量订单）。 */
  lookupOrder: (structureId: number, orderCode: string) =>
    api.get<{ id: number; orderCode: string; status: string }>(
      `/audit-orders/lookup?structureId=${structureId}&orderCode=${encodeURIComponent(orderCode)}`),
  /**
   * 直接把一个审核员派到某个名额（班次）。只改这一个格子，立即生效。
   * 硬约束由服务端重算（资格/等级/不可用/冲突/转场），前端候选名单不作数。
   */
  assignSlot: (structureId: number, orderId: number,
    body: { shiftId: number; employeeId: number; reason?: string }) =>
    api.post<{ shiftId: number; employeeId: number; employeeName: string; previousEmployeeId: number | null }>(
      `/audit-orders/${orderId}/assign-slot?structureId=${structureId}`, body),
  /** 定稿：把方案里的分配写进班次并封存快照（DRAFT → EXECUTED）。 */
  publish: (structureId: number, id: number) => api.post<SchedulePlan>(`/schedule-plans/${id}/publish?structureId=${structureId}`, {}),
  /**
   * 定稿影响预览：这次定稿会替代哪些已定稿方案。
   * 与 publish 共用后端同一段判定（`supersedeTargets`），所以弹窗里的数字永远等于真正发生的事。
   * `targets` 为空 = 不影响任何其他方案（不同订单的草案各管各的）。
   */
  supersedePreview: (structureId: number, id: number) =>
    api.get<SupersedePreview>(`/schedule-plans/${id}/supersede-preview?structureId=${structureId}`),
  candidates: (structureId: number, planId: number, assignmentId: number, limit = 5) =>
    api.get<Candidate[]>(`/schedule-plans/${planId}/assignments/${assignmentId}/candidates?structureId=${structureId}&limit=${limit}`),
  orderCandidates: (structureId: number, orderCode: string, limit = 5) => {
    const query = new URLSearchParams({ structureId: String(structureId), orderCode: orderCode.trim(), limit: String(limit) })
    return api.get<OrderCandidateResult>(`/schedule-plans/order-candidates?${query}`)
  },
  orderCandidateExclusions: (structureId: number, orderCode: string, phaseId: number, slotIndex: number, risk: string) => {
    const query = new URLSearchParams({ structureId: String(structureId), orderCode: orderCode.trim(), phaseId: String(phaseId), slotIndex: String(slotIndex), risk })
    return api.get<ExclusionList>(`/schedule-plans/order-candidates/exclusions?${query}`)
  },
  employeeProfile: (structureId: number, employeeId: number) =>
    api.get<EmployeeProfile>(`/demo-data/getemployee/${employeeId}?structureId=${structureId}`),
  employeeShifts: (structureId: number, employeeId: number) =>
    api.get<EmployeeShiftList>(`/schedule-plans/employee-shifts?structureId=${structureId}&employeeId=${employeeId}`),
  candidateWeights: (structureId: number) =>
    api.get<CandidateWeightProfile>(`/candidate-weights?structureId=${structureId}`),
  candidateWeightHistory: (structureId: number) =>
    api.get<CandidateWeightProfile[]>(`/candidate-weights/history?structureId=${structureId}`),
  updateCandidateWeights: (structureId: number, input: CandidateWeightInput) =>
    api.put<CandidateWeightProfile>(`/candidate-weights?structureId=${structureId}`, input),
  candidateAiStatus: () => api.get<CandidateAiStatus>('/schedule-plans/ai-status'),
  candidateAiReview: (structureId: number, orderCode: string, limit = 5) => {
    const query = new URLSearchParams({ structureId: String(structureId), orderCode: orderCode.trim(), limit: String(limit) })
    return api.post<CandidateAiReview>(`/schedule-plans/order-candidates/ai-review?${query}`, {})
  },
  updateAssignment: (structureId: number, planId: number, assignmentId: number,
    input: { employeeId: number | null; locked: boolean; assignmentRole: string; changeReason: string }) =>
    api.put<PlanAssignment>(`/schedule-plans/${planId}/assignments/${assignmentId}?structureId=${structureId}`, input),
  imports: (structureId: number) => api.get<ImportJob[]>(`/imports?structureId=${structureId}`),
  uploadWorkbook,
  confirmImport: (structureId: number, token: string) => api.post(`/imports/${token}/confirm?structureId=${structureId}`, {}),
  auditEvents: (structureId: number) => api.get<AuditEvent[]>(`/audit-events?structureId=${structureId}&limit=300`),
  downloadTemplate: () => downloadBlob('/imports/template'),
  downloadErrors: (structureId: number, token: string) => downloadBlob(`/imports/${token}/errors?structureId=${structureId}`),
  exportPlanExcel: (structureId: number, id: number) => downloadBlob(`/schedule-plans/${id}/export.xlsx?structureId=${structureId}`),
}

export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = fileName; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
