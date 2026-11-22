-- Scratch schema for the v2New contract target (bill.netterhome.net/bayarQR/notify.php).
--
-- Copied verbatim from production SHOW CREATE TABLE on stbjkt (database v2),
-- 2026-11-22, with AUTO_INCREMENT counters removed. No production data, no
-- production credentials. Tables are ordered so foreign keys resolve.
--
-- Tables NOT included (and why):
--   report: unreachable in the scratch copy. qris_apply.php only touches
--     report inside the WhatsApp block, which requires wa/wa_config.php
--     (not copied) and a non-empty customer phone (seeds use '').
--   Router/finance/wa PHP files are deliberately NOT copied to the scratch
--     tree; their absence makes MikroTik, email and WhatsApp steps skip
--     via the file_exists guards already present in qris_apply.php.

CREATE DATABASE IF NOT EXISTS paylist_contract_v2
  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE paylist_contract_v2;

CREATE TABLE `pelanggan` (
  `id_pelanggan` int(11) NOT NULL AUTO_INCREMENT,
  `username` varchar(255) DEFAULT NULL,
  `nama_pelanggan` varchar(255) NOT NULL,
  `layanan` varchar(255) NOT NULL,
  `email` varchar(255) NOT NULL,
  `no_hp` varchar(255) NOT NULL,
  `tagihan` decimal(10,0) DEFAULT NULL,
  `tanggal_pemasangan` date NOT NULL,
  `password` varchar(255) NOT NULL,
  `pajak` decimal(10,2) NOT NULL DEFAULT 0.00,
  `status_pembayaran` varchar(255) DEFAULT NULL,
  `wa` varchar(255) DEFAULT NULL,
  `tipe` varchar(255) DEFAULT NULL,
  `alamat` varchar(255) DEFAULT NULL,
  `ipAddress` varchar(1999) DEFAULT NULL,
  `coordinates` varchar(50) DEFAULT NULL,
  `poin` int(11) DEFAULT 0,
  `router` varchar(255) DEFAULT NULL,
  `masa_aktif` varchar(100) DEFAULT '0',
  `isolir` enum('masa aktif','tanggal pemasangan') DEFAULT 'masa aktif',
  `refcode` varchar(10) DEFAULT NULL,
  `addon` varchar(255) DEFAULT NULL,
  `jatuhTempo` date DEFAULT NULL,
  `email_flag` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id_pelanggan`),
  KEY `idx_jatuhTempo` (`jatuhTempo`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `proforma` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `idtrx` varchar(255) DEFAULT NULL,
  `tripayRef` varchar(255) DEFAULT NULL,
  `router` varchar(255) DEFAULT NULL,
  `id_pelanggan` int(11) NOT NULL,
  `nama_pelanggan` varchar(255) NOT NULL,
  `layanan` varchar(255) NOT NULL,
  `tagihan` decimal(10,2) NOT NULL,
  `tanggal_terbit` date NOT NULL,
  `biaya_instalasi` decimal(10,2) DEFAULT NULL,
  `addon` decimal(10,2) DEFAULT NULL,
  `diskon` decimal(10,2) DEFAULT NULL,
  `total_tagihan` decimal(10,2) DEFAULT NULL,
  `pajak` decimal(10,2) DEFAULT NULL,
  `status` varchar(50) DEFAULT NULL,
  `tanggal_bayar` date DEFAULT NULL,
  `kode_unik` smallint(5) unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `id_pelanggan` (`id_pelanggan`),
  KEY `idx_kode_unik` (`kode_unik`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `pemasukan` (
  `id_pembayaran` int(11) NOT NULL AUTO_INCREMENT,
  `idtrx` varchar(255) DEFAULT NULL,
  `id_pelanggan` int(11) NOT NULL,
  `nama_pelanggan` varchar(50) NOT NULL,
  `layanan` varchar(50) NOT NULL,
  `tagihan` varchar(20) NOT NULL,
  `tanggal_pembayaran` date NOT NULL,
  `biaya_instalasi` varchar(25) DEFAULT NULL,
  `diskon` varchar(25) DEFAULT NULL,
  `metode` varchar(50) NOT NULL,
  `user` varchar(50) NOT NULL,
  PRIMARY KEY (`id_pembayaran`),
  KEY `id_pelanggan` (`id_pelanggan`),
  CONSTRAINT `pemasukan_ibfk_1` FOREIGN KEY (`id_pelanggan`) REFERENCES `pelanggan` (`id_pelanggan`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `qris_notif_log` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `received_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `device` varchar(64) NOT NULL,
  `source_pkg` varchar(128) NOT NULL DEFAULT '',
  `amount` bigint(20) unsigned DEFAULT NULL,
  `raw_title` varchar(500) NOT NULL DEFAULT '',
  `raw_text` varchar(500) NOT NULL DEFAULT '',
  `notif_time` varchar(32) NOT NULL DEFAULT '',
  `dedupe_key` varchar(128) NOT NULL,
  `match_status` enum('logged','matched','ambiguous') NOT NULL DEFAULT 'logged',
  `matched_id_pelanggan` int(10) unsigned DEFAULT NULL,
  `matched_id_proforma` int(10) unsigned DEFAULT NULL,
  `applied_at` timestamp NULL DEFAULT NULL,
  `apply_note` varchar(255) DEFAULT NULL,
  `payload_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`payload_json`)),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_dedupe` (`dedupe_key`),
  KEY `idx_received` (`received_at`),
  KEY `idx_amount` (`amount`),
  KEY `idx_device` (`device`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `router` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `router` varchar(255) NOT NULL,
  `host` varchar(255) NOT NULL,
  `user` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `port` int(11) DEFAULT NULL,
  `username` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `paylist_config` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `secret_hash` char(64) NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `updated_by` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `poin_status` (
  `status_pembayaran` varchar(50) NOT NULL,
  `poin` int(11) NOT NULL,
  PRIMARY KEY (`status_pembayaran`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
