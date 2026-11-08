package org.gcap.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object QaSummaryExporter {

    fun generate(appStorage: GCapStorage, samples: List<GroundCall>): Pair<String, ByteArray> {
        val dateStr = SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date()).lowercase()
        val filenameBase = "${dateStr}_all_xgvs_for_QA"

        val validSamples = samples
            .filter { it.sampleId.length < 8 }
            .sortedBy { it.sampleId.lowercase() }

        // One sheet per sample, each following the individual <id>.ods model
        // (six app pages combined on one sheet, titled by sample id).
        val allSheets = mutableListOf<OdsExporter.Sheet>()

        for (gc in validSamples) {
            allSheets.add(OdsExporter.individualSheet(gc))
        }

        val odsBytes = OdsExporter.packageSheets(allSheets)
        val xlsxBytes = XlsxExporter.packageBytes(allSheets)

        appStorage.writeSubFolderBytes("XGV_ODS_XLSX", "$filenameBase.ods", odsBytes)
        appStorage.writeSubFolderBytes("XGV_ODS_XLSX", "$filenameBase.xlsx", xlsxBytes)

        return "$filenameBase.ods" to odsBytes
    }
}
