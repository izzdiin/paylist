-- Perbaikan C2 (desain 08 §4d): kolom audit re-match + indeks nominal
-- (dipakai lookup hook kode-birth) di paylist_log (warganet).
-- Aditif saja: baris lama tidak berubah (rematch_count=0, stempel NULL).
ALTER TABLE paylist_log
  ADD COLUMN rematch_count INT NOT NULL DEFAULT 0,
  ADD COLUMN last_rematch_at DATETIME NULL,
  ADD COLUMN rematched_by VARCHAR(64) NULL,
  ADD KEY idx_amount (amount);
