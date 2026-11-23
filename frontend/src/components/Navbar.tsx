/**
 * @file Navbar.tsx
 * @brief Main application navigation bar.
 *
 * @details
 * Includes:
 * - Brand with current structure name and active section
 * - Links to the main sections in the application's operational order
 * - Structure-selector dropdown (visible only when multiple structures exist)
 * - Language-selector dropdown (Chinese and English)
 *
 * On first load, automatically selects the first available structure
 * when none is already saved in the store.
 */

import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { Navbar as BsNavbar, Nav, Container, NavDropdown, ButtonGroup, Button } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import {
  faCalendarDays,
  faUsers,
  faLocationDot,
  faCalendarCheck,
  faChartColumn,
  faGear,
  faCircleUser,
  faRightFromBracket,
  faUserGear,
  faPowerOff,
  faHouse,
  faMapLocationDot,
} from '@fortawesome/free-solid-svg-icons'
import { useAppStore } from '../store/useAppStore'
import { UI_LANGUAGES } from '../i18n/languages'
import { tx } from '../i18n/uiText'
import { api } from '../api/client'
import { generalSettingsApi } from '../api/generalSettings'
import { useAuth } from '../auth/AuthContext'
import toast from 'react-hot-toast'
import { systemInfoApi } from '../api/systemInfo'
import type { Structure } from '../types'
import ConfirmModal from './ConfirmModal'

export default function Navbar() {
  const { t, i18n } = useTranslation()
  const { session, isAdmin, isDispatcher, logout } = useAuth()
  const { currentStructure, setCurrentStructure, language, setLanguage, showTranslationKeys,
    setShiftWindowMode } = useAppStore()
  const [structures, setStructures] = useState<Structure[]>([])
  const [exitConfirmOpen, setExitConfirmOpen] = useState(false)
  const [exitBusy, setExitBusy] = useState(false)

  function tryCloseWindow() {
    try {
      window.open('', '_self')
      window.close()
    } catch {
      // best-effort
    }
    // If the browser blocks window.close(), at least clear the page.
    setTimeout(() => {
      try {
        if (!window.closed) {
          window.location.replace('about:blank')
        }
      } catch {
        // ignore
      }
    }, 200)
  }

  // Configuration: administrators only. The backend rejects access anyway, but showing an entry
  // that leads to an error is worse than hiding it.
  const LINKS = [
    { to: '/workflow', label: t('nav.workflow', 'Scheduling Workflow'), icon: faCalendarCheck },
    // 只读总览，五个角色都能看（后端 /map-overview 同样对全部角色开放）。
    { to: '/map', label: t('nav.map', 'Auditor Map'), icon: faMapLocationDot },
    ...(isDispatcher ? [
      { to: '/shifts', label: t('nav.shifts', 'Shift Management'), icon: faCalendarDays },
      { to: '/locations', label: t('nav.locations', 'Locations'), icon: faLocationDot },
      { to: '/employees', label: t('nav.employees', 'Operators'), icon: faUsers },
      { to: '/dates', label: t('nav.dates', 'Operator Date Preferences'), icon: faCalendarCheck },
      { to: '/report', label: t('nav.report', 'Report'), icon: faChartColumn },
    ] : []),
    ...(isAdmin ? [
      { to: '/config', label: t('nav.config', 'Configuration'), icon: faGear },
      { to: '/users', label: t('nav.users', 'Users'), icon: faUserGear },
    ] : []),
  ]

  useEffect(() => {
    // Needed by everyone, not just administrators: without the list there is no selected
    // structure and every page remains empty. It is not sensitive read-only data.
    api.get<Structure[]>('/structures')
      .then(setStructures)
      .catch(() => {})
  }, [])

  // The selection lives in localStorage and survives reinstallations, backup restores, and
  // deletions performed elsewhere: compare it with the actual list rather than merely checking
  // for its presence. A structure that no longer exists yields empty lists rather than errors.
  useEffect(() => {
    if (structures.length === 0) return
    const selected = currentStructure && structures.find(s => s.id === currentStructure.id)
    if (!selected) {
      setCurrentStructure(structures[0])
    } else if (selected.name !== currentStructure.name) {
      // Name changed elsewhere: the store retains the entire object, not just the ID.
      setCurrentStructure(selected)
    }
  }, [structures, currentStructure, setCurrentStructure])

  // Synchronize General Settings (window granularity) from the selected structure: the backend
  // is the source of truth (per structure), while the store is only the current-structure cache
  // read by Shift Management / Report.
  useEffect(() => {
    const id = currentStructure?.id
    if (!id) return
    let cancelled = false
    generalSettingsApi.get(id)
      .then(gs => {
        if (cancelled) return
        setShiftWindowMode(gs.shift_window_mode)
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [currentStructure?.id, setShiftWindowMode])

  const LANGUAGES = UI_LANGUAGES

  return (
    <>
      {/* expand="xl"（≥1200px 才展开）：中文菜单项在 992–1199px 之间放不下，
          展开会导致文字折行或右侧的部门/语言/账号被挤出屏幕；折叠成汉堡菜单更可用。 */}
      <BsNavbar bg="dark" variant="dark" expand="xl" className="px-3" style={{ minHeight: 64 }}>
        <Container fluid>
        <BsNavbar.Brand as={NavLink} to="/" className="fw-bold" aria-label={t('nav.shiftManagement', 'Shift Management')}>
          <FontAwesomeIcon icon={faHouse} className="me-1" />
        </BsNavbar.Brand>

        <BsNavbar.Toggle aria-controls="main-nav" />
        <BsNavbar.Collapse id="main-nav">
          <Nav className="me-auto ms-4 ps-3 gap-2">
            {LINKS.map(({ to, label, icon }) => (
              <Nav.Link key={to} as={NavLink} to={to}>
                <FontAwesomeIcon icon={icon} className="me-2" />
                {label}
              </Nav.Link>
            ))}
          </Nav>

          <Nav className="ms-auto align-items-lg-center gap-2">
            {/* Structure selector */}
            {structures.length > 0 && (
              <NavDropdown
                title={currentStructure?.name ?? t('nav.structure', 'Structure')}
                id="structure-dropdown"
                align="end"
              >
                {structures.map(s => (
                  <NavDropdown.Item
                    key={s.id}
                    active={currentStructure?.id === s.id}
                    onClick={() => setCurrentStructure(s)}
                  >
                    {s.name}
                  </NavDropdown.Item>
                ))}
              </NavDropdown>
            )}

            {/* 中英一键切换：演示 / 汇报时需要整站瞬间切英文，下拉要多点一次且不显眼，
                这里改成常驻的分段按钮。两种语言都写自名（中文 / EN），不随当前语言变化。 */}
            <ButtonGroup size="sm" className="ms-2" aria-label={t('nav.language', 'Language')}>
              {LANGUAGES.map(l => (
                <Button
                  key={l.code}
                  variant={language === l.code ? 'light' : 'outline-light'}
                  className="px-2 fw-semibold"
                  aria-pressed={language === l.code}
                  title={l.label}
                  onClick={() => {
                    setLanguage(l.code)
                    i18n.changeLanguage(showTranslationKeys ? 'cimode' : l.code)
                  }}
                >
                  {l.code === 'zh' ? '中文' : 'EN'}
                </Button>
              ))}
            </ButtonGroup>

            {/* Signed-in user and logout */}
            <NavDropdown
              title={<><FontAwesomeIcon icon={faCircleUser} className="me-1" />{session?.displayName ?? session?.username ?? ''}</>}
              id="user-dropdown"
              align="end"
            >
              <NavDropdown.Header>
                {tx(isAdmin ? '管理员' : session?.roles?.includes('APPROVER') ? '审批人'
                  : session?.roles?.includes('VIEWER') ? '只读查看' : '调度员')}
              </NavDropdown.Header>
              <NavDropdown.Divider />
              {/* Shown to a CAPOSALA only on the desktop package, where closing the application
                  is closing the window in front of them. On a shared server it would take the
                  service down for everyone, and the backend answers 403 EXIT_REQUIRES_ADMIN. */}
              {(isAdmin || session?.standalone) && (
                <NavDropdown.Item onClick={() => setExitConfirmOpen(true)}>
                  <FontAwesomeIcon icon={faPowerOff} className="me-2" />
                  {t('user.exitApp', 'Close application')}
                </NavDropdown.Item>
              )}
              <NavDropdown.Item onClick={() => { void logout() }}>
                <FontAwesomeIcon icon={faRightFromBracket} className="me-2" />
                {t('user.logout', 'Sign out')}
              </NavDropdown.Item>
            </NavDropdown>

          </Nav>
        </BsNavbar.Collapse>
        </Container>
      </BsNavbar>

      <ConfirmModal
        show={exitConfirmOpen}
        title={t('confirm.exitAppTitle', 'Close the application?')}
        message={t('confirm.exitAppBody', 'The app will close for all users on this PC.')}
        confirmLabel={t('confirm.exitAppConfirm', 'Close app')}
        confirmVariant="danger"
        loading={exitBusy}
        onClose={() => { if (!exitBusy) setExitConfirmOpen(false) }}
        onConfirm={async () => {
          if (exitBusy) return
          setExitBusy(true)
          try {
            await systemInfoApi.exit()
            tryCloseWindow()
          } catch {
            toast.error(t('toast.errorSave', 'Error while saving.'))
            setExitBusy(false)
            return
          }
          setExitBusy(false)
          setExitConfirmOpen(false)
        }}
      />
    </>
  )
}
