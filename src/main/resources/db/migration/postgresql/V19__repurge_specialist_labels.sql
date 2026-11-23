-- Re-runs the specialist label purge of V18.
--
-- V18 alone was not enough, and the reason is an ordering subtlety worth recording:
-- on every boot, Flyway migrations run FIRST and UiTranslationSyncService seeds the
-- label catalog SECOND. When V18 shipped, the canonical catalog
-- (src/main/resources/i18n/ui-translations.tsv) still contained these 29 keys, so the
-- seeding pass simply re-inserted every row V18 had just deleted (it uses
-- ON CONFLICT DO NOTHING, which inserts whatever is missing). V18 therefore had no
-- lasting effect on any database whose catalog still listed the keys.
--
-- The root cause is fixed at the source: the 29 keys were removed from
-- ui-translations.tsv, so no database -- fresh or existing -- will seed them again.
-- This migration exists to repair databases that already ran V18 while the catalog
-- was still stale: it runs before the seeding pass, and this time nothing puts the
-- rows back.
--
-- Idempotent: on a database that never had the rows, both DELETE statements match
-- nothing. On a fresh install the V1..V19 chain also ends clean, because the seeding
-- source no longer contains any of these keys.

DELETE FROM localizzazioni
WHERE entity_type = 'labels'
  AND entity_id IN (
    SELECT id FROM labels WHERE key IN (
        'affinity.avoid',
        'affinity.incompatible',
        'affinity.selectSpecialist',
        'btn.addSpecialist',
        'col.avoidSpecialists',
        'col.incompatibleSpecialists',
        'confirm.deleteSpecialistTitle',
        'constraint.avoid_specialist',
        'constraint.incompatible_specialist',
        'hint.incompatibleSpecialist',
        'label.specialist',
        'label.specialistCompatibility',
        'modal.addSpecialist',
        'modal.editSpecialist',
        'msg.err.locationSpecialistInvalid',
        'msg.err.specialistCodeInUse',
        'msg.err.specialistCodeRequired',
        'msg.err.specialistEmailInvalid',
        'msg.err.specialistFirstNameRequired',
        'msg.err.specialistLastNameRequired',
        'msg.noSpecialists',
        'nav.specialists',
        'solver.help.avoid_specialist_weight',
        'solver.hint.avoid_specialist_weight',
        'solver.label.avoid_specialist_weight',
        'toast.specialistAdded',
        'toast.specialistCodeDuplicate',
        'toast.specialistDeleted',
        'toast.specialistUpdated'
    )
  );

DELETE FROM labels WHERE key IN (
    'affinity.avoid',
    'affinity.incompatible',
    'affinity.selectSpecialist',
    'btn.addSpecialist',
    'col.avoidSpecialists',
    'col.incompatibleSpecialists',
    'confirm.deleteSpecialistTitle',
    'constraint.avoid_specialist',
    'constraint.incompatible_specialist',
    'hint.incompatibleSpecialist',
    'label.specialist',
    'label.specialistCompatibility',
    'modal.addSpecialist',
    'modal.editSpecialist',
    'msg.err.locationSpecialistInvalid',
    'msg.err.specialistCodeInUse',
    'msg.err.specialistCodeRequired',
    'msg.err.specialistEmailInvalid',
    'msg.err.specialistFirstNameRequired',
    'msg.err.specialistLastNameRequired',
    'msg.noSpecialists',
    'nav.specialists',
    'solver.help.avoid_specialist_weight',
    'solver.hint.avoid_specialist_weight',
    'solver.label.avoid_specialist_weight',
    'toast.specialistAdded',
    'toast.specialistCodeDuplicate',
    'toast.specialistDeleted',
    'toast.specialistUpdated'
);
