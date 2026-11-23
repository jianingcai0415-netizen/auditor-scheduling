-- Records WHICH plan superseded this one at publish time.
--
-- Publishing marks every already-published plan whose window overlaps as SUPERSEDED, but a
-- partial overlap only replaces the assignments inside the overlap; the older plan's
-- assignments outside the overlap keep running. Without the successor's id, the UI cannot
-- distinguish a full replacement from a partial one, and users mis-read "superseded" as
-- "no longer in effect anywhere". Nullable: plans superseded before this migration have no
-- recorded successor and are rendered with a generic note.
ALTER TABLE schedule_plans ADD COLUMN superseded_by_plan_id INTEGER;
