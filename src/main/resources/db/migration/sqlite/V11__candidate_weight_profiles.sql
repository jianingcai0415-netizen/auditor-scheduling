CREATE TABLE candidate_weight_profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL,
    profile_version INTEGER NOT NULL,
    ability_weight INTEGER NOT NULL,
    location_weight INTEGER NOT NULL,
    workload_weight INTEGER NOT NULL,
    continuity_weight INTEGER NOT NULL,
    age_weight INTEGER NOT NULL,
    change_reason TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (structure_id, profile_version),
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE
);

CREATE INDEX idx_candidate_weights_structure_version
    ON candidate_weight_profiles(structure_id, profile_version);
