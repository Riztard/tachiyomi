package eu.kanade.tachiyomi.source.online.all

import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.toMangaInfo
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.toSChapter
import eu.kanade.tachiyomi.source.model.toSManga
import eu.kanade.tachiyomi.source.online.SuspendHttpSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.lang.awaitSingle
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import exh.MERGED_SOURCE_ID
import exh.merged.sql.models.MergedMangaReference
import exh.util.executeOnIO
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy

class MergedSource : SuspendHttpSource() {
    private val db: DatabaseHelper by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val downloadManager: DownloadManager by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    override val id: Long = MERGED_SOURCE_ID

    override val baseUrl = ""

    override suspend fun popularMangaRequestSuspended(page: Int) = throw UnsupportedOperationException()
    override suspend fun popularMangaParseSuspended(response: Response) = throw UnsupportedOperationException()
    override suspend fun searchMangaRequestSuspended(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override suspend fun searchMangaParseSuspended(response: Response) = throw UnsupportedOperationException()
    override suspend fun latestUpdatesRequestSuspended(page: Int) = throw UnsupportedOperationException()
    override suspend fun latestUpdatesParseSuspended(response: Response) = throw UnsupportedOperationException()
    override suspend fun mangaDetailsParseSuspended(response: Response) = throw UnsupportedOperationException()
    override suspend fun chapterListParseSuspended(response: Response) = throw UnsupportedOperationException()
    override suspend fun pageListParseSuspended(response: Response) = throw UnsupportedOperationException()
    override suspend fun imageUrlParseSuspended(response: Response) = throw UnsupportedOperationException()
    override suspend fun fetchChapterListSuspended(manga: SManga) = throw UnsupportedOperationException()
    override suspend fun fetchImageSuspended(page: Page) = throw UnsupportedOperationException()
    override suspend fun fetchImageUrlSuspended(page: Page) = throw UnsupportedOperationException()
    override suspend fun fetchPageListSuspended(chapter: SChapter) = throw UnsupportedOperationException()
    override suspend fun fetchLatestUpdatesSuspended(page: Int) = throw UnsupportedOperationException()
    override suspend fun fetchPopularMangaSuspended(page: Int) = throw UnsupportedOperationException()

    override suspend fun fetchMangaDetailsSuspended(manga: SManga): SManga {
        return withIOContext {
            val mergedManga = db.getManga(manga.url, id).executeAsBlocking() ?: throw Exception("merged manga not in db")
            val mangaReferences = mergedManga.id?.let { db.getMergedMangaReferences(it).executeOnIO() } ?: throw Exception("merged manga id is null")
            if (mangaReferences.isEmpty()) throw IllegalArgumentException("Manga references are empty, info unavailable, merge is likely corrupted")
            if (mangaReferences.size == 1 || run {
                val mangaReference = mangaReferences.firstOrNull()
                mangaReference == null || (mangaReference.mangaSourceId == MERGED_SOURCE_ID)
            }
            ) throw IllegalArgumentException("Manga references contain only the merged reference, merge is likely corrupted")

            SManga.create().apply {
                val mangaInfoReference = mangaReferences.firstOrNull { it.isInfoManga } ?: mangaReferences.firstOrNull { it.mangaId != it.mergeId }
                val dbManga = mangaInfoReference?.let { db.getManga(it.mangaUrl, it.mangaSourceId).executeOnIO() }
                this.copyFrom(dbManga ?: mergedManga)
                url = manga.url
            }
        }
    }

    fun getChaptersFromDB(manga: Manga, editScanlators: Boolean = false, dedupe: Boolean = true): Observable<List<Chapter>> {
        // TODO more chapter dedupe
        return db.getChaptersByMergedMangaId(manga.id!!).asRxObservable()
            .map { chapterList ->
                val mangaReferences = db.getMergedMangaReferences(manga.id!!).executeAsBlocking()
                if (editScanlators) {
                    val sources = mangaReferences.map { sourceManager.getOrStub(it.mangaSourceId) to it.mangaId }
                    chapterList.onEach { chapter ->
                        val source = sources.firstOrNull { chapter.manga_id == it.second }?.first
                        if (source != null) {
                            chapter.scanlator = if (chapter.scanlator.isNullOrBlank()) source.name
                            else "$source: ${chapter.scanlator}"
                        }
                    }
                }
                if (dedupe) dedupeChapterList(mangaReferences, chapterList) else chapterList
            }
    }

    private fun dedupeChapterList(mangaReferences: List<MergedMangaReference>, chapterList: List<Chapter>): List<Chapter> {
        return when (mangaReferences.firstOrNull { it.mangaSourceId == MERGED_SOURCE_ID }?.chapterSortMode) {
            MergedMangaReference.CHAPTER_SORT_NO_DEDUPE, MergedMangaReference.CHAPTER_SORT_NONE -> chapterList
            MergedMangaReference.CHAPTER_SORT_PRIORITY -> chapterList
            MergedMangaReference.CHAPTER_SORT_MOST_CHAPTERS -> {
                findSourceWithMostChapters(chapterList)?.let { mangaId ->
                    chapterList.filter { it.manga_id == mangaId }
                } ?: chapterList
            }
            MergedMangaReference.CHAPTER_SORT_HIGHEST_CHAPTER_NUMBER -> {
                findSourceWithHighestChapterNumber(chapterList)?.let { mangaId ->
                    chapterList.filter { it.manga_id == mangaId }
                } ?: chapterList
            }
            else -> chapterList
        }
    }

    private fun findSourceWithMostChapters(chapterList: List<Chapter>): Long? {
        return chapterList.groupBy { it.manga_id }.maxByOrNull { it.value.size }?.key
    }

    private fun findSourceWithHighestChapterNumber(chapterList: List<Chapter>): Long? {
        return chapterList.maxByOrNull { it.chapter_number }?.manga_id
    }

    suspend fun fetchChaptersForMergedManga(manga: Manga, downloadChapters: Boolean = true, editScanlators: Boolean = false, dedupe: Boolean = true): List<Chapter> {
        return withIOContext {
            fetchChaptersAndSync(manga, downloadChapters)
            getChaptersFromDB(manga, editScanlators, dedupe).awaitSingle()
        }
    }

    suspend fun fetchChaptersAndSync(manga: Manga, downloadChapters: Boolean = true): Pair<List<Chapter>, List<Chapter>> {
        val mangaReferences = db.getMergedMangaReferences(manga.id!!).executeAsBlocking()
        if (mangaReferences.isEmpty()) throw IllegalArgumentException("Manga references are empty, chapters unavailable, merge is likely corrupted")

        val ifDownloadNewChapters = downloadChapters && manga.shouldDownloadNewChapters(db, preferences)
        return mangaReferences.filter { it.mangaSourceId != MERGED_SOURCE_ID }.map {
            it.load(db, sourceManager)
        }.mapNotNull { loadedManga ->
            withIOContext {
                if (loadedManga.manga != null && loadedManga.reference.getChapterUpdates) {
                    loadedManga.source.getChapterList(loadedManga.manga.toMangaInfo())
                        .map { it.toSChapter() }
                        .let { syncChaptersWithSource(db, it, loadedManga.manga, loadedManga.source) }
                        .also {
                            if (ifDownloadNewChapters && loadedManga.reference.downloadChapters) {
                                downloadManager.downloadChapters(loadedManga.manga, it.first)
                            }
                        }
                } else {
                    null
                }
            }
        }.let { pairs ->
            val firsts = mutableListOf<Chapter>()
            val seconds = mutableListOf<Chapter>()

            pairs.forEach {
                firsts.addAll(it.first)
                seconds.addAll(it.second)
            }

            firsts to seconds
        }
    }

    suspend fun MergedMangaReference.load(db: DatabaseHelper, sourceManager: SourceManager): LoadedMangaSource {
        var manga = db.getManga(mangaUrl, mangaSourceId).executeOnIO()
        val source = sourceManager.getOrStub(manga?.source ?: mangaSourceId)
        if (manga == null) {
            manga = Manga.create(mangaSourceId).apply {
                url = mangaUrl
            }
            manga.copyFrom(source.getMangaDetails(manga.toMangaInfo()).toSManga())
            try {
                manga.id = db.insertManga(manga).executeOnIO().insertedId()
                mangaId = manga.id
                db.insertNewMergedMangaId(this).executeOnIO()
            } catch (e: Exception) {
                XLog.tag("MergedSource").enableStackTrace(e.stackTrace.contentToString(), 5)
            }
        }
        return LoadedMangaSource(source, manga, this)
    }

    data class LoadedMangaSource(val source: Source, val manga: Manga?, val reference: MergedMangaReference)

    override val lang = "all"
    override val supportsLatest = false
    override val name = "MergedSource"
}
