/**
 * @file LocationsPage.tsx
 * @brief Location management page.
 *
 * @details
 * On load, performs two operations in parallel:
 * - Location list with assigned skills, loaded in a single request
 * - Only IDs referenced by shifts, used to disable deletion
 *
 * Skills displayed as badges:
 * - Rosso (`bg-danger`) → skills richieste
 * - Giallo (`bg-warning`) → skills opzionali
 */

import { useEffect, useState, useCallback, useRef } from 'react'
import { Button, Table, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faTrash, faPlus } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { locationsApi, type Location } from '../api/locations'
import { shiftsApi } from '../api/shifts'
import { errorCode } from '../api/client'
import { backendErrorText } from '../i18n/backendErrors'
import { useAppStore } from '../store/useAppStore'
import LocationModal from '../components/locations/LocationModal'
import ConfirmModal from '../components/ConfirmModal'

export default function LocationsPage() {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const [locations, setLocations] = useState<Location[]>([])
  const [usedLocationIds, setUsedLocationIds] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(false)
  const loadGeneration = useRef(0)
  const currentStructureRef = useRef(structureId)
  const deleteGeneration = useRef(0)
  currentStructureRef.current = structureId

  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)

  const [deleteTarget, setDeleteTarget] = useState<{ location: Location; structureId: number } | null>(null)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(() => {
    const generation = ++loadGeneration.current
    setLoading(true)
    Promise.all([
      locationsApi.list(structureId),
      shiftsApi.usage(structureId),
    ])
      .then(([locs, usage]) => {
        if (generation !== loadGeneration.current) return
        setLocations(locs)
        setUsedLocationIds(new Set(usage.locationIds))
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

  function openAdd() { setEditId(null); setModalOpen(true) }
  function openEdit(id: number) { setEditId(id); setModalOpen(true) }

  async function confirmDelete() {
    if (!deleteTarget) return
    const generation = ++deleteGeneration.current
    setDeleting(true)
    try {
      await locationsApi.delete(deleteTarget.location.id, deleteTarget.structureId)
      if (generation !== deleteGeneration.current || deleteTarget.structureId !== currentStructureRef.current) return
      toast.success(t('toast.locationDeleted', 'Location deleted.'))
      setDeleteTarget(null)
      load()
    } catch (err) {
      // The 409 "location referenced by shifts or templates" has a precise cause: explaining it
      // prevents the user from retrying the same deletion without understanding why it fails.
      if (generation === deleteGeneration.current && deleteTarget.structureId === currentStructureRef.current) {
        toast.error(backendErrorText(errorCode(err), t) ?? t('toast.errorDelete', "Error while deleting."))
      }
    } finally {
      if (generation === deleteGeneration.current) setDeleting(false)
    }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('nav.locations', 'Locations')}</h5>
        <Button
          variant="primary"
          size="sm"
          title={t('btn.addLocation', 'Add Location')}
          onClick={openAdd}
        >
          <FontAwesomeIcon icon={faPlus} className="me-2" />
          {t('btn.add', 'Add')}
        </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : (
        <Table hover bordered responsive size="sm">
          <thead className="table-dark">
            <tr>
              <th>{t('table.code', 'Code')}</th>
              <th>{t('table.order', 'Order')}</th>
              <th>{t('table.name', 'Name')}</th>
              <th>{t('table.requiredSkills', 'R.Skills')}</th>
              <th>{t('table.optionalSkills', 'O.Skills')}</th>
              <th style={{ width: 80 }}>{t('label.active', 'Active')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Actions')}</th>
            </tr>
          </thead>
          <tbody>
            {locations.length === 0 ? (
              <tr><td colSpan={7} className="text-center text-muted">{t('msg.noLocations', 'No locations.')}</td></tr>
            ) : locations.map(loc => (
              <tr key={loc.id} className={loc.active === false ? 'table-secondary' : undefined}>
                <td><code>{loc.code}</code></td>
                <td>{loc.order}</td>
                <td>{t('location.' + loc.id, loc.name)}</td>
                <td>
                  {(loc.requiredSkills ?? []).filter(s => s.used).map(s => (
                    <span key={s.id} className="badge bg-danger me-1">{t('skill.' + s.id, s.name)}</span>
                  ))}
                </td>
                <td>
                  {(loc.optionalSkills ?? []).filter(s => s.used).map(s => (
                    <span key={s.id} className="badge bg-warning text-dark me-1">{t('skill.' + s.id, s.name)}</span>
                  ))}
                </td>
                <td className="text-center">
                  {loc.active === false
                    ? <span className="badge bg-secondary">{t('label.inactive', 'No')}</span>
                    : <span className="badge bg-success">{t('label.activeYes', 'Yes')}</span>}
                </td>
                <td>
                  <Button variant="link" size="sm" className="p-0 me-3 text-primary" title={t('tooltip.editLocation', 'Edit location')} onClick={() => openEdit(loc.id)}>
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  {usedLocationIds.has(loc.id) ? (
                    <FontAwesomeIcon
                      icon={faTrash}
                      style={{ color: '#f5a0a0', cursor: 'not-allowed', fontSize: '0.85rem' }}
                      title={t('tooltip.locationHasShifts', 'Location has assigned shifts — cannot be deleted')}
                    />
                  ) : (
                    <Button variant="link" size="sm" className="p-0 text-danger" title={t('tooltip.delete', 'Delete')} onClick={() => setDeleteTarget({ location: loc, structureId })}>
                      <FontAwesomeIcon icon={faTrash} />
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <LocationModal
        show={modalOpen}
        locationId={editId}
        structureId={structureId}
        onClose={() => setModalOpen(false)}
        onSaved={load}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('confirm.deleteLocationTitle', 'Delete Location')}
        message={`${t('confirm.deletePrefix', 'Are you sure you want to delete')} "${deleteTarget ? t('location.' + deleteTarget.location.id, deleteTarget.location.name) : ''}"?`}
        confirmLabel={t('btn.delete', 'Delete')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  )
}
