package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class DailymotionProvider : MainAPI() {

    // --- DATA CLASSES ---
    data class VideoSearchResponse(@JsonProperty("list") val list: List<VideoItem>)
    data class VideoItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null
    )

    data class PlaylistSearchResponse(@JsonProperty("list") val list: List<PlaylistItem>)
    data class PlaylistItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null
    )

    override var mainUrl = "https://api.dailymotion.com"
    override var name = "Dailymotion"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    // --- MAIN PAGE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()

        // 1. Video phổ biến
        val videoRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page").text
        tryParseJson<VideoSearchResponse>(videoRes)?.list?.let { items ->
            homePages.add(HomePageList("Popular Videos", items.map { 
                newMovieSearchResponse(it.title, "https://www.dailymotion.com/video/${it.id}", TvType.Movie) {
                    this.posterUrl = it.thumbnail360Url
                }
            }))
        }

        // 2. Playlist của người dùng thientam.nguyen
        val userPlaylistRes = app.get("$mainUrl/user/thientam.nguyen/playlists?fields=id,name,thumbnail_360_url&limit=20").text
        tryParseJson<PlaylistSearchResponse>(userPlaylistRes)?.list?.let { items ->
            homePages.add(HomePageList("thientam.nguyen's Playlists", items.map { 
                newMovieSearchResponse(it.name, "https://www.dailymotion.com/playlist/${it.id}", TvType.TvSeries) {
                    this.posterUrl = it.thumbnail360Url
                }
            }))
        }

        return newHomePageResponse(homePages, false)
    }

    // --- SEARCH (Chỉ tìm Video theo yêu cầu) ---
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val vRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&search=$query").text
        val results = tryParseJson<VideoSearchResponse>(vRes)?.list?.map { 
            newMovieSearchResponse(it.title, "https://www.dailymotion.com/video/${it.id}", TvType.Movie) {
                this.posterUrl = it.thumbnail360Url
            }
        }
        return results?.toNewSearchResponseList()
    }

    // --- LOAD ---
    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("(?:video|playlist)/([a-zA-Z0-9]+)").find(url)?.groups?.get(1)?.value ?: return null

        if (url.contains("/playlist/")) {
            val detailRes = app.get("$mainUrl/playlist/$id?fields=id,name,thumbnail_720_url").text
            val detail = tryParseJson<PlaylistItem>(detailRes) ?: return null
            
            val videosRes = app.get("$mainUrl/playlist/$id/videos?fields=id,title,thumbnail_360_url&limit=100").text
            val videos = tryParseJson<VideoSearchResponse>(videosRes)?.list ?: emptyList()

            return newTvSeriesLoadResponse(detail.name, url, TvType.TvSeries, videos.map { video ->
                newEpisode("https://www.dailymotion.com/video/${video.id}") {
                    this.name = video.title
                    this.posterUrl = video.thumbnail360Url
                }
            }) {
                this.posterUrl = detail.thumbnail360Url
            }
        }

        // Load Video đơn lẻ
        val response = app.get("$mainUrl/video/$id?fields=id,title,thumbnail_720_url").text
        val v = tryParseJson<VideoItem>(response) ?: return null
        
        return newMovieLoadResponse(v.title, url, TvType.Movie, "https://www.dailymotion.com/video/${v.id}") {
            this.posterUrl = v.thumbnail360Url
        }
    }

    // --- LOAD LINKS ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data truyền vào là URL đầy đủ từ hàm load, chuyển thẳng cho bộ giải mã gốc
        return loadExtractor(data, subtitleCallback, callback)
    }
}
