-- Scratch schema for the warganet contract target (warga.netter.my.id/paylist/notify.php).
--
-- Copied verbatim from production SHOW CREATE TABLE on laptopgrt (database
-- warganet), 2026-11-22, with AUTO_INCREMENT counters removed. No production
-- data, no production credentials. Tables are ordered so foreign keys resolve.
--
-- The report table IS included here (unlike the v2New schema): the warganet
-- apply path always attempts a WhatsApp call after commit, and on failure
-- writes a FAILED row to report. In the scratch stack that call dies at a
-- dead local proxy (see setup-scratch.sh), so the FAILED row is expected
-- evidence of local-only failure, not of a real send.

CREATE DATABASE IF NOT EXISTS paylist_contract_wg
  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE paylist_contract_wg;

CREATE TABLE `pelanggan` (
  `id_pelanggan` int(11) NOT NULL AUTO_INCREMENT,
  `nama_pelanggan` varchar(255) NOT NULL,
  `username` varchar(255) DEFAULT NULL,
  `layanan` varchar(255) NOT NULL,
  `email` varchar(255) NOT NULL,
  `no_hp` varchar(255) NOT NULL,
  `tagihan` decimal(10,0) DEFAULT NULL,
  `tanggal_pemasangan` date NOT NULL,
  `password` varchar(255) NOT NULL,
  `pajak` decimal(10,2) NOT NULL DEFAULT 0.00,
  `status_pembayaran` varchar(255) DEFAULT NULL,
  `wa` varchar(255) DEFAULT NULL,
  `alamat` varchar(255) DEFAULT NULL,
  `kode_bayar` varchar(1999) DEFAULT NULL,
  `coordinates` varchar(50) DEFAULT NULL,
  `poin` int(11) DEFAULT 0,
  `router` varchar(255) NOT NULL,
  `masa_aktif` varchar(100) DEFAULT '0',
  `refcode` varchar(10) DEFAULT NULL,
  `addon` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id_pelanggan`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `payment` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `nama_pelanggan` varchar(255) NOT NULL,
  `idtrx` varchar(255) NOT NULL,
  `status` varchar(255) NOT NULL,
  `id_pelanggan` varchar(20) DEFAULT NULL,
  `layanan` varchar(255) NOT NULL,
  `tagihan` decimal(10,2) NOT NULL,
  `waktu` timestamp NOT NULL DEFAULT current_timestamp(),
  `processed` int(11) DEFAULT 0,
  `nama_mitra` varchar(255) DEFAULT NULL,
  `metode` varchar(255) NOT NULL DEFAULT 'Unknown',
  `kode_unik` smallint(5) unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kode_unik` (`kode_unik`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `paylist_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `received_at` timestamp NULL DEFAULT current_timestamp(),
  `device` varchar(64) DEFAULT NULL,
  `source_pkg` varchar(128) DEFAULT NULL,
  `amount` bigint(20) DEFAULT NULL,
  `raw_title` varchar(500) DEFAULT NULL,
  `raw_text` varchar(500) DEFAULT NULL,
  `notif_time` varchar(32) DEFAULT NULL,
  `dedupe_key` varchar(128) NOT NULL,
  `match_status` enum('logged','matched','ambiguous') DEFAULT 'logged',
  `matched_id_pelanggan` bigint(20) DEFAULT NULL,
  `matched_id_payment` int(11) DEFAULT NULL,
  `payload_json` longtext DEFAULT NULL,
  `applied_at` timestamp NULL DEFAULT NULL,
  `apply_note` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `dedupe_key` (`dedupe_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `poin_status` (
  `status_pembayaran` varchar(50) NOT NULL,
  `poin` int(11) NOT NULL,
  PRIMARY KEY (`status_pembayaran`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `pppoe` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `local_address` varchar(255) NOT NULL,
  `remote_address` varchar(255) NOT NULL,
  `rate_limit` varchar(50) NOT NULL,
  `parent_queue` varchar(255) NOT NULL,
  `only_one` varchar(10) NOT NULL,
  `comment` text DEFAULT NULL,
  `masa_aktif` int(11) NOT NULL,
  PRIMARY KEY (`id`)
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
  `tipe_payment` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id_pembayaran`),
  KEY `id_pelanggan` (`id_pelanggan`),
  CONSTRAINT `pemasukan_ibfk_1` FOREIGN KEY (`id_pelanggan`) REFERENCES `pelanggan` (`id_pelanggan`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `saldo_mitra` (
  `nama_mitra` varchar(255) NOT NULL,
  `saldo_tambahan` int(11) NOT NULL,
  PRIMARY KEY (`nama_mitra`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `mitra` (
  `id_mitra` int(11) NOT NULL AUTO_INCREMENT,
  `nama_mitra` varchar(100) NOT NULL,
  `no_hp` varchar(15) NOT NULL,
  `email` varchar(100) NOT NULL,
  `alamat` text NOT NULL,
  `bank` varchar(50) NOT NULL,
  `no_rek` varchar(20) NOT NULL,
  `saldo` decimal(15,2) NOT NULL DEFAULT 0.00,
  `refcode` varchar(10) NOT NULL,
  PRIMARY KEY (`id_mitra`),
  UNIQUE KEY `no_hp` (`no_hp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `saldo_history` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `username` varchar(255) NOT NULL,
  `saldo_before` decimal(10,2) NOT NULL,
  `saldo_after` decimal(10,2) NOT NULL,
  `transaction_amount` decimal(10,2) NOT NULL,
  `transaction_date` datetime NOT NULL,
  `note` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `report` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `message_id` varchar(255) NOT NULL,
  `target` varchar(20) NOT NULL,
  `message` text NOT NULL,
  `status` varchar(100) NOT NULL,
  `timestamp` datetime NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `router` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `router` varchar(255) NOT NULL,
  `host` varchar(255) NOT NULL,
  `user` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `port` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `paylist_config` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `secret_hash` char(64) NOT NULL,
  `updated_at` timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `updated_by` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `pemasukan_hotspot` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `idtrx` varchar(255) DEFAULT NULL,
  `id_mitra` int(11) NOT NULL,
  `nama_mitra` varchar(100) DEFAULT NULL,
  `nominal` decimal(15,2) NOT NULL,
  `tanggal_pembayaran` date NOT NULL,
  `tipe_payment` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mitra` (`id_mitra`),
  CONSTRAINT `pemasukan_hotspot_ibfk_1` FOREIGN KEY (`id_mitra`) REFERENCES `mitra` (`id_mitra`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
