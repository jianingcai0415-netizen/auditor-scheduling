import { useEffect, useState, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Table, Spinner, Modal, Form, Row, Col } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faUserPlus, faUserSlash, faUserCheck } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { usersApi, type AppUser, type CreateUserPayload, type UpdateUserPayload, type UserRole } from '../api/users'
import { errorCode } from '../api/client'
import { backendErrorText } from '../i18n/backendErrors'
import { useAuth } from '../auth/AuthContext'
import ConfirmModal from '../components/ConfirmModal'
import { tx } from '../i18n/uiText'

const ROLES: { value: UserRole; label: string }[] = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'DISPATCHER', label: '调度员' },
  { value: 'APPROVER', label: '审批人' },
  { value: 'VIEWER', label: '只读查看' },
  { value: 'CAPOSALA', label: '调度员（旧角色）' },
]

export default function UsersPage() {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (!isAdmin) navigate('/', { replace: true })
  }, [isAdmin, navigate])

  const currentUsername = useAuth().session?.username
  const [users, setUsers] = useState<AppUser[]>([])
  const [loading, setLoading] = useState(false)
  const loadGeneration = useRef(0)

  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)
  const [form, setForm] = useState({ username: '', displayName: '', email: '', role: 'DISPATCHER' as UserRole, rawPassword: '' })
  const [saving, setSaving] = useState(false)

  const [deactivateTarget, setDeactivateTarget] = useState<AppUser | null>(null)
  const [deactivating, setDeactivating] = useState(false)
  const [activateTarget, setActivateTarget] = useState<AppUser | null>(null)
  const [activating, setActivating] = useState(false)

  const load = useCallback(() => {
    const generation = ++loadGeneration.current
    setLoading(true)
    usersApi.list()
      .then(data => { if (generation === loadGeneration.current) setUsers(data) })
      .catch(() => { if (generation === loadGeneration.current) toast.error(t('toast.errorLoad', 'Error while loading.')) })
      .finally(() => { if (generation === loadGeneration.current) setLoading(false) })
  }, [t])

  // Administrators only: without this condition, the GET still starts in the same
  // commit as the redirect, returns 403, and leaves an error toast on screen that survives
  // the page change.
  useEffect(() => { if (isAdmin) load() }, [isAdmin, load])

  function openAdd() {
    setEditId(null)
    setForm({ username: '', displayName: '', email: '', role: 'DISPATCHER', rawPassword: '' })
    setModalOpen(true)
  }

  function openEdit(user: AppUser) {
    setEditId(user.id)
    setForm({ username: user.username, displayName: user.displayName ?? '', email: user.email ?? '', role: user.role, rawPassword: '' })
    setModalOpen(true)
  }

  async function handleSave() {
    if (!form.username.trim()) {
      toast.error(t('msg.err.userUsernameRequired', 'Username is required'))
      return
    }
    if (!editId && !form.rawPassword.trim()) {
      toast.error(t('msg.err.userPasswordRequired', 'Password is required'))
      return
    }
    setSaving(true)
    try {
      if (editId) {
        const payload: UpdateUserPayload = { username: form.username.trim(), role: form.role, displayName: form.displayName.trim(), email: form.email.trim() }
        if (form.rawPassword.trim()) payload.rawPassword = form.rawPassword
        await usersApi.update(editId, payload)
        toast.success(t('toast.userSaved', 'User updated.'))
      } else {
        const payload: CreateUserPayload = { username: form.username.trim(), rawPassword: form.rawPassword, role: form.role, displayName: form.displayName.trim(), email: form.email.trim() }
        await usersApi.create(payload)
        toast.success(t('toast.userCreated', 'User created.'))
      }
      setModalOpen(false)
      load()
    } catch (e) {
      const code = errorCode(e)
      const msg = backendErrorText(code, t) ?? t('toast.errorSave', 'Error while saving.')
      toast.error(msg)
    } finally {
      setSaving(false)
    }
  }

  async function confirmDeactivate() {
    if (!deactivateTarget) return
    setDeactivating(true)
    try {
      await usersApi.deactivate(deactivateTarget.id)
      toast.success(t('toast.userDeactivated', 'User deactivated.'))
      setDeactivateTarget(null)
      load()
    } catch (e) {
      const code = errorCode(e)
      const msg = backendErrorText(code, t) ?? t('toast.errorDelete', "Error while deleting.")
      toast.error(msg)
    } finally {
      setDeactivating(false)
    }
  }

  async function confirmActivate() {
    if (!activateTarget) return
    setActivating(true)
    try {
      await usersApi.update(activateTarget.id, { active: true })
      toast.success(t('toast.userActivated', 'User activated.'))
      setActivateTarget(null)
      load()
    } catch (e) {
      const code = errorCode(e)
      const msg = backendErrorText(code, t) ?? t('toast.errorSave', 'Error while saving.')
      toast.error(msg)
    } finally {
      setActivating(false)
    }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('nav.users', 'Users')}</h5>
        <Button variant="primary" size="sm" title={t('btn.addUser', 'Add user')} onClick={openAdd}>
          <FontAwesomeIcon icon={faUserPlus} className="me-2" />
          {t('btn.add', 'Add')}
        </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : users.length === 0 ? (
        <p className="text-muted">{t('users.empty', 'No users.')}</p>
      ) : (
        <Table hover bordered responsive size="sm">
          <thead className="table-dark">
            <tr>
              <th style={{ width: 180 }}>{t('login.username', 'Username')}</th>
              <th style={{ width: 180 }}>{t('user.displayName', 'Display name')}</th>
              <th>{t('user.email', 'Email')}</th>
              <th style={{ width: 150 }}>{t('user.role', 'Role')}</th>
              <th style={{ width: 100 }}>{t('label.active', 'Active')}</th>
              <th style={{ width: 180 }}>{t('col.createdAt', 'Created on')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Actions')}</th>
            </tr>
          </thead>
          <tbody>
            {users.map(user => (
              <tr key={user.id} className={!user.active ? 'table-secondary' : undefined}>
                <td><code>{user.username}</code></td>
                <td>{user.displayName || '—'}</td>
                <td className="text-muted small">{user.email || '—'}</td>
                <td>
                  <span className={`badge ${user.role === 'ADMIN' ? 'bg-primary' : user.role === 'APPROVER' ? 'bg-success' : 'bg-secondary'}`}>
                    {tx(ROLES.find(role => role.value === user.role)?.label ?? user.role)}
                  </span>
                </td>
                <td className="text-center">
                  {user.active
                    ? <span className="badge bg-success">{t('label.activeYes', 'Yes')}</span>
                    : <span className="badge bg-warning text-dark">{t('user.pending', 'Pending')}</span>}
                </td>
                <td className="text-muted small">{user.createdAt || '—'}</td>
                <td>
                  <Button
                    variant="link" size="sm" className="p-0 me-3 text-primary"
                    title={t('btn.edit', 'Edit')}
                    onClick={() => openEdit(user)}
                  >
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  {!user.active && (
                    <Button
                      variant="link" size="sm" className="p-0 me-3 text-success"
                      title={t('btn.approve', 'Approve / activate')}
                      onClick={() => setActivateTarget(user)}
                    >
                      <FontAwesomeIcon icon={faUserCheck} />
                    </Button>
                  )}
                  {user.active && user.username !== currentUsername && (
                    <Button
                      variant="link" size="sm" className="p-0 text-danger"
                      title={t('btn.deactivate', 'Deactivate')}
                      onClick={() => setDeactivateTarget(user)}
                    >
                      <FontAwesomeIcon icon={faUserSlash} />
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <Modal show={modalOpen} onHide={() => setModalOpen(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>{editId ? t('modal.editUser', 'Edit user') : t('modal.addUser', 'New user')}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Form>
            <Form.Group className="mb-3">
              <Form.Label>{t('login.username', 'Username')}</Form.Label>
              <Form.Control
                autoFocus
                value={form.username}
                onChange={e => setForm({ ...form, username: e.target.value })}
                placeholder={t('login.username', 'Username')}
                maxLength={100}
                required
              />
            </Form.Group>
            <Form.Group className="mb-3">
              <Form.Label>{t('user.displayName', 'Display name')}</Form.Label>
              <Form.Control
                value={form.displayName}
                onChange={e => setForm({ ...form, displayName: e.target.value })}
                placeholder={t('user.displayNamePlaceholder', 'e.g. Mario Rossi')}
                maxLength={100}
              />
            </Form.Group>
            <Form.Group className="mb-3">
              <Form.Label>{t('user.email', 'Email')}</Form.Label>
              <Form.Control
                type="email"
                value={form.email}
                onChange={e => setForm({ ...form, email: e.target.value })}
                placeholder={t('placeholder.email', 'name@example.com')}
                maxLength={200}
              />
            </Form.Group>
            <Row className="mb-3">
              <Col>
                <Form.Label>{t('user.role', 'Role')}</Form.Label>
                <Form.Select value={form.role} onChange={e => setForm({ ...form, role: e.target.value as UserRole })}>
                  {ROLES.map(r => (
                    <option key={r.value} value={r.value}>
                      {tx(r.label)}
                    </option>
                  ))}
                </Form.Select>
              </Col>
            </Row>
            <Form.Group className="mb-3">
              <Form.Label>
                {editId ? t('user.newPassword', 'New password (leave blank to keep unchanged)') : t('login.password', 'Password')}
              </Form.Label>
              <Form.Control
                type="password"
                value={form.rawPassword}
                onChange={e => setForm({ ...form, rawPassword: e.target.value })}
                placeholder={t('login.password', 'Password')}
                autoComplete="new-password"
                required={!editId}
              />
            </Form.Group>
          </Form>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setModalOpen(false)} disabled={saving}>
            {t('btn.cancel', 'Cancel')}
          </Button>
          <Button variant="primary" onClick={handleSave} disabled={saving}>
            {saving ? <Spinner size="sm" /> : t('btn.save', 'Save')}
          </Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={deactivateTarget !== null}
        title={t('user.deactivateTitle', 'Deactivate user')}
        message={`${t('user.deactivateMsg', "The user")} "${deactivateTarget?.username ?? ''}" ${t('user.deactivateMsg2', 'will be deactivated and will no longer be able to sign in.')}`}
        confirmLabel={t('btn.deactivate', 'Deactivate')}
        confirmVariant="warning"
        loading={deactivating}
        onConfirm={confirmDeactivate}
        onClose={() => setDeactivateTarget(null)}
      />

      <ConfirmModal
        show={activateTarget !== null}
        title={t('user.activateTitle', 'Approve user')}
        message={`${t('user.activateMsg', "The user")} "${activateTarget?.username ?? ''}" ${t('user.activateMsg2', 'will be activated and will be able to sign in.')}`}
        confirmLabel={t('btn.approve', 'Approve / activate')}
        confirmVariant="success"
        loading={activating}
        onConfirm={confirmActivate}
        onClose={() => setActivateTarget(null)}
      />
    </div>
  )
}
