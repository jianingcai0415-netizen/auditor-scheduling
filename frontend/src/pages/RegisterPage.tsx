/**
 * @file RegisterPage.tsx
 * @brief Self-service registration with mode-specific flows.
 *
 * @details Server mode (PostgreSQL): email → OTP code → profile, with a stepper,
 *          six code boxes, resend countdown, and password-strength indicator.
 *          Standalone mode (desktop SQLite): no email/OTP — go straight to
 *          username+password. The first user is created as an active ADMIN; others as HEAD_NURSE
 *          are created pending approval.
 */

import { useState, useEffect, useRef, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Button, Card, Form, ProgressBar, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import {
  faEnvelope, faKey, faUser, faLock, faArrowLeft, faCheck, faShieldHalved, faPaperPlane,
} from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import toast from 'react-hot-toast'
import { registerApi } from '../api/register'
import { errorCode } from '../api/client'
import { backendErrorText } from '../i18n/backendErrors'
import { useAuth } from '../auth/AuthContext'
import AuthLanguageSelect from '../components/AuthLanguageSelect'

type Step = 'email' | 'otp' | 'profile' | 'done'

const EMAIL_PATTERN = /^[^\s@]{1,64}@[^\s@]+\.[^\s@]{1,64}$/

const USERNAME_RE = /^[A-Za-z0-9_.-]{3,64}$/
const RESEND_SECONDS = 60

export default function RegisterPage() {
  const { t } = useTranslation()
  const { refresh, login } = useAuth()
  const [step, setStep] = useState<Step>('email')
  const [firstUser, setFirstUser] = useState(false)
  const [otpRequired, setOtpRequired] = useState(true)
  const [email, setEmail] = useState('')
  const [otp, setOtp] = useState('')
  const [token, setToken] = useState<string | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [resendIn, setResendIn] = useState(0)
  const otpInputs = useRef<(HTMLInputElement | null)[]>([])

  useEffect(() => {
    registerApi.status()
      .then(res => {
        setFirstUser(res.firstUser)
        setOtpRequired(res.otpRequired)
        // Standalone mode has no email/OTP steps: start from the profile.
        if (!res.otpRequired) setStep('profile')
      })
      .catch(() => { setFirstUser(false); setOtpRequired(true) })
  }, [])

  // Countdown for resending the OTP.
  useEffect(() => {
    if (resendIn <= 0) return
    const id = setTimeout(() => setResendIn(s => s - 1), 1000)
    return () => clearTimeout(id)
  }, [resendIn])

  function showError(err: unknown, fallback: string) {
    const code = errorCode(err)
    setError(backendErrorText(code, t) ?? fallback)
  }

  async function submitEmail(e: FormEvent) {
    e.preventDefault()
    const normalizedEmail = email.trim()
    if (!EMAIL_PATTERN.test(normalizedEmail)) {
      setError(t('msg.err.emailInvalid', 'Invalid email address.'))
      return
    }
    setBusy(true)
    setError(null)
    try {
      await registerApi.requestOtp(normalizedEmail)
      setResendIn(RESEND_SECONDS)
      setStep('otp')
      toast.success(t('register.otpSent', 'Code sent. Check your email.'))
    } catch (err) {
      showError(err, t('register.errorGeneric', 'An error occurred.'))
    } finally {
      setBusy(false)
    }
  }

  function handleOtpDigit(index: number, value: string) {
    const digit = value.replace(/\D/g, '').slice(-1)
    const next = otp.slice(0, index) + digit + otp.slice(index + 1)
    setOtp(next)
    if (digit && index < 5) otpInputs.current[index + 1]?.focus()
  }

  function handleOtpKeyDown(index: number, e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Backspace' && !otp[index] && index > 0) otpInputs.current[index - 1]?.focus()
  }

  async function submitOtp(e: FormEvent) {
    e.preventDefault()
    if (otp.length !== 6) return
    setBusy(true)
    setError(null)
    try {
      const res = await registerApi.verifyOtp(email.trim(), otp)
      setToken(res.token)
      setStep('profile')
    } catch (err) {
      showError(err, t('register.errorGeneric', 'An error occurred.'))
      setOtp('')
      otpInputs.current[0]?.focus()
    } finally {
      setBusy(false)
    }
  }

  async function resendOtp() {
    setBusy(true)
    setError(null)
    try {
      await registerApi.requestOtp(email.trim())
      setResendIn(RESEND_SECONDS)
      toast.success(t('register.otpSent', 'Code sent. Check your email.'))
    } catch (err) {
      showError(err, t('register.errorGeneric', 'An error occurred.'))
    } finally {
      setBusy(false)
    }
  }

  async function submitProfile(e: FormEvent) {
    e.preventDefault()
    if (!username.trim() || !password) return
    if (otpRequired && !token) return
    setBusy(true)
    setError(null)
    try {
      const res = await registerApi.complete(otpRequired ? token : null, username.trim(), password)
      // First user: the account is created as an already-active ADMIN, so sign in immediately
      // without making the user re-enter the credentials they just chose. Other accounts are
      // created pending approval: login would fail for them, so show the outcome.
      if (res?.admin) {
        try {
          await login(username.trim(), password)
          return // active session: routing takes the user home
        } catch {
          // Automatic login failed: continue with the manual flow.
        }
      }
      // The server declares the outcome, not the state read on mount: if someone else created
      // the administrator in the meantime, this account is pending approval, and showing
      // "you can sign in now" would be incorrect.
      setFirstUser(!res?.pendingApproval)
      // The account was created: update global state (needsFirstAdmin becomes false),
      // so "Back to login" actually goes to login rather than
      // being redirected back to registration.
      await refresh()
      setStep('done')
    } catch (err) {
      showError(err, t('register.errorGeneric', 'An error occurred.'))
    } finally {
      setBusy(false)
    }
  }

  function back() {
    setError(null)
    // Standalone mode has no preceding steps: return to login.
    if (!otpRequired) return
    setStep(prev => (prev === 'otp' ? 'email' : prev === 'profile' ? 'otp' : 'email'))
  }

  // ── Real-time validation ────────────────────────────────────────────────────

  const usernameValid = USERNAME_RE.test(username)
  const usernameHint = username.trim().length > 0 && !usernameValid

  const pwChecks = [
    password.length >= 8,
    /[A-Z]/.test(password) && /[a-z]/.test(password),
    /\d/.test(password),
    /[^A-Za-z0-9]/.test(password),
  ]
  const pwScore = pwChecks.filter(Boolean).length
  const pwVariant = pwScore <= 1 ? 'danger' : pwScore === 2 ? 'warning' : pwScore === 3 ? 'info' : 'success'
  const pwLabel = pwScore <= 1
    ? t('register.pwWeak', 'Weak')
    : pwScore === 2 ? t('register.pwFair', 'Fair')
    : pwScore === 3 ? t('register.pwGood', 'Good')
    : t('register.pwStrong', 'Strong')

  const steps: { id: Step; label: string; icon: typeof faEnvelope }[] = [
    { id: 'email', label: t('register.stepEmail', 'Email'), icon: faEnvelope },
    { id: 'otp', label: t('register.stepOtp', 'Code'), icon: faKey },
    { id: 'profile', label: t('register.stepProfile', 'Profile'), icon: faUser },
  ]
  const visibleSteps = otpRequired ? steps : steps.slice(2)
  const stepIndex = visibleSteps.findIndex(s => s.id === step)
  const normalizedEmail = email.trim()
  const emailIsValid = EMAIL_PATTERN.test(normalizedEmail)

  return (
    <div className="d-flex justify-content-center align-items-center py-4" style={{ minHeight: '100vh', background: 'linear-gradient(180deg, var(--bs-primary-bg-subtle) 0%, var(--bs-body-bg) 100%)' }}>
      <Card className="shadow border-0" style={{ width: '100%', maxWidth: 440, borderRadius: '1rem' }}>
        <Card.Body className="p-4">
          <div className="d-flex justify-content-end mb-2"><AuthLanguageSelect /></div>

          {/* Header */}
          <div className="text-center mb-4">
            <div className="mx-auto mb-2 d-flex align-items-center justify-content-center rounded-circle bg-primary text-white"
                 style={{ width: 52, height: 52 }}>
              <FontAwesomeIcon icon={faShieldHalved} size="lg" />
            </div>
            <h5 className="mb-1 fw-bold">
              {firstUser ? t('register.firstAdminTitle', 'Create the initial administrator') : t('register.title', 'Registration')}
            </h5>
            <p className="text-muted small mb-0">
              {firstUser
                ? (otpRequired
                    ? t('register.firstAdminSubtitle', 'You are the first person to use the application: verify your email and create the administrator account.')
                    : t('register.firstAdminStandaloneSubtitle', 'You are the first person to use the application: create the administrator account credentials.'))
                : (otpRequired
                    ? t('register.subtitle', 'Verify your email and create the shift management account.')
                    : t('register.standaloneSubtitle', 'Create the credentials for your shift management account.'))}
            </p>
          </div>

          {/* Stepper */}
          {step !== 'done' && (
            <div className="d-flex align-items-center justify-content-center mb-4">
              {steps.map((s, i) => (
                <div key={s.id} className="d-flex align-items-center">
                  <div className="d-flex flex-column align-items-center" style={{ width: 64 }}>
                    <div className={`d-flex align-items-center justify-content-center rounded-circle border-2 ${i < stepIndex ? 'bg-success text-white border-success' : i === stepIndex ? 'bg-primary text-white border-primary' : 'border-secondary text-secondary'}`}
                         style={{ width: 34, height: 34 }}>
                      {i < stepIndex ? <FontAwesomeIcon icon={faCheck} size="xs" /> : <FontAwesomeIcon icon={s.icon} size="xs" />}
                    </div>
                    <span className={`small mt-1 ${i === stepIndex ? 'fw-semibold text-body' : 'text-muted'}`}>{s.label}</span>
                  </div>
                  {i < steps.length - 1 && (
                    <div className="mx-1 mb-3" style={{ width: 36, height: 2, background: i < stepIndex ? 'var(--bs-success)' : 'var(--bs-secondary-bg)' }} />
                  )}
                </div>
              ))}
            </div>
          )}

          {/* Step 1 — Email */}
          {step === 'email' && (
            <Form onSubmit={submitEmail} noValidate>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-semibold">{t('register.email', 'Email address')}</Form.Label>
                <div className="input-group input-group-lg">
                  <span className="input-group-text bg-transparent"><FontAwesomeIcon icon={faEnvelope} className="text-secondary" /></span>
                  <Form.Control
                    type="email"
                    value={email}
                    onChange={e => {
                      setEmail(e.target.value)
                      setError(null)
                    }}
                    autoComplete="email"
                    autoFocus
                    disabled={busy}
                    placeholder={t('placeholder.email', 'name@example.com')}
                    required
                    isInvalid={normalizedEmail.length > 0 && !emailIsValid}
                    className="border-start-0"
                  />
                </div>
                {normalizedEmail.length > 0 && !emailIsValid && (
                  <div className="invalid-feedback d-block">
                    {t('msg.err.emailInvalid', 'Invalid email address.')}
                  </div>
                )}
                <Form.Text className="text-muted">
                  {t('register.emailHint', 'We will send you a verification code to this address.')}
                </Form.Text>
              </Form.Group>

              {error && <Alert variant="danger" className="py-2 small">{error}</Alert>}

              <Button type="submit" variant="primary" className="w-100" size="lg" disabled={busy || !emailIsValid}>
                {busy ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faPaperPlane} className="me-2" />{t('register.sendOtp', 'Send code')}</>}
              </Button>

              <div className="mt-3 text-center">
                <Link to="/login" className="small text-decoration-none">
                  <FontAwesomeIcon icon={faArrowLeft} className="me-1" />
                  {t('register.backToLogin', 'Back to login')}
                </Link>
              </div>
            </Form>
          )}

          {/* Step 2 — OTP */}
          {step === 'otp' && (
            <Form onSubmit={submitOtp} noValidate>
              <Form.Label className="small fw-semibold">{t('register.otp', 'Verification code')}</Form.Label>
              <div className="d-flex justify-content-between mb-2" dir="ltr">
                {Array.from({ length: 6 }).map((_, i) => (
                  <input
                    key={i}
                    ref={el => { otpInputs.current[i] = el }}
                    value={otp[i] ?? ''}
                    onChange={e => handleOtpDigit(i, e.target.value)}
                    onKeyDown={e => handleOtpKeyDown(i, e)}
                    onFocus={e => e.target.select()}
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    disabled={busy}
                    maxLength={2}
                    className="form-control form-control-lg text-center fw-bold"
                    style={{ width: 52, height: 56, fontSize: '1.35rem' }}
                    aria-label={`${t('register.otp', 'Verification code')} ${i + 1}`}
                  />
                ))}
              </div>
              <Form.Text className="text-muted d-block mb-3">
                {t('register.otpSentTo', 'Sent to')} <strong>{email}</strong>
                {resendIn > 0
                  ? <> · {t('register.resendIn', 'resend in')} <strong>{resendIn}s</strong></>
                  : null}
              </Form.Text>

              {error && <Alert variant="danger" className="py-2 small">{error}</Alert>}

              <Button type="submit" variant="primary" className="w-100" size="lg" disabled={busy || otp.length !== 6}>
                {busy ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faKey} className="me-2" />{t('register.verifyOtp', 'Verify code')}</>}
              </Button>

              <div className="text-center mt-3">
                <Button variant="link" size="sm" className="text-decoration-none" onClick={resendOtp} disabled={busy || resendIn > 0}>
                  {t('register.resendOtp', 'Resend the code')}
                </Button>
              </div>

              <div className="text-center">
                <Button variant="link" size="sm" className="text-decoration-none" onClick={back} disabled={busy}>
                  <FontAwesomeIcon icon={faArrowLeft} className="me-1" />
                  {t('register.cambiaEmail', 'Change email')}
                </Button>
              </div>
            </Form>
          )}

          {/* Step 3 — Profile */}
          {step === 'profile' && (
            <Form onSubmit={submitProfile} noValidate>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-semibold">{t('login.username', 'Username')}</Form.Label>
                <div className="input-group input-group-lg">
                  <span className="input-group-text bg-transparent"><FontAwesomeIcon icon={faUser} className="text-secondary" /></span>
                  <Form.Control
                    value={username}
                    onChange={e => setUsername(e.target.value)}
                    autoComplete="username"
                    autoFocus
                    disabled={busy}
                    maxLength={64}
                    isInvalid={usernameHint}
                    className="border-start-0"
                    placeholder="mario.rossi"
                  />
                </div>
                {usernameHint && (
                  <Form.Text className="text-danger">
                    {t('register.usernameHint', '3-64 characters: letters, numbers, dots, dashes or underscores.')}
                  </Form.Text>
                )}
              </Form.Group>

              <Form.Group className="mb-4">
                <Form.Label className="small fw-semibold">{t('login.password', 'Password')}</Form.Label>
                <div className="input-group input-group-lg">
                  <span className="input-group-text bg-transparent"><FontAwesomeIcon icon={faLock} className="text-secondary" /></span>
                  <Form.Control
                    type="password"
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    autoComplete="new-password"
                    disabled={busy}
                    className="border-start-0"
                    placeholder="••••••••"
                  />
                </div>
                {password.length > 0 && (
                  <>
                    <ProgressBar now={(pwScore / 4) * 100} variant={pwVariant} className="mt-2" style={{ height: 6 }} />
                    <div className="d-flex justify-content-between small mt-1">
                      <span className="text-muted">{t('register.passwordStrength', 'Strength')}</span>
                      <span className={`fw-semibold text-${pwVariant}`}>{pwLabel}</span>
                    </div>
                  </>
                )}
                <Form.Text className="text-muted">
                  {t('register.passwordHint', 'At least 8 characters.')}
                </Form.Text>
              </Form.Group>

              {error && <Alert variant="danger" className="py-2 small">{error}</Alert>}

              <Button type="submit" variant="primary" className="w-100" size="lg"
                      disabled={busy || !usernameValid || pwScore < 2}>
                {busy ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faUser} className="me-2" />{t('register.complete', 'Create account')}</>}
              </Button>

              <div className="text-center mt-3">
                {otpRequired ? (
                  <Button variant="link" size="sm" className="text-decoration-none" onClick={back} disabled={busy}>
                    <FontAwesomeIcon icon={faArrowLeft} className="me-1" />
                    {t('register.back', 'Back')}
                  </Button>
                ) : (
                  // In standalone mode the profile is the first step: without this, the browser's
                  // Back button would be the only way out.
                  <Link to="/login" className="small text-decoration-none">
                    <FontAwesomeIcon icon={faArrowLeft} className="me-1" />
                    {t('register.backToLogin', 'Back to login')}
                  </Link>
                )}
              </div>
            </Form>
          )}

          {/* Final step — Outcome */}
          {step === 'done' && (
            <>
              <div className="text-center mb-3">
                <div className={`mx-auto d-flex align-items-center justify-content-center rounded-circle ${firstUser ? 'bg-success' : 'bg-warning'} text-white`}
                     style={{ width: 64, height: 64 }}>
                  <FontAwesomeIcon icon={faCheck} size="xl" />
                </div>
              </div>
              <Alert variant={firstUser ? 'success' : 'warning'} className="text-center">
                <h6 className="mb-2 fw-bold">
                  {firstUser
                    ? t('register.firstAdminDoneTitle', 'Administrator created!')
                    : t('register.doneTitle', 'Registration submitted!')}
                </h6>
                <p className="small mb-0">
                  {firstUser
                    ? t('register.firstAdminDoneBody', 'Your administrator account is active: you can sign in now.')
                    : t('register.doneBody', 'An administrator must approve your account before you can sign in. You will be notified upon activation.')}
                </p>
              </Alert>
              <Link to="/login" className="btn btn-primary w-100 btn-lg">
                <FontAwesomeIcon icon={faArrowLeft} className="me-2" />
                {t('register.backToLogin', 'Back to login')}
              </Link>
            </>
          )}

          {/* Footer */}
          {otpRequired && (
            <div className="text-center mt-4">
              <span className="text-muted" style={{ fontSize: '0.75rem' }}>
                {t('register.securityNote', 'Your email is verified with a one-time code. No data is shared.')}
              </span>
            </div>
          )}
        </Card.Body>
      </Card>
    </div>
  )
}
