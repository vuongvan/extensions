package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor

class DailymotionProvider : MainAPI() {

    // --- DATA CLASSES ---

    data class VideoSearchResponse(
        @JsonProperty("list") val list: List<VideoItem>
    )

    data class VideoItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null
    )

    // Thêm data class cho Playlist
    data class PlaylistSearchResponse(
        @JsonProperty("list") val list: List<PlaylistItem>
    )

    data class PlaylistItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null
    )

    data class VideoDetailResponse(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumbnail_720_url") val thumbnail720Url: String? = null
    )

    override var mainUrl = "https://api.dailymotion.com"
    override var name = "Dailymotion"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override var lang = "vi"
    override val hasMainPage = true

    // --- MAIN PAGE ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Lấy video phổ biến
        val videoRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page").text
        val popularVideos = tryParseJson<VideoSearchResponse>(videoRes)?.list ?: emptyList()

        // Lấy playlist phổ biến (Mới thêm)
        val playlistRes = app.get("$mainUrl/playlists?fields=id,name,thumbnail_360_url&limit=20&page=$page").text
        val popularPlaylists = tryParseJson<PlaylistSearchResponse>(playlistRes)?.list ?: emptyList()

        val homePages = mutableListOf<HomePageList>()
        
        if (popularVideos.isNotEmpty()) {
            homePages.add(HomePageList("Popular Videos", popularVideos.map { it.toSearchResponse() }))
        }
        
        if (popularPlaylists.isNotEmpty()) {
            homePages.add(HomePageList("Featured Playlists", popularPlaylists.map { it.toSearchResponse() }))
        }

        return newHomePageResponse(homePages, false)
    }

    // --- SEARCH ---

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val response = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=26&page=$page&search=${query.encodeUri()}").text
        return tryParseJson<VideoSearchResponse>(response)?.list?.map {
            it.toSearchResponse()
        }?.toNewSearchResponseList()
    }

    // --- LOAD (Xử lý cả Video và Playlist) ---

    override suspend fun load(url: String): LoadResponse? {
    return if (url.contains("/playlist/")) {
        val playlistId = Regex("playlist/([a-zA-Z0-9]+)").find(url)?.groups?.get(1)?.value ?: return null
        val detailRes = app.get("$mainUrl/playlist/$playlistId?fields=id,name,description,thumbnail_720_url").text
        val detail = tryParseJson<PlaylistItem>(detailRes) ?: return null
        
        val videosRes = app.get("$mainUrl/playlist/$playlistId/videos?fields=id,title,thumbnail_360_url&limit=100").text
        val videos = tryParseJson<VideoSearchResponse>(videosRes)?.list ?: emptyList()

        newTvSeriesLoadResponse(detail.name, url, TvType.TvSeries, videos.map { video ->
            // Sửa lỗi tại đây
            newEpisode(video.id) {
                this.name = video.title
                this.posterUrl = video.thumbnail360Url
            }
        }) {
            this.posterUrl = detail.thumbnail360Url
            this.plot = "Playlist content"
        }
    } else {
        // ... (phần code cho Video đơn lẻ giữ nguyên)
    }
    }
    

    // --- EXTRACTOR ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' ở đây chính là Video ID
        loadExtractor(
            "https://www.dailymotion.com/embed/video/$data",
            subtitleCallback,
            callback
        )
        return true
    }

    // --- HELPERS ---

    private fun VideoItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(this.title, "https://www.dailymotion.com/video/${this.id}", TvType.Movie) {
            this.posterUrl = thumbnail360Url
        }
    }

    private fun PlaylistItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(this.name, "https://www.dailymotion.com/playlist/${this.id}", TvType.TvSeries) {
            this.posterUrl = thumbnail360Url
        }
    }
}
