/**
 * @file languages.ts
 * @brief Languages offered by the UI switchers — single shared source.
 *
 * @details The installation ships Simplified Chinese and English only
 *          (it/fr/es/de remain in the database for historical data but are no
 *          longer offered). Kept in one module so the Navbar switcher and the
 *          pre-login AuthLanguageSelect cannot drift apart.
 */
export const UI_LANGUAGES = [
  { code: 'zh', label: '简体中文' },
  { code: 'en', label: 'English' },
] as const

/** @brief Language codes offered by the UI, for normalization checks. */
export const UI_LANGUAGE_CODES: readonly string[] = UI_LANGUAGES.map(l => l.code)
