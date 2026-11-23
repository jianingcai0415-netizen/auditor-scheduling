/**
 * @file i18n/backendErrors.ts
 * @brief Translation of error codes returned by the backend.
 *
 * @details
 * The backend does not know the user's language, so it cannot compose the message: it responds
 * with a stable code (`{"error": "EMPLOYEE_CODE_REQUIRED"}`), while the text remains here with
 * all other UI strings. Previously these messages were written in Italian in REST responses
 * and appeared unchanged even for English- or German-speaking users.
 *
 * The map lives in one file because the same codes are handled by multiple modals: duplicating it
 * per component means translating a new entry in one place and forgetting it in the others.
 *
 * Every `msg.err.*` key must exist in `src/main/resources/i18n/ui-translations.tsv` for
 * all six languages.
 */

/** @brief i18n key and English fallback for each backend error code. */
const BACKEND_ERRORS: Record<string, [key: string, fallback: string]> = {
  BACKUP_TOOLS_UNAVAILABLE: ['msg.err.backupToolsUnavailable',
    'Backup unavailable: the database client tools are not installed or are older than the server.'],
  BACKUP_ADMIN_AUTH_REQUIRED: ['msg.err.backupAdminAuthRequired',
    'Backup administrator token required.'],
  BACKUP_ADMIN_TOKEN_NOT_CONFIGURED: ['msg.err.backupAdminTokenNotConfigured',
    'Backup API is disabled: configure BACKUP_ADMIN_TOKEN on the server.'],
  BACKUP_ADMIN_TLS_REQUIRED: ['msg.err.backupAdminTlsRequired',
    'A secure connection is required to administer backups.'],
  BACKUP_ADMIN_RATE_LIMITED: ['msg.err.backupAdminRateLimited',
    'Too many authentication attempts: wait one minute.'],
  RESTORE_NOT_SUPPORTED: ['msg.err.restoreNotSupported',
    'Automatic restore is not available on this database: download the backup and restore it with pg_restore.'],
  DATABASE_BUSY: ['msg.err.databaseBusy',
    'Database busy: try again shortly. No data was changed.'],
  BACKUP_IN_PROGRESS: ['msg.err.backupInProgress',
    'Backup in progress: try again when it finishes. No data was changed.'],
  SAFETY_BACKUP_FAILED: ['msg.err.safetyBackupFailed',
    'Operation cancelled: the safety backup could not be created.'],
  // The two below are the same refusal as above, split because they need opposite reactions:
  // one is a missing prerequisite that no retry will fix, the other clears by itself.
  SAFETY_BACKUP_CLIENT_TOOLS_MISSING: ['msg.err.safetyBackupClientToolsMissing',
    'Operation cancelled: pg_dump is missing or older than the server, so the safety backup cannot be created. Contact your system administrator — retrying will not help.'],
  SAFETY_BACKUP_BUSY: ['msg.err.safetyBackupBusy',
    'Operation cancelled: another backup is running. Try again in a minute. No data was changed.'],
  NOT_A_DATABASE: ['msg.err.notADatabase',
    'The selected file is not a readable database backup. No data was changed.'],
  INCOMPATIBLE_DATABASE: ['msg.err.incompatibleDatabase',
    'The backup is from an older or different application version (schema migrations do not match). No data was changed: update the application and try again, or use a more recent backup.'],
  NO_ROLLBACK_SNAPSHOT: ['msg.err.noRollbackSnapshot',
    'The safety backup could not be created: restore was cancelled without changes.'],
  PROMOTION_BUSY: ['msg.err.promotionBusy',
    'The database is busy: restore was cancelled without changes. Try again shortly.'],
  PROMOTION_IO_ERROR: ['msg.err.promotionIoError',
    'Restore failed; check the status reported by the server.'],

  EMPLOYEE_FIRST_NAME_REQUIRED: ['msg.err.employeeFirstNameRequired', "The operator first name is required."],
  EMPLOYEE_LAST_NAME_REQUIRED: ['msg.err.employeeLastNameRequired', "The operator last name is required."],
  EMPLOYEE_CODE_REQUIRED: ['msg.err.employeeCodeRequired', "The operator code is required."],
  EMPLOYEE_CODE_IN_USE: ['msg.err.employeeCodeInUse', 'Operator code already in use. Choose a different one.'],
  EMPLOYEE_EMAIL_INVALID: ['msg.err.employeeEmailInvalid', "Invalid operator email address."],

  LOCATION_NAME_REQUIRED: ['msg.err.locationNameRequired', 'The location name is required.'],
  LOCATION_ORDER_REQUIRED: ['msg.err.locationOrderRequired', "The location order must be greater than zero."],
  LOCATION_CODE_IN_USE: ['msg.err.locationCodeInUse', 'Location code already in use. Choose a different one.'],
  LOCATION_SKILLS_INVALID: ['msg.err.locationSkillsInvalid', 'Invalid skills for the location.'],
  LOCATION_IN_USE: ['msg.err.locationInUse', 'The location is used by shifts or templates and cannot be deleted.'],

  LANGUAGE_CODE_DESCRIPTION_REQUIRED: ['msg.warning.langCodeDescRequired', 'Code and description are required.'],
  LANGUAGE_NOT_FOUND: ['msg.err.languageNotFound', 'Language not found.'],
  LANGUAGE_ACTIVE_CANNOT_DELETE: ['msg.err.languageActiveCannotDelete', 'The active language cannot be deleted.'],
  LANGUAGE_INSERT_FAILED: ['msg.err.languageInsertFailed', "Error inserting the language."],

  LABEL_KEY_DESCRIPTION_REQUIRED: ['msg.warning.labelKeyDescRequired', 'Key and description are required.'],
  LABEL_NOT_FOUND: ['msg.err.labelNotFound', 'Label not found.'],
  LABEL_INSERT_FAILED: ['msg.err.labelInsertFailed', "Error inserting the label."],

  STRUCTURE_NAME_REQUIRED: ['msg.warning.structureNameRequired', 'Structure name is required.'],

  USER_USERNAME_REQUIRED: ['msg.err.userUsernameRequired', 'Username is required'],
  USER_PASSWORD_REQUIRED: ['msg.err.userPasswordRequired', 'Password is required'],
  USER_ROLE_INVALID: ['msg.err.userRoleInvalid', 'Invalid role'],
  USER_DUPLICATE: ['msg.err.userDuplicate', 'Username already in use'],
  USER_NOT_FOUND: ['msg.err.userNotFound', 'User not found'],
  USER_CANNOT_SELF_DEACTIVATE: ['msg.err.userCannotSelfDeactivate', 'You cannot deactivate your own account'],
  USER_EMAIL_DUPLICATE: ['msg.err.userEmailDuplicate', 'Email address already in use.'],
  USER_LAST_ADMIN: ['msg.err.userLastAdmin', 'You cannot remove the last active administrator.'],
  ACCOUNT_INACTIVE: ['msg.err.accountInactive', 'Account pending approval or disabled.'],

  EMAIL_INVALID: ['msg.err.emailInvalid', 'Invalid email address.'],
  EMAIL_ALREADY_REGISTERED: ['msg.err.emailAlreadyRegistered', 'This email is already registered.'],
  OTP_INVALID: ['msg.err.otpInvalid', 'Invalid or expired code.'],
  OTP_ALREADY_USED: ['msg.err.otpAlreadyUsed', 'Code already used. Request a new one.'],
  OTP_TOO_MANY: ['msg.err.otpTooMany', 'Too many attempts. Try again in a few minutes.'],
  OTP_SEND_FAILED: ['msg.err.otpSendFailed', "Failed to send the code. Try again."],
  OTP_NOT_REQUIRED: ['msg.err.otpNotRequired', 'Email verification is not required in this installation.'],
}

/** @brief Minimal react-i18next `t` signature, to avoid coupling the module to the full type. */
type Translate = (key: string, fallback: string) => string

/**
 * @brief Translates a backend error code into the message to display.
 * @param code Code extracted with `errorCode()`, or null if the response did not contain one.
 * @param t    react-i18next translation function.
 * @return The localized message, or null if the code is unknown: in that case the caller
 *         displays its own generic message without exposing technical strings.
 */
export function backendErrorText(code: string | null, t: Translate): string | null {
  if (!code) return null
  const entry = BACKEND_ERRORS[code]
  return entry ? t(entry[0], entry[1]) : null
}
