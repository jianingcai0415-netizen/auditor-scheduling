/**
 * @file BackupSection.tsx
 * @brief Configuration → Backup section: listing, execution, download, and restore.
 *
 * @details
 * Automatic backups run at server-side intervals; here the head nurse can create one on demand,
 * download them, and — where supported by the engine and with double confirmation — restore one
 * previous state.
 *
 * Restore is NOT available on all engines: `restoreSupported` comes from settings and determines
 * whether the action is offered. SQLite and PostgreSQL first save the current state
 * (prerestore tag); the page reloads when the operation completes.
 */

import { useEffect, useState, useCallback, type FormEvent } from 'react'
import { Alert, Badge, Button, Card, Form, InputGroup, Spinner, Table } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faDatabase, faDownload, faClockRotateLeft, faFloppyDisk, faTrash } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import toast from 'react-hot-toast'
import { backupApi, type BackupInfo, type BackupSettings } from '../../api/backup'
import { errorBody, errorCode, setBackupAdminToken } from '../../api/client'
import { backendErrorText } from '../../i18n/backendErrors'
import ConfirmModal from '../ConfirmModal'

/** @brief Colored badge for the backup type. */
function TagBadge({ tag, t }: { tag?: string; t: (k: string, d: string) => string }) {
  switch (tag) {
    case 'auto':       return <Badge bg="secondary">{t('backup.tag.auto', 'Automatic')}</Badge>
    case 'manual':     return <Badge bg="primary">{t('backup.tag.manual', 'Manual')}</Badge>
    case 'preop':      return <Badge bg="warning" text="dark">{t('backup.tag.preop', 'Pre-operation')}</Badge>
    case 'prerestore': return <Badge bg="info" text="dark">{t('backup.tag.prerestore', 'Pre-restore')}</Badge>
    default:           return <Badge bg="light" text="dark">{tag ?? '—'}</Badge>
  }
}

export default function BackupSection() {
  const { t } = useTranslation()
  const [backups, setBackups] = useState<BackupInfo[]>([])
  const [loading, setLoading] = useState(false)
  const [running, setRunning] = useState(false)
  const [restoreTarget, setRestoreTarget] = useState<BackupInfo | null>(null)
  const [restoring, setRestoring] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<BackupInfo | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [settings, setSettings] = useState<BackupSettings | null>(null)
  const [savingSettings, setSavingSettings] = useState(false)
  /** @brief Last PERSISTED rotation values: lowering them is destructive (see saveSettings). */
  const [savedSettings, setSavedSettings] = useState<BackupSettings | null>(null)
  /** @brief Rotation settings awaiting confirmation because they lower retention/keep. */
  const [pendingSettings, setPendingSettings] = useState<BackupSettings | null>(null)
  /** @brief Engine capability, not a setting: determines whether restore is offered. */
  const [restoreSupported, setRestoreSupported] = useState(false)
  /** @brief Reason the backup is unavailable, exactly as received from the server. */
  const [unavailable, setUnavailable] = useState<string | null>(null)
  const [authRequired, setAuthRequired] = useState(false)
  const [adminToken, setAdminToken] = useState('')

  const settingsError = settings == null ? null
    : !Number.isInteger(settings.intervalMinutes) || settings.intervalMinutes < 1 || settings.intervalMinutes > 1440
      ? t('backup.validation.interval', 'The interval must be an integer between 1 and 1440 minutes.')
      : !Number.isInteger(settings.autoRetentionDays) || settings.autoRetentionDays < 1 || settings.autoRetentionDays > 3650
        ? t('backup.validation.autoDays', 'Automatic backups retention must be between 1 and 3650 days.')
        : !Number.isInteger(settings.otherRetentionDays) || settings.otherRetentionDays < 1 || settings.otherRetentionDays > 3650
          ? t('backup.validation.otherDays', 'Other backups retention must be between 1 and 3650 days.')
          : !Number.isInteger(settings.autoKeep) || settings.autoKeep < 1 || settings.autoKeep > 100000
            ? t('backup.validation.autoKeep', 'The maximum number of automatic backups must be between 1 and 100000.')
            : !Number.isInteger(settings.otherKeep) || settings.otherKeep < 1 || settings.otherKeep > 100000
              ? t('backup.validation.otherKeep', 'The maximum number of other backups must be between 1 and 100000.')
              : settings.autoRetentionDays * 1440 < settings.intervalMinutes * 2
                ? t('backup.validation.coherence', 'Automatic backups retention must cover at least two full backup intervals.')
                : null

  /**
   * @brief Actual error message when the backend sent a mapped one; otherwise a generic message.
   * @details Without this, "rejected, database intact" reached the user as "Error during restore",
   *          which reads as if data had been damaged.
   */
  const errorText = useCallback((e: unknown, fallbackKey: string, fallbackText: string) =>
    backendErrorText(errorCode(e), t) ?? t(fallbackKey, fallbackText), [t])

  /** @brief true when the server reports that backups cannot be run at all (501). */
  const isUnavailable = (e: unknown) => errorCode(e) === 'BACKUP_TOOLS_UNAVAILABLE'
  const isAuthRequired = (e: unknown) => errorCode(e) === 'BACKUP_ADMIN_AUTH_REQUIRED'
  const isAdminMisconfigured = (e: unknown) => errorCode(e) === 'BACKUP_ADMIN_TOKEN_NOT_CONFIGURED'

  const load = useCallback(() => {
    setLoading(true)
    backupApi.list()
      .then(backups => { setBackups(backups); setUnavailable(null) })
      .catch(e => {
        // One message only: when backup is unavailable, the panel reports it rather than a toast
        // repeated twice (both the list and settings requests failed).
        if (isAuthRequired(e)) setAuthRequired(true)
        else if (isUnavailable(e) || isAdminMisconfigured(e))
          setUnavailable(errorText(e, 'toast.errorLoad', 'Error while loading.'))
        else toast.error(errorText(e, 'toast.errorLoad', 'Error while loading.'))
      })
      .finally(() => setLoading(false))
  }, [errorText])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    backupApi.settings()
      .then(response => {
        setSettings(response)
        setSavedSettings(response)
        setRestoreSupported(response.restoreSupported !== false)
      })
      .catch(e => {
        if (isAuthRequired(e)) setAuthRequired(true)
        else if (isAdminMisconfigured(e)) setUnavailable(errorText(e, 'toast.errorLoad', 'Error while loading.'))
        else if (!isUnavailable(e)) toast.error(errorText(e, 'toast.errorLoad', 'Error while loading.'))
      })
  }, [t, errorText])

  /**
   * @brief The rotation applies DELETE rules: lowering retention days or max backups
   *        destroys files at the next rotation. Saved only after explicit confirmation;
   *        interval-only changes save immediately.
   */
  async function saveSettings() {
    if (!settings || settingsError) return
    const destructive = savedSettings != null && (
      settings.autoRetentionDays < savedSettings.autoRetentionDays ||
      settings.otherRetentionDays < savedSettings.otherRetentionDays ||
      settings.autoKeep < savedSettings.autoKeep ||
      settings.otherKeep < savedSettings.otherKeep)
    if (destructive) {
      setPendingSettings(settings)
      return
    }
    await persistSettings(settings)
  }

  async function persistSettings(toSave: BackupSettings) {
    setSavingSettings(true)
    try {
      const saved = await backupApi.saveSettings(toSave)
      setSettings(saved)
      setSavedSettings(saved)
      toast.success(t('toast.backupSettingsSaved', 'Backup settings saved.'))
      load()
    } catch {
      toast.error(t('toast.backupSettingsError', 'Error saving backup settings.'))
    } finally {
      setSavingSettings(false)
    }
  }

  async function confirmSettingsSave() {
    if (!pendingSettings) return
    const toSave = pendingSettings
    setPendingSettings(null)
    await persistSettings(toSave)
  }

  async function runNow() {
    setRunning(true)
    try {
      await backupApi.run()
      toast.success(t('toast.backupDone', 'Backup completed!'))
      load()
    } catch (e) {
      toast.error(errorText(e, 'toast.backupError', 'Error during backup.'))
    } finally {
      setRunning(false)
    }
  }

  async function confirmRestore() {
    if (!restoreTarget) return
    setRestoring(true)
    try {
      await backupApi.restore(restoreTarget.filename)
      toast.success(t('toast.restored', 'Database restored. Reloading the page…'))
      setTimeout(() => window.location.reload(), 1200)
    } catch (e) {
      const body = errorBody(e)
      const recoveryFile = typeof body?.recoveryFile === 'string' ? body.recoveryFile : null
      const message = errorText(e, 'toast.restoreError', 'Error during restore.')
      toast.error(recoveryFile
        ? `${message} ${t('backup.recoveryFile', 'Manual intervention required: use the emergency backup')} ${recoveryFile}.`
        : message,
      { duration: recoveryFile ? 12_000 : 4_000 })
      setRestoring(false)
      setRestoreTarget(null)
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await backupApi.remove(deleteTarget.filename)
      setBackups(current => current.filter(b => b.filename !== deleteTarget.filename))
      toast.success(t('toast.backupDeleted', 'Backup deleted.'))
      setDeleteTarget(null)
    } catch (e) {
      toast.error(errorText(e, 'toast.backupDeleteError', 'Error deleting the backup.'))
    } finally {
      setDeleting(false)
    }
  }

  function unlockBackupAdmin(event: FormEvent) {
    event.preventDefault()
    if (!adminToken.trim()) return
    setBackupAdminToken(adminToken)
    window.location.reload()
  }

  function lockBackupAdmin() {
    setBackupAdminToken('')
    window.location.reload()
  }

  async function downloadBackup(backup: BackupInfo) {
    try {
      const blob = await backupApi.download(backup.filename)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = backup.filename
      anchor.style.display = 'none'
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (e) {
      if (isAuthRequired(e)) setAuthRequired(true)
      else toast.error(errorText(e, 'toast.errorDownload', 'Error while downloading.'))
    }
  }

  const fmtSize = (bytes: number) => bytes >= 1024 * 1024
    ? `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('config.menu.backup', 'Backup')}</h5>
        <div className="d-flex gap-2">
          {!authRequired && (settings !== null || backups.length > 0) && (
            <Button variant="outline-secondary" size="sm" onClick={lockBackupAdmin}>
              {t('backup.lock', 'Lock')}
            </Button>
          )}
          <Button variant="primary" size="sm" onClick={runNow}
            disabled={running || unavailable !== null || authRequired}>
            {running ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faDatabase} className="me-1" />{t('backup.runNow', 'Back up now')}</>}
          </Button>
        </div>
      </div>

      {unavailable && <Alert variant="warning" className="py-2 small">{unavailable}</Alert>}

      {authRequired && (
        <Alert variant="warning">
          <div className="fw-semibold mb-2">
            {t('backup.adminAuthRequired', 'This section requires the backup administrator token.')}
          </div>
          <Form onSubmit={unlockBackupAdmin} className="d-flex gap-2">
            <Form.Control type="password" value={adminToken} autoComplete="off"
              placeholder={t('backup.adminToken', 'Administrator token')}
              onChange={event => setAdminToken(event.target.value)} />
            <Button type="submit" disabled={!adminToken.trim()}>
              {t('backup.unlock', 'Unlock')}
            </Button>
          </Form>
        </Alert>
      )}

      <p className="text-muted small">
        {t('backup.hintAuto', 'Automatic backup runs at regular intervals and before operations that overwrite shifts.')}
        {restoreSupported && ' ' + t('backup.hintRestore', 'A safety snapshot is created before restore to enable recovery.')}
      </p>

      {settings && (
        <Card className="mb-3 shadow-sm">
          <Card.Header className="fw-semibold py-2">{t('backup.rotationSettings', 'Automatic rotation')}</Card.Header>
          <Card.Body className="py-3">
            <div className="d-flex flex-wrap gap-3 align-items-end">
              <div style={{ width: 210 }}>
                <Form.Label className="small fw-semibold mb-1">{t('backup.intervalMinutes', 'Automatic interval')}</Form.Label>
                <InputGroup size="sm">
                  <Form.Control type="number" min={1} max={1440} value={settings.intervalMinutes}
                    onChange={e => setSettings({ ...settings, intervalMinutes: Number(e.target.value) })} />
                  <InputGroup.Text>min</InputGroup.Text>
                </InputGroup>
              </div>
              <div style={{ width: 220 }}>
                <Form.Label className="small fw-semibold mb-1">{t('backup.autoRetentionDays', 'Automatic backups retention')}</Form.Label>
                <InputGroup size="sm">
                  <Form.Control type="number" min={1} max={3650} value={settings.autoRetentionDays}
                    onChange={e => setSettings({ ...settings, autoRetentionDays: Number(e.target.value) })} />
                  <InputGroup.Text>{t('backup.days', 'days')}</InputGroup.Text>
                </InputGroup>
              </div>
              <div style={{ width: 220 }}>
                <Form.Label className="small fw-semibold mb-1">{t('backup.otherRetentionDays', 'Other backups retention')}</Form.Label>
                <InputGroup size="sm">
                  <Form.Control type="number" min={1} max={3650} value={settings.otherRetentionDays}
                    onChange={e => setSettings({ ...settings, otherRetentionDays: Number(e.target.value) })} />
                  <InputGroup.Text>{t('backup.days', 'days')}</InputGroup.Text>
                </InputGroup>
              </div>
              <div style={{ width: 220 }}>
                <Form.Label className="small fw-semibold mb-1">{t('backup.autoKeep', 'Max automatic backups')}</Form.Label>
                <InputGroup size="sm">
                  <Form.Control type="number" min={1} max={100000} value={settings.autoKeep}
                    onChange={e => setSettings({ ...settings, autoKeep: Number(e.target.value) })} />
                  <InputGroup.Text>{t('backup.files', 'files')}</InputGroup.Text>
                </InputGroup>
              </div>
              <div style={{ width: 220 }}>
                <Form.Label className="small fw-semibold mb-1">{t('backup.otherKeep', 'Max other backups')}</Form.Label>
                <InputGroup size="sm">
                  <Form.Control type="number" min={1} max={100000} value={settings.otherKeep}
                    onChange={e => setSettings({ ...settings, otherKeep: Number(e.target.value) })} />
                  <InputGroup.Text>{t('backup.files', 'files')}</InputGroup.Text>
                </InputGroup>
              </div>
              <Button size="sm" className="px-3" onClick={saveSettings} disabled={savingSettings || settingsError !== null}>
                {savingSettings ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.save', 'Save')}</>}
              </Button>
            </div>
            <Form.Text className="text-muted d-block mt-2">
              {t('backup.rotationHint', 'Files older than the specified days are deleted on rotation; the maximum backup count limits also remain active.')}
            </Form.Text>
            {settingsError && <div className="text-danger small mt-1">{settingsError}</div>}
          </Card.Body>
        </Card>
      )}

      {loading ? (
        <div className="text-center py-4"><Spinner /></div>
      ) : backups.length === 0 ? (
        // Without this distinction, unavailable backups promised an automatic first backup
        // that would never arrive, misleading readers into believing they were protected.
        <p className="text-muted">{unavailable
          ? t('backup.emptyUnavailable', 'No backups: the feature is currently unavailable.')
          : t('backup.empty', 'No backups yet: the first one will be created automatically.')}</p>
      ) : (
        <Table hover bordered responsive size="sm" className="align-middle">
          <thead className="table-dark">
            <tr>
              <th style={{ width: 180 }}>{t('table.date', 'Date')}</th>
              <th style={{ width: 140 }}>{t('table.type', 'Type')}</th>
              <th className="text-end" style={{ width: 110 }}>{t('backup.size', 'Size')}</th>
              <th>{t('backup.file', 'File')}</th>
              <th className="text-center" style={{ width: 150 }}>{t('table.actions', 'Actions')}</th>
            </tr>
          </thead>
          <tbody>
            {backups.map(b => (
              <tr key={b.filename}>
                <td>{b.timestamp ?? '—'}</td>
                <td><TagBadge tag={b.tag} t={t} /></td>
                <td className="text-end">{fmtSize(b.size)}</td>
                <td className="text-muted small">{b.filename}</td>
                <td className="text-center">
                  <Button
                    variant="link" size="sm" className="p-0 me-3"
                    title={t('backup.download', 'Download the backup')}
                    onClick={() => downloadBackup(b)}
                  >
                    <FontAwesomeIcon icon={faDownload} />
                  </Button>
                  {restoreSupported && (
                    <Button
                      variant="link" size="sm" className="p-0 me-3 text-danger"
                      title={t('backup.restore', 'Restore this backup')}
                      onClick={() => setRestoreTarget(b)}
                    >
                      <FontAwesomeIcon icon={faClockRotateLeft} />
                    </Button>
                  )}
                  <Button
                    variant="link" size="sm" className="p-0 text-danger"
                    title={t('backup.delete', 'Delete this backup')}
                    onClick={() => setDeleteTarget(b)}
                  >
                    <FontAwesomeIcon icon={faTrash} />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <ConfirmModal
        show={restoreTarget !== null}
        title={t('backup.restoreTitle', 'Restore database')}
        message={`${t('backup.restoreMsg', 'The database will be reverted to its state as of')} ${restoreTarget?.timestamp ?? ''}. ${t('backup.restoreMsg2', 'Data entered after that moment will be lost (the current state is saved first anyway). Continue?')}`}
        confirmLabel={t('backup.restoreConfirm', 'Restore')}
        confirmVariant="danger"
        loading={restoring}
        onConfirm={confirmRestore}
        onClose={() => setRestoreTarget(null)}
      />
      <ConfirmModal
        show={deleteTarget !== null}
        title={t('backup.deleteTitle', 'Delete backup')}
        message={`${t('backup.deleteMsg', 'Permanently delete this backup?')} ${deleteTarget?.filename ?? ''}`}
        confirmLabel={t('backup.deleteConfirm', 'Delete')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
      <ConfirmModal
        show={pendingSettings !== null}
        title={t('backup.confirmRetentionTitle', 'Confirm rotation')}
        message={t('backup.confirmRetentionMsg', 'You are lowering the retention or the maximum number of backups: at the next rotation, files beyond the new limits will be permanently deleted. Continue?')}
        confirmVariant="danger"
        loading={savingSettings}
        onConfirm={confirmSettingsSave}
        onClose={() => setPendingSettings(null)}
      />
    </div>
  )
}
