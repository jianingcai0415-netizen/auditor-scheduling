-- La cache dei tempi di percorrenza passa da "coppia di sedi" a "coppia di coordinate".
--
-- Prima: travel_time_matrix era indicizzata per from_location_id / to_location_id, con due
-- chiavi esterne verso locations. Conseguenza: ogni modifica all'elenco delle sedi
-- (aggiunta, rinomina, cambio coordinate, disattivazione) invalidava l'intera cache e
-- costringeva a richiamare 高德 per tutte le coppie. Con 30 sedi sono 870 richieste a
-- ~2.5 QPS, e la quota giornaliera di una chiave personale non e' negoziabile: rinominare
-- una sede bruciava la stessa quota di un ricalcolo completo.
--
-- Ora: l'identita' di una voce di cache e' la coppia di coordinate, non la coppia di sedi.
-- Il tempo di percorrenza tra due punti dipende solo dai punti, quindi la cache
-- sopravvive a qualsiasi modifica dell'anagrafica delle sedi: rinominare una sede non
-- tocca piu' nulla, cambiare le coordinate invalida solo le coppie di quella sede, e
-- aggiungere una sede calcola solo le sue n-1 coppie. Le due chiavi esterne spariscono --
-- e' proprio la loro presenza a legare la cache all'identita' delle righe di locations.
-- Le voci non piu' referenziate scadono da sole e vengono ripulite per anzianita'.
--
-- Le coordinate sono arrotondate a 5 decimali (~1.1 m) in scrittura e in lettura, cosi'
-- che la stessa sede ricreata con le stesse coordinate ritrovi la propria voce. I valori
-- esistenti hanno al massimo 4 decimali, quindi l'arrotondamento qui e' un'operazione
-- nulla e i valori migrati restano bit per bit quelli di prima: nessuna richiesta viene
-- sprecata dopo l'aggiornamento.
--
-- travel_time_meta viene eliminata: serviva a validare l'intera matrice come blocco unico
-- (firma dell'insieme di sedi + conteggio), nozione che con una cache incrementale non ha
-- piu' senso. La freschezza ora e' per riga, tramite computed_at.

CREATE TABLE travel_time_matrix_new (
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

-- Migrazione delle voci esistenti: le coordinate si ricavano dalle sedi ancora presenti.
-- Le righe la cui sede non esiste piu' vengono semplicemente lasciate indietro.
--
-- L'ordine va normalizzato. La cache identifica una coppia in modo NON ordinato, mentre la
-- vecchia tabella memorizzava la direzione che il calcolo aveva incontrato per prima
-- (from_location_id < to_location_id). Copiando alla cieca la stessa coppia finirebbe due
-- volte, una per verso: l'upsert confronta le coordinate esatte, quindi il ricalcolo non
-- riconoscerebbe la riga migrata e ne inserirebbe una seconda, lasciando la tabella con
-- righe duplicate e permanentemente non normalizzate.
WITH pairs AS (
    SELECT ROUND(o.latitude, 5) AS olat, ROUND(o.longitude, 5) AS olon,
           ROUND(d.latitude, 5) AS dlat, ROUND(d.longitude, 5) AS dlon,
           m.minutes AS minutes, m.distance_meters AS distance_meters, m.computed_at AS computed_at
    FROM travel_time_matrix m
    JOIN locations o ON o.id = m.from_location_id
    JOIN locations d ON d.id = m.to_location_id
)
INSERT INTO travel_time_matrix_new (from_lat, from_lon, to_lat, to_lon, minutes, distance_meters, computed_at)
SELECT
    CASE WHEN olat < dlat OR (olat = dlat AND olon <= dlon) THEN olat ELSE dlat END,
    CASE WHEN olat < dlat OR (olat = dlat AND olon <= dlon) THEN olon ELSE dlon END,
    CASE WHEN olat < dlat OR (olat = dlat AND olon <= dlon) THEN dlat ELSE olat END,
    CASE WHEN olat < dlat OR (olat = dlat AND olon <= dlon) THEN dlon ELSE olon END,
    minutes, distance_meters, computed_at
FROM pairs;

DROP TABLE travel_time_matrix;
ALTER TABLE travel_time_matrix_new RENAME TO travel_time_matrix;

DROP TABLE IF EXISTS travel_time_meta;

CREATE INDEX IF NOT EXISTS idx_travel_time_computed ON travel_time_matrix(computed_at);
