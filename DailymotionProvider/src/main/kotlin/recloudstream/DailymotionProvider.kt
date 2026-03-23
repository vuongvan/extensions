package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink // Import quan trọng

class DailymotionProvider : MainAPI() {

    // --- 1. DATA CLASSES (Nằm trong Class) ---
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

    data class VideoDetailResponse(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumbnail_720_url") val thumbnail720Url: String? = null
    )

    override var mainUrl = "https://api.dailymotion.com"
    override var name = "Dailymotion"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    // --- 2. MAIN PAGE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()

        // Video phổ biến
        val videoRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page").text
        tryParseJson<VideoSearchResponse>(videoRes)?.list?.let { items ->
            homePages.add(HomePageList("Popular Videos", items.map { it.toVideoSearchResponse() }))
        }

        // Playlist của thientam.nguyen
        val userPlaylistRes = app.get("$mainUrl/user/thientam.nguyen/playlists?fields=id,name,thumbnail_360_url&limit=20").text
        tryParseJson<PlaylistSearchResponse>(userPlaylistRes)?.list?.let { items ->
            homePages.add(HomePageList("thientam.nguyen's Playlists", items.map { it.toPlaylistSearchResponse() }))
        }

        // Playlist phổ biến
        val playlistRes = app.get("$mainUrl/playlists?fields=id,name,thumbnail_360_url&limit=20&page=$page").text
        tryParseJson<PlaylistSearchResponse>(playlistRes)?.list?.let { items ->
            homePages.add(HomePageList("Featured Playlists", items.map { it.toPlaylistSearchResponse() }))
        }

        return newHomePageResponse(homePages, false)
    }

    // --- 3. SEARCH ---
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val results = mutableListOf<SearchResponse>()
        val encodedQuery = query.encodeUri()

        val vRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&search=$encodedQuery").text
        tryParseJson<VideoSearchResponse>(vRes)?.list?.forEach { results.add(it.toVideoSearchResponse()) }

        val pRes = app.get("$mainUrl/playlists?fields=id,name,thumbnail_360_url&limit=10&page=$page&search=$encodedQuery").text
        tryParseJson<PlaylistSearchResponse>(pRes)?.list?.forEach { results.add(it.toPlaylistSearchResponse()) }

        return results.toNewSearchResponseList()
    }

    // --- 4. LOAD ---
    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("(?:video|playlist)/([a-zA-Z0-9]+)").find(url)?.groups?.get(1)?.value ?: return null

        if (url.contains("/playlist/")) {
            val detailRes = app.get("$mainUrl/playlist/$id?fields=id,name,description,thumbnail_720_url").text
            val detail = tryParseJson<PlaylistItem>(detailRes) ?: return null
            
            val videosRes = app.get("$mainUrl/playlist/$id/videos?fields=id,title,thumbnail_360_url&limit=100").text
            val videos = tryParseJson<VideoSearchResponse>(videosRes)?.list ?: emptyList()

            return newTvSeriesLoadResponse(detail.name, url, TvType.TvSeries, videos.map { video ->
                newEpisode(video.id) {
                    this.name = video.title
                    this.posterUrl = video.thumbnail360Url
                }
            }) {
                this.posterUrl = detail.thumbnail360Url
                this.plot = "Dailymotion Playlist"
            }
        }

        val response = app.get("$mainUrl/video/$id?fields=id,title,description,thumbnail_720_url").text
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null
        
        return newMovieLoadResponse(videoDetail.title, url, TvType.Movie, videoDetail.id) {
            this.plot = videoDetail.description
            this.posterUrl = videoDetail.thumbnail720Url
        }
    }

    // --- 5. LOAD LINKS ---
        override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$data"
            val response = app.get(metaUrl).text
            val m3u8Regex = """"type":"application/x-mpegURL","url":"([^"]+)"""".toRegex()
            val match = m3u8Regex.find(response)
            
            if (match != null) {
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                
                // SỬA TẠI ĐÂY: Chuyển các tham số vào trong block { }
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dailymotion Direct",
                        url = m3u8Url,
                    ) {
                        this.referer = "https://www.dailymotion.com/"
                        this.quality = Qualities.Unknown.value
                        this.isM3u8 = true
                    }
                )
                return true
            }
        } catch (e: Exception) { e.printStackTrace() }

        return loadExtractor("https://www.dailymotion.com/video/$data", subtitleCallback, callback)
        }

        

    // --- 6. HELPERS (Đổi tên để tránh Ambiguity) ---
    private fun VideoItem.toVideoSearchResponse(): SearchResponse {
        return newMovieSearchResponse(this.title, "https://www.dailymotion.com/video/${this.id}", TvType.Movie) {
            this.posterUrl = thumbnail360Url
        }
    }

    private fun PlaylistItem.toPlaylistSearchResponse(): SearchResponse {
        return newMovieSearchResponse(this.name, "https://www.dailymotion.com/playlist/${this.id}", TvType.TvSeries) {
            this.posterUrl = thumbnail360Url
        }
    }
}
