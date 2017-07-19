/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package at.bitfire.davdroid.syncadapter

import android.accounts.Account
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.NotificationManagerCompat
import android.support.v7.app.NotificationCompat
import at.bitfire.dav4android.DavResource
import at.bitfire.dav4android.exception.*
import at.bitfire.dav4android.property.GetCTag
import at.bitfire.dav4android.property.GetETag
import at.bitfire.davdroid.AccountSettings
import at.bitfire.davdroid.App
import at.bitfire.davdroid.HttpClient
import at.bitfire.davdroid.R
import at.bitfire.davdroid.resource.LocalCollection
import at.bitfire.davdroid.resource.LocalResource
import at.bitfire.davdroid.ui.AccountSettingsActivity
import at.bitfire.davdroid.ui.DebugInfoActivity
import at.bitfire.ical4android.CalendarStorageException
import at.bitfire.vcard4android.ContactsStorageException
import okhttp3.HttpUrl
import okhttp3.RequestBody
import java.io.IOException
import java.util.*
import java.util.logging.Level

abstract class SyncManager(
        val context: Context,
        val account: Account,
        val settings: AccountSettings,
        val extras: Bundle,
        val authority: String,
        val syncResult: SyncResult,
        val uniqueCollectionId: String
) {

    val SYNC_PHASE_PREPARE = 0
    val SYNC_PHASE_QUERY_CAPABILITIES = 1
    val SYNC_PHASE_PROCESS_LOCALLY_DELETED = 2
    val SYNC_PHASE_PREPARE_DIRTY = 3
    val SYNC_PHASE_UPLOAD_DIRTY = 4
    val SYNC_PHASE_CHECK_SYNC_STATE = 5
    val SYNC_PHASE_LIST_LOCAL = 6
    val SYNC_PHASE_LIST_REMOTE = 7
    val SYNC_PHASE_COMPARE_LOCAL_REMOTE = 8
    val SYNC_PHASE_DOWNLOAD_REMOTE = 9
    val SYNC_PHASE_POST_PROCESSING = 10
    val SYNC_PHASE_SAVE_SYNC_STATE = 11

    protected val notificationManager = NotificationManagerCompat.from(context)!!

    protected lateinit var localCollection: LocalCollection<*>

    protected val httpClient = HttpClient.create(context, settings)
    protected lateinit var collectionURL: HttpUrl
    protected lateinit var davCollection: DavResource


    /** state information for debug info (local resource) */
    protected var currentLocalResource: LocalResource? = null

    /** state information for debug info (remote resource) */
    protected var currentDavResource: DavResource? = null


    /** remote CTag at the time of {@link #listRemote()} */
    protected var remoteCTag: String? = null

    /** sync-able resources in the local collection, as enumerated by {@link #listLocal()} */
    protected lateinit var localResources: MutableMap<String, LocalResource>

    /** sync-able resources in the remote collection, as enumerated by {@link #listRemote()} */
    protected lateinit var remoteResources: MutableMap<String, DavResource>

    /** resources which have changed on the server, as determined by {@link #compareLocalRemote()} */
    protected val toDownload = mutableSetOf<DavResource>()


    protected abstract fun notificationId(): Int
    protected abstract fun getSyncErrorTitle(): String

    fun performSync() {
        // dismiss previous error notifications
        notificationManager.cancel(uniqueCollectionId, notificationId())

        var syncPhase = SYNC_PHASE_PREPARE
        try {
            App.log.info("Preparing synchronization")
            if (!prepare()) {
                App.log.info("No reason to synchronize, aborting")
                return
            }

            if (Thread.interrupted())
                return
            syncPhase = SYNC_PHASE_QUERY_CAPABILITIES
            App.log.info("Querying capabilities")
            queryCapabilities()

            syncPhase = SYNC_PHASE_PROCESS_LOCALLY_DELETED
            App.log.info("Processing locally deleted entries")
            processLocallyDeleted()

            if (Thread.interrupted())
                return
            syncPhase = SYNC_PHASE_PREPARE_DIRTY
            App.log.info("Locally preparing dirty entries")
            prepareDirty()

            syncPhase = SYNC_PHASE_UPLOAD_DIRTY
            App.log.info("Uploading dirty entries")
            uploadDirty()

            syncPhase = SYNC_PHASE_CHECK_SYNC_STATE
            App.log.info("Checking sync state")
            if (checkSyncState()) {
                syncPhase = SYNC_PHASE_LIST_LOCAL
                App.log.info("Listing local entries")
                listLocal()

                if (Thread.interrupted())
                    return
                syncPhase = SYNC_PHASE_LIST_REMOTE
                App.log.info("Listing remote entries")
                listRemote()

                if (Thread.interrupted())
                    return
                syncPhase = SYNC_PHASE_COMPARE_LOCAL_REMOTE
                App.log.info("Comparing local/remote entries")
                compareLocalRemote()

                syncPhase = SYNC_PHASE_DOWNLOAD_REMOTE
                App.log.info("Downloading remote entries")
                downloadRemote()

                syncPhase = SYNC_PHASE_POST_PROCESSING
                App.log.info("Post-processing")
                postProcess()

                syncPhase = SYNC_PHASE_SAVE_SYNC_STATE
                App.log.info("Saving sync state")
                saveSyncState()
            } else
                App.log.info("Remote collection didn't change, skipping remote sync")

        } catch(e: IOException) {
            App.log.log(Level.WARNING, "I/O exception during sync, trying again later", e)
            syncResult.stats.numIoExceptions++
        } catch(e: ServiceUnavailableException) {
            App.log.log(Level.WARNING, "Got 503 Service unavailable, trying again later", e)
            syncResult.stats.numIoExceptions++
            e.retryAfter?.let { retryAfter ->
                // how many seconds to wait? getTime() returns ms, so divide by 1000
                syncResult.delayUntil = (retryAfter.time - Date().time) / 1000
            }
        } catch(e: Throwable) {
            val messageString: Int

            when (e) {
                is UnauthorizedException -> {
                    App.log.log(Level.SEVERE, "Not authorized anymore", e)
                    messageString = R.string.sync_error_unauthorized
                    syncResult.stats.numAuthExceptions++
                }
                is HttpException, is DavException -> {
                    App.log.log(Level.SEVERE, "HTTP/DAV Exception during sync", e)
                    messageString = R.string.sync_error_http_dav
                    syncResult.stats.numParseExceptions++
                }
                is CalendarStorageException, is ContactsStorageException -> {
                    App.log.log(Level.SEVERE, "Couldn't access local storage", e)
                    messageString = R.string.sync_error_local_storage
                    syncResult.databaseError = true
                }
                else -> {
                    App.log.log(Level.SEVERE, "Unknown sync error", e)
                    messageString = R.string.sync_error
                    syncResult.stats.numParseExceptions++
                }
            }

            val detailsIntent: Intent
            if (e is UnauthorizedException) {
                detailsIntent = Intent(context, AccountSettingsActivity::class.java)
                detailsIntent.putExtra(AccountSettingsActivity.EXTRA_ACCOUNT, account)
            } else {
                detailsIntent = Intent(context, DebugInfoActivity::class.java)
                detailsIntent.putExtra(DebugInfoActivity.KEY_THROWABLE, e)
                detailsIntent.putExtra(DebugInfoActivity.KEY_ACCOUNT, account)
                detailsIntent.putExtra(DebugInfoActivity.KEY_AUTHORITY, authority)
                detailsIntent.putExtra(DebugInfoActivity.KEY_PHASE, syncPhase)
                currentLocalResource?.let { detailsIntent.putExtra(DebugInfoActivity.KEY_LOCAL_RESOURCE, it.toString()) }
                currentDavResource?.let { detailsIntent.putExtra(DebugInfoActivity.KEY_REMOTE_RESOURCE, it.toString()) }
            }

            // to make the PendingIntent unique
            detailsIntent.data = Uri.parse("uri://${javaClass.name}/$uniqueCollectionId")

            val builder = NotificationCompat.Builder(context)
            builder .setSmallIcon(R.drawable.ic_error_light)
                    .setLargeIcon(App.getLauncherBitmap(context))
                    .setContentTitle(getSyncErrorTitle())
                    .setContentIntent(PendingIntent.getActivity(context, 0, detailsIntent, PendingIntent.FLAG_CANCEL_CURRENT))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)

            try {
                val phases = context.resources.getStringArray(R.array.sync_error_phases)
                val message = context.getString(messageString, phases[syncPhase])
                builder.setContentText(message)
            } catch (ex: IndexOutOfBoundsException) {
                // should never happen
            }

            notificationManager.notify(uniqueCollectionId, notificationId(), builder.build())
        }
    }


    /** Prepares synchronization (for instance, allocates necessary resources).
     * @return whether actual synchronization is required / can be made. true = synchronization
     *         shall be continued, false = synchronization can be skipped */
    abstract protected fun prepare(): Boolean

    abstract protected fun queryCapabilities()

    /**
     * Process locally deleted entries (DELETE them on the server as well).
     * Checks Thread.interrupted() before each request to allow quick sync cancellation.
     */
    protected fun processLocallyDeleted() {
        // Remove locally deleted entries from server (if they have a name, i.e. if they were uploaded before),
        // but only if they don't have changed on the server. Then finally remove them from the local address book.
        val localList = localCollection.getDeleted()
        for (local in localList) {
            if (Thread.interrupted())
                return

            currentLocalResource = local

            val fileName = local.fileName
            if (fileName != null) {
                App.log.info("$fileName has been deleted locally -> deleting from server")

                val remote = DavResource(httpClient, collectionURL.newBuilder().addPathSegment(fileName).build())
                currentDavResource = remote
                try {
                    remote.delete(local.eTag)
                } catch (e: HttpException) {
                    App.log.warning("Couldn't delete $fileName from server; ignoring (may be downloaded again)")
                }
            } else
                App.log.info("Removing local record #${local.id} which has been deleted locally and was never uploaded")
            local.delete()
            syncResult.stats.numDeletes++

            currentLocalResource = null
            currentDavResource = null
        }
    }

    protected open fun prepareDirty() {
        // assign file names and UIDs to new contacts so that we can use the file name as an index
        App.log.info("Looking for contacts/groups without file name")
        for (local in localCollection.getWithoutFileName()) {
            currentLocalResource = local

            App.log.fine("Found local record #${local.id} without file name; generating file name/UID if necessary")
            local.prepareForUpload()

            currentLocalResource = null
        }
    }

    abstract protected fun prepareUpload(resource: LocalResource): RequestBody

    /**
     * Uploads dirty records to the server, using a PUT request for each record.
     * Checks Thread.interrupted() before each request to allow quick sync cancellation.
     */
    protected fun uploadDirty() {
        // upload dirty contacts
        for (local in localCollection.getDirty()) {
            if (Thread.interrupted())
                return

            currentLocalResource = local
            val fileName = local.fileName

            val remote = DavResource(httpClient, collectionURL.newBuilder().addPathSegment(fileName).build())
            currentDavResource = remote

            // generate entity to upload (VCard, iCal, whatever)
            val body = prepareUpload(local)

            try {
                if (local.eTag == null) {
                    App.log.info("Uploading new record $fileName")
                    remote.put(body, null, true)
                } else {
                    App.log.info("Uploading locally modified record $fileName")
                    remote.put(body, local.eTag, false)
                }
            } catch(e: ConflictException) {
                // we can't interact with the user to resolve the conflict, so we treat 409 like 412
                App.log.log(Level.INFO, "Edit conflict, ignoring", e)
            } catch(e: PreconditionFailedException) {
                App.log.log(Level.INFO, "Resource has been modified on the server before upload, ignoring", e)
            }

            val newETag = remote.properties[GetETag.NAME] as GetETag?
            val eTag: String?
            if (newETag != null) {
                eTag = newETag.eTag
                App.log.fine("Received new ETag=$eTag after uploading")
            } else {
                App.log.fine("Didn't receive new ETag after uploading, setting to null")
                eTag = null
            }

            local.clearDirty(eTag)

            currentLocalResource = null
            currentDavResource = null
        }
    }

    /**
     * Checks the current sync state (e.g. CTag) and whether synchronization from remote is required.
     * @return <ul>
     *      <li><code>true</code>   if the remote collection has changed, i.e. synchronization from remote is required</li>
     *      <li><code>false</code>  if the remote collection hasn't changed</li>
     * </ul>
     */
    protected fun checkSyncState(): Boolean {
        // check CTag (ignore on manual sync)
        (davCollection.properties[GetCTag.NAME] as GetCTag?)?.let { remoteCTag = it.cTag }

        val localCTag = if (extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL)) {
            App.log.info("Manual sync, ignoring CTag")
            null
        } else
            localCollection.getCTag()

        return if (remoteCTag != null && remoteCTag == localCTag) {
            App.log.info("Remote collection didn't change (CTag=$remoteCTag), no need to query children")
            false
        } else
            true
    }

    /**
     * Lists all local resources which should be taken into account for synchronization into {@link #localResources}.
     */
    protected fun listLocal() {
        // fetch list of local contacts and build hash table to index file name
        val localList = localCollection.getAll()
        val resources = HashMap<String, LocalResource>(localList.size)
        for (resource in localList) {
            App.log.fine("Found local resource: ${resource.fileName}")
            resource.fileName?.let { resources[it] = resource }
        }
        localResources = resources
    }

    /**
     * Lists all members of the remote collection which should be taken into account for synchronization into {@link #remoteResources}.
     */
    abstract protected fun listRemote()

    /**
     * Compares {@link #localResources} and {@link #remoteResources} by file name and ETag:
     * <ul>
     *     <li>Local resources which are not available in the remote collection (anymore) will be removed.</li>
     *     <li>Resources whose remote ETag has changed will be added into {@link #toDownload}</li>
     * </ul>
     */
    protected fun compareLocalRemote() {
        /* check which contacts
           1. are not present anymore remotely -> delete immediately on local side
           2. updated remotely -> add to downloadNames
           3. added remotely  -> add to downloadNames
         */
        toDownload.clear()
        for ((name,local) in localResources) {
            val remote = remoteResources[name]
            currentDavResource = remote

            if (remote == null) {
                App.log.info("$name is not on server anymore, deleting")
                currentLocalResource = local
                local.delete()
                syncResult.stats.numDeletes++
            } else {
                // contact is still on server, check whether it has been updated remotely
                val getETag = remote.properties[GetETag.NAME] as GetETag?
                if (getETag == null || getETag.eTag == null)
                    throw DavException("Server didn't provide ETag")
                val localETag = local.eTag
                val remoteETag = getETag.eTag
                if (remoteETag == localETag) {
                    App.log.fine("$name has not been changed on server (ETag still $remoteETag)")
                    syncResult.stats.numSkippedEntries++
                } else {
                    App.log.info("$name has been changed on server (current ETag=$remoteETag, last known ETag=$localETag)")
                    toDownload.add(remote)
                }

                // remote entry has been seen, remove from list
                remoteResources.remove(name)

                currentDavResource = null
                currentLocalResource = null
            }
        }

        // add all unseen (= remotely added) remote contacts
        if (remoteResources.isNotEmpty()) {
            App.log.info("New resources have been found on the server: ${remoteResources.keys.joinToString(", ")}")
            toDownload.addAll(remoteResources.values)
        }
    }

    /**
     * Downloads the remote resources in {@link #toDownload} and stores them locally.
     * Must check Thread.interrupted() periodically to allow quick sync cancellation.
     */
    abstract protected fun downloadRemote()

    /**
     * For post-processing of entries, for instance assigning groups.
     */
    protected open fun postProcess() {}

    protected fun saveSyncState() {
        /* Save sync state (CTag). It doesn't matter if it has changed during the sync process
           (for instance, because another client has uploaded changes), because this will simply
           cause all remote entries to be listed at the next sync. */
        App.log.info("Saving CTag=$remoteCTag")
        localCollection.setCTag(remoteCTag)
    }

}