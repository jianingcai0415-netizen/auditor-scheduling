/**
 * @file employees.ts
 * @brief API for managing employees.
 *
 * @details
 * Main backend endpoints: `/demo-data/employees`, `/demo-data/addemployee`, etc.
 * Skills are returned with a `used` flag indicating whether the employee has that skill.
 */

import { api, rawFetch } from './client'

/**
 * @brief Skill with a usage flag.
 * @details Used for both employees and locations.
 *          `used = true` means the skill is assigned to the current entity.
 */
export interface SkillOption {
  id: number
  name: string
  /** @brief true if the skill is assigned to the entity (employee or location). */
  used?: boolean
}

export interface AuditorQualification {
  skillId: number
  certificateNumber: string
  qualificationLevel: string
  validFrom?: string | null
  validUntil?: string | null
  status: 'ACTIVE' | 'EXPIRING' | 'EXPIRED' | 'SUSPENDED'
}

/**
 * @brief Employee as returned by the API.
 */
export interface Employee {
  id: number
  /** @brief Unique code (for example, "EMP001"). */
  code: string
  firstName: string
  lastName: string
  email?: string
  /** @brief Full name (firstName + lastName), calculated by the backend. */
  fullName?: string
  /** @brief List of all skills with their `used` flag. */
  skills?: SkillOption[]
  /** @brief If false, the employee is disabled (excluded from Shift Management and the solver). */
  active?: boolean
  qualifications?: AuditorQualification[]
  operationalStatus?: 'AVAILABLE' | 'ASSIGNED' | 'TRAVELLING' | 'UNAVAILABLE'
  busyUntil?: string | null
  statusUpdatedAt?: string | null
  currentLocationId?: number | null
  bufferBeforeMinutes?: number
  bufferAfterMinutes?: number
  /** @brief 简历附件文件名；空表示尚未上传（上传接口尚未开放）。 */
  resumeFileName?: string | null
  /** @brief 简历上传时间（yyyy-MM-dd HH:mm:ss）。 */
  resumeUploadedAt?: string | null
}

/**
 * @brief Payload for creating/updating an employee.
 */
export interface EmployeePayload {
  id?: number
  code: string
  firstName: string
  lastName: string
  email?: string
  /** @brief Skills to assign (id and name only, without `used`). */
  skills: { id: number; name: string }[]
  /** @brief Enabled/disabled status. */
  active?: boolean
  qualifications?: AuditorQualification[]
  operationalStatus?: 'AVAILABLE' | 'ASSIGNED' | 'TRAVELLING' | 'UNAVAILABLE'
  busyUntil?: string | null
  currentLocationId?: number | null
  bufferBeforeMinutes?: number
  bufferAfterMinutes?: number
}

/**
 * @brief Employee API methods.
 */
export const employeesApi = {
  /**
   * @brief Retrieves all employees belonging to a structure.
   * @param structureId Current structure ID
   */
  list: (structureId: number) =>
    api.get<Employee[]>(`/demo-data/employees?structureId=${structureId}`),

  /**
   * @brief Retrieves a single employee with their skills.
   * @param id Employee ID
   */
  get: (id: number, structureId: number) =>
    api.get<Employee>(`/demo-data/getemployee/${id}?structureId=${structureId}`),

  /**
   * @brief Generates the next available sequential code (for example, "EMP015").
   */
  nextCode: () =>
    api.get<{ code: string }>('/demo-data/next-employee-code'),

  /**
   * @brief Creates a new employee in the specified structure (the backend returns 201 with no body).
   * @param payload     Employee data
   * @param structureId ID of the structure it belongs to
   */
  add: (payload: EmployeePayload, structureId: number) =>
    api.post<void>(`/demo-data/addemployee?structureId=${structureId}`, payload),

  /**
   * @brief Updates an existing employee (the backend returns 200 with no body).
   * @param id      ID of the employee to update
   * @param payload New data
   */
  update: (id: number, payload: EmployeePayload, structureId: number) =>
    api.put<void>(`/demo-data/updateemployee/${id}?structureId=${structureId}`, payload),

  /** Publishes live dispatch state without replacing profile or qualification data. */
  updateStatus: (id: number, payload: Pick<EmployeePayload,
    'operationalStatus' | 'busyUntil' | 'currentLocationId' | 'bufferBeforeMinutes' | 'bufferAfterMinutes'>,
    structureId: number) =>
    api.put<{ statusUpdatedAt: string }>(`/demo-data/employees/${id}/status?structureId=${structureId}`, payload),

  /**
   * @brief Deletes an employee.
   * @param id Employee ID
   */
  delete: (id: number, structureId: number) =>
    api.delete<void>(`/demo-data/employees/${id}?structureId=${structureId}`),

  /**
   * @brief 上传或替换简历（PDF，≤10 MB）。
   *
   * @details 走原始字节体而不是 multipart：后端按 `byte[]` 接收，与 Excel 导入一致。
   *          用 `rawFetch` 而不是 `api.post`，因为后者会 `JSON.stringify`，把 File 变成一个空对象。
   */
  uploadResume: async (id: number, structureId: number, file: File) => {
    const url = `/demo-data/employees/${id}/resume?structureId=${structureId}`
      + `&fileName=${encodeURIComponent(file.name)}`
    const res = await rawFetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/pdf' },
      body: file,
    })
    if (!res.ok) {
      const text = await res.text().catch(() => `HTTP ${res.status}`)
      throw Object.assign(new Error(text || `HTTP ${res.status}`), { status: res.status })
    }
    return res.json() as Promise<{ resumeFileName: string; resumeUploadedAt: string }>
  },

  /** @brief 简历下载地址；同源请求会自动带上会话 Cookie。 */
  resumeDownloadUrl: (id: number, structureId: number) =>
    `/demo-data/employees/${id}/resume?structureId=${structureId}`,

  /** @brief 删除简历附件（附件不存在也算成功）。 */
  deleteResume: (id: number, structureId: number) =>
    api.delete<{ cleared: boolean; fileRemoved: boolean }>(
      `/demo-data/employees/${id}/resume?structureId=${structureId}`),

  /**
   * @brief Retrieves all skills available in the system.
   * @details Used by modals to show the list of selectable skills.
   */
  allSkills: (structureId: number) =>
    api.get<SkillOption[]>(`/demo-data/get_skills?structureId=${structureId}`),
}
