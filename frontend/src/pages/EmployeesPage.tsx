/**
 * @file EmployeesPage.tsx
 * @brief Employee management page.
 *
 * @details
 * On load, performs two calls in parallel:
 * - `employeesApi.list()` — employee list with assigned skills
 * - `shiftsApi.usage()` — IDs referenced by shifts (history vs. still open)
 *
 * The delete column shows a disabled (pink) icon only for employees who hold a
 * current or upcoming shift. Historical shifts do not block deletion — otherwise
 * anyone ever scheduled would be permanently undeletable. The backend enforces
 * the same rule (409 EMPLOYEE_IN_USE), so the UI cannot be bypassed.
 */

import { useEffect, useState, useCallback, useRef } from 'react'
import { Button, Table, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faTrash, faUserPlus } from '@fortawesome/free-solid-svg-icons'
import { useSearchParams } from 'react-router-dom'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { employeesApi, type Employee } from '../api/employees'
import { shiftsApi } from '../api/shifts'
import { errorCode } from '../api/client'
import { useAppStore } from '../store/useAppStore'
import EmployeeModal from '../components/employees/EmployeeModal'
import ConfirmModal from '../components/ConfirmModal'

export default function EmployeesPage() {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const [employees, setEmployees] = useState<Employee[]>([])
  // 有未结束班次（进行中或未来）的人不可删除；历史班次不锁人，否则离职的人永远删不掉。
  const [blockedEmployeeIds, setBlockedEmployeeIds] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(false)
  const loadGeneration = useRef(0)
  const currentStructureRef = useRef(structureId)
  const deleteGeneration = useRef(0)
  currentStructureRef.current = structureId

  // Modal add/edit
  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)

  // Modal confirm delete
  const [deleteTarget, setDeleteTarget] = useState<{ employee: Employee; structureId: number } | null>(null)
  const [deleting, setDeleting] = useState(false)

  const [searchParams, setSearchParams] = useSearchParams()

  // 深链接：从地图「查看审核员详情」过来（/employees?employee=ID）。
  // 必须等员工列表加载完成、确认此人属于当前部门后再开弹窗——否则会对着空列表打开一个 undefined 的编辑框。
  // 打开后立刻移除参数，这样关闭弹窗再刷新不会又被弹开。
  const focusEmployeeId = Number(searchParams.get('employee')) || null
  useEffect(() => {
    if (!focusEmployeeId) return
    if (!employees.some(emp => emp.id === focusEmployeeId)) return
    setEditId(focusEmployeeId)
    setModalOpen(true)
    const next = new URLSearchParams(searchParams)
    next.delete('employee')
    setSearchParams(next, { replace: true })
  }, [focusEmployeeId, employees, searchParams, setSearchParams])

  const load = useCallback(() => {
    const generation = ++loadGeneration.current
    setLoading(true)
    Promise.all([
      employeesApi.list(structureId),
      shiftsApi.usage(structureId),
    ])
      .then(([emps, usage]) => {
        if (generation !== loadGeneration.current) return
        setEmployees(emps)
        // activeEmployeeIds 缺失时回退到 employeeIds（保守：宁可多锁，不可误删）。
        setBlockedEmployeeIds(new Set(usage.activeEmployeeIds ?? usage.employeeIds))
      })
      .catch(() => { if (generation === loadGeneration.current) toast.error(i18n.t('toast.errorLoad', 'Error while loading.')) })
      .finally(() => { if (generation === loadGeneration.current) setLoading(false) })
  }, [structureId, i18n])

  useEffect(() => {
    const generationRef = loadGeneration
    load()
    return () => { generationRef.current++ }
  }, [load])

  useEffect(() => {
    deleteGeneration.current++
    setModalOpen(false)
    setEditId(null)
    setDeleteTarget(null)
    setDeleting(false)
  }, [structureId])

  function openAdd() {
    setEditId(null)
    setModalOpen(true)
  }

  function openEdit(id: number) {
    setEditId(id)
    setModalOpen(true)
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    const generation = ++deleteGeneration.current
    setDeleting(true)
    try {
      await employeesApi.delete(deleteTarget.employee.id, deleteTarget.structureId)
      if (generation !== deleteGeneration.current || deleteTarget.structureId !== currentStructureRef.current) return
      toast.success(t('toast.employeeDeleted', 'Operator deleted.'))
      setDeleteTarget(null)
      load()
    } catch (error) {
      if (generation === deleteGeneration.current && deleteTarget.structureId === currentStructureRef.current) {
        // 后端会守住同一条规则（防止绕过界面）：有在途班次返回 409 EMPLOYEE_IN_USE。
        toast.error(errorCode(error) === 'EMPLOYEE_IN_USE'
          ? t('error.employeeInUse', 'This auditor still has a current or upcoming shift. Reassign it first.')
          : t('toast.errorDelete', 'Error while deleting.'))
      }
    } finally {
      if (generation === deleteGeneration.current) setDeleting(false)
    }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('nav.employees', 'Operators')}</h5>
        <Button
          variant="primary"
          size="sm"
          title={t('btn.addEmployee', 'Add Operator')}
          onClick={openAdd}
        >
          <FontAwesomeIcon icon={faUserPlus} className="me-2" />
          {t('btn.add', 'Add')}
        </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : (
        <Table hover bordered responsive size="sm">
          <thead className="table-dark">
            <tr>
              <th style={{ width: 130 }}>{t('table.code', 'Code')}</th>
              <th style={{ width: 130 }}>{t('label.firstName', 'First Name')}</th>
              <th style={{ width: 90 }}>{t('label.lastName', 'Last Name')}</th>
              <th style={{ width: 230 }}>{t('label.email', 'Email')}</th>
              <th>{t('label.skills', 'Skills')}</th>
              <th style={{ width: 80 }}>{t('label.active', 'Active')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Actions')}</th>
            </tr>
          </thead>
          <tbody>
            {employees.length === 0 ? (
              <tr><td colSpan={7} className="text-center text-muted">{t('msg.noEmployees', 'No operators.')}</td></tr>
            ) : employees.map(emp => {
              // 资格最多显示 3 个，其余折成 +N 并把完整列表放进 title：
              // 一处人最多持 7 项，全部铺开会让每行高到 130px、一屏只能看 5 行。
              const usedSkills = (emp.skills ?? []).filter(s => s.used)
              const shownSkills = usedSkills.slice(0, 3)
              const hiddenCount = usedSkills.length - shownSkills.length
              return (
              <tr key={emp.id} className={emp.active === false ? 'table-secondary' : undefined}>
                <td className="text-nowrap"><code>{emp.code}</code></td>
                <td className="text-nowrap">{emp.firstName}</td>
                <td className="text-nowrap">{emp.lastName}</td>
                <td className="text-nowrap">{emp.email || '—'}</td>
                <td>
                  {shownSkills.map(s => (
                    <span key={s.id} className="badge bg-secondary me-1">{t('skill.' + s.id, s.name)}</span>
                  ))}
                  {hiddenCount > 0 && (
                    <span
                      className="badge bg-light text-dark border"
                      title={usedSkills.map(s => t('skill.' + s.id, s.name)).join('、')}
                    >
                      +{hiddenCount}
                    </span>
                  )}
                </td>
                <td className="text-center">
                  {emp.active === false
                    ? <span className="badge bg-secondary">{t('label.inactive', 'No')}</span>
                    : <span className="badge bg-success">{t('label.activeYes', 'Yes')}</span>}
                </td>
                <td>
                  <Button
                    variant="link" size="sm" className="p-0 me-3 text-primary"
                    title={t('tooltip.editEmployee', 'Edit operator')}
                    onClick={() => openEdit(emp.id)}
                  >
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  {blockedEmployeeIds.has(emp.id) ? (
                    <FontAwesomeIcon
                      icon={faTrash}
                      style={{ color: '#f5a0a0', cursor: 'not-allowed', fontSize: '0.85rem' }}
                      title={t('tooltip.employeeHasUpcomingShifts',
                        'This auditor is on a current or upcoming shift — reassign it before deleting.')}
                    />
                  ) : (
                    <Button
                      variant="link" size="sm" className="p-0 text-danger"
                      title={t('tooltip.delete', 'Delete')}
                      onClick={() => setDeleteTarget({ employee: emp, structureId })}
                    >
                      <FontAwesomeIcon icon={faTrash} />
                    </Button>
                  )}
                </td>
              </tr>
              )
            })}
          </tbody>
        </Table>
      )}

      <EmployeeModal
        show={modalOpen}
        employeeId={editId}
        structureId={structureId}
        onClose={() => setModalOpen(false)}
        onSaved={load}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('confirm.deleteEmployeeTitle', 'Delete Operator')}
        message={`${t('confirm.deletePrefix', 'Are you sure you want to delete')} ${deleteTarget?.employee.firstName ?? ''} ${deleteTarget?.employee.lastName ?? ''}?`}
        confirmLabel={t('btn.delete', 'Delete')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  )
}
