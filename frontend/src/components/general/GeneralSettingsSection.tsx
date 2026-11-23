import { useCallback, useEffect, useState } from 'react'
import { Button, Form, Modal, Spinner, Table, ToggleButton, ToggleButtonGroup } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFloppyDisk, faPen } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { structuresApi, type Structure } from '../../api/structures'
import { generalSettingsApi, type GeneralSettings } from '../../api/generalSettings'
import { useAppStore } from '../../store/useAppStore'

type RowData = { structure: Structure; settings: GeneralSettings }

/** Modal for editing a structure's general settings. */
function SettingsModal({ row, onClose, onSaved }: { row: RowData | null; onClose: () => void; onSaved: (saved: GeneralSettings) => void }) {
  const { t } = useTranslation()
  const [form, setForm] = useState<GeneralSettings | null>(null)
  const [saving, setSaving] = useState(false)
  useEffect(() => setForm(row ? { ...row.settings } : null), [row])
  if (!row || !form) return null

  async function save() {
    setSaving(true)
    try {
      const saved = await generalSettingsApi.save(row!.structure.id, form!)
      toast.success(t('toast.generalSettingsSaved', 'General settings saved.'))
      onSaved(saved)
      onClose()
    } catch {
      toast.error(t('toast.errorSave', 'Error while saving.'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal show onHide={onClose} centered>
      <Modal.Header closeButton>
        <Modal.Title>{t('config.menu.general', 'General settings')} — {row.structure.name}</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <Form.Label className="d-block">{t('config.shiftWindow', 'Shift window granularity')}</Form.Label>
        <ToggleButtonGroup
          type="radio" name="gs-shiftWindowMode" value={form.shift_window_mode}
          onChange={(val: 'week' | 'month') => setForm(f => f && ({ ...f, shift_window_mode: val }))}
        >
          <ToggleButton id="gs-swm-week" variant="outline-primary" value="week">{t('config.window.week', 'Week')}</ToggleButton>
          <ToggleButton id="gs-swm-month" variant="outline-primary" value="month">{t('config.window.month', 'Month')}</ToggleButton>
        </ToggleButtonGroup>
        <Form.Text className="d-block mt-2 text-muted">
          {t('config.shiftWindow.hint', 'Determines the window width and the arrow step in Shift Management.')}
        </Form.Text>
      </Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={onClose} disabled={saving}>{t('btn.cancel', 'Cancel')}</Button>
        <Button onClick={save} disabled={saving}>
          {saving ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.save', 'Save')}</>}
        </Button>
      </Modal.Footer>
    </Modal>
  )
}

export default function GeneralSettingsSection() {
  const { t } = useTranslation()
  const [rows, setRows] = useState<RowData[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<RowData | null>(null)
  const currentStructureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const setShiftWindowMode = useAppStore(s => s.setShiftWindowMode)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const structures = await structuresApi.list()
      const settings = await Promise.all(structures.map(s => generalSettingsApi.get(s.id)))
      setRows(structures.map((s, i) => ({ structure: s, settings: settings[i] })))
    } catch {
      toast.error(t('toast.errorLoad', 'Error while loading.'))
    } finally {
      setLoading(false)
    }
  }, [t])
  useEffect(() => { load() }, [load])

  /** On save, also updates the store when this is the current structure (Shift Management reflects it immediately). */
  function handleSaved(saved: GeneralSettings) {
    if (saved.structure_id === currentStructureId) {
      setShiftWindowMode(saved.shift_window_mode)
    }
    load()
  }

  if (loading) return <div className="text-center py-5"><Spinner /></div>

  return (
    <div>
      <h5 className="mb-3">{t('config.menu.general', 'General settings')}</h5>
      <p className="text-muted small">{t('config.general.listHint', 'Settings are per structure. Use the pencil to edit them.')}</p>
      <Table bordered hover responsive size="sm" className="align-middle" style={{ maxWidth: 900 }}>
        <thead className="table-dark"><tr>
          <th style={{ width: 55 }}>ID</th>
          <th>{t('col.company', 'Company')}</th>
          <th style={{ width: 160 }}>{t('config.general.col.window', 'Granularity')}</th>
          <th style={{ width: 90 }}>{t('col.actions', 'Actions')}</th>
        </tr></thead>
        <tbody>
          {rows.map(row => <tr key={row.structure.id}>
            <td className="text-muted">{row.structure.id}</td>
            <td className="fw-semibold">{row.structure.name}</td>
            <td>{row.settings.shift_window_mode === 'week' ? t('config.window.week', 'Week') : t('config.window.month', 'Month')}</td>
            <td>
              <Button variant="link" size="sm" className="p-0" title={t('btn.edit', 'Edit')} onClick={() => setEditing(row)}>
                <FontAwesomeIcon icon={faPen} />
              </Button>
            </td>
          </tr>)}
        </tbody>
      </Table>
      <SettingsModal row={editing} onClose={() => setEditing(null)} onSaved={handleSaved} />
    </div>
  )
}
