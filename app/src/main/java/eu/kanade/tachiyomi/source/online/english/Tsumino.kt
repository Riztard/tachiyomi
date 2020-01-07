package eu.kanade.tachiyomi.source.online.english

import android.content.Context
import android.net.Uri
import com.crashlytics.android.Crashlytics
import com.github.salomonbrys.kotson.*
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.LewdSource
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.toast
import exh.TSUMINO_SOURCE_ID
import exh.metadata.metadata.TsuminoSearchMetadata
import exh.metadata.metadata.TsuminoSearchMetadata.Companion.BASE_URL
import exh.metadata.metadata.TsuminoSearchMetadata.Companion.TAG_TYPE_DEFAULT
import exh.metadata.metadata.base.RaisedSearchMetadata.Companion.TAG_TYPE_VIRTUAL
import exh.metadata.metadata.base.RaisedTag
import exh.ui.captcha.ActionCompletionVerifier
import exh.ui.captcha.BrowserActionActivity
import exh.util.urlImportFetchSearchManga
import okhttp3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class Tsumino(private val context: Context): ParsedHttpSource(),
        LewdSource<TsuminoSearchMetadata, Document>,
        ActionCompletionVerifier,
        UrlImportableSource {
    override val metaClass = TsuminoSearchMetadata::class

    private val preferences: PreferencesHelper by injectLazy()



    override val id = TSUMINO_SOURCE_ID
    
    override val lang = "en"
    override val supportsLatest = true
    override val name = "Tsumino"
    
    override val baseUrl = BASE_URL

    override fun parseIntoMetadata(metadata: TsuminoSearchMetadata, input: Document) {
        with(metadata) {
            tmId = TsuminoSearchMetadata.tmIdFromUrl(input.location()).toInt()
            tags.clear()

//            input.getElementsByClass(".book-page-image")?.first()?.attr("src")?.text()?.trim()?.let {
//                thumbNail = it;
//            }

            input.getElementById("Title")?.text()?.let {
                title = it.trim()
            }

            input.getElementById("Artist")?.children()?.first()?.text()?.trim()?.let {
                tags.add(RaisedTag("artist", it, TAG_TYPE_VIRTUAL))
                artist = it
            }

            input.getElementById("Uploader")?.children()?.first()?.text()?.trim()?.let {
                uploader = it
            }

            input.getElementById("Uploaded")?.text()?.let {
                uploadDate = TM_DATE_FORMAT.parse(it.trim()).time
            }

            input.getElementById("Pages")?.text()?.let {
                length = it.trim().toIntOrNull()
            }

            input.getElementById("Rating")?.text()?.let {
                ratingString = it.trim()
            }

            input.getElementById("Category")?.children()?.first()?.text()?.let {
                category = it.trim()
                tags.add(RaisedTag("genre", it, TAG_TYPE_VIRTUAL))
            }

            input.getElementById("Collection")?.children()?.first()?.text()?.let {
                collection = it.trim()
            }

            input.getElementById("Group")?.children()?.first()?.text()?.let {
                group = it.trim()
                tags.add(RaisedTag("group", it, TAG_TYPE_VIRTUAL))
            }

            val newParody = mutableListOf<String>()
            input.getElementById("Parody")?.children()?.forEach {
                val entry = it.text().trim()
                newParody.add(entry)
                tags.add(RaisedTag("parody", entry, TAG_TYPE_VIRTUAL))
            }
            parody = newParody

            val newCharacter = mutableListOf<String>()
            input.getElementById("Character")?.children()?.forEach {
                val entry = it.text().trim()
                newCharacter.add(entry)
                tags.add(RaisedTag("character", entry, TAG_TYPE_VIRTUAL))
            }
            character = newCharacter

            input.getElementById("Tag")?.children()?.let {
                tags.addAll(it.map {
                    RaisedTag(null, it.text().trim(), TAG_TYPE_DEFAULT)
                })
            }
        }
    }

    fun genericMangaParse(response: Response): MangasPage {
        val json = jsonParser.parse(response.body!!.string()!!).asJsonObject
        val hasNextPage = json["pageNumber"].int < json["pageCount"].int
        
        val manga = json["data"].array.map {
            val obj = it.obj["entry"].obj
            
            SManga.create().apply {
                val id = obj["id"].long
                url = TsuminoSearchMetadata.mangaUrlFromId(id.toString())
                thumbnail_url = obj["thumbnailUrl"].asString
                
                title = obj["title"].string
            }
        }
        
        return MangasPage(manga, hasNextPage)
    }
    
    fun genericMangaRequest(page: Int,
                            query: String,
                            sort: SortType,
                            length: LengthType,
                            minRating: Int,
                            excludeParodies: Boolean = false,
                            advSearch: List<AdvSearchEntry> = emptyList())
        = POST("$BASE_URL/Search/Operate", body = FormBody.Builder()
            .add("PageNumber", page.toString())
            .add("Text", query)
            .add("Sort", sort.name)
            .add("List", "0")
            .add("Length", length.id.toString())
            .add("MinimumRating", minRating.toString())
            .apply {
                advSearch.forEachIndexed { index, entry ->
                    add("Tags[$index][Type]", entry.type.toString())
                    add("Tags[$index][Text]", entry.text)
                    add("Tags[$index][Exclude]", entry.exclude.toString())
                }
                
                if(excludeParodies)
                    add("Exclude[]", "6")
            }
            .build())
    
    enum class SortType {
        Newest,
        Oldest,
        Alphabetical,
        Rating,
        Pages,
        Views,
        Random,
        Comments,
        Popularity
    }
    
    enum class LengthType(val id: Int) {
        Any(0),
        Short(1),
        Medium(2),
        Long(3)
    }
    
    override fun popularMangaSelector() = throw UnsupportedOperationException("Unused method called!")
    override fun popularMangaFromElement(element: Element) = throw UnsupportedOperationException("Unused method called!")
    override fun popularMangaNextPageSelector() = throw UnsupportedOperationException("Unused method called!")
    override fun popularMangaRequest(page: Int) = genericMangaRequest(page,
            "",
            SortType.Random,
            LengthType.Any,
            0)
    
    override fun popularMangaParse(response: Response) = genericMangaParse(response)
    
    override fun latestUpdatesSelector() = throw UnsupportedOperationException("Unused method called!")
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException("Unused method called!")
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException("Unused method called!")
    override fun latestUpdatesRequest(page: Int) = genericMangaRequest(page,
            "",
            SortType.Newest,
            LengthType.Any,
            0)
    override fun latestUpdatesParse(response: Response) = genericMangaParse(response)
    
    //Support direct URL importing
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) =
            urlImportFetchSearchManga(query) {
                super.fetchSearchManga(page, query, filters)
            }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Append filters again, to provide fallback in case a filter is not provided
        // Since we only work with the first filter when building the result, if the filter is provided,
        // the original filter is ignored
        val f = filters + getFilterList()
        
        return genericMangaRequest(
                page,
                query,
                SortType.values()[f.filterIsInstance<SortFilter>().first().state],
                LengthType.values()[f.filterIsInstance<LengthFilter>().first().state],
                f.filterIsInstance<MinimumRatingFilter>().first().state,
                f.filterIsInstance<ExcludeParodiesFilter>().first().state,
                f.filterIsInstance<AdvSearchEntryFilter>().flatMap { filter ->
                    val splitState = filter.state.split(",").map(String::trim).filterNot(String::isBlank)
                    
                    splitState.map {
                        AdvSearchEntry(filter.type, it.removePrefix("-"), it.startsWith("-"))
                    }
                }
        )
    }
    
    override fun searchMangaSelector() = throw UnsupportedOperationException("Unused method called!")
    override fun searchMangaFromElement(element: Element) = throw UnsupportedOperationException("Unused method called!")
    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException("Unused method called!")
    override fun searchMangaParse(response: Response) = genericMangaParse(response)

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
                .asObservableSuccess()
                .flatMap {
                    parseToManga(manga, it.asJsoup()).andThen(Observable.just(manga.apply {
                        initialized = true
                    }))
                }
    }

    override fun mangaDetailsParse(document: Document)
            = throw UnsupportedOperationException("Unused method called!")

/*    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.book-page-container")
        val manga = SManga.create()

        manga.title = infoElement.select("#Title").text()
        manga.artist = getArtists(document)
        manga.author = manga.artist
        manga.status = SManga.COMPLETED
        manga.thumbnail_url = infoElement.select("img").attr("src")
        manga.description = getDesc(document)

        return manga
    }
*/

    override fun chapterListSelector() = throw UnsupportedOperationException("Unused method called!")
    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException("Unused method called!")
    override fun fetchChapterList(manga: SManga) = getOrLoadMetadata(manga.id) {
        client.newCall(mangaDetailsRequest(manga))
                .asObservableSuccess()
                .map { it.asJsoup() }
                .toSingle()
    }.map {

        listOf(
                SChapter.create().apply {
                    url = "/entry/${it.tmId}"
                    name = "Chapter"
                    
                    it.uploadDate?.let { date_upload = it }
                    
                    chapter_number = 1f
                }
        )
    }.toObservable()

    override val client: OkHttpClient
        // Do not call super here as we don't want auto-captcha detection here
        get() = network.client.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .addNetworkInterceptor {
                    val cAspNetCookie = preferences.eh_ts_aspNetCookie().getOrDefault()

                    var request = it.request()

                    if(cAspNetCookie.isNotBlank()) {
                        request = it.request()
                                .newBuilder()
                                .header("Cookie", "ASP.NET_SessionId=$cAspNetCookie")
                                .build()
                    }

                   val response = it.proceed(request)

                    val newCookie = response.headers("Set-Cookie").map(String::trim).find {
                        it.startsWith(ASP_NET_COOKIE_NAME)
                    }

                    if(newCookie != null) {
                        val res = newCookie.substringAfter('=')
                                .substringBefore(';')
                                .trim()

                        preferences.eh_ts_aspNetCookie().set(res)
                    }

                    response
                }.build()


    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val id = chapter.url.substringAfterLast('/')
        val call = POST("$BASE_URL/Read/Load", body = FormBody.Builder().add("q", id).build())
        return client.newCall(call).asObservableSuccess().map {
            val page = client.newCall(GET("$BASE_URL/Read/Index/$id?page=1")).execute().asJsoup()
            val numPages = page.select("h1").text().split(" ").last()

            if (numPages.isNotEmpty()) {
                val pageArr = Array(numPages.toInt()) {i -> (
                    page.select("#image-container").attr("data-cdn")
                .replace("[PAGE]", (i+1).toString())
                )}

                val pageUrls = Array(numPages.toInt()) {i -> (
                    "$BASE_URL/Read/Index/$id?page="+(i+1).toString()
                )}
                pageUrls.mapIndexed {index, obj -> 
                    Page(index, pageUrls[index], pageArr[index])
                }
            } else {
                throw IOException("probably a captcha")
            }
        }.doOnError {
            try {
                val aspNetCookie = preferences.eh_ts_aspNetCookie().getOrDefault()

                val cookiesMap = if (aspNetCookie.isNotBlank())
                    mapOf(ASP_NET_COOKIE_NAME to aspNetCookie)
                else
                    emptyMap()

                BrowserActionActivity.launchCaptcha(context,
                        this,
                        cookiesMap,
                        CAPTCHA_SCRIPT,
                        "$BASE_URL/Read/Auth/$id",
                        ".book-read-button")
            } catch(t: Throwable) {
                Crashlytics.logException(t)
                context.toast("Could not launch captcha-solving activity: ${t.message}")
            }
        }
    }


    override fun verifyComplete(url: String): Boolean {
        return Uri.parse(url).pathSegments.getOrNull(1) == "View"
    }

    override fun pageListParse(document: Document) = throw UnsupportedOperationException("Unused method called!")
    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Unused method called!")

    data class AdvSearchEntry(val type: Int, val text: String, val exclude: Boolean)

    override fun getFilterList() = FilterList(
            Filter.Header("Separate tags with commas"),
            Filter.Header("Prepend with dash to exclude"),
            TagFilter(),
            CategoryFilter(),
            CollectionFilter(),
            GroupFilter(),
            ArtistFilter(),
            ParodyFilter(),
            CharactersFilter(),
            UploaderFilter(),

            Filter.Separator(),

            SortFilter(),
            LengthFilter(),
            MinimumRatingFilter(),
            ExcludeParodiesFilter()
    )

    class TagFilter : AdvSearchEntryFilter("Tags", 1)
    class CategoryFilter : AdvSearchEntryFilter("Categories", 2)
    class CollectionFilter : AdvSearchEntryFilter("Collections", 3)
    class GroupFilter : AdvSearchEntryFilter("Groups", 4)
    class ArtistFilter : AdvSearchEntryFilter("Artists", 5)
    class ParodyFilter : AdvSearchEntryFilter("Parodies", 6)
    class CharactersFilter : AdvSearchEntryFilter("Characters", 7)
    class UploaderFilter : AdvSearchEntryFilter("Uploaders", 8)
    open class AdvSearchEntryFilter(name: String, val type: Int) : Filter.Text(name)

    class SortFilter : Filter.Select<SortType>("Sort by", SortType.values())
    class LengthFilter : Filter.Select<LengthType>("Length", LengthType.values())
    class MinimumRatingFilter : Filter.Select<String>("Minimum rating", (0 .. 5).map { "$it stars" }.toTypedArray())
    class ExcludeParodiesFilter : Filter.CheckBox("Exclude parodies")

    override val matchingHosts = listOf(
            "www.tsumino.com"
    )

    override fun mapUrlToMangaUrl(uri: Uri): String? {
        val lcFirstPathSegment = uri.pathSegments.firstOrNull()?.toLowerCase() ?: return null

        if(lcFirstPathSegment != "read" && lcFirstPathSegment != "book")
            return null

        return "https://tsumino.com/Book/Info/${uri.pathSegments[2]}"
    }

        private fun getArtists(document: Document): String {
            val stringBuilder = StringBuilder()
            val artists = document.select("#Artist a")

            artists.forEach {
                stringBuilder.append(it.text())

                if (it != artists.last())
                    stringBuilder.append(", ")
            }

            return stringBuilder.toString()
        }


       private fun getDesc(document: Document): String {
            val stringBuilder = StringBuilder()
            val pages = document.select("#Pages").text()
            val parodies = document.select("#Parody a")
            val characters = document.select("#Character a")
            val tags = document.select("#Tag a")

            stringBuilder.append("Pages: $pages")

            if (parodies.size > 0) {
                stringBuilder.append("\n\n")
                stringBuilder.append("Parodies: ")

                parodies.forEach {
                    stringBuilder.append(it.text())

                    if (it != parodies.last())
                        stringBuilder.append(", ")
                }
            }

            if (characters.size > 0) {
                stringBuilder.append("\n\n")
                stringBuilder.append("Characters: ")

                characters.forEach {
                    stringBuilder.append(it.text())

                    if (it != characters.last())
                        stringBuilder.append(", ")
                }
            }

            if (tags.size > 0) {
                stringBuilder.append("\n\n")
                stringBuilder.append("Tags: ")

                tags.forEach {
                    stringBuilder.append(it.text())

                    if (it != tags.last())
                        stringBuilder.append(", ")
                }
            }

            return stringBuilder.toString()
        }



    companion object {
        val jsonParser by lazy {
            JsonParser()
        }

        val TM_DATE_FORMAT = SimpleDateFormat("yyyy MMM dd", Locale.US)

        private val ASP_NET_COOKIE_NAME = "ASP.NET_SessionId"

        private val CAPTCHA_SCRIPT = """
            |try{ document.querySelector('.tsumino-nav-btn').remove(); } catch(e) {}
            |try{ document.querySelector('.tsumino-nav-title').href = '#' ;} catch(e) {}
            |try{ document.querySelector('.tsumino-nav-items').remove() ;} catch(e) {}
            """.trimMargin()
    }
}