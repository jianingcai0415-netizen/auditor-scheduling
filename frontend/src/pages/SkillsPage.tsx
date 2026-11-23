/**
 * @file SkillsPage.tsx
 * @brief Skills managed per company.
 *
 * @details Two levels, like General Settings: first the structure list with each structure's
 *          skill count, then — through the pencil icon — management of that structure.
 *          Skills belong to exactly one structure, so all of them can be managed here
 *          without changing the structure in the top bar.
 */

import { useEffect, useState, useCallback } from 'react'
import { Button, Table, Spinner, Modal } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPlus, faTrash, faPenToSquare } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { skillsApi, type Skill } from '../api/skills'
import { structuresApi, type Structure } from '../api/structures'
import { refreshTranslations } from '../i18n'
import ConfirmModal from '../components/ConfirmModal'
import SkillModal from '../components/skills/SkillModal'

interface SkillsPageProps {
  embedded?: boolean
}

/** @brief Structure with the number of skills it owns. */
interface StructureRow {
  structure: Structure
  count: number
}

export default function SkillsPage({ embedded = false }: SkillsPageProps) {
  const { t } = useTranslation()

  const [structureRows, setStructureRows] = useState<StructureRow[]>([])
  const [loadingStructures, setLoadingStructures] = useState(false)
  /** Structure being managed: null = show the company list. */
  const [selected, setSelected] = useState<Structure | null>(null)

  const [rows, setRows] = useState<Skill[]>([])
  const [loading, setLoading] = useState(false)
  const [modalTarget, setModalTarget] = useState<Skill | null | false>(false)
  const [deleteTarget, setDeleteTarget] = useState<Skill | null>(null)
  const [deleting, setDeleting] = useState(false)

  const structureId = selected?.id ?? 0

  const loadStructures = useCallback(() => {
    setLoadingStructures(true)
    structuresApi.list()
      .then(async structures => {
        // The count makes it immediately clear that the lists are separate.
        const counts = await Promise.all(
          structures.map(s => skillsApi.list(s.id).then(list => list.length).catch(() => 0)))
        setStructureRows(structures.map((s, i) => ({ structure: s, count: counts[i] })))
      })
      .catch(() => toast.error(t('toast.errorLoad', 'Error while loading.')))
      .finally(() => setLoadingStructures(false))
  }, [t])

  const load = useCallback(() => {
    if (!structureId) return
    setLoading(true)
    skillsApi.list(structureId)
      .then(setRows)
      .catch(() => toast.error(t('msg.error.loadSkills', 'Error loading skills.')))
      .finally(() => setLoading(false))
  }, [structureId, t])

  useEffect(() => { if (!selected) loadStructures() }, [selected, loadStructures])
  useEffect(() => { load() }, [load])

  async function saveSkill(name: string, order: number, active: boolean, languageId?: number, isDefaultLang?: boolean) {
    const trimmedName = name.trim()
    if (!trimmedName) {
      toast.error(t('skills.namesRequired', 'All names are required.'))
      return
    }
    // The base `name` column reflects the key language: update it only on creation
    // or when editing in that language; otherwise retain the existing base name.
    const nameColumn = (!modalTarget || isDefaultLang) ? trimmedName : (modalTarget.name || trimmedName)
    const updatedRows = modalTarget
      ? rows.map(row => row.id === modalTarget.id ? { ...row, name: nameColumn, order, active } : row)
      : [...rows, { id: 0, name: trimmedName, order, used: false, active }]
    try {
      const targetId = modalTarget && typeof modalTarget === 'object' ? modalTarget.id : 0
      await skillsApi.save(structureId, updatedRows.map(row => {
        const isTarget = modalTarget
          ? row.id === targetId
          : row.id === 0 && row.name === trimmedName
        return {
        id: row.id || null,
        name: row.name,
        order: row.order,
        used: row.used ?? false,
        active: row.active ?? true,
        translationLanguageId: isTarget ? (languageId ?? undefined) : undefined,
        translationValue: isTarget && languageId ? trimmedName : undefined,
        }
      }))
      const fresh = await skillsApi.list(structureId)
      await refreshTranslations()
      toast.success(t('skills.saved', 'Skills saved!'))
      setModalTarget(false)
      setRows(fresh)
    } catch {
      toast.error(t('msg.error.saveSkills', 'Error saving skills.'))
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await skillsApi.delete(structureId, deleteTarget.id)
      setDeleteTarget(null)
      toast.success(t('skills.deleted', 'Skill deleted.'))
      load()
    } catch {
      toast.error(t('msg.error.deleteSkill', "Error deleting skill."))
    } finally {
      setDeleting(false)
    }
  }

  const nextOrder = rows.length > 0 ? Math.max(...rows.map(row => row.order)) + 1 : 1
  const title = embedded ? t('config.menu.skills', 'Skills') : t('nav.skills', 'Skills')

  /** Closes the modal and realigns counts: additions and removals change them. */
  function closeSkills() {
    setSelected(null)
    setRows([])
    loadStructures()
  }

  return (
    <>
      <div>
        <h5 className="mb-3">{title}</h5>
        <p className="text-muted small">
          {t('config.skills.listHint',
            'Skills belong to each structure. Use the pencil to manage them.')}
        </p>
        {loadingStructures ? (
          <div className="text-center py-5"><Spinner /></div>
        ) : (
          <Table hover bordered responsive size="sm" style={{ maxWidth: 720 }}>
            <thead className="table-dark">
              <tr>
                <th style={{ width: 55 }}>{t('table.id', 'ID')}</th>
                <th>{t('col.company', 'Company')}</th>
                <th style={{ width: 160 }}>{t('config.skills.col.count', 'Skills')}</th>
                <th style={{ width: 90 }}>{t('col.actions', 'Actions')}</th>
              </tr>
            </thead>
            <tbody>
              {structureRows.length === 0 ? (
                <tr><td colSpan={4} className="text-center text-muted">
                  {t('structures.noResults', 'No structures.')}
                </td></tr>
              ) : structureRows.map(row => (
                <tr key={row.structure.id}>
                  <td className="text-muted align-middle">{row.structure.id}</td>
                  <td className="align-middle">{row.structure.name}</td>
                  <td className="align-middle">{row.count}</td>
                  <td className="align-middle">
                    <Button
                      variant="link" size="sm" className="p-0"
                      title={t('btn.edit', 'Edit')}
                      onClick={() => setSelected(row.structure)}
                    >
                      <FontAwesomeIcon icon={faPenToSquare} />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </div>

      {/* Skills for the selected structure: modal, as in Structures and General Settings.
          The modal for editing an individual skill opens above this one. */}
      <Modal show={selected !== null} onHide={closeSkills} size="lg" centered>
        <Modal.Header closeButton>
          <Modal.Title>{title}{selected ? ` — ${selected.name}` : ''}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
      <div className="d-flex justify-content-end mb-3">
          <Button
            variant="outline-primary"
            size="sm"
            title={t('skills.addTooltip', 'Add a skill')}
            onClick={() => setModalTarget(null)}
          >
            <FontAwesomeIcon icon={faPlus} className="me-2" />{t('skills.new', 'New')}
          </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : (
        <Table hover bordered responsive size="sm">
          <thead className="table-dark">
            <tr>
              <th style={{ width: 60 }}>{t('table.id', 'ID')}</th>
              <th>{t('table.name', 'Name')}</th>
              <th style={{ width: 90 }}>{t('table.order', 'Order')}</th>
              <th style={{ width: 90 }}>{t('skills.active', 'Active')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Actions')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr><td colSpan={5} className="text-center text-muted">{t('skills.noResults', 'No skills.')}</td></tr>
            ) : rows.map(row => (
              <tr key={row.id}>
                <td className="text-muted align-middle">{row.id}</td>
                <td className="align-middle">{t('skill.' + row.id, row.name)}</td>
                <td className="align-middle">{row.order}</td>
                <td className="align-middle"><span className={`badge ${row.active ? 'bg-success' : 'bg-secondary'}`}>{row.active ? t('common.yes', 'Yes') : t('common.no', 'No')}</span></td>
                <td className="align-middle">
                  <Button
                    variant="link" size="sm" className="p-0 me-3 text-primary"
                    title={t('tooltip.edit', 'Edit')}
                    onClick={() => setModalTarget(row)}
                  >
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  {row.used ? (
                    <FontAwesomeIcon
                      icon={faTrash}
                      style={{ color: '#f5a0a0', cursor: 'not-allowed', fontSize: '0.85rem' }}
                      title={t('skills.inUseTooltip', 'Skill in use — cannot be deleted')}
                    />
                  ) : (
                    <Button
                      variant="link" size="sm" className="p-0 text-danger"
                      title={t('tooltip.delete', 'Delete')}
                      onClick={() => setDeleteTarget(row)}
                    >
                      <FontAwesomeIcon icon={faTrash} />
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={closeSkills}>{t('btn.close', 'Close')}</Button>
        </Modal.Footer>
      </Modal>

      <SkillModal
        show={modalTarget !== false}
        skill={modalTarget || null}
        nextOrder={nextOrder}
        onClose={() => setModalTarget(false)}
        onSave={saveSkill}
        structureId={structureId}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('skills.deleteTitle', 'Delete skill')}
        message={`${t('confirm.deleteSkill', 'Are you sure you want to delete this skill?')} "${deleteTarget ? t('skill.' + deleteTarget.id, deleteTarget.name) : ''}"`}
        confirmLabel={t('btn.delete', 'Delete')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </>
  )
}
