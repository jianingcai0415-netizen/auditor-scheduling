/**
 * @file EmailSettingsSection.tsx
 * @brief Configuration → Email Settings section: SMTP settings stored in the database.
 *
 * @details
 * Changes take effect IMMEDIATELY (the backend builds the SMTP client for every send using
 * database values); if the server is not configured here, the .env fallback remains active.
 * The password is write-only: never displayed; empty field = keep the saved value.
 * The "Send test email" button verifies the configuration with a real send.
 */

import { useEffect, useState } from 'react'
import { Button, Col, Form, Row, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFloppyDisk, faPaperPlane } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import toast from 'react-hot-toast'
import { emailApi, type EmailSettings } from '../../api/email'

const EMPTY: EmailSettings = { host: '', port: 587, start_tls: true, username: '', password: '', mail_from: '' }

export default function EmailSettingsSection() {
  const { t } = useTranslation()
  const [form, setForm] = useState<EmailSettings>(EMPTY)
  const [hasPassword, setHasPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testTo, setTestTo] = useState('')
  const [testing, setTesting] = useState(false)

  useEffect(() => {
    setLoading(true)
    emailApi.getSettings()
      .then(s => { setForm({ ...s, password: '' }); setHasPassword(!!s.has_password) })
      .catch(() => toast.error(t('toast.errorLoad', 'Error while loading.')))
      .finally(() => setLoading(false))
  }, [t])

  /** @brief Translates the backend error code into a clear message (same keys as Report). */
  function errorText(err: unknown): string {
    let code = ''
    try { code = JSON.parse(String((err as Error)?.message ?? '')).error ?? '' } catch { /* non-JSON text */ }
    switch (code) {
      case 'AUTH_FAILED': return t('error.email.auth', 'The mail server rejected the credentials: check the SMTP username and password in the configuration.')
      case 'CONNECTION_FAILED': return t('error.email.connection', 'Cannot reach the mail server: check your Internet connection and try again.')
      case 'SENDER_REJECTED': return t('error.email.sender', 'The server rejected the sender: the sender address is not authorized on the mail service.')
      case 'RECIPIENT_REJECTED': return t('error.email.recipient', 'The server rejected the recipient: check that the operator\'s email address is correct.')
      case 'QUOTA_EXCEEDED': return t('error.email.quota', 'The mail service sending limit was reached: try again later.')
      case 'SMTP_NOT_CONFIGURED': return t('error.email.notConfigured', 'The SMTP server is not configured: complete the Email Settings in Configuration.')
      case 'INVALID_EMAIL': return t('validation.emailInvalid', 'Enter a valid email address.')
      default: return t('error.email.generic', 'Sending failed due to a mail server problem: try again later.')
    }
  }

  async function handleSave() {
    setSaving(true)
    try {
      const saved = await emailApi.saveSettings(form)
      setForm({ ...saved, password: '' })
      setHasPassword(!!saved.has_password)
      toast.success(t('toast.emailSettingsSaved', 'Email settings saved: effective immediately.'))
    } catch {
      toast.error(t('toast.errorSave', 'Error while saving.'))
    } finally {
      setSaving(false)
    }
  }

  async function handleTest() {
    if (!testTo) return
    setTesting(true)
    try {
      const r = await emailApi.sendTest(testTo)
      toast.success(`${t('toast.testEmailSent', 'Test email sent to')} ${r.to}`)
    } catch (err: unknown) {
      toast.error(errorText(err))
    } finally {
      setTesting(false)
    }
  }

  if (loading) return <div className="text-center py-4"><Spinner /></div>

  const set = <K extends keyof EmailSettings>(key: K, value: EmailSettings[K]) =>
    setForm(f => ({ ...f, [key]: value }))

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('config.menu.emailSettings', 'Email Settings')}</h5>
        <Button variant="primary" size="sm" onClick={handleSave} disabled={saving}>
          {saving ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.save', 'Save')}</>}
        </Button>
      </div>

      <p className="text-muted small">
        {t('emailSet.hint', 'SMTP server settings for sending emails. Changes take effect immediately; if no server is configured here, the startup configuration (.env) is used.')}
      </p>

      <Row className="g-3" style={{ maxWidth: 720 }}>
        <Col sm={8}>
          <Form.Label className="fw-semibold">{t('emailSet.host', 'SMTP server')}</Form.Label>
          <Form.Control
            value={form.host}
            placeholder={t('placeholder.smtpHost', 'smtp.example.com')}
            onChange={e => set('host', e.target.value.replace(/\s+/g, ''))}
          />
        </Col>
        <Col sm={2}>
          <Form.Label className="fw-semibold">{t('emailSet.port', 'Port')}</Form.Label>
          <Form.Control
            type="number" min={1} max={65535}
            value={form.port}
            onChange={e => set('port', parseInt(e.target.value) || 587)}
          />
        </Col>
        <Col sm={2} className="d-flex align-items-end">
          <Form.Check
            type="switch" id="smtp-tls"
            label={t('emailSet.tls', 'STARTTLS')}
            checked={form.start_tls}
            onChange={e => set('start_tls', e.target.checked)}
          />
        </Col>

        <Col sm={6}>
          <Form.Label className="fw-semibold">{t('emailSet.username', 'Username')}</Form.Label>
          <Form.Control
            value={form.username}
            autoComplete="off"
            onChange={e => set('username', e.target.value.replace(/\s+/g, ''))}
          />
        </Col>
        <Col sm={6}>
          <Form.Label className="fw-semibold">{t('emailSet.password', 'Password')}</Form.Label>
          <Form.Control
            type="password"
            value={form.password}
            autoComplete="new-password"
            placeholder={hasPassword ? t('emailSet.passwordKept', '•••••• (leave empty to keep it)') : ''}
            onChange={e => set('password', e.target.value)}
          />
        </Col>

        <Col sm={12}>
          <Form.Label className="fw-semibold">{t('emailSet.from', 'Sender')}</Form.Label>
          <Form.Control
            value={form.mail_from}
            placeholder={t('emailSet.fromPlaceholder', 'Display Name <address@example.com>')}
            onChange={e => set('mail_from', e.target.value.replace(/^\s+/, ''))}
          />
        </Col>
      </Row>

      <hr className="my-4" />

      <Form.Label className="fw-semibold d-block">{t('emailSet.test', 'Test email')}</Form.Label>
      <div className="d-flex gap-2 align-items-center" style={{ maxWidth: 520 }}>
        <Form.Control
          type="email"
          value={testTo}
          placeholder={t('placeholder.email', 'name@example.com')}
          onChange={e => setTestTo(e.target.value.replace(/\s+/g, ''))}
        />
        <Button variant="outline-primary" onClick={handleTest} disabled={testing || !testTo} className="text-nowrap">
          {testing ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faPaperPlane} className="me-1" />{t('emailSet.sendTest', 'Send test')}</>}
        </Button>
      </div>
      <Form.Text className="text-muted">
        {t('emailSet.testHint', 'Sends a real email with the current settings (save your changes first).')}
      </Form.Text>
    </div>
  )
}
