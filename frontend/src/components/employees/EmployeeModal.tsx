/**
 * @file EmployeeModal.tsx
 * @brief Modal for adding and editing an employee.
 *
 * @details
 * In "add" mode (employeeId=null), it automatically suggests the next available code
 * (`GET /demo-data/next-employee-code`).
 * In "edit" mode, it loads the existing data, including assigned skills.
 * It handles duplicate codes with a warning toast (HTTP 409).
 */

import { useEffect, useLayoutEffect, useRef, useState, type ChangeEvent } from 'react'
import { Modal, Button, Form, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faDownload, faTrashCan, faUpload } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { employeesApi, type Employee, type SkillOption, type AuditorQualification } from '../../api/employees'
import { locationsApi, type Location } from '../../api/locations'
import { errorCode, errorStatus } from '../../api/client'
import { backendErrorText } from '../../i18n/backendErrors'

/**
 * @brief EmployeeModal component properties.
 */
interface Props {
  show: boolean
  /** @brief ID of the employee to edit, or `null` when adding one. */
  employeeId: number | null
  structureId: number
  onClose: () => void
  onSaved: () => void
}

type OperationalStatus = NonNullable<Employee['operationalStatus']>

const EMPTY_FORM = {
  firstName: '', lastName: '', email: '', code: '', skills: [] as number[], active: true,
  qualifications: [] as AuditorQualification[],
  operationalStatus: 'AVAILABLE' as OperationalStatus, busyUntil: '', currentLocationId: 0,
  bufferBeforeMinutes: 30, bufferAfterMinutes: 30,
}

function toInput(iso?: string | null) {
  return iso ? iso.replace(' ', 'T').slice(0, 16) : ''
}

/** @brief Removes leading whitespace (for values pasted with whitespace in front). */
const stripLeading = (s: string) => s.replace(/^\s+/, '')
/** @brief Removes all whitespace (an email address cannot contain any). */
const stripSpaces = (s: string) => s.replace(/\s+/g, '')

export default function EmployeeModal({ show, employeeId, structureId, onClose, onSaved }: Props) {
  const { t, i18n } = useTranslation()
  const isEdit = employeeId !== null
  const [form, setForm] = useState(EMPTY_FORM)
  /**
   * 简历附件（只读回显）。刻意不进 form：上传/删除是独立动作，立即落库并作用于磁盘，
   * 若混进表单载荷，一次「保存」就会把刚上传的附件名覆盖回旧值。
   */
  const [resume, setResume] = useState<{ fileName?: string | null; uploadedAt?: string | null }>({})
  /** 简历上传/删除进行中：按钮禁用，避免连点产生两份附件。 */
  const [resumeBusy, setResumeBusy] = useState(false)
  const resumeInputRef = useRef<HTMLInputElement | null>(null)
  const [allSkills, setAllSkills] = useState<SkillOption[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)
  const sessionIdentity = `${show}:${structureId}:${employeeId ?? 'new'}`
  const currentSession = useRef(sessionIdentity)
  const operationInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentSession.current = sessionIdentity
    operationInFlight.current = null
    setSaving(false)
  }, [sessionIdentity])

  // Load the available skills when the modal opens
  useEffect(() => {
    if (!show) return
    let current = true
    employeesApi.allSkills(structureId).then(skills => { if (current) setAllSkills(skills) }).catch(() => {})
    locationsApi.list(structureId).then(items => { if (current) setLocations(items) }).catch(() => {})
    return () => { current = false }
  }, [show, structureId])

  // Load employee data when editing, or suggest a code when adding
  useEffect(() => {
    if (!show) return
    let current = true
    if (isEdit) {
      setLoading(true)
      employeesApi.get(employeeId!, structureId)
        .then((emp: Employee) => {
          if (!current) return
          setForm({
            firstName: emp.firstName,
            lastName: emp.lastName,
            email: emp.email ?? '',
            code: emp.code,
            skills: (emp.skills ?? []).filter(s => s.used).map(s => s.id),
            active: emp.active !== false,
            qualifications: emp.qualifications ?? [],
            operationalStatus: emp.operationalStatus ?? 'AVAILABLE',
            busyUntil: toInput(emp.busyUntil),
            currentLocationId: emp.currentLocationId ?? 0,
            bufferBeforeMinutes: emp.bufferBeforeMinutes ?? 30,
            bufferAfterMinutes: emp.bufferAfterMinutes ?? 30,
          })
          setResume({ fileName: emp.resumeFileName, uploadedAt: emp.resumeUploadedAt })
        })
        .catch(() => { if (current) toast.error(i18n.t('toast.errorLoad', 'Error while loading.')) })
        .finally(() => { if (current) setLoading(false) })
    } else {
      setLoading(false)
      setForm(EMPTY_FORM)
      setResume({})
      employeesApi.nextCode()
        .then(({ code }) => { if (current) setForm(f => ({ ...f, code })) })
        .catch(() => {})
    }
    return () => { current = false }
  }, [show, employeeId, isEdit, structureId, i18n])

  function toggleSkill(id: number) {
    setForm(f => ({
      ...f,
      skills: f.skills.includes(id) ? f.skills.filter(s => s !== id) : [...f.skills, id],
      qualifications: f.skills.includes(id)
        ? f.qualifications.filter(q => q.skillId !== id)
        : [...f.qualifications, {
            skillId: id, certificateNumber: `PENDING-${id}`, qualificationLevel: 'AUDITOR',
            validFrom: null, validUntil: null, status: 'SUSPENDED',
          }],
    }))
  }

  function updateQualification(skillId: number, change: Partial<AuditorQualification>) {
    setForm(f => ({ ...f, qualifications: f.qualifications.map(q =>
      q.skillId === skillId ? { ...q, ...change } : q) }))
  }

  // ------------------------------------------------------------------
  // 简历附件：上传 / 下载 / 删除
  // 上传与员工表单是两条独立的保存路径——简历立即生效，不随"保存"一起提交。
  // 这样用户选完文件就能看到结果，也不会因为忘记点保存而丢附件。
  // ------------------------------------------------------------------

  const RESUME_MAX_BYTES = 10 * 1024 * 1024

  async function handleResumePicked(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    // 立刻清空：否则连续两次选同一个文件不会触发 change
    event.target.value = ''
    if (!file || employeeId === null) return
    if (file.size > RESUME_MAX_BYTES) {
      toast.error(t('error.resumeTooLarge', 'File is larger than 10 MB.'))
      return
    }
    setResumeBusy(true)
    try {
      const updated = await employeesApi.uploadResume(employeeId, structureId, file)
      setResume({ fileName: updated.resumeFileName, uploadedAt: updated.resumeUploadedAt })
      toast.success(t('toast.resumeUploaded', 'Resume uploaded.'))
    } catch (error) {
      const code = errorCode(error)
      toast.error(
        code === 'RESUME_TYPE_INVALID' ? t('error.resumeType', 'Only PDF files are accepted.')
          : code === 'RESUME_TOO_LARGE' ? t('error.resumeTooLarge', 'File is larger than 10 MB.')
            : code === 'RESUME_STORE_FAILED' ? t('error.resumeStoreFailed', 'Could not save the file — check disk space.')
              : t('toast.errorUpload', 'Upload failed.'),
      )
    } finally {
      setResumeBusy(false)
    }
  }

  function downloadResume() {
    if (employeeId === null || !resume.fileName) return
    const link = document.createElement('a')
    link.href = employeesApi.resumeDownloadUrl(employeeId, structureId)
    link.download = resume.fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
  }

  async function removeResume() {
    if (employeeId === null) return
    if (!window.confirm(t('confirm.deleteResume', 'Delete this resume? This cannot be undone.'))) return
    setResumeBusy(true)
    try {
      await employeesApi.deleteResume(employeeId, structureId)
      setResume({})
      toast.success(t('toast.resumeDeleted', 'Resume deleted.'))
    } catch {
      toast.error(t('toast.errorDelete', 'Error while deleting.'))
    } finally {
      setResumeBusy(false)
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (operationInFlight.current) return
    const email = form.email.trim()
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      toast.error(t('validation.emailInvalid', 'Enter a valid email address.'))
      return
    }
    const operation = Symbol('employee-save')
    operationInFlight.current = operation
    setSaving(true)
    const submitSession = sessionIdentity
    const payload = {
      id: isEdit ? employeeId! : undefined,
      firstName: form.firstName.trim(),
      lastName: form.lastName.trim(),
      email,
      code: form.code.trim(),
      skills: allSkills.filter(s => form.skills.includes(s.id)).map(s => ({ id: s.id, name: s.name })),
      active: form.active,
      qualifications: form.qualifications.filter(q => form.skills.includes(q.skillId)),
      operationalStatus: form.operationalStatus,
      busyUntil: form.busyUntil ? form.busyUntil + ':00' : null,
      currentLocationId: form.currentLocationId || null,
      bufferBeforeMinutes: form.bufferBeforeMinutes,
      bufferAfterMinutes: form.bufferAfterMinutes,
    }
    try {
      if (isEdit) {
        await employeesApi.update(employeeId!, payload, structureId)
      } else {
        await employeesApi.add(payload, structureId)
      }
      if (currentSession.current !== submitSession) return
      toast.success(isEdit ? t('toast.employeeUpdated', 'Operator updated!') : t('toast.employeeAdded', 'Operator added!'))
      onSaved()
      onClose()
    } catch (err: unknown) {
      // The backend identifies WHICH field it rejected; showing it avoids a generic message
      // that forces the user to guess what needs fixing.
      if (currentSession.current !== submitSession) return
      const specific = backendErrorText(errorCode(err), t)
      if (specific) {
        toast.error(specific)
      } else if (errorStatus(err) === 409) {
        toast.error(t('toast.employeeCodeDuplicate', 'Operator code already in use. Choose a different one.'))
      } else {
        toast.error(isEdit ? t('toast.errorEdit', 'Error while editing.') : t('toast.errorAdd', "Error while adding."))
      }
    } finally {
      if (operationInFlight.current === operation) {
        operationInFlight.current = null
        if (currentSession.current === submitSession) setSaving(false)
      }
    }
  }

  return (
    <Modal show={show} onHide={() => { if (!saving) onClose() }} centered size="lg">
      <Form onSubmit={handleSubmit}>
        <Modal.Header closeButton={!saving}>
          <Modal.Title>{isEdit ? t('modal.editEmployee', 'Edit Operator') : t('modal.addEmployee', 'Add Operator')}</Modal.Title>
        </Modal.Header>

        <Modal.Body>
          <fieldset disabled={saving} className="border-0 p-0 m-0 w-100">
          {loading ? (
            <div className="text-center py-3"><Spinner /></div>
          ) : (
            <>
              <Form.Group className="mb-3">
                <Form.Label>{t('label.firstName', 'First Name')}</Form.Label>
                <Form.Control
                  required
                  value={form.firstName}
                  onChange={e => setForm(f => ({ ...f, firstName: stripLeading(e.target.value) }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Label>{t('label.lastName', 'Last Name')}</Form.Label>
                <Form.Control
                  required
                  value={form.lastName}
                  onChange={e => setForm(f => ({ ...f, lastName: stripLeading(e.target.value) }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Label>{t('label.code', 'Code')}</Form.Label>
                <Form.Control
                  required
                  value={form.code}
                  onChange={e => setForm(f => ({ ...f, code: stripLeading(e.target.value) }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Label>{t('label.email', 'Email')}</Form.Label>
                <Form.Control
                  type="email"
                  value={form.email}
                  placeholder={t('placeholder.email', 'name@example.com')}
                  onChange={e => setForm(f => ({ ...f, email: stripSpaces(e.target.value) }))}
                />
              </Form.Group>

              <div className="border rounded p-3 mb-3">
                <Form.Label className="fw-semibold">{t('label.liveDispatchStatus', 'Live dispatch status')}</Form.Label>
                <div className="row g-2">
                  <div className="col-md-6">
                    <Form.Select value={form.operationalStatus}
                      onChange={e => setForm(f => ({ ...f, operationalStatus: e.target.value as OperationalStatus }))}>
                      <option value="AVAILABLE">{t('status.available', 'Available')}</option>
                      <option value="ASSIGNED">{t('status.assigned', 'On external audit')}</option>
                      <option value="TRAVELLING">{t('status.travelling', 'Travelling')}</option>
                      <option value="UNAVAILABLE">{t('status.unavailable', 'Unavailable')}</option>
                    </Form.Select>
                  </div>
                  <div className="col-md-6">
                    <Form.Control type="datetime-local" value={form.busyUntil}
                      onChange={e => setForm(f => ({ ...f, busyUntil: e.target.value }))} />
                  </div>
                  <div className="col-md-6">
                    <Form.Select value={form.currentLocationId}
                      onChange={e => setForm(f => ({ ...f, currentLocationId: Number(e.target.value) }))}>
                      <option value={0}>{t('select.currentLocationUnknown', 'Current location not known')}</option>
                      {locations.map(location => <option key={location.id} value={location.id}>{location.name}</option>)}
                    </Form.Select>
                  </div>
                  <div className="col-md-3">
                    <Form.Control type="number" min={0} max={1440} value={form.bufferBeforeMinutes}
                      aria-label={t('label.bufferBefore', 'Buffer before')}
                      onChange={e => setForm(f => ({ ...f, bufferBeforeMinutes: Number(e.target.value) }))} />
                    <Form.Text>{t('label.bufferBefore', 'Buffer before')}</Form.Text>
                  </div>
                  <div className="col-md-3">
                    <Form.Control type="number" min={0} max={1440} value={form.bufferAfterMinutes}
                      aria-label={t('label.bufferAfter', 'Buffer after')}
                      onChange={e => setForm(f => ({ ...f, bufferAfterMinutes: Number(e.target.value) }))} />
                    <Form.Text>{t('label.bufferAfter', 'Buffer after')}</Form.Text>
                  </div>
                </div>
              </div>

              <Form.Group className="mb-3">
                <Form.Check
                  type="switch"
                  id="emp-active"
                  label={t('label.active', 'Active')}
                  checked={form.active}
                  onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                />
                <Form.Text className="text-muted">
                  {t('hint.inactiveEmployee', 'If deactivated, it no longer appears in Shift Management and the solver won\'t assign shifts to them.')}
                </Form.Text>
              </Form.Group>

              {allSkills.length > 0 && (
                <Form.Group>
                  <Form.Label>{t('label.skills', 'Skills')}</Form.Label>
                  <div className="d-flex flex-wrap gap-2">
                    {allSkills.map(skill => (
                      <Form.Check
                        key={skill.id}
                        type="checkbox"
                        id={`skill-${skill.id}`}
                        label={t('skill.' + skill.id, skill.name)}
                        checked={form.skills.includes(skill.id)}
                        onChange={() => toggleSkill(skill.id)}
                      />
                    ))}
                  </div>
                </Form.Group>
              )}

              {form.skills.length > 0 && (
                <div className="mt-3">
                  <Form.Label className="fw-semibold">{t('label.qualifications', 'Qualifications and certificates')}</Form.Label>
                  {form.skills.map(skillId => {
                    const skill = allSkills.find(item => item.id === skillId)
                    const q = form.qualifications.find(item => item.skillId === skillId)
                    if (!q) return null
                    return (
                      <div className="border rounded p-2 mb-2" key={skillId}>
                        <div className="fw-semibold mb-2">{skill?.name ?? `#${skillId}`}</div>
                        <div className="row g-2">
                          <div className="col-md-6"><Form.Control required value={q.certificateNumber}
                            placeholder={t('label.certificateNumber', 'Certificate number')}
                            onChange={e => updateQualification(skillId, { certificateNumber: e.target.value })} /></div>
                          <div className="col-md-3"><Form.Control required value={q.qualificationLevel}
                            placeholder={t('label.qualificationLevel', 'Level')}
                            onChange={e => updateQualification(skillId, { qualificationLevel: e.target.value })} /></div>
                          <div className="col-md-3"><Form.Select value={q.status}
                            onChange={e => updateQualification(skillId, { status: e.target.value as AuditorQualification['status'] })}>
                            <option value="ACTIVE">ACTIVE</option><option value="EXPIRING">EXPIRING</option>
                            <option value="EXPIRED">EXPIRED</option><option value="SUSPENDED">SUSPENDED</option>
                          </Form.Select></div>
                          <div className="col-md-6"><Form.Control type="date" value={q.validFrom ?? ''}
                            onChange={e => updateQualification(skillId, { validFrom: e.target.value || null })} /></div>
                          <div className="col-md-6"><Form.Control type="date" value={q.validUntil ?? ''}
                            onChange={e => updateQualification(skillId, { validUntil: e.target.value || null })} /></div>
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}

              {/* 简历附件：PDF，≤10 MB。新增模式下还没有 id，先提示保存后再上传。 */}
              <div className="mt-3">
                <Form.Label className="fw-semibold">{t('label.resume', 'Resume')}</Form.Label>
                <div className="border rounded p-2">
                  {!isEdit ? (
                    <span className="text-muted small">
                      {t('msg.resumeSaveFirst', 'Save this auditor first, then you can upload a resume.')}
                    </span>
                  ) : resume.fileName ? (
                    <div className="d-flex align-items-center gap-2">
                      <span className="text-truncate flex-grow-1" title={resume.fileName}>{resume.fileName}</span>
                      {resume.uploadedAt && (
                        <span className="text-muted small text-nowrap">{resume.uploadedAt}</span>
                      )}
                      <Button size="sm" variant="outline-primary" disabled={resumeBusy}
                        title={t('tooltip.downloadResume', 'Download resume')} onClick={downloadResume}>
                        <FontAwesomeIcon icon={faDownload} />
                      </Button>
                      {/* 替换：不必先删再传，旧附件在服务端按"先写新、再删旧"处理 */}
                      <Button size="sm" variant="outline-secondary" disabled={resumeBusy}
                        title={t('tooltip.replaceResume', 'Replace resume')}
                        onClick={() => resumeInputRef.current?.click()}>
                        <FontAwesomeIcon icon={faUpload} />
                      </Button>
                      <Button size="sm" variant="outline-danger" disabled={resumeBusy}
                        title={t('tooltip.deleteResume', 'Delete resume')} onClick={() => void removeResume()}>
                        <FontAwesomeIcon icon={faTrashCan} />
                      </Button>
                    </div>
                  ) : (
                    <div className="d-flex align-items-center gap-2">
                      <span className="text-muted small flex-grow-1">
                        {t('msg.resumeEmpty', 'No resume uploaded yet — PDF only, up to 10 MB.')}
                      </span>
                      <Button size="sm" variant="outline-secondary" disabled={resumeBusy}
                        onClick={() => resumeInputRef.current?.click()}>
                        {resumeBusy
                          ? <Spinner size="sm" />
                          : <><FontAwesomeIcon icon={faUpload} className="me-1" />{t('btn.upload', 'Upload')}</>}
                      </Button>
                    </div>
                  )}
                </div>
                <input ref={resumeInputRef} type="file" accept="application/pdf,.pdf" className="d-none"
                  onChange={event => void handleResumePicked(event)} />
              </div>
            </>
          )}
          </fieldset>
        </Modal.Body>

        <Modal.Footer>
          <Button variant="secondary" onClick={onClose} disabled={saving}>
            {t('btn.cancel', 'Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={saving || loading}>
            {saving ? <Spinner size="sm" /> : isEdit ? t('btn.save', 'Save') : t('btn.add', 'Add')}
          </Button>
        </Modal.Footer>
      </Form>
    </Modal>
  )
}
