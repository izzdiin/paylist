package com.netter.paylist.privacy

import com.netter.paylist.export.ReconciliationExport
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F55 (design §10, §7.7): static/build inspection confirms no
 * diagnostic/recovery export path exists, the reconciliation
 * export (§7.7A) is the only export path in the tree, and no
 * raw/hash key leaks into exported content.
 *
 * The runtime half of F55 (no internal key in any exported
 * file) is proven by ReconciliationExportTest F57/F65, which
 * assert on the actual CSV bytes.
 */
class DiagnosticsExportAbsenceTest {

    @Test
    fun f55_reconciliationExportIsTheOnlyExportPath() {
        val root = findSourceRoot()
            ?: throw AssertionError("source root not found — fail-closed: cannot prove F55")
        val moduleDir = root.absoluteFile.parentFile?.parentFile?.parentFile
            ?: throw AssertionError("cannot derive the module dir for F55")
        val scanRoots = listOf(
            File(moduleDir, "src/main/java"),
            File(moduleDir, "src/test/java"),
            File(moduleDir, "src/androidTest/java"),
        ).filter { it.isDirectory }
        assertTrue("no source roots found for F55", scanRoots.isNotEmpty())

        // File/share APIs that could carry an export out of the app.
        val exportApis = listOf(
            "FileOutputStream", "openOutputStream", "ACTION_CREATE_DOCUMENT",
            "ACTION_SEND", "createDocument", "OutputStreamWriter",
            "FileWriter", "PrintWriter", ".csv", "text/csv",
        )
        // The ONLY files allowed to touch an export API: the §7.7A
        // reconciliation export, its Activity, and its tests (this
        // test itself names the APIs as scan terms).
        val allowed = setOf(
            "ReconciliationExport.kt", "ReconciliationExportRunner.kt",
            "ReconciliationExportActivity.kt",
            "ReconciliationExportTest.kt", "DiagnosticsExportAbsenceTest.kt",
            // LH-T08 names the same APIs as its own scan terms.
            "HistoryTest.kt",
        )
        val violations = mutableListOf<String>()
        for (scanRoot in scanRoots) {
            scanRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    if (file.name in allowed) return@forEach
                    file.readLines().forEachIndexed { index, line ->
                        if (isComment(line)) return@forEachIndexed
                        for (api in exportApis) {
                            if (line.contains(api)) {
                                violations.add(
                                    "${file.relativeTo(moduleDir)}:${index + 1}: ${line.trim()}",
                                )
                            }
                        }
                    }
                }
        }
        assertTrue(
            "export API outside the reconciliation export (F55):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun f55_exportSchemaCarriesNoInternalIdentifier() {
        // The 20-column schema is the only thing the export
        // writes: it must not name an internal identifier.
        val forbidden = listOf(
            "dedupe_key", "content_sha256", "event_id", "legacy_qkey",
            "qkey", "dedupeKey", "contentSha256", "eventId",
        )
        for (column in ReconciliationExport.COLUMNS) {
            for (id in forbidden) {
                assertTrue(
                    "export column '$column' names internal identifier '$id' (F55)",
                    !column.contains(id),
                )
            }
        }
        // The export package's non-comment code must not carry an
        // internal identifier as a string literal (a column name
        // or a key) — internal use via typed properties
        // (`event.dedupeKey`, `event.eventId`) is fine and is how
        // the quarantine check + DAO reads work.
        val root = findSourceRoot()
            ?: throw AssertionError("source root not found — fail-closed: cannot prove F55")
        val exportDir = File(root, "com/netter/paylist/export")
        assertTrue("export package must exist for F55", exportDir.isDirectory)
        val literalIds = listOf(
            "\"dedupe_key\"", "\"content_sha256\"", "\"event_id\"",
            "\"legacy_qkey\"", "\"qkey\"",
        )
        val violations = mutableListOf<String>()
        exportDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (isComment(line)) return@forEachIndexed
                    for (id in literalIds) {
                        if (line.contains(id)) {
                            violations.add("${file.name}:${index + 1}: ${line.trim()}")
                        }
                    }
                }
            }
        assertTrue(
            "internal identifier literal in export code (F55):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("*") || trimmed.startsWith("//") ||
            trimmed.startsWith("/*")
    }

    /**
     * Locates `src/main/java` for the static check (mirrors
     * CutoverTest F27). Returns null when not found — the caller
     * fails closed.
     */
    private fun findSourceRoot(): File? {
        listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }?.let { return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val appSrc = File(dir, "app/src/main/java")
            if (appSrc.isDirectory) return appSrc
            val src = File(dir, "src/main/java")
            if (src.isDirectory) return src
            dir = dir.parentFile
        }
        return null
    }
}
