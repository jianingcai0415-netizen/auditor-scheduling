-- Gemella della migrazione PostgreSQL di pari versione: qui la tabella viene ricostruita con
-- le stesse colonne, perche' su SQLite "REAL" e' gia' un double a 8 byte -- la ricostruzione
-- non cambia nulla nei dati.
--
-- Perche' allora ricostruire? Perche' su PostgreSQL le colonne erano REAL a 4 byte, e la
-- migrazione gemella deve poter essere letta e confrontata riga per riga con questa. Le due
-- migrazioni descrivono lo stesso schema logico; se una sola delle due ricostruisse la
-- tabella, il contratto fra i due motori non sarebbe piu' verificabile a occhio.
--
-- L'arrotondamento a 5 decimali e' comunque parte del contratto: le coordinate vengono
-- quantizzate in scrittura (vedi TravelTimeMatrixEntity), quindi i valori gia' presenti
-- hanno al massimo 5 decimali e ROUND qui non sposta nulla.

CREATE TABLE travel_time_matrix_v27 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_lat REAL NOT NULL,
    from_lon REAL NOT NULL,
    to_lat REAL NOT NULL,
    to_lon REAL NOT NULL,
    minutes INTEGER NOT NULL,
    distance_meters INTEGER,
    computed_at TEXT NOT NULL,
    UNIQUE (from_lat, from_lon, to_lat, to_lon),
    CHECK (from_lat <> to_lat OR from_lon <> to_lon)
);

-- Gli id non vengono copiati: nessuna riga vi fa riferimento (la chiave della cache e' la
-- coppia di coordinate) e su PostgreSQL la sequenza identity ripartirebbe comunque da 1.
INSERT INTO travel_time_matrix_v27 (from_lat, from_lon, to_lat, to_lon, minutes, distance_meters, computed_at)
SELECT
    ROUND(from_lat, 5),
    ROUND(from_lon, 5),
    ROUND(to_lat, 5),
    ROUND(to_lon, 5),
    minutes, distance_meters, computed_at
FROM travel_time_matrix;

DROP TABLE travel_time_matrix;
ALTER TABLE travel_time_matrix_v27 RENAME TO travel_time_matrix;

CREATE INDEX IF NOT EXISTS idx_travel_time_computed ON travel_time_matrix(computed_at);
