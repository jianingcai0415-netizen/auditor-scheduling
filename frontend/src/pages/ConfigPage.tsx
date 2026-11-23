/**
 * @file ConfigPage.tsx
 * @brief Configuration page — sidebar + lazily loaded settings sections.
 *
 * @details
 * 每个设置区都是独立组件按需加载（<Suspense>），本文件只负责侧栏导航与路由参数（?section=）。
 * 「排班模板」整个功能已于 2026-10-29 移除（从未被使用），侧栏不再有其入口。
 */

import { lazy, Suspense, useEffect } from 'react'
import { Spinner, Row, Col } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faSliders, faLanguage, faBuilding, faCertificate, faEnvelope, faFilePdf, faRobot, faAt, faDatabase, faCircleInfo, faPalette } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import './ConfigPage.css'

const LabelsPage = lazy(() => import('./LabelsPage'))
const StructuresPage = lazy(() => import('./StructuresPage'))
const SkillsPage = lazy(() => import('./SkillsPage'))
const EmailTemplateSection = lazy(() => import('../components/email/EmailTemplateSection'))
const EmailSettingsSection = lazy(() => import('../components/email/EmailSettingsSection'))
const BackupSection = lazy(() => import('../components/backup/BackupSection'))
const PdfTemplateSection = lazy(() => import('../components/pdf/PdfTemplateSection'))
const SolverSettingsSection = lazy(() => import('../components/solver/SolverSettingsSection'))
const GeneralSettingsSection = lazy(() => import('../components/general/GeneralSettingsSection'))
const InfoSection = lazy(() => import('../components/info/InfoSection'))
const HomeUiSettingsSection = lazy(() => import('../components/info/HomeUiSettingsSection'))

export default function ConfigPage() {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()

  // Configuration is restricted to administrators: the backend rejects access anyway, but without
  // this guard a head nurse entering /config sees the entire sidebar and every section fails with 403.
  useEffect(() => {
    if (!isAdmin) navigate('/', { replace: true })
  }, [isAdmin, navigate])

  const [searchParams, setSearchParams] = useSearchParams()
  const sectionParam = searchParams.get('section')
  const activeSection = sectionParam === 'info' || sectionParam === 'homeUi' || sectionParam === 'general' || sectionParam === 'localizations' || sectionParam === 'structures' || sectionParam === 'skills' || sectionParam === 'emailTemplate' || sectionParam === 'pdfTemplate' || sectionParam === 'solverSettings' || sectionParam === 'emailSettings' || sectionParam === 'backup' ? sectionParam : 'info'
  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('config.title', 'Configuration')}</h5>
      </div>

      <Row className="g-3">
        <Col md={3} xl={2}>
          <nav className="config-sidebar" aria-label={t('config.title', 'Configuration')}>
            {([
              ['info', t('config.menu.info', 'System Info'), t('config.menu.info.tooltip', 'System versions and information'), faCircleInfo],
              ['homeUi', t('config.menu.homeUi', 'General configuration'), t('config.menu.homeUi.tooltip', 'Manage home cover and texts'), faPalette],
              ['structures', t('config.menu.structures', 'Structures'), t('config.menu.structures.tooltip', 'Manage organizational structures'), faBuilding],
              ['general', t('config.menu.general', 'General settings'), t('config.menu.general.tooltip', 'Configure general settings'), faSliders],
              ['emailSettings', t('config.menu.emailSettings', 'Email Settings'), t('config.menu.emailSettings.tooltip', 'Configure the SMTP server for sending emails'), faAt],
              ['solverSettings', t('config.menu.solverSettings', 'Solver Settings'), t('config.menu.solverSettings.tooltip', 'Configure optimization engine settings'), faRobot],
              ['localizations', t('config.menu.localizations', 'Localizations'), t('config.menu.localizations.tooltip', 'Manage labels and translations'), faLanguage],
              ['skills', t('config.menu.skills', 'Skills'), t('config.menu.skills.tooltip', 'Manage skills'), faCertificate],
              ['emailTemplate', t('config.menu.emailTemplate', 'Email Template'), t('config.menu.emailTemplate.tooltip', 'Configure the email template'), faEnvelope],
              ['pdfTemplate', t('config.menu.pdfTemplate', 'PDF templates'), t('config.menu.pdfTemplate.tooltip', 'Configure PDF logo, header and footer'), faFilePdf],
              ['backup', t('config.menu.backup', 'Backup'), t('config.menu.backup.tooltip', 'Database backup and restore'), faDatabase],
            ] as const).map(([key, label, tooltip, icon]) => (
              <button
                key={key}
                type="button"
                className={`config-sidebar-item${activeSection === key ? ' active' : ''}`}
                title={tooltip}
                aria-current={activeSection === key ? 'page' : undefined}
                onClick={() => setSearchParams({ section: key }, { replace: true })}
              >
                <FontAwesomeIcon icon={icon} />
                <span>{label}</span>
              </button>
            ))}
          </nav>
        </Col>
        <Col md={9} xl={10}>
      <Suspense fallback={<div className="text-center py-4"><Spinner /></div>}>
        {activeSection === 'info' && <InfoSection />}
        {activeSection === 'homeUi' && <HomeUiSettingsSection />}
        {activeSection === 'localizations' && <LabelsPage embedded />}
        {activeSection === 'structures' && <StructuresPage embedded />}
        {activeSection === 'skills' && <SkillsPage embedded />}
        {activeSection === 'emailTemplate' && <EmailTemplateSection />}
        {activeSection === 'emailSettings' && <EmailSettingsSection />}
        {activeSection === 'backup' && <BackupSection />}
        {activeSection === 'pdfTemplate' && <PdfTemplateSection />}
        {activeSection === 'solverSettings' && <SolverSettingsSection />}
        {activeSection === 'general' && <GeneralSettingsSection />}
      </Suspense>
        </Col>
      </Row>
    </div>
  )
}
