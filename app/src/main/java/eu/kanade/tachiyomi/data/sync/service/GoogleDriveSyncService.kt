package eu.kanade.tachiyomi.data.sync.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GoogleDriveSyncService(context: Context, json: Json, syncPreferences: SyncPreferences) : SyncService(
    context,
    json,
    syncPreferences,
) {
    constructor(context: Context) : this(
        context,
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        },
        Injekt.get<SyncPreferences>(),
    )

    enum class DeleteSyncDataStatus {
        NOT_INITIALIZED,
        NO_FILES,
        SUCCESS,
        ERROR,
    }

    private val appName = context.stringResource(MR.strings.app_name)

    private val remoteFileName = "${appName}_sync.proto.gz"

    private val googleDriveService = GoogleDriveService(context)

    private val protoBuf: ProtoBuf = Injekt.get()

    override suspend fun doSync(syncData: SyncData): Backup? {
        // KMK -->
        try {
            beforeSync()
            // KMK <--
            val remoteSData = pullSyncData()

            if (remoteSData != null) {
                // Get local unique device ID
                val localDeviceId = syncPreferences.uniqueDeviceID()
                val lastSyncDeviceId = remoteSData.deviceId

                // Log the device IDs
                logcat(LogPriority.DEBUG, "SyncService") {
                    "Local device ID: $localDeviceId, Last sync device ID: $lastSyncDeviceId"
                }

                // check if the last sync was done by the same device if so overwrite the remote data with the local data
                return if (lastSyncDeviceId == localDeviceId) {
                    pushSyncData(syncData)
                    syncData.backup
                } else {
                    // Merge the local and remote sync data
                    val mergedSyncData = mergeSyncData(syncData, remoteSData)
                    pushSyncData(mergedSyncData)
                    mergedSyncData.backup
                }
            }

            pushSyncData(syncData)
            return syncData.backup
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, "SyncService") { "Error syncing: ${e.message}" }
            return null
        }
    }

    private suspend fun beforeSync() {
        googleDriveService.refreshToken()
    }

    private fun pullSyncData(): SyncData? {
        val drive = googleDriveService.driveService
            ?: throw Exception(context.stringResource(SYMR.strings.google_drive_not_signed_in))

        val fileList = getAppDataFileList(drive)
        if (fileList.isEmpty()) {
            logcat(LogPriority.INFO) { "No files found in app data" }
            return null
        }

        val gdriveFileId = fileList[0].id
        logcat(LogPriority.DEBUG) { "Google Drive File ID: $gdriveFileId" }

        try {
            drive.files().get(gdriveFileId).executeMediaAsInputStream().use { inputStream ->
                GZIPInputStream(inputStream).use { gzipInputStream ->
                    val byteArray = gzipInputStream.readBytes()
                    val backup = protoBuf.decodeFromByteArray(Backup.serializer(), byteArray)
                    val deviceId = fileList[0].appProperties["deviceId"] ?: ""
                    return SyncData(deviceId = deviceId, backup = backup)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e) { "Error downloading file" }
            throw Exception("Failed to download sync data: ${e.message}", e)
        }
    }

    private suspend fun pushSyncData(syncData: SyncData) {
        val drive = googleDriveService.driveService
            ?: throw Exception(context.stringResource(SYMR.strings.google_drive_not_signed_in))

        val fileList = getAppDataFileList(drive)
        val backup = syncData.backup ?: return

        val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) {
            throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
        }

        PipedOutputStream().use { pos ->
            PipedInputStream(pos).use { pis ->
                withIOContext {
                    launch {
                        GZIPOutputStream(pos).use { gzipOutputStream ->
                            gzipOutputStream.write(byteArray)
                        }
                    }

                    val mediaContent = InputStreamContent("application/octet-stream", pis)

                    if (fileList.isNotEmpty()) {
                        val fileId = fileList[0].id
                        val fileMetadata = File().apply {
                            name = remoteFileName
                            mimeType = "application/octet-stream"
                            appProperties = mapOf("deviceId" to syncData.deviceId)
                        }
                        drive.files().update(fileId, fileMetadata, mediaContent).execute()
                        logcat(LogPriority.DEBUG) {
                            "Updated existing sync data file in Google Drive with file ID: $fileId"
                        }
                    } else {
                        val fileMetadata = File().apply {
                            name = remoteFileName
                            mimeType = "application/octet-stream"
                            parents = listOf("appDataFolder")
                            appProperties = mapOf("deviceId" to syncData.deviceId)
                        }
                        val uploadedFile = drive.files().create(fileMetadata, mediaContent)
                            .setFields("id")
                            .execute()
                        logcat(LogPriority.DEBUG) {
                            "Created new sync data file in Google Drive with file ID: ${uploadedFile.id}"
                        }
                    }
                }
            }
        }
    }

    private fun getAppDataFileList(drive: Drive): MutableList<File> {
        try {
            // Search for the existing file by name in the appData folder
            // KMK -->
            // Uploaded backups are application/octet-stream, so filtering for application/x-gzip hides them.
            val query = "name = '$remoteFileName'"
            // KMK <--
            val fileList = drive.files()
                .list()
                .setSpaces("appDataFolder")
                .setQ(query)
                .setFields("files(id, name, createdTime, appProperties)")
                .execute()
                .files
            logcat { "AppData folder file list: $fileList" }

            return fileList
        } catch (e: Exception) {
            // KMK -->
            // A Drive error is not an empty folder: never upload as a new backup after a failed lookup.
            logcat(LogPriority.ERROR, throwable = e) { "Failed to list Google Drive sync data" }
            throw e
            // KMK <--
        }
    }

    suspend fun deleteSyncDataFromGoogleDrive(): DeleteSyncDataStatus {
        return withIOContext {
            try {
                // KMK -->
                googleDriveService.refreshToken()
                val drive = googleDriveService.driveService
                    ?: return@withIOContext DeleteSyncDataStatus.NOT_INITIALIZED
                // KMK <--
                val appDataFileList = getAppDataFileList(drive)

                if (appDataFileList.isEmpty()) {
                    this@GoogleDriveSyncService
                        .logcat(LogPriority.DEBUG) { "No sync data file found in appData folder of Google Drive" }
                    DeleteSyncDataStatus.NO_FILES
                } else {
                    for (file in appDataFileList) {
                        drive.files().delete(file.id).execute()
                        this@GoogleDriveSyncService.logcat(
                            LogPriority.DEBUG,
                        ) { "Deleted sync data file in appData folder of Google Drive with file ID: ${file.id}" }
                    }
                    DeleteSyncDataStatus.SUCCESS
                }
            } catch (e: Exception) {
                this@GoogleDriveSyncService.logcat(LogPriority.ERROR, throwable = e) {
                    "Error occurred while interacting with Google Drive"
                }
                DeleteSyncDataStatus.ERROR
            }
        }
    }
}

// KMK -->
// Google Identity Services owns account consent and token refresh. Never package OAuth client secrets.
class GoogleDriveService(private val context: Context) {
    private val syncPreferences = Injekt.get<SyncPreferences>()
    private val authorizationClient by lazy { Identity.getAuthorizationClient(context.applicationContext) }
    private val requestedScopes = listOf(Scope(DriveScopes.DRIVE_APPDATA))

    var driveService: Drive? = null
        private set

    private fun authorizationRequest() = AuthorizationRequest.builder()
        .setRequestedScopes(requestedScopes)
        .build()

    fun requestAuthorization(
        onResolution: (PendingIntent) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        authorizationClient.authorize(authorizationRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.let(onResolution)
                        ?: onFailure("Google sign-in could not be opened")
                } else {
                    runCatching { acceptAuthorization(result) }
                        .onSuccess { onSuccess() }
                        .onFailure { onFailure(it.localizedMessage ?: "Google sign-in failed") }
                }
            }
            .addOnFailureListener { onFailure(it.localizedMessage ?: "Google sign-in unavailable") }
    }

    fun completeAuthorization(data: Intent?) {
        requireNotNull(data) { "Google sign-in returned no result" }
        acceptAuthorization(authorizationClient.getAuthorizationResultFromIntent(data))
    }

    private fun acceptAuthorization(result: AuthorizationResult) {
        check(DriveScopes.DRIVE_APPDATA in result.grantedScopes) {
            "Google Drive app data permission was not granted"
        }
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google Drive did not return an access token")
        // Google Play services owns tokens; remove credentials left by the retired OAuth flow.
        syncPreferences.googleDriveAccessToken().set("")
        syncPreferences.googleDriveRefreshToken().set("")
        setupGoogleDriveService(token)
    }

    internal suspend fun refreshToken() = withIOContext {
        val result = suspendCancellableCoroutine<AuthorizationResult> { continuation ->
            authorizationClient.authorize(authorizationRequest())
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
        }
        if (result.hasResolution()) {
            driveService = null
            throw IllegalStateException("Sign in to Google Drive from Settings before synchronizing")
        }
        acceptAuthorization(result)
    }

    private fun setupGoogleDriveService(accessToken: String) {
        val jsonFactory = GsonFactory.getDefaultInstance()
        val transport = NetHttpTransport()
        val credential = GoogleCredential.Builder()
            .setTransport(transport)
            .setJsonFactory(jsonFactory)
            .build()
        credential.accessToken = accessToken
        driveService = Drive.Builder(transport, jsonFactory, credential)
            .setApplicationName(context.stringResource(MR.strings.app_name))
            .build()
    }
}
// KMK <--
