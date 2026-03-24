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
    @JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null,
    @JsonProperty("duration") val duration: Int? = null // Thời lượng tính bằng giây
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

    // --- MAIN PAGE (Đã áp dụng logic chuẩn từ OPhim) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()
        
        // 1. LẤY DANH SÁCH USER (CỐ ĐỊNH, KHÔNG DÙNG BIẾN PAGE Ở ĐÂY)
        // Chúng ta lấy 1 lần 10 người bạn đang theo dõi để làm 10 danh mục (Categories)
        // Nếu dùng biến page ở đây, danh sách user sẽ bị đổi khi cuộn xuống làm hỏng việc gộp hàng.
        val followingUrl = "$mainUrl/user/taunt-preface-runt/following?fields=id,screenname&limit=10&page=1"
        val followingRes = app.get(followingUrl).text
        val users = tryParseJson<FollowingResponse>(followingRes)?.list ?: emptyList()

        // 2. LẤY PLAYLIST CHO TỪNG USER VÀ DÙNG BIẾN PAGE ĐỂ PHÂN TRANG NGANG
        users.forEach { user ->
            val playlistUrl = "$mainUrl/user/${user.id}/playlists?fields=id,name,thumbnail_360_url&limit=20&page=$page"
            val playlistRes = app.get(playlistUrl).text
            
            tryParseJson<PlaylistSearchResponse>(playlistRes)?.list?.let { playlists ->
                if (playlists.isNotEmpty()) {
                    homePages.add(HomePageList(
                        name = user.screenname, // QUAN TRỌNG: Tên phải cố định để Cloudstream tự gộp ngang
                        list = playlists.map { 
                            newMovieSearchResponse(it.name, "https://www.dailymotion.com/playlist/${it.id}", TvType.TvSeries) {
                                this.posterUrl = it.thumbnail360Url
                            }
                        }
                    ))
                }
            }
        }

        // Trả về hasNext = true để báo cho Cloudstream biết cứ lướt xuống là lấy tiếp Trang 2, Trang 3...
        return newHomePageResponse(homePages, hasNext = true)
    }

    // --- SEARCH ---
        override suspend fun search(query: String, page: Int): SearchResponseList? {
        // 1. Kiểm tra nếu người dùng dán Link Video hoặc Playlist
        val videoId = Regex("(?:video|playlist)/([a-zA-Z0-9]+)").find(query)?.groups?.get(1)?.value
        
        if (query.startsWith("http") && videoId != null) {
            if (query.contains("/playlist/")) {
                // Nếu là link Playlist -> Lấy thông tin playlist đó
                val res = app.get("$mainUrl/playlist/$videoId?fields=id,name,thumbnail_360_url").text
                val it = tryParseJson<PlaylistItem>(res) ?: return null
                return listOf(
                    newMovieSearchResponse(it.name, "https://www.dailymotion.com/playlist/${it.id}", TvType.TvSeries) {
                        this.posterUrl = it.thumbnail360Url
                    }
                ).toNewSearchResponseList()
            } else {
                // Nếu là link Video -> Lấy thông tin video đó
                val res = app.get("$mainUrl/video/$videoId?fields=id,title,thumbnail_360_url").text
                val it = tryParseJson<VideoItem>(res) ?: return null
                return listOf(
                    newMovieSearchResponse(it.title, "https://www.dailymotion.com/video/${it.id}", TvType.Movie) {
                        this.posterUrl = it.thumbnail360Url
                    }
                ).toNewSearchResponseList()
            }
        }

        // 2. Nếu không phải Link, thực hiện tìm kiếm bình thường (đã thêm sort=relevance)
        val vRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&search=$query&sort=relevance").text
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
        
        // Thêm "duration" vào fields
        val videosRes = app.get("$mainUrl/playlist/$id/videos?fields=id,title,thumbnail_360_url,duration&limit=100").text
        val videos = tryParseJson<VideoSearchResponse>(videosRes)?.list ?: emptyList()

        return newTvSeriesLoadResponse(detail.name, url, TvType.TvSeries, videos.reversed().map { video ->
            val durationMin = video.duration?.let { it / 60 } // Đổi sang phút
            val durationSec = video.duration?.let { it % 60 } // Giây dư ra
            
            newEpisode("https://www.dailymotion.com/video/${video.id}") {
                this.name = video.title
                this.posterUrl = video.thumbnail360Url
                this.runTime =  video.duration
                // Hiển thị thời lượng ở phần mô tả tập phim
                this.description = if (durationMin != null) "Thời lượng: ${durationMin}p ${durationSec}s" else null
            }
        }) {
            this.posterUrl = detail.thumbnail360Url
        }
    }

    // Xử lý cho link Video đơn lẻ
    val response = app.get("$mainUrl/video/$id?fields=id,title,thumbnail_720_url,duration").text
    val v = tryParseJson<VideoItem>(response) ?: return null
    
    return newMovieLoadResponse(v.title, url, TvType.Movie, "https://www.dailymotion.com/video/${v.id}") {
        this.posterUrl = v.thumbnail360Url
        // Hiển thị thời lượng trong phần thông tin phim
        this.duration = v.duration?.let { it / 60 } 
        
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
