package com.netter.paylist.export

import com.netter.paylist.data.dao.ExportAuditDao
import com.netter.paylist.data.entity.ExportAuditEntity
import com.netter.paylist.data.entity.ExportStatus
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

/**
 * One export run request (foreground user action only, §7.7A).
 * The Activity supplies the SAF stream opener + the partial-file
 * cleanup; tests supply in-memory equivalents.
 */
data class ExportRequest(
    val filter: ExportFilter,
    val includeIdentifiers: Boolean,
    /** [ExportAuth.NONE] / `BIOMETRIC` / `DEVICE_CREDENTIAL`. */
    val auth: String,
    val actor: String,
    val fileName: String,
    /** Opens the destination stream (SAF `OutputStream` in the app). */
    val openStream: () -> OutputStream,
    /** Best-effort deletion of the partial file; returns success. */
    val deletePartial: () -> Boolean,
    /** Cancellation signal, checked before every row write. */
    val isCancelled: () -> Boolean = { false },
    /** Progress hook (rows written so far); tests use it to inject revocation. */
    val onRowWritten: ((written: Int) -> Unit)? = null,
)

/** The outcome of one export run. */
sealed interface ExportResult {
    val auditId: String
    val rowsWritten: Int

    data class Completed(
        override val auditId: String,
        override val rowsWritten: Int,
        val excludedCount: Int,
        val hasPartialChain: Boolean,
    ) : ExportResult

    data class Failed(
        override val auditId: String,
        override val rowsWritten: Int,
        val reason: String,
        val cleanupAttempted: Boolean,
        val cleanupSucceeded: Boolean,
    ) : ExportResult

    data class Cancelled(
        override val auditId: String,
        override val rowsWritten: Int,
        val cleanupAttempted: Boolean,
        val cleanupSucceeded: Boolean,
    ) : ExportResult
}

/**
 * Streams a frozen export to its destination (§7.7A write
 * protocol): BOM + RFC 4180 header + rows (CRLF), incremental
 * with periodic flush; COMPLETED only after a final flush +
 * close with the final row count.
 *
 * Authorization (§7.7A, R33/MAJOR E, R35): the snapshot is
 * captured by [ReconciliationExport.build] after device auth and
 * immediately before the first byte is written, and revalidated
 * before the first row write AND before every row write. A
 * consent/config change aborts at the next row — no further row
 * is written — with a truthful FAILED audit and best-effort
 * partial-file cleanup. Precise guarantee: no row is STARTED
 * after a revocation is observed; a revocation landing between
 * the check and the write of one row cannot prevent that row
 * from completing, and previously written bytes may remain
 * despite cleanup. The exporter never claims retraction.
 *
 * Zero side effects (F60): the only write is the `export_audit`
 * row. No POST, no retry, no delivery mutation. No copy of the
 * exported file is retained (no temp file is used at all).
 */
class ReconciliationExportRunner(
    private val export: ReconciliationExport,
    private val auditDao: ExportAuditDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Flush every N rows (periodic flush; the auth check stays per-row). */
        const val FLUSH_EVERY_ROWS: Int = 50
    }

    suspend fun run(request: ExportRequest): ExportResult {
        val auditId = UUID.randomUUID().toString()
        val frozen = export.build(request.filter, request.includeIdentifiers)

        // Row-id collision check before writing (F65): a SHA-256
        // collision is treated as an internal error — FAILED, no
        // file written. It cannot occur in practice.
        if (frozen.rows.map { it.rowId }.toSet().size != frozen.rows.size) {
            writeAudit(request, auditId, ExportStatus.FAILED, 0, frozen,
                notes = "kolisi row_id internal — ekspor dibatalkan sebelum menulis")
            return ExportResult.Failed(
                auditId, 0, "kolisi row_id internal",
                cleanupAttempted = false, cleanupSucceeded = false,
            )
        }

        if (request.isCancelled()) {
            writeAudit(request, auditId, ExportStatus.CANCELLED, 0, frozen,
                notes = "dibatalkan sebelum menulis")
            return ExportResult.Cancelled(
                auditId, 0, cleanupAttempted = false, cleanupSucceeded = false,
            )
        }

        val stream: OutputStream
        try {
            stream = request.openStream()
        } catch (e: Exception) {
            writeAudit(request, auditId, ExportStatus.FAILED, 0, frozen,
                notes = "gagal membuka tujuan: ${e.message?.take(120)}")
            return ExportResult.Failed(
                auditId, 0, "gagal membuka tujuan",
                cleanupAttempted = false, cleanupSucceeded = false,
            )
        }

        var written = 0
        var streamFailed: String? = null
        var authAborted = false
        var cancelled = false
        try {
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                try {
                    // UTF-8 BOM + header row.
                    writer.write("\uFEFF")
                    writer.write(ReconciliationExport.COLUMNS.joinToString(","))
                    writer.write("\r\n")
                    for (row in frozen.rows) {
                        // Per-row revalidation (R35): no row is
                        // STARTED after a revocation is observed.
                        if (!export.revalidate(frozen.snapshot)) {
                            authAborted = true
                            break
                        }
                        if (request.isCancelled()) {
                            cancelled = true
                            break
                        }
                        val line = row.fields.mapIndexed { index, value ->
                            ReconciliationExport.encodeField(
                                ReconciliationExport.COLUMNS[index], value,
                            )
                        }.joinToString(",")
                        writer.write(line)
                        writer.write("\r\n")
                        written++
                        if (written % FLUSH_EVERY_ROWS == 0) writer.flush()
                        request.onRowWritten?.invoke(written)
                    }
                    writer.flush()
                } catch (e: Exception) {
                    streamFailed = e.message?.take(120) ?: e.javaClass.simpleName
                }
            }
        } catch (e: Exception) {
            if (streamFailed == null) {
                streamFailed = e.message?.take(120) ?: e.javaClass.simpleName
            }
        }

        return when {
            streamFailed != null -> {
                val cleanup = cleanupPartial(request)
                writeAudit(request, auditId, ExportStatus.FAILED, written, frozen,
                    notes = "gagal menulis setelah $written baris: $streamFailed; " +
                        cleanupNote(cleanup))
                ExportResult.Failed(
                    auditId, written, "gagal menulis: $streamFailed",
                    cleanupAttempted = cleanup.first, cleanupSucceeded = cleanup.second,
                )
            }
            authAborted -> {
                val cleanup = cleanupPartial(request)
                writeAudit(request, auditId, ExportStatus.FAILED, written, frozen,
                    notes = "persetujuan/konfigurasi rute berubah saat menulis " +
                        "(berhenti setelah $written baris); " + cleanupNote(cleanup))
                ExportResult.Failed(
                    auditId, written,
                    "persetujuan/konfigurasi rute berubah saat menulis",
                    cleanupAttempted = cleanup.first, cleanupSucceeded = cleanup.second,
                )
            }
            cancelled -> {
                val cleanup = cleanupPartial(request)
                writeAudit(request, auditId, ExportStatus.CANCELLED, written, frozen,
                    notes = "dibatalkan pengguna setelah $written baris; " + cleanupNote(cleanup))
                ExportResult.Cancelled(
                    auditId, written,
                    cleanupAttempted = cleanup.first, cleanupSucceeded = cleanup.second,
                )
            }
            else -> {
                writeAudit(request, auditId, ExportStatus.COMPLETED, written, frozen)
                ExportResult.Completed(
                    auditId, written, frozen.excludedCount, frozen.hasPartialChain,
                )
            }
        }
    }

    private fun cleanupPartial(request: ExportRequest): Pair<Boolean, Boolean> {
        return try {
            true to request.deletePartial()
        } catch (e: Exception) {
            true to false
        }
    }

    private fun cleanupNote(cleanup: Pair<Boolean, Boolean>): String =
        if (!cleanup.first) {
            "pembersihan berkas parsial tidak dicoba"
        } else if (cleanup.second) {
            "berkas parsial berhasil dihapus"
        } else {
            "berkas parsial MUNGKIN masih ada di lokasi tujuan — hapus manual bila perlu"
        }

    private suspend fun writeAudit(
        request: ExportRequest,
        auditId: String,
        status: String,
        rowsWritten: Int,
        frozen: FrozenExport,
        notes: String? = null,
    ) {
        val filterJson = request.filter.toJson().apply {
            put("include_identifiers", request.includeIdentifiers)
        }
        val auditNotes = buildString {
            if (notes != null) append(notes)
            if (frozen.hasPartialChain) {
                if (isNotEmpty()) append(" | ")
                append("ekspor berisi rantai parsial (hanya hop yang tercapai)")
            }
        }
        auditDao.insert(
            ExportAuditEntity(
                exportId = auditId,
                exportedAtMs = clock(),
                actor = request.actor,
                filterJson = filterJson.toString(),
                rowCount = rowsWritten,
                excludedRowCount = frozen.excludedCount,
                fileName = request.fileName,
                status = status,
                auth = request.auth,
                epochsJson = frozen.snapshot.toJson().toString(),
                notes = auditNotes.ifEmpty { null },
            ),
        )
    }
}
