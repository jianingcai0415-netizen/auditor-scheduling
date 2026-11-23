-- Elimina definitivamente locations.specialist_id e la tabella specialists.
--
-- V17 ha svuotato specialists e ha azzerato locations.specialist_id, ma non ha potuto
-- togliere ne' la colonna ne' la tabella:
--   * SQLite rifiuta ALTER TABLE ... DROP COLUMN quando la colonna compare in un vincolo
--     di chiave esterna della tabella stessa ("unknown column specialist_id in foreign
--     key definition");
--   * PostgreSQL rifiuta DROP TABLE finche' una chiave esterna la referenzia.
-- La colonna era l'unico anello che teneva in vita la tabella: tolta quella, specialists
-- non e' piu' referenziata da nulla.
--
-- Ricostruzione della tabella, la stessa forma gia' usata da V5 per skills: e' l'unico
-- modo che SQLite ha di cambiare un vincolo. I sei vincoli che puntano a locations
-- (shifts, location_skills, shift_templates, audit_order_phases, travel_time_matrix x2)
-- vivono nel corpo delle tabelle figlie e tornano validi da soli con la rinomina: qui non
-- vanno ricreati, a differenza della gemella PostgreSQL dove CASCADE li porta via.
--
-- foreign_keys va disattivato. Con i vincoli attivi DROP TABLE esegue una DELETE implicita
-- di tutte le sedi, e le tabelle figlie la bloccano con "FOREIGN KEY constraint failed"
-- (verificato sul database reale: e' esattamente l'errore che si ottiene senza questo
-- PRAGMA). V5 non ne aveva bisogno solo perche' azzera prima le tabelle figlie, cosa qui
-- impossibile: shifts contiene dati veri.
--
-- Il PRAGMA non basta da solo: dentro una transazione SQLite lo ignora in silenzio, e
-- Flyway avvolge ogni migrazione in una transazione per default. Per questo accanto a questo
-- file c'e' V20__drop_specialists_table.sql.conf con executeInTransaction=false: da Flyway 12
-- la configurazione dello script si legge da quel file gemello, non piu' da un commento qui
-- dentro. Il prezzo e' che questa migrazione non e' atomica; la finestra di rischio e' di due
-- sole istruzioni e i dati sono gia' copiati in locations_new prima che locations venga
-- eliminata, quindi un'interruzione lascia le sedi recuperabili (rinominando locations_new) e
-- la migrazione marcata come fallita, che impedisce l'avvio silenzioso su uno schema incoerente.

PRAGMA foreign_keys=OFF;

CREATE TABLE locations_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    l_order INTEGER,
    code TEXT UNIQUE,
    structure_id INTEGER NOT NULL DEFAULT 1,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    latitude REAL,
    longitude REAL,
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE
);

-- Copia integrale: gli id sono referenziati dalle tabelle figlie e devono restare quelli.
INSERT INTO locations_new (id, name, l_order, code, structure_id, active, latitude, longitude)
SELECT id, name, l_order, code, structure_id, active, latitude, longitude FROM locations;

DROP TABLE locations;
ALTER TABLE locations_new RENAME TO locations;

-- L'indice muore con la tabella: va ricreato con lo stesso nome.
CREATE INDEX IF NOT EXISTS idx_locations_structure ON locations(structure_id);

-- Ormai non piu' referenziata da nessuno: nessun CASCADE necessario.
DROP TABLE IF EXISTS specialists;

PRAGMA foreign_keys=ON;
