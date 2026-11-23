CREATE TABLE travel_time_matrix (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_location_id INTEGER NOT NULL,
    to_location_id INTEGER NOT NULL,
    minutes INTEGER NOT NULL,
    distance_meters INTEGER,
    computed_at TEXT NOT NULL,
    UNIQUE (from_location_id, to_location_id),
    FOREIGN KEY (from_location_id) REFERENCES locations(id) ON DELETE CASCADE,
    FOREIGN KEY (to_location_id) REFERENCES locations(id) ON DELETE CASCADE,
    CHECK (from_location_id <> to_location_id)
);

CREATE INDEX idx_travel_matrix_from ON travel_time_matrix(from_location_id);
CREATE INDEX idx_travel_matrix_to ON travel_time_matrix(to_location_id);

CREATE TABLE travel_time_meta (
    id INTEGER PRIMARY KEY,
    location_signature TEXT NOT NULL,
    location_count INTEGER NOT NULL,
    pair_count INTEGER NOT NULL,
    computed_at TEXT NOT NULL
);
