-- Purges the label definitions and translations that belonged to the specialists /
-- affinity module, dropped from the code base in V17.
--
-- V17 handled the schema and the feature tables. The label rows are a different
-- story: DemoDataRepository used to insert them at startup, so databases seeded
-- before the removal still carry them. Left in place they keep appearing in the
-- Labels admin page as orphan entries describing a feature that no longer exists
-- (29 keys: nav.specialists, btn.addSpecialist, the affinity levels, the
-- avoid_specialist_weight solver help, the SPECIALIST_* backend error messages,
-- and the two solver constraint captions).
--
-- localizzazioni has no FOREIGN KEY to labels -- entity_type/entity_id are a
-- naming convention -- so the translations are deleted explicitly, first.
--
-- A new migration rather than an edit to V17, because V17 is already applied to
-- live databases and Flyway tracks it by checksum.

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
