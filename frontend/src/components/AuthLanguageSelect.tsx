import { Form } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../store/useAppStore'
import { UI_LANGUAGES } from '../i18n/languages'

/** Compact language selector for pages rendered before the authenticated navbar exists. */
export default function AuthLanguageSelect() {
  const { i18n } = useTranslation()
  const language = useAppStore(state => state.language)
  const setLanguage = useAppStore(state => state.setLanguage)

  return (
    <Form.Select
      size="sm"
      aria-label="Language"
      value={language}
      onChange={event => {
        setLanguage(event.target.value)
        i18n.changeLanguage(event.target.value)
      }}
      style={{ width: 120 }}
    >
      {UI_LANGUAGES.map(l => <option key={l.code} value={l.code}>{l.label}</option>)}
    </Form.Select>
  )
}

