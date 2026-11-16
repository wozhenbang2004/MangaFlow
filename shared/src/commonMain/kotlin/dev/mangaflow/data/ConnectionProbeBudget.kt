package dev.mangaflow.data

import kotlinx.coroutines.sync.Semaphore

/** Only explicit connection checks share this limit; normal reading/download admission is unchanged. */
internal object ConnectionProbeBudget { val requests = Semaphore(6) }
