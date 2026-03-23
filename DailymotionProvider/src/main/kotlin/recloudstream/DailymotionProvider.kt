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

    data class FollowingResponse(@JsonProperty("list") val list: List<UserItem>)
    data class UserItem(
        @JsonProperty("screenname") val screenname: String,
        @JsonProperty("id") val id: String
    )

    override var mainUrl = "https://api.dailymotion.com"
    override var name = "Dailymotion"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    // --- XỬ LÝ CATALOGUE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()
        
        // 1. Lấy danh sách Following (Tăng limit để lấy nhiều User hơn làm Catalogue)
        val followingUrl = "$mainUrl/user/taunt-preface-runt/following?fields=id,screenname&limit=10"
        val followingRes = app.get(followingUrl).text
        val users = tryParseJson<FollowingResponse>(followingRes)?.list ?: emptyList()

        // 2. Với mỗi User, ta xử lý như một Catalogue riêng biệt
        users.forEach { user ->
            // Sử dụng page của Cloudstream để lấy các bộ phim tiếp theo cho từng hàng
            val playlistUrl = "$mainUrl/user/${user.id}/playlists?fields=id,name,thumbnail_360_url&limit=20&page=$page"
            val playlistRes = app.get(playlistUrl).text
            
            tryParseJson<PlaylistSearchResponse>(playlistRes)?.list?.let { playlists ->
                if (playlists.isNotEmpty()) {
                    homePages.add(HomePageList(
                        name = "${user.screenname} (P.$page)", 
                        list = playlists.map { 
                            newMovieSearchResponse(it.name, "https://www.dailymotion.com/playlist/${it.id}", TvType.TvSeries) {
                                this.posterUrl = it.thumbnail360Url
                            }
                        },
                        horizontal = true // ĐÃ SỬA: Thay isHorizontal thành horizontal
                    ))
                }
            }
        }

        // hasNext = true để khi bạn cuộn dọc xuống dưới cùng, nó sẽ gọi trang 2, trang 3...
        // cho TẤT CẢ các User cùng lúc, giúp cập nhật thêm phim mới vào các hàng
        return newHomePageResponse(homePages, users.isNotEmpty())
    }

    // --- SEARCH ---
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val vRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&search=$query").text
        val results = tryParseJson<VideoSearchResponse>(vRes)?.list?.map { 
            newMovieSearchResponse(it.title, "https://www.dailymotion.com/video/${it.id}", TvType.Movie) {
                this.posterUrl = it.thumbnail360Url
            }
        }
        return results?.toNewSearchResponseList()
    }

    // --- LOAD (Lấy toàn bộ video trong Playlist) ---
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

        val response = app.get("$mainUrl/video/$id?fields=id,title,thumbnail_720_url").text
        val v = tryParseJson<VideoItem>(response) ?: return null
        
        return newMovieLoadResponse(v.title, url, TvType.Movie, "https://www.dailymotion.com/video/${v.id}") {
            this.posterUrl = v.thumbnail360Url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(data, subtitleCallback, callback)
    }
}
