-- Perbaikan C2 (desain 08 §4d): kolom audit re-match di qris_notif_log (v2New).
-- Aditif saja: baris lama tidak berubah (rematch_count=0, stempel NULL).
-- v2New SUDAH punya KEY idx_amount (amount) — tidak perlu indeks baru.
ALTER TABLE qris_notif_log
  ADD COLUMN rematch_count INT NOT NULL DEFAULT 0,
  ADD COLUMN last_rematch_at DATETIME NULL,
  ADD COLUMN rematched_by VARCHAR(64) NULL;
