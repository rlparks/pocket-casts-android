package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.core.content.IntentCompat
import androidx.core.os.bundleOf
import androidx.media.utils.MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_MEDIA_ID
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.EpisodeAnalytics
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.Settings.MediaNotificationControls
import au.com.shiftyjelly.pocketcasts.preferences.model.HeadphoneAction
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkHelper
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.auto.AutoConverter
import au.com.shiftyjelly.pocketcasts.repositories.playback.auto.AutoMediaId
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.SmartPlaylistManager
import au.com.shiftyjelly.pocketcasts.utils.Optional
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.extensions.getLaunchActivityPendingIntent
import au.com.shiftyjelly.pocketcasts.utils.extensions.roundedSpeed
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import au.com.shiftyjelly.pocketcasts.images.R as IR

class MediaSessionManager(
    val playbackManager: PlaybackManager,
    val podcastManager: PodcastManager,
    val episodeManager: EpisodeManager,
    val smartPlaylistManager: SmartPlaylistManager,
    val settings: Settings,
    val context: Context,
    val episodeAnalytics: EpisodeAnalytics,
    val bookmarkManager: BookmarkManager,
    applicationScope: CoroutineScope,
) : CoroutineScope {
    companion object {
        const val EXTRA_TRANSIENT = "pocketcasts_transient_loss"
        const val ACTION_NOT_SUPPORTED = "action_not_supported"

        // These manufacturers have issues when the skip to next/previous track are missing from the media session.
        private val MANUFACTURERS_TO_HIDE_CUSTOM_SKIP_BUTTONS = listOf("mercedes-benz")

        fun calculateSearchQueryOptions(query: String): List<String> {
            val options = mutableListOf<String>()
            options.add(query)
            val parts = query.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size > 1) {
                for (i in parts.size - 1 downTo 1) {
                    val lessParts = arrayOfNulls<String>(i)
                    System.arraycopy(parts, 0, lessParts, 0, i)
                    options.add(lessParts.joinToString(separator = " "))
                }
            }
            return options
        }
    }

    val mediaSession = MediaSessionCompat(context, "PocketCastsMediaSession")
    val disposables = CompositeDisposable()
    private val source = SourceView.MEDIA_BUTTON_BROADCAST_ACTION

    private var bookmarkHelper: BookmarkHelper

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    private val commandQueue: MutableSharedFlow<QueuedCommand> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 10, // 10 is a somewhat arbitrary number--if we have more than that many commands queued, something has probably gone very wrong.
    )

    init {
        mediaSession.setCallback(
            MediaSessionCallback(
                playbackManager,
                episodeManager,
                enqueueCommand = { tag, command ->
                    val added = commandQueue.tryEmit(Pair(tag, command))
                    if (added) {
                        Timber.i("Added command to queue: $tag")
                    } else {
                        LogBuffer.e(LogBuffer.TAG_PLAYBACK, "Failed to add command to queue: $tag")
                    }
                },
            ),
        )

        if (!Util.isAutomotive(context)) { // We can't start activities on automotive
            mediaSession.setSessionActivity(context.getLaunchActivityPendingIntent())
        }
        mediaSession.setRatingType(RatingCompat.RATING_HEART)

        // this tells the session not to shuffle all our buttons over one when there's no playlist currently loaded. This keeps our skip buttons on either side of play/pause
        val extras = Bundle()
        extras.putBoolean("com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_QUEUE", true)
        mediaSession.setExtras(extras)

        bookmarkHelper = BookmarkHelper(
            playbackManager,
            bookmarkManager,
            settings,
        )

        connect()

        applicationScope.launch {
            commandQueue.collect { (tag, command) ->
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Executing queued command: $tag")
                command()
            }
        }
    }

    fun startObserving() {
        observePlaybackState()
        observeCustomMediaActionsVisibility()
        observeMediaNotificationControls()

        val upNextQueueChanges = playbackManager.upNextQueue.getChangesFlowWithLiveCurrentEpisode(episodeManager, podcastManager)
            .distinctUntilChanged { stateOne, stateTwo ->
                UpNextQueue.State.isEqualWithEpisodeCompare(stateOne, stateTwo) { episodeOne, episodeTwo ->
                    episodeOne.uuid == episodeTwo.uuid &&
                        episodeOne.duration == episodeTwo.duration &&
                        episodeOne.isStarred == episodeTwo.isStarred
                }
            }

        combine(upNextQueueChanges, settings.artworkConfiguration.flow) { queueState, artworkConfiguration -> queueState to artworkConfiguration }
            .onEach { (queueState, artworkConfiguration) -> updateUpNext(queueState, artworkConfiguration.useEpisodeArtwork) }
            .catch { Timber.e(it) }
            .launchIn(this)
    }

    private fun observeCustomMediaActionsVisibility() {
        launch {
            settings.customMediaActionsVisibility.flow.collect {
                withContext(Dispatchers.Main) {
                    val playbackStateCompat = getPlaybackStateCompat(playbackManager.playbackStateRelay.blockingFirst(), currentEpisode = playbackManager.getCurrentEpisode())
                    // Called to update playback state with updated custom media actions visibility
                    updatePlaybackState(playbackStateCompat)
                }
            }
        }
    }

    private fun observeMediaNotificationControls() {
        launch {
            settings.mediaControlItems.flow.collect {
                withContext(Dispatchers.Main) {
                    val playbackStateCompat = getPlaybackStateCompat(playbackManager.playbackStateRelay.blockingFirst(), currentEpisode = playbackManager.getCurrentEpisode())
                    updatePlaybackState(playbackStateCompat)
                }
            }
        }
    }

    private fun connect() {
        // start the foreground service
        val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {}
        val mediaBrowser = MediaBrowserCompat(context, ComponentName(context, PlaybackService::class.java), connectionCallback, null)
        mediaBrowser.connect()
    }

    private fun getPlaybackStateRx(playbackState: PlaybackState, currentEpisode: Optional<BaseEpisode>): Single<PlaybackStateCompat> {
        return Single.fromCallable {
            getPlaybackStateCompat(playbackState, currentEpisode.get())
        }
    }

    private fun updatePlaybackState(playbackState: PlaybackStateCompat) {
        Timber.i("MediaSession playback state. $playbackState")
        mediaSession.setPlaybackState(playbackState)
    }

    private fun getPlaybackStateCompat(playbackState: PlaybackState, currentEpisode: BaseEpisode?): PlaybackStateCompat {
        if (playbackState.isError) {
            mediaSession.isActive = false
            return PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
                .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, playbackState.lastErrorMessage)
                .build()
        }

        if (playbackState.isPlaying || playbackState.transientLoss) {
            mediaSession.isActive = true
        }

        if (playbackState.isEmpty || currentEpisode == null) {
            val stateBuilder = PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
            return stateBuilder.build()
        }

        val state = if (playbackState.isPlaying) {
            if (playbackState.isBuffering) PlaybackStateCompat.STATE_BUFFERING else PlaybackStateCompat.STATE_PLAYING
        } else {
            if (playbackState.state == PlaybackState.State.STOPPED) PlaybackStateCompat.STATE_STOPPED else PlaybackStateCompat.STATE_PAUSED
        }

        val currentSpeed = playbackState.playbackSpeed
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(state, playbackState.positionMs.toLong(), currentSpeed.toFloat(), SystemClock.elapsedRealtime())
            .setActions(getSupportedActions(playbackState))
            .setExtras(
                bundleOf(
                    PLAYBACK_STATE_EXTRAS_KEY_MEDIA_ID to currentEpisode.uuid,
                    EXTRA_TRANSIENT to playbackState.transientLoss,
                ),
            )

        // Do not add custom actions on Wear OS because there is a bug in Wear 3.5 that causes
        // this to make the Wear OS media notification stop working. This bug was fixed
        // internally by the Wear OS team in June 2023. Once that fix is released we should be
        // able to remove this guard.
        if (!Util.isWearOs(context)) {
            addCustomActions(stateBuilder, currentEpisode, playbackState)
        }

        return stateBuilder.build()
    }

    private fun getSupportedActions(playbackState: PlaybackState): Long {
        val prepareActions = PlaybackStateCompat.ACTION_PREPARE or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH

        if (playbackState.isEmpty) {
            return PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                prepareActions
        } else {
            val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                prepareActions

            return if (useCustomSkipButtons()) {
                actions
            } else {
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    actions
            }
        }
    }

    private fun updateUpNext(upNext: UpNextQueue.State, useEpisodeArtwork: Boolean) {
        try {
            mediaSession.setQueueTitle("Up Next")
            if (upNext is UpNextQueue.State.Loaded) {
                updateMetadata(upNext.episode, useEpisodeArtwork)

                val items = upNext.queue.map { episode ->
                    val podcastUuid = if (episode is PodcastEpisode) episode.podcastUuid else null
                    val podcast = podcastUuid?.let { podcastManager.findPodcastByUuidBlocking(it) }
                    val podcastTitle = episode.displaySubtitle(podcast)
                    val localUri = AutoConverter.getPodcastArtworkUri(podcast, episode, context, settings.artworkConfiguration.value.useEpisodeArtwork)
                    val description = MediaDescriptionCompat.Builder()
                        .setDescription(episode.episodeDescription)
                        .setTitle(episode.title)
                        .setSubtitle(podcastTitle)
                        .setMediaId(episode.uuid)
                        .setIconUri(localUri)
                        .build()

                    return@map MediaSessionCompat.QueueItem(description, episode.adapterId)
                }
                mediaSession.setQueue(items)
            } else {
                updateMetadata(null, useEpisodeArtwork)
                mediaSession.setQueue(emptyList())

                val playbackStateCompat = getPlaybackStateCompat(PlaybackState(state = PlaybackState.State.EMPTY), currentEpisode = null)
                updatePlaybackState(playbackStateCompat)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun observePlaybackState() {
        val ignoreStates = listOf(
            // ignore buffer position because it isn't displayed in the media session
            PlaybackManager.LastChangeFrom.OnUpdateBufferPosition.value,
            // ignore the playback progress updates as the media session can calculate this without being sent it every second
            PlaybackManager.LastChangeFrom.OnUpdateCurrentPosition.value,
            // ignore the user seeking as the event onBufferingStateChanged will update the buffering state
            PlaybackManager.LastChangeFrom.OnUserSeeking.value,
        )

        var previousEpisode: BaseEpisode? = null

        playbackManager.playbackStateRelay
            .observeOn(Schedulers.io())
            .switchMap { state ->
                val episodeSource =
                    if (state.isEmpty) {
                        Observable.just(Optional.empty())
                    } else {
                        episodeManager.findEpisodeByUuidRxFlowable(state.episodeUuid)
                            .distinctUntilChanged(BaseEpisode.isMediaSessionEqual)
                            .map { Optional.of(it) }
                            // if the episode is deleted from the database while playing catch the error and just return an empty state
                            .onErrorReturn { Optional.empty() }
                            .toObservable()
                    }
                Observables.combineLatest(Observable.just(state), episodeSource)
            }
            .filter {
                !ignoreStates.contains(it.first.lastChangeFrom) || !BaseEpisode.isMediaSessionEqual(it.second.get(), previousEpisode)
            }
            .doOnNext {
                previousEpisode = it.second.get()
            }
            .switchMap { (state, episode) -> getPlaybackStateRx(state, episode).toObservable().onErrorResumeNext(Observable.empty()) }
            .switchMap {
                Observable.fromCallable { updatePlaybackState(it) }
                    .doOnError { LogBuffer.e(LogBuffer.TAG_PLAYBACK, "Error updating playback state in media session: ${it.message}") }.retry(3)
            }
            .ignoreElements()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onError = { throwable ->
                    LogBuffer.e(LogBuffer.TAG_PLAYBACK, "MEDIA SESSION ERROR: Error updating playback state: ${throwable.message}")
                },
            ).addTo(disposables)
    }

    private fun updateMetadata(episode: BaseEpisode?, useEpisodeArtwork: Boolean) {
        if (episode == null) {
            Timber.i("MediaSession metadata. Nothing Playing.")
            mediaSession.setMetadata(NOTHING_PLAYING)
            return
        }

        val podcastUuid = if (episode is PodcastEpisode) episode.podcastUuid else null
        val podcast = podcastUuid?.let { podcastManager.findPodcastByUuidBlocking(it) }

        val podcastTitle = episode.displaySubtitle(podcast)
        val safeCharacterPodcastTitle = podcastTitle.replace("%", "pct")
        var nowPlayingBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, episode.uuid)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, safeCharacterPodcastTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, episode.durationMs.toLong())
            .putString(MediaMetadataCompat.METADATA_KEY_GENRE, "Podcast")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, episode.title)

        if (episode is PodcastEpisode) {
            nowPlayingBuilder.putRating(MediaMetadataCompat.METADATA_KEY_RATING, RatingCompat.newHeartRating(episode.isStarred))
        }

        if (podcast != null && podcast.author.isNotEmpty()) {
            nowPlayingBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, podcast.author)
        }

        Timber.i("MediaSession metadata. Episode: ${episode.uuid} ${episode.title} Duration: ${episode.durationMs.toLong()}")

        if (settings.showArtworkOnLockScreen.value) {
            val bitmapUri = AutoConverter.getPodcastArtworkUri(podcast, episode, context, useEpisodeArtwork)?.toString()
            nowPlayingBuilder = nowPlayingBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, bitmapUri)
            if (Util.isAutomotive(context)) nowPlayingBuilder = nowPlayingBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, bitmapUri)

            // Send the bitmap, as some devices don't support URLs, such as a Tesla.
            // Don't do this for Wear OS or Automotive to reduce the amount memory used.
            if (!Util.isWearOs(context) && !Util.isAutomotive(context)) {
                AutoConverter.getPodcastArtworkBitmap(episode, context, useEpisodeArtwork)?.let { bitmap ->
                    nowPlayingBuilder = nowPlayingBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                }
            }
            Timber.i("MediaSession metadata. With artwork.")
        }

        val nowPlaying = nowPlayingBuilder.build()
        mediaSession.setMetadata(nowPlaying)
    }

    private fun addCustomActions(stateBuilder: PlaybackStateCompat.Builder, currentEpisode: BaseEpisode, playbackState: PlaybackState) {
        if (useCustomSkipButtons()) {
            addCustomAction(stateBuilder, APP_ACTION_SKIP_BACK, "Skip back", IR.drawable.media_skipback)
            addCustomAction(stateBuilder, APP_ACTION_SKIP_FWD, "Skip forward", IR.drawable.media_skipforward)
        }

        val visibleCount = if (settings.customMediaActionsVisibility.value) MediaNotificationControls.MAX_VISIBLE_OPTIONS else 0
        settings.mediaControlItems.value.take(visibleCount).forEach { mediaControl ->
            when (mediaControl) {
                MediaNotificationControls.Archive -> addCustomAction(stateBuilder, APP_ACTION_ARCHIVE, "Archive", IR.drawable.ic_archive)
                MediaNotificationControls.MarkAsPlayed -> addCustomAction(stateBuilder, APP_ACTION_MARK_AS_PLAYED, "Mark as played", IR.drawable.auto_markasplayed)
                MediaNotificationControls.PlayNext -> addCustomAction(stateBuilder, APP_ACTION_PLAY_NEXT, "Play next", com.google.android.gms.cast.framework.R.drawable.cast_ic_mini_controller_skip_next)
                MediaNotificationControls.PlaybackSpeed -> {
                    if (playbackManager.isAudioEffectsAvailable()) {
                        val drawableId = when (playbackState.playbackSpeed.roundedSpeed()) {
                            in 0.0..<0.55 -> IR.drawable.auto_0_5
                            in 0.55..<0.65 -> IR.drawable.auto_0_6
                            in 0.65..<0.75 -> IR.drawable.auto_0_7
                            in 0.75..<0.85 -> IR.drawable.auto_0_8
                            in 0.85..<0.95 -> IR.drawable.auto_0_9
                            in 0.95..<1.05 -> IR.drawable.auto_1
                            in 1.05..<1.15 -> IR.drawable.auto_1_1
                            in 1.15..<1.25 -> IR.drawable.auto_1_2
                            in 1.25..<1.35 -> IR.drawable.auto_1_3
                            in 1.35..<1.45 -> IR.drawable.auto_1_4
                            in 1.45..<1.55 -> IR.drawable.auto_1_5
                            in 1.55..<1.65 -> IR.drawable.auto_1_6
                            in 1.65..<1.75 -> IR.drawable.auto_1_7
                            in 1.75..<1.85 -> IR.drawable.auto_1_8
                            in 1.85..<1.95 -> IR.drawable.auto_1_9
                            in 1.95..<2.05 -> IR.drawable.auto_2
                            in 2.05..<2.15 -> IR.drawable.auto_2_1
                            in 2.15..<2.25 -> IR.drawable.auto_2_2
                            in 2.25..<2.35 -> IR.drawable.auto_2_3
                            in 2.35..<2.45 -> IR.drawable.auto_2_4
                            in 2.45..<2.55 -> IR.drawable.auto_2_5
                            in 2.55..<2.65 -> IR.drawable.auto_2_6
                            in 2.65..<2.75 -> IR.drawable.auto_2_7
                            in 2.75..<2.85 -> IR.drawable.auto_2_8
                            in 2.85..<2.95 -> IR.drawable.auto_2_9
                            in 2.95..<3.05 -> IR.drawable.auto_3
                            in 3.05..<3.15 -> IR.drawable.auto_3_1
                            in 3.15..<3.25 -> IR.drawable.auto_3_2
                            in 3.25..<3.35 -> IR.drawable.auto_3_3
                            in 3.35..<3.45 -> IR.drawable.auto_3_4
                            in 3.45..<3.55 -> IR.drawable.auto_3_5
                            in 3.55..<3.65 -> IR.drawable.auto_3_6
                            in 3.65..<3.75 -> IR.drawable.auto_3_7
                            in 3.75..<3.85 -> IR.drawable.auto_3_8
                            in 3.85..<3.95 -> IR.drawable.auto_3_9
                            in 3.95..<4.05 -> IR.drawable.auto_4
                            in 4.05..<4.15 -> IR.drawable.auto_4_1
                            in 4.15..<4.25 -> IR.drawable.auto_4_2
                            in 4.25..<4.35 -> IR.drawable.auto_4_3
                            in 4.35..<4.45 -> IR.drawable.auto_4_4
                            in 4.45..<4.55 -> IR.drawable.auto_4_5
                            in 4.55..<4.65 -> IR.drawable.auto_4_6
                            in 4.65..<4.75 -> IR.drawable.auto_4_7
                            in 4.75..<4.85 -> IR.drawable.auto_4_8
                            in 4.85..<4.95 -> IR.drawable.auto_4_9
                            in 4.95..<5.05 -> IR.drawable.auto_5
                            else -> IR.drawable.auto_1
                        }

                        stateBuilder.addCustomAction(APP_ACTION_CHANGE_SPEED, "Change speed", drawableId)
                    }
                }
                MediaNotificationControls.Star -> {
                    if (currentEpisode is PodcastEpisode) {
                        if (currentEpisode.isStarred) {
                            addCustomAction(stateBuilder, APP_ACTION_UNSTAR, "Unstar", IR.drawable.auto_starred)
                        } else {
                            addCustomAction(stateBuilder, APP_ACTION_STAR, "Star", IR.drawable.auto_star)
                        }
                    }
                }
            }
        }
    }

    private fun addCustomAction(stateBuilder: PlaybackStateCompat.Builder, action: String, name: CharSequence, @DrawableRes icon: Int) {
        val addToWearExtras = Bundle().apply {
            putBoolean("android.support.wearable.media.extra.CUSTOM_ACTION_SHOW_ON_WEAR", true)
        }

        val skipBackBuilder = PlaybackStateCompat.CustomAction.Builder(action, name, icon).apply {
            setExtras(addToWearExtras)
        }
        stateBuilder.addCustomAction(skipBackBuilder.build())
    }

    inner class MediaSessionCallback(
        val playbackManager: PlaybackManager,
        val episodeManager: EpisodeManager,
        val enqueueCommand: (String, suspend () -> Unit) -> Unit,
    ) : MediaSessionCompat.Callback() {

        private var playFromSearchDisposable: Disposable? = null
        private val mediaEventQueue = MediaEventQueue(scope = this@MediaSessionManager)

        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            if (Intent.ACTION_MEDIA_BUTTON == mediaButtonEvent.action) {
                val keyEvent = IntentCompat.getParcelableExtra(mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                logEvent(keyEvent.toString())
                if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                    LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Media button Android event: ${keyEvent.action}")
                    val inputEvent = when (keyEvent.keyCode) {
                        /**
                         * KEYCODE_MEDIA_PLAY_PAUSE - called when the player audio has focus
                         * KEYCODE_MEDIA_PLAY - can be called when the player doesn't have focus such when sleep mode
                         * KEYCODE_HEADSETHOOK - called on most wired headsets
                         */
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> MediaEvent.SingleTap
                        KeyEvent.KEYCODE_MEDIA_NEXT -> MediaEvent.DoubleTap
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaEvent.TripleTap
                        else -> null
                    }
                    LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Media button input event: ${keyEvent.action}")

                    if (inputEvent != null) {
                        launch {
                            val outputEvent = mediaEventQueue.consumeEvent(inputEvent)
                            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Media button output event: ${keyEvent.action}")
                            when (outputEvent) {
                                MediaEvent.SingleTap -> handleMediaButtonSingleTap()
                                MediaEvent.DoubleTap -> handleMediaButtonDoubleTap()
                                MediaEvent.TripleTap -> handleMediaButtonTripleTap()
                                null -> Unit
                            }
                        }
                        return true
                    }
                }
            } else {
                logEvent("onMediaButtonEvent(${mediaButtonEvent.action ?: "unknown action"})")
            }

            return super.onMediaButtonEvent(mediaButtonEvent)
        }

        private fun onAddBookmark() {
            logEvent("add bookmark")
            val coroutineContext = CoroutineScope(Dispatchers.Main + Job())
            coroutineContext.launch {
                Util.isAndroidAutoConnectedFlow(context).collect {
                    bookmarkHelper.handleAddBookmarkAction(context, it)
                    // Cancel the coroutine after the bookmark has been added
                    coroutineContext.cancel()
                }
            }
        }

        private fun logEvent(action: String) {
            val userInfo = runCatching {
                val info = mediaSession.currentControllerInfo
                "Controller: ${info.packageName} pid: ${info.pid} uid: ${info.uid}"
            }.getOrNull()
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Event from Media Session to $action. ${userInfo.orEmpty()}")
        }
        private fun handleMediaButtonSingleTap() {
            playbackManager.playPause(sourceView = source)
        }

        private fun handleMediaButtonDoubleTap() {
            handleMediaButtonAction(settings.headphoneControlsNextAction.value)
        }

        private fun handleMediaButtonTripleTap() {
            handleMediaButtonAction(settings.headphoneControlsPreviousAction.value)
        }

        private fun handleMediaButtonAction(action: HeadphoneAction) {
            when (action) {
                HeadphoneAction.ADD_BOOKMARK -> onAddBookmark()
                HeadphoneAction.SKIP_FORWARD -> {
                    onSkipToNext()
                    if (!playbackManager.isPlaying()) {
                        enqueueCommand("play") { playbackManager.playQueueSuspend(source) }
                    }
                }
                HeadphoneAction.SKIP_BACK -> {
                    onSkipToPrevious()
                }
                HeadphoneAction.NEXT_CHAPTER,
                HeadphoneAction.PREVIOUS_CHAPTER,
                -> Timber.e(ACTION_NOT_SUPPORTED)
            }
        }

        // We don't need to do anything special to prepare but this will make things
        // faster apparently. The Google Sample App UAMP does the same
        override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
            super.onPrepareFromSearch(query, extras)
            onPlayFromSearch(query, extras)
        }

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
            super.onPrepareFromMediaId(mediaId, extras)
            onPlayFromMediaId(mediaId, extras)
        }

        override fun onPlay() {
            if (Util.isAutomotive(context) && !settings.automotiveConnectedToMediaSession()) {
                // https://developer.android.com/docs/quality-guidelines/car-app-quality#media-autoplay
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Auto start playback ignored just after automotive app restart.")
                return
            }

            logEvent("play")
            enqueueCommand("play") { playbackManager.playQueueSuspend(sourceView = source) }
        }

        override fun onPause() {
            logEvent("pause")
            enqueueCommand("pause") { playbackManager.pauseSuspend(sourceView = source) }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            logEvent("play from search")
            playFromSearchDisposable?.dispose()
            playFromSearchDisposable = performPlayFromSearchRx(query)
                .subscribeOn(Schedulers.io())
                .subscribeBy(onError = { Timber.e(it) })
        }

        // note: the stop event is called from cars when they only want to pause, this is less destructive and doesn't cause issues if they try to play again
        override fun onStop() {
            // This event is causing issues during casting, so as a temporary solution, we are going to ignore it for now
            if (playbackManager.player !is CastPlayer) {
                logEvent("stop")
                enqueueCommand("stop") { playbackManager.pauseSuspend(sourceView = source) }
            }
        }

        override fun onSkipToPrevious() {
            onRewind()
        }

        override fun onSkipToNext() {
            onFastForward()
        }

        override fun onRewind() {
            logEvent("skip backwards")
            enqueueCommand("skip backwards") { playbackManager.skipBackwardSuspend(sourceView = source) }
        }

        override fun onFastForward() {
            logEvent("skip forwards")
            enqueueCommand("skip forwards") { playbackManager.skipForwardSuspend(sourceView = source) }
        }

        override fun onSetRating(rating: RatingCompat?) {
            super.onSetRating(rating)

            if (rating?.hasHeart() == true) {
                starEpisode()
            } else {
                unstarEpisode()
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId ?: return
            launch {
                logEvent("play from media id")

                val autoMediaId = AutoMediaId.fromMediaId(mediaId)
                val episodeId = autoMediaId.episodeId
                episodeManager.findEpisodeByUuid(episodeId)?.let { episode ->
                    enqueueCommand("play from media id") {
                        playbackManager.playNowSuspend(episode = episode, sourceView = source)
                    }
                }
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            action ?: return

            when (action) {
                APP_ACTION_SKIP_BACK -> enqueueCommand("custom action: skip back") {
                    playbackManager.skipBackwardSuspend()
                }
                APP_ACTION_SKIP_FWD -> enqueueCommand("custom action: skip forward") {
                    playbackManager.skipForwardSuspend()
                }
                APP_ACTION_MARK_AS_PLAYED -> markAsPlayed()
                APP_ACTION_STAR -> starEpisode()
                APP_ACTION_UNSTAR -> unstarEpisode()
                APP_ACTION_CHANGE_SPEED -> changePlaybackSpeed()
                APP_ACTION_ARCHIVE -> archive()
                APP_ACTION_PLAY_NEXT -> enqueueCommand("suctom action: play next") {
                    playbackManager.playNextInQueue()
                }
            }
        }

        override fun onSkipToQueueItem(id: Long) {
            val state = playbackManager.upNextQueue.changesObservable.blockingFirst()
            if (state is UpNextQueue.State.Loaded) {
                state.queue.find { it.adapterId == id }?.let { episode ->
                    logEvent("play from skip to queue item")
                    enqueueCommand("skip to queue item") {
                        playbackManager.playNowSuspend(episode = episode, sourceView = source)
                    }
                }
            }
        }

        override fun onSeekTo(pos: Long) {
            logEvent("seek to $pos")
            enqueueCommand("seek to $pos") {
                playbackManager.seekToTimeMsSuspend(pos.toInt())
                playbackManager.trackPlaybackSeek(pos.toInt(), SourceView.MEDIA_BUTTON_BROADCAST_ACTION)
            }
        }
    }

    private fun markAsPlayed() {
        launch {
            val episode = playbackManager.getCurrentEpisode()
            episodeManager.markAsPlayedBlocking(episode, playbackManager, podcastManager)
            episode?.let {
                episodeAnalytics.trackEvent(AnalyticsEvent.EPISODE_MARKED_AS_PLAYED, source, it.uuid)
            }
        }
    }

    private fun starEpisode() {
        launch {
            playbackManager.getCurrentEpisode()?.let {
                if (it is PodcastEpisode) {
                    it.isStarred = true
                    episodeManager.starEpisode(episode = it, starred = true, sourceView = source)
                }
            }
        }
    }

    private fun unstarEpisode() {
        launch {
            playbackManager.getCurrentEpisode()?.let {
                if (it is PodcastEpisode) {
                    it.isStarred = false
                    episodeManager.starEpisode(episode = it, starred = false, sourceView = source)
                }
            }
        }
    }

    private fun changePlaybackSpeed() {
        launch {
            val newSpeed = when (playbackManager.getPlaybackSpeed()) {
                in 0.0..<0.60 -> 0.6
                in 0.60..<0.80 -> 0.8
                in 0.80..<1.00 -> 1.0
                in 1.00..<1.20 -> 1.2
                in 1.20..<1.40 -> 1.4
                in 1.40..<1.60 -> 1.6
                in 1.60..<1.80 -> 1.8
                in 1.80..<2.00 -> 2.0
                in 2.00..<3.00 -> 3.0
                in 3.00..<3.05 -> 0.6
                else -> 1.0
            }

            val episode = playbackManager.getCurrentEpisode() ?: return@launch
            if (episode is PodcastEpisode) {
                // update per podcast playback speed
                val podcast = podcastManager.findPodcastByUuid(episode.podcastUuid)
                if (podcast != null && podcast.overrideGlobalEffects) {
                    podcast.playbackSpeed = newSpeed
                    podcastManager.updatePlaybackSpeedBlocking(podcast = podcast, speed = newSpeed)
                    playbackManager.updatePlayerEffects(effects = podcast.playbackEffects)
                    return@launch
                }
            }
            // update global playback speed
            val effects = settings.globalPlaybackEffects.value
            effects.playbackSpeed = newSpeed
            settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
            playbackManager.updatePlayerEffects(effects = effects)
        }
    }

    private fun archive() {
        launch {
            playbackManager.getCurrentEpisode()?.let {
                if (it is PodcastEpisode) {
                    it.isArchived = true
                    episodeManager.archiveBlocking(it, playbackManager)
                    episodeAnalytics.trackEvent(AnalyticsEvent.EPISODE_ARCHIVED, source, it.uuid)
                }
            }
        }
    }

    fun playFromSearchExternal(query: String) {
        performPlayFromSearch(query)
    }

    /**
     * Test the search using the following terminal command
     * adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p au.com.shiftyjelly.pocketcasts -n au.com.shiftyjelly.pocketcasts/.ui.MainActivity --es query "next\ episode\ in"
     * In the debug you can use the following
     * adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p au.com.shiftyjelly.pocketcasts.debug --es query "The\ Daily\ in"
     * Say the phrase ‘OK, Google’ followed by one of the following
     * ‘Listen to [podcast name] in Pocket Casts’
     * ‘Listen to [filter name] in Pocket Casts’
     * ‘Listen to Up Next in Pocket Casts’
     * ‘Play Up Next in Pocket Casts’
     * ‘Play New Releases Next in Pocket Casts’
     */
    private fun performPlayFromSearch(searchTerm: String?) {
        Timber.d("performSearch $searchTerm")
        val query: String = searchTerm?.trim { it <= ' ' }?.lowercase() ?: return

        Timber.i("performPlayFromSearch query: $query")

        val sourceView = SourceView.MEDIA_BUTTON_BROADCAST_SEARCH_ACTION
        launch {
            if (query.startsWith("up next")) {
                playbackManager.playQueue(sourceView = sourceView)
                return@launch
            }

            if (query.startsWith("next episode") || query.startsWith("next podcast")) {
                val queueEpisodes = playbackManager.upNextQueue.queueEpisodes
                queueEpisodes.firstOrNull()?.let { episode ->
                    launch { playbackManager.playNext(episode = episode, source = source) }
                    return@launch
                }
            }

            val options = calculateSearchQueryOptions(query)
            for (option in options) {
                val matchingPodcast: Podcast? = podcastManager.searchPodcastByTitleBlocking(option)
                if (matchingPodcast != null) {
                    LogBuffer.i(LogBuffer.TAG_PLAYBACK, "User played podcast from search %s.", option)
                    playPodcast(podcast = matchingPodcast, sourceView = sourceView)
                    return@launch
                }
            }

            for (option in options) {
                val matchingEpisode = episodeManager.findFirstBySearchQuery(option) ?: continue
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "User played episode from search %s.", option)
                playbackManager.playNow(episode = matchingEpisode, sourceView = sourceView)
                return@launch
            }

            for (option in options) {
                val playlist = smartPlaylistManager.findFirstByTitleBlocking(option) ?: continue

                Timber.i("Playing matched playlist '$option'")

                val episodeCount = smartPlaylistManager.countEpisodesBlocking(playlist.id, episodeManager, playbackManager)
                if (episodeCount == 0) return@launch

                val episodesToPlay = smartPlaylistManager.findEpisodesBlocking(playlist, episodeManager, playbackManager).take(5)
                if (episodesToPlay.isEmpty()) return@launch

                playEpisodes(episodesToPlay, sourceView)

                return@launch
            }

            withContext(Dispatchers.Main) {
                Timber.i("No search results")
                // couldn't find a match if we get here
                errorPlaybackState("No search results")
            }
        }
    }

    private fun errorPlaybackState(message: String) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
            .setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, message)
            .setActions(getSupportedActions(PlaybackState()))

        Timber.i("MediaSession playback state. Error: $message")
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private fun performPlayFromSearchRx(searchTerm: String?): Completable {
        return Completable.fromAction { performPlayFromSearch(searchTerm) }
    }

    private fun playEpisodes(episodes: List<PodcastEpisode>, sourceView: SourceView) {
        if (episodes.isEmpty()) {
            return
        }

        playbackManager.playEpisodes(episodes = episodes, sourceView = sourceView)
    }

    private suspend fun playPodcast(podcast: Podcast, sourceView: SourceView = SourceView.UNKNOWN) {
        val latestEpisode = withContext(Dispatchers.Default) { episodeManager.findLatestUnfinishedEpisodeByPodcastBlocking(podcast) } ?: return
        playbackManager.playNow(episode = latestEpisode, sourceView = sourceView)
    }

    private fun useCustomSkipButtons(): Boolean {
        return !MANUFACTURERS_TO_HIDE_CUSTOM_SKIP_BUTTONS.contains(Build.MANUFACTURER.lowercase()) &&
            !settings.nextPreviousTrackSkipButtons.value
    }
}

typealias QueuedCommand = Pair<String, suspend () -> Unit>

private const val APP_ACTION_STAR = "star"
private const val APP_ACTION_UNSTAR = "unstar"
private const val APP_ACTION_SKIP_BACK = "jumpBack"
private const val APP_ACTION_SKIP_FWD = "jumpFwd"
private const val APP_ACTION_MARK_AS_PLAYED = "markAsPlayed"
private const val APP_ACTION_CHANGE_SPEED = "changeSpeed"
private const val APP_ACTION_ARCHIVE = "archive"
private const val APP_ACTION_PLAY_NEXT = "playNext"

private val NOTHING_PLAYING: MediaMetadataCompat = MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "")
    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
    .build()
