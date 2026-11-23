-- Adds Simplified Chinese to the languages table so the UI translation catalog
-- (i18n/ui-translations.tsv, synced at startup by UiTranslationSyncService) can
-- apply its zh column. The sync service skips catalog languages that are absent
-- from this table, so the row must exist before the translation values arrive.
--
-- id 6 keeps parity with the V1 seed (it/en/fr/es/de occupy ids 1-5). active=0:
-- the "active" flag is a legacy single-choice radio used only to guard language
-- deletion; language availability is decided by the frontend switcher.
INSERT INTO languages (id, code, description, active) VALUES (6, 'zh', '简体中文', 0);
