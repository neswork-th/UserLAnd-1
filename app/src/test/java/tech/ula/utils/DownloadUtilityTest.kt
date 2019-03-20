package tech.ula.utils

import android.app.DownloadManager
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.* // ktlint-disable no-wildcard-imports
import org.mockito.junit.MockitoJUnitRunner
import tech.ula.model.repositories.DownloadMetadata
import java.io.File

@RunWith(MockitoJUnitRunner::class)
class DownloadUtilityTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Mock lateinit var assetPreferences: AssetPreferences

    @Mock lateinit var downloadManagerWrapper: DownloadManagerWrapper

    @Mock lateinit var requestReturn1: DownloadManager.Request

    @Mock lateinit var requestReturn2: DownloadManager.Request

    private lateinit var downloadDirectory: File

    private val userlandDownloadPrefix = "UserLAnd-"

    private val name1 = "name1"
    private val name2 = "name2"
    private val type1 = "type1"
    private val type2 = "type2"
    private val url1 = "url1"
    private val url2 = "url2"
    private val version = "v0"

    private val downloadMetadata1 = DownloadMetadata(name1, type1, version, url1)
    private val downloadMetadata2 = DownloadMetadata(name2, type2, version, url2)
    private val downloadList = listOf(downloadMetadata1, downloadMetadata2)

    private lateinit var downloadUtility: DownloadUtility

    @Before
    fun setup() {
        downloadDirectory = tempFolder.newFolder("downloads")
        whenever(downloadManagerWrapper.getDownloadsDirectory())
                .thenReturn(downloadDirectory)
        whenever(downloadManagerWrapper.generateDownloadRequest(downloadMetadata1.url, "$userlandDownloadPrefix${downloadMetadata1.downloadTitle}"))
                .thenReturn(requestReturn1)
        whenever(downloadManagerWrapper.generateDownloadRequest(downloadMetadata2.url, "$userlandDownloadPrefix${downloadMetadata2.downloadTitle}"))
                .thenReturn(requestReturn2)

        downloadUtility = DownloadUtility(assetPreferences, downloadManagerWrapper, applicationFilesDir = tempFolder.root)
    }

    @Test
    fun `Returns appropriate value from asset preferences about whether cache is populated`() {
        val expectedFirstResult = true
        val expectedSecondResult = false
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(expectedFirstResult)
                .thenReturn(expectedSecondResult)

        val firstResult = downloadUtility.downloadStateHasBeenCached()
        val secondResult = downloadUtility.downloadStateHasBeenCached()

        assertEquals(expectedFirstResult, firstResult)
        assertEquals(expectedSecondResult, secondResult)
    }

    @Test
    fun `Returns CacheSyncAttemptedWhileCacheIsEmpty if sync cache called while nothing is cached`() {
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(false)

        val result = downloadUtility.syncStateWithCache()

        assertTrue(result is CacheSyncAttemptedWhileCacheIsEmpty)
    }

    @Test
    fun `Returns AssetDownloadFailure while syncing if any cached downloads failed`() {
        val downloadId = 0L
        val failureReason = "fail"
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(true)
        whenever(assetPreferences.getEnqueuedDownloads())
                .thenReturn(setOf(downloadId))

        whenever(downloadManagerWrapper.downloadHasFailed(downloadId))
                .thenReturn(true)
        whenever(downloadManagerWrapper.getDownloadFailureReason(downloadId))
                .thenReturn(failureReason)

        val result = downloadUtility.syncStateWithCache()
        assertTrue(result is AssetDownloadFailure)
        val cast = result as AssetDownloadFailure
        assertEquals(failureReason, cast.reason)
    }

    @Test
    fun `Returns AllDownloadsCompletedSuccessfully if all downloads have completed since cache was updated`() {
        val downloadId = 0L
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(true)
        whenever(assetPreferences.getEnqueuedDownloads())
                .thenReturn(setOf(downloadId))
        whenever(downloadManagerWrapper.downloadHasFailed(downloadId))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasSucceeded(downloadId))
                .thenReturn(true)

        val result = downloadUtility.syncStateWithCache()

        assertTrue(result is AllDownloadsCompletedSuccessfully)
        verify(assetPreferences).setDownloadsAreInProgress(false)
        verify(assetPreferences).clearEnqueuedDownloadsCache()
    }

    @Test
    fun `Returns CompletedDownloadsUpdate if downloads are still in progress during sync`() {
        val downloadIds = setOf<Long>(0, 1)
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(true)
        whenever(assetPreferences.getEnqueuedDownloads())
                .thenReturn(downloadIds)
        whenever(downloadManagerWrapper.downloadHasFailed(0))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasSucceeded(0))
                .thenReturn(true)
        whenever(downloadManagerWrapper.downloadHasFailed(1))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasSucceeded(1))
                .thenReturn(false)

        val result = downloadUtility.syncStateWithCache()

        assertTrue(result is CompletedDownloadsUpdate)
        val cast = result as CompletedDownloadsUpdate
        assertEquals(1, cast.numCompleted)
        assertEquals(2, cast.numTotal)
    }

    @Test
    fun `Sets up download process`() {
        whenever(downloadManagerWrapper.enqueue(requestReturn1))
                .thenReturn(0)
        whenever(downloadManagerWrapper.enqueue(requestReturn2))
                .thenReturn(1)

        downloadUtility.downloadRequirements(downloadList)

        verify(assetPreferences).clearEnqueuedDownloadsCache()
        verify(assetPreferences).setDownloadsAreInProgress(true)
        verify(assetPreferences).setEnqueuedDownloads(setOf(0, 1))
    }

    private fun setupDownloadState() {
        whenever(downloadManagerWrapper.enqueue(requestReturn1))
                .thenReturn(0)
        whenever(downloadManagerWrapper.enqueue(requestReturn2))
                .thenReturn(1)

        downloadUtility.downloadRequirements(downloadList)
    }

    @Test
    fun `Returns NonUserLandDownloadFound if a a download we did not start is found`() {
        setupDownloadState()

        val result = downloadUtility.handleDownloadComplete(-1)

        assertTrue(result is NonUserlandDownloadFound)
    }

    @Test
    fun `Returns AssetDownloadFailure if any downloads fail`() {
        setupDownloadState()
        whenever(downloadManagerWrapper.downloadHasFailed(0))
                .thenReturn(true)
        whenever(downloadManagerWrapper.getDownloadFailureReason(0))
                .thenReturn("fail")

        val result = downloadUtility.handleDownloadComplete(0)

        assertTrue(result is AssetDownloadFailure)
        result as AssetDownloadFailure
        assertEquals("fail", result.reason)
    }

    @Test
    fun `Completes downloads and then resets cache when all complete`() {
        setupDownloadState()
        whenever(downloadManagerWrapper.downloadHasFailed(0))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasFailed(1))
                .thenReturn(false)

        val result1 = downloadUtility.handleDownloadComplete(0)
        val result2 = downloadUtility.handleDownloadComplete(1)

        assertTrue(result1 is CompletedDownloadsUpdate)
        assertTrue(result2 is AllDownloadsCompletedSuccessfully)
        result1 as CompletedDownloadsUpdate
        result2 as AllDownloadsCompletedSuccessfully
        assertEquals(1, result1.numCompleted)
        assertEquals(2, result1.numTotal)
        verify(assetPreferences).setDownloadsAreInProgress(false)
        verify(assetPreferences, times(2)).clearEnqueuedDownloadsCache()
    }

    @Test
    fun `Clears download directory of userland files`() {
        val asset1DownloadsFile = File("${downloadDirectory.path}/$userlandDownloadPrefix${downloadMetadata1.downloadTitle}")
        val asset2DownloadsFile = File("${downloadDirectory.path}/$userlandDownloadPrefix${downloadMetadata2.downloadTitle}")
        asset1DownloadsFile.createNewFile()
        asset2DownloadsFile.createNewFile()
        assertTrue(asset1DownloadsFile.exists())
        assertTrue(asset2DownloadsFile.exists())

        downloadUtility.downloadRequirements(downloadList)

        assertFalse(asset1DownloadsFile.exists())
        assertFalse(asset2DownloadsFile.exists())
    }
}