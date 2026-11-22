package dev.mangaflow.storage

import dev.mangaflow.platform.storage.StorageFile

internal actual suspend fun renderLocalPdf(
    source: StorageFile, staging: StorageFile, limits: LocalPdfLimits,
): List<LocalPdfPage> = throw LocalPdfImportException(LocalPdfFailure.UNAVAILABLE)
