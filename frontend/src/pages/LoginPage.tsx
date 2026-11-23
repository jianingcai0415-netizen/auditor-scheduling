/**
 * @file LoginPage.tsx
 * @brief Login screen.
 *
 * @details
 * Shown instead of the application until a session exists. It has no Navbar: users cannot yet
 * navigate anywhere from here, and offering an inactive menu would be confusing.
 *
 * The error message is intentionally the same for an unknown user and an incorrect password:
 * more detail would allow account enumeration by trying usernames one at a time.
 */

import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faRightToBracket, faUser, faLock, faUserPlus } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { InvalidCredentialsError, InactiveAccountError } from '../api/auth'
import AuthLanguageSelect from '../components/AuthLanguageSelect'

export default function LoginPage() {
  const { t } = useTranslation()
  const { login, refresh } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!username.trim() || !password) return
    setBusy(true)
    setError(null)
    try {
      await login(username.trim(), password)
      // The backend set the cookie: reload global state and enter the application.
      await refresh()
      navigate('/', { replace: true })
    } catch (err) {
      if (err instanceof InactiveAccountError) {
        setError(t('login.inactive', 'Account pending approval or disabled. Contact an administrator.'))
      } else if (err instanceof InvalidCredentialsError) {
        setError(t('login.invalid', 'Incorrect username or password.'))
      } else {
        setError(t('login.unreachable', 'Server unreachable. Try again shortly.'))
      }
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '100vh' }}>
      <Card className="shadow-sm" style={{ width: '100%', maxWidth: 380 }}>
        <Card.Body className="p-4">
          <div className="d-flex justify-content-end mb-3"><AuthLanguageSelect /></div>
          <h5 className="mb-1">{t('login.title', 'Sign in')}</h5>
          <p className="text-muted small mb-4">
            {t('login.subtitle', 'Enter your credentials to manage shifts.')}
          </p>

          {/* Password-manager hints: ask them not to intervene.
              Actual behavior, to avoid overestimating these attributes:
              1Password (data-1p-ignore), LastPass (data-lpignore), and Dashlane
              (data-form-type="other") honor them; Chrome deliberately ignores
              autocomplete="off" on login forms so sites cannot disable password
              managers. Its native prompt may therefore reappear and can only be
              disabled in the browser settings. */}
          <Form onSubmit={submit} autoComplete="off" data-form-type="other">
            <Form.Group className="mb-3">
              <Form.Label className="small fw-semibold">{t('login.username', 'Username')}</Form.Label>
              <div className="input-group">
                <span className="input-group-text"><FontAwesomeIcon icon={faUser} /></span>
                <Form.Control
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  autoComplete="off"
                  autoFocus
                  disabled={busy}
                  data-1p-ignore
                  data-bwignore
                  data-lpignore="true"
                  data-form-type="other"
                />
              </div>
            </Form.Group>

            <Form.Group className="mb-4">
              <Form.Label className="small fw-semibold">{t('login.password', 'Password')}</Form.Label>
              <div className="input-group">
                <span className="input-group-text"><FontAwesomeIcon icon={faLock} /></span>
                <Form.Control
                  type="password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  autoComplete="off"
                  disabled={busy}
                  data-1p-ignore
                  data-bwignore
                  data-lpignore="true"
                  data-form-type="other"
                />
              </div>
            </Form.Group>

            {error && <Alert variant="danger" className="py-2 small">{error}</Alert>}

            <Button type="submit" variant="primary" className="w-100"
                    disabled={busy || !username.trim() || !password}>
              {busy
                ? <Spinner size="sm" />
                : <><FontAwesomeIcon icon={faRightToBracket} className="me-2" />{t('login.submit', 'Sign in')}</>}
            </Button>
          </Form>

          <hr className="my-4" />
          <div className="text-center">
            <span className="text-muted small d-block mb-2">
              {t('login.noAccount', 'Don\'t have an account?')}
            </span>
            <Link to="/register" className="btn btn-outline-primary btn-sm w-100">
              <FontAwesomeIcon icon={faUserPlus} className="me-2" />
              {t('login.register', 'Register')}
            </Link>
          </div>
        </Card.Body>
      </Card>
    </div>
  )
}
