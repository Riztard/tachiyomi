package eu.kanade.tachiyomi.ui.manga.track

import android.os.Bundle
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.system.toast
import exh.mangaDexSourceIds
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TrackPresenter(
    val manga: Manga,
    preferences: PreferencesHelper = Injekt.get(),
    private val db: DatabaseHelper = Injekt.get(),
    private val trackManager: TrackManager = Injekt.get()
) : BasePresenter<TrackController>() {

    private val context = preferences.context

    private var trackList: List<TrackItem> = emptyList()

    private val loggedServices by lazy { trackManager.services.filter { it.isLogged } }

    private var trackSubscription: Subscription? = null
    private var searchJob: Job? = null
    private var refreshJob: Job? = null

    // SY -->
    var needsRefresh = false
    // SY <--

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        fetchTrackings()
    }

    private fun fetchTrackings() {
        trackSubscription?.let { remove(it) }
        trackSubscription = db.getTracks(manga)
            .asRxObservable()
            .map { tracks ->
                loggedServices.map { service ->
                    TrackItem(tracks.find { it.sync_id == service.id }, service)
                }
            }
            .observeOn(AndroidSchedulers.mainThread())
            // SY -->
            .map { trackItems ->
                val mdTrack = trackItems.firstOrNull { it.service.id == TrackManager.MDLIST }
                if (manga.source in mangaDexSourceIds) {
                    when {
                        mdTrack == null -> {
                            trackItems
                        }
                        mdTrack.track == null -> {
                            needsRefresh = true
                            trackItems - mdTrack + createMdListTrack()
                        }
                        else -> trackItems
                    }
                } else mdTrack?.let { trackItems - it } ?: trackItems
            }
            // SY <--
            .doOnNext { trackList = it }
            .subscribeLatestCache(TrackController::onNextTrackings)
    }

    // SY -->
    private fun createMdListTrack(): TrackItem {
        val track = trackManager.mdList.createInitialTracker(manga)
        track.id = db.insertTrack(track).executeAsBlocking().insertedId()
        return TrackItem(track, trackManager.mdList)
    }
    // SY <--

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = launchIO {
            supervisorScope {
                try {
                    trackList
                        .filter { it.track != null }
                        .map {
                            async {
                                val track = it.service.refresh(it.track!!)
                                db.insertTrack(track).executeAsBlocking()
                            }
                        }
                        .awaitAll()

                    withUIContext { view?.onRefreshDone() }
                } catch (e: Throwable) {
                    withUIContext { view?.onRefreshError(e) }
                }
            }
        }
    }

    fun search(query: String, service: TrackService) {
        searchJob?.cancel()
        searchJob = launchIO {
            try {
                val results = service.search(query)
                withUIContext { view?.onSearchResults(results) }
            } catch (e: Throwable) {
                withUIContext { view?.onSearchResultsError(e) }
            }
        }
    }

    fun registerTracking(item: Track?, service: TrackService) {
        if (item != null) {
            item.manga_id = manga.id!!
            launchIO {
                try {
                    service.bind(item)
                    db.insertTrack(item).executeAsBlocking()
                } catch (e: Throwable) {
                    withUIContext { context.toast(e.message) }
                }
            }
        } else {
            unregisterTracking(service)
        }
    }

    fun unregisterTracking(service: TrackService) {
        db.deleteTrackForManga(manga, service).executeAsBlocking()
    }

    private fun updateRemote(track: Track, service: TrackService) {
        launchIO {
            try {
                service.update(track)
                db.insertTrack(track).executeAsBlocking()
                withUIContext { view?.onRefreshDone() }
            } catch (e: Throwable) {
                withUIContext { view?.onRefreshError(e) }

                // Restart on error to set old values
                fetchTrackings()
            }
        }
    }

    fun setStatus(item: TrackItem, index: Int) {
        val track = item.track!!
        track.status = item.service.getStatusList()[index]
        if (track.status == item.service.getCompletionStatus() && track.total_chapters != 0) {
            track.last_chapter_read = track.total_chapters
        }
        updateRemote(track, item.service)
    }

    fun setScore(item: TrackItem, index: Int) {
        val track = item.track!!
        track.score = item.service.indexToScore(index)
        updateRemote(track, item.service)
    }

    fun setLastChapterRead(item: TrackItem, chapterNumber: Int) {
        val track = item.track!!
        track.last_chapter_read = chapterNumber
        if (track.total_chapters != 0 && track.last_chapter_read == track.total_chapters) {
            track.status = item.service.getCompletionStatus()
        }
        updateRemote(track, item.service)
    }
}
