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

    // --- ĐỊNH NGHĨA CATALOGUE (DANH MỤC) ---
    // Chúng ta tạo một danh sách các "Tab" hoặc "Danh mục" để chọn ở màn hình chính
    override val mainPage = listOf(
        MainPageData("Popular Videos", "popular"),
        // Các User bạn theo dõi sẽ được hiển thị ở đây. 
        // Vì Following là động, tôi sẽ xử lý logic lấy danh sách này ngay trong getMainPage
    )

    // --- MAIN PAGE (Xử lý theo từng Catalogue) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()
        
        // 1. Lấy danh sách Following của bạn (Tối đa 15 người)
        val followingUrl = "$mainUrl/user/taunt-preface-runt/following?fields=id,screenname&limit=15"
        val followingRes = app.get(followingUrl).text
        val users = tryParseJson<FollowingResponse>(followingRes)?.list ?: emptyList()

        // 2. Logic "Catalogue riêng biệt":
        // Nếu người dùng đang ở trang chủ mặc định (chưa chọn User nào cụ thể)
        if (request.data == "popular" || request.data.isEmpty()) {
            
            // Hiện 1 hàng cho mỗi User (mỗi hàng lấy 20 bộ đầu tiên)
            users.forEach { user ->
                val pUrl = "$mainUrl/user/${user.id}/playlists?fields=id,name,thumbnail_360_url&limit=20&page=1"
                val pRes = app.get(pUrl).text
                tryParseJson<PlaylistSearchResponse>(pRes)?.list?.let { playlists ->
                    if (playlists.isNotEmpty()) {
                        homePages.add(HomePageList(
                            user.screenname, 
                            playlists.map { it.toSearchResponse() },
                            isHorizontal = true
                        ))
                    }
                }
            }
            
            // Hiện thêm một hàng Video phổ biến
            val vRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page").text
            tryParseJson<VideoSearchResponse>(vRes)?.list?.let { videos ->
                homePages.add(HomePageList("Popular Videos", videos.map { it.toSearchResponse() }))
            }
            
            // Ở trang tổng hợp này, ta không phân trang dọc quá nhiều để tránh rối
            return newHomePageResponse(homePages, false)

        } else {
            // ĐÂY LÀ PHẦN QUAN TRỌNG: Nếu bạn chọn 1 User cụ thể từ menu Catalogue
            // Biến 'page' lúc này sẽ hoạt động RIÊNG BIỆT cho User đó.
            val userId = request.data // Giả sử ta truyền ID vào đây
            val pUrl = "$mainUrl/user/$userId/playlists?fields=id,name,thumbnail_360_url&limit=20&page=$page"
            val pRes = app.get(pUrl).text
            
            val playlists = tryParseJson<PlaylistSearchResponse>(pRes)?.list ?: emptyList()
            homePages.add(HomePageList(request.name, playlists.map { it.toSearchResponse() }))
            
            return newHomePageResponse(homePages, playlists.isNotEmpty())
        }
    }

    // --- HELPERS (Rút gọn code để tránh lỗi lặp lại) ---
    private fun VideoItem.toSearchResponse() = newMovieSearchResponse(
        this.title, "https://www.dailymotion.com/video/${this.id}", TvType.Movie
    ) { this.posterUrl = thumbnail360Url }

    private fun PlaylistItem.toSearchResponse() = newMovieSearchResponse(
        this.name, "https://www.dailymotion.com/playlist/${this.id}", TvType.TvSeries
    ) { this.posterUrl = thumbnail360Url }

    // --- CÁC HÀM CÒN LẠI ---
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val vRes = app.get("$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&search=$query").text
        return tryParseJson<VideoSearchResponse>(vRes)?.list?.map { it.toSearchResponse() }?.toNewSearchResponseList()
    }

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
            }) { this.posterUrl = detail.thumbnail360Url }
        }
        val response = app.get("$mainUrl/video/$id?fields=id,title,thumbnail_720_url").text
        val v = tryParseJson<VideoItem>(response) ?: return null
        return newMovieLoadResponse(v.title, url, TvType.Movie, "https://www.dailymotion.com/video/${v.id}") { this.posterUrl = v.thumbnail360Url }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return loadExtractor(data, subtitleCallback, callback)
    }
}
