/**
 * @file useAppStore.ts
 * @brief Global application store (Zustand + persist).
 *
 * @details
 * Keeps the minimum global state persisted in localStorage:
 * - **currentStructure** — organizational structure selected by the user.
 *   All endpoints requiring `structureId` read it from here.
 * - **language** — current language code (e.g. "zh", "en").
 *   Read by `initI18n()` at startup to initialize react-i18next.
 *
 * The localStorage key is `app-store` (defined in `persist.name`).
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Structure } from '../types'
import { UI_LANGUAGE_CODES } from '../i18n/languages'

/**
 * @brief Shape of the global state.
 */
interface AppState {
  /** @brief Currently selected structure (null = no selection). */
  currentStructure: Structure | null
  /** @brief Sets the active structure. Called by the Navbar when the structure changes. */
  setCurrentStructure: (s: Structure | null) => void

  /** @brief Current language code (default "zh" for new local installations). */
  language: string
  /** @brief Sets the language. Called by the Navbar when the language changes. */
  setLanguage: (lang: string) => void

  /** @brief Shows i18n keys instead of translated text. */
  showTranslationKeys: boolean
  /** @brief Enables/disables persistent display of i18n keys. */
  setShowTranslationKeys: (show: boolean) => void

  /**
   * @brief Shift-window granularity ('week' | 'month'). Cache for the current structure:
   *        the backend is the source of truth (per structure), synchronized by the Navbar
   *        when the structure changes. Default 'month'.
   */
  shiftWindowMode: 'week' | 'month'
  /** @brief Updates the granularity cache (Navbar synchronization + save in Configuration). */
  setShiftWindowMode: (m: 'week' | 'month') => void

}

/**
 * @brief Zustand hook for accessing the global store.
 * @example
 * ```tsx
 * const { currentStructure, setCurrentStructure } = useAppStore()
 * ```
 */
export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      currentStructure: null,
      setCurrentStructure: (s) => set({ currentStructure: s }),

      language: 'zh',
      setLanguage: (lang) => set({ language: lang }),

      showTranslationKeys: false,
      setShowTranslationKeys: (show) => set({ showTranslationKeys: show }),

      shiftWindowMode: 'month',
      setShiftWindowMode: (m) => set({ shiftWindowMode: m }),

    }),
    {
      name: 'app-store',
      // v1: the UI ships Chinese and English only; remap any stored legacy language
      // (it/fr/es/de from earlier versions) so the switcher always has a matching option.
      version: 1,
      migrate: (persistedState, version) => {
        const state = persistedState as Partial<AppState>
        if (version < 1 && state.language && !UI_LANGUAGE_CODES.includes(state.language)) {
          state.language = 'zh'
        }
        return state as AppState
      },
      // Persist only the required fields (exclude functions)
      partialize: (state) => ({
        currentStructure: state.currentStructure,
        language: state.language,
        showTranslationKeys: state.showTranslationKeys,
        shiftWindowMode: state.shiftWindowMode,
      }),
    }
  )
)
