package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import kotlin.collections.ArrayList

class BflixProvider(providerUrl: String, providerName: String) : MainAPI() {
    override val mainUrl = providerUrl
    override val name = providerName
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override suspend fun getMainPage(): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/home", "Movies"),
            Pair("$mainUrl/tv-series", "Series"),
            Pair("$mainUrl/top-imdb", "Top"),
        )
        for (i in urls) {
            try {
                val response = app.get(i.first)
                val soup = Jsoup.parse(response.text)
                val home = soup.select(".filmlist div.item").map {
                    val title = it.selectFirst("h3 a").text()
                    val link = fixUrl(it.selectFirst("a").attr("href"))
                    TvSeriesSearchResponse(
                        title,
                        link,
                        this.name,
                        if (link.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                        it.selectFirst("a.poster img").attr("src"),
                        null,
                        null,
                    )
                }

                items.add(HomePageList(i.second, home))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }
    //Credits to https://github.com/jmir1
    val key = "eST4kCjadnvlAm5b1BOGyLJzrE90Q6oKgRfhV+M8NDYtcxW3IP/qp2i7XHuwZFUs"

    private fun getVrf(id: String): String? {
        val reversed = ue(encode(id) + "0000000").slice(0..5).reversed()
        return reversed + ue(je(reversed, encode(id)?.replace("+","%20") ?: return null)).replace(
            """=+$""".toRegex(),
            ""
        )
    }

    private fun getLink(url: String): String? {
        val i = url.slice(0..5)
        val n = url.slice(6..url.lastIndex)
        return decode(je(i, ze(n)))
    }

    private fun ue(input: String): String {
        if (input.any { it.code >= 256 }) throw Exception("illegal characters!")
        var output = ""
        for (i in input.indices step 3) {
            val a = intArrayOf(-1, -1, -1, -1)
            a[0] = input[i].code shr 2
            a[1] = (3 and input[i].code) shl 4
            if (input.length > i + 1) {
                a[1] = a[1] or (input[i + 1].code shr 4)
                a[2] = (15 and input[i + 1].code) shl 2
            }
            if (input.length > i + 2) {
                a[2] = a[2] or (input[i + 2].code shr 6)
                a[3] = 63 and input[i + 2].code
            }
            for (n in a) {
                if (n == -1) output += "="
                else {
                    if (n in 0..63) output += key[n]
                }
            }
        }
        return output
    }

    private fun je(inputOne: String, inputTwo: String): String {
        val arr = IntArray(256) { it }
        var output = ""
        var u = 0
        var r: Int
        for (a in arr.indices) {
            u = (u + arr[a] + inputOne[a % inputOne.length].code) % 256
            r = arr[a]
            arr[a] = arr[u]
            arr[u] = r
        }
        u = 0
        var c = 0
        for (f in inputTwo.indices) {
            c = (c + f) % 256
            u = (u + arr[c]) % 256
            r = arr[c]
            arr[c] = arr[u]
            arr[u] = r
            output += (inputTwo[f].code xor arr[(arr[c] + arr[u]) % 256]).toChar()
        }
        return output
    }

    private fun ze(input: String): String {
        val t = if (input.replace("""[\t\n\f\r]""".toRegex(), "").length % 4 == 0) {
            input.replace("""/==?$/""".toRegex(), "")
        } else input
        if (t.length % 4 == 1 || t.contains("""[^+/0-9A-Za-z]""".toRegex())) throw Exception("bad input")
        var i: Int
        var r = ""
        var e = 0
        var u = 0
        for (o in t.indices) {
            e = e shl 6
            i = key.indexOf(t[o])
            e = e or i
            u += 6
            if (24 == u) {
                r += ((16711680 and e) shr 16).toChar()
                r += ((65280 and e) shr 8).toChar()
                r += (255 and e).toChar()
                e = 0
                u = 0
            }
        }
        return if (12 == u) {
            e = e shr 4
            r + e.toChar()
        } else {
            if (18 == u) {
                e = e shr 2
                r += ((65280 and e) shr 8).toChar()
                r += (255 and e).toChar()
            }
            r
        }
    }

    private fun encode(input: String): String? = java.net.URLEncoder.encode(input, "utf-8")
    private fun decode(input: String): String? = java.net.URLDecoder.decode(input, "utf-8")

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedquery = getVrf(query)?.let { encode(it) } ?: return null
        val url = "$mainUrl/search?keyword=$query&vrf=$encodedquery"
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        return document.select(".filmlist div.item").map {
            val title = it.selectFirst("h3 a").text()
            val href = fixUrl(it.selectFirst("a").attr("href"))
            val image = it.selectFirst("a.poster img").attr("src")
            val isMovie = href.contains("/movie/")

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null
                )
            }
        }
    }

    data class Response (
        @JsonProperty("html") val html: String
    )


    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document
        val movieid = soup.selectFirst("div#watch").attr("data-id")
        val movieidencoded = encode(getVrf(movieid) ?: return null)
        val tvType = if (url.contains("/movie/")) TvType.Movie else TvType.TvSeries

        val title = soup.selectFirst("div.info h1").text()
        val description = soup.selectFirst(".info .desc")?.text()?.trim()
        val poster: String? = try {
            soup.selectFirst("img.poster").attr("src")
        } catch (e:Exception) {
            soup.selectFirst(".info .poster img").attr("src")
        }

        val tags = soup.select("div.info .meta div:contains(Genre) a").map { it.text() }
        val episodes = if (tvType == TvType.TvSeries) Jsoup.parse(
            parseJson<Response>(
                app.get(
                    "$mainUrl/ajax/film/servers?id=$movieid&vrf=$movieidencoded"
                ).text
            ).html
        ).select("div.episode").map {
            val href = fixUrl(it.selectFirst("a").attr("href"))
            val eptitle = it.selectFirst(".episode a span.name").text()
            TvSeriesEpisode(
                eptitle,
                null,
                null,
                href,
            )
        } else null
        val recommendations =
            soup.select("div.bl-2 section.bl div.content div.filmlist div.item")?.mapNotNull { element ->
                val recTitle = element.select("h3 a").text() ?: return@mapNotNull null
                val image = element.select("a.poster img")?.attr("src")
                val recUrl = fixUrl(element.select("a").attr("href"))
                MovieSearchResponse(
                    recTitle,
                    recUrl,
                    this.name,
                    if (recUrl.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                    image,
                    year = null
                )
            }

        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes!!,
                    poster,
                    null,
                    description,
                    null,
                    null,
                    null,
                    tags,
                    recommendations = recommendations,
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    url,
                    poster,
                    null,
                    description,
                    null,
                    null,
                    tags,
                    recommendations = recommendations
                )
            }
            else -> null
        }
    }


    data class Subtitles (
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val kind: String
    )

    data class Links (
        @JsonProperty("url") val url: String
    )

    data class Servers (
        @JsonProperty("28") val mcloud: String?,
        @JsonProperty("35") val mp4upload: String?,
        @JsonProperty("40") val streamtape: String?,
        @JsonProperty("41") val vidstream: String?,
        @JsonProperty("43") val videovard: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val soup = app.get(data).document
        val movieid = encode(soup.selectFirst("div#watch").attr("data-id") ?: return false)
        val movieidencoded =  encode(getVrf(movieid!!) ?: return false)
        Jsoup.parse(
            parseJson<Response>(
                app.get(
                    "$mainUrl/ajax/film/servers?id=$movieid&vrf=$movieidencoded"
                ).text
            ).html
        )
            .select("html body #episodes").map {
                val tvType = if (data.contains("movie/")) TvType.Movie else TvType.TvSeries
                val cleandata = data.replace(mainUrl,"")
                val servers = if (tvType == TvType.Movie) it.select(".episode a").attr("data-ep")
                else
                    it.select(".episode a[href=$cleandata]").attr("data-ep")
                        ?: it.select(".episode a[href=${cleandata.replace("/1-full","")}]").attr("data-ep")
                val jsonservers = parseJson<Servers?>(servers) ?: return@map
                listOfNotNull(
                    jsonservers.vidstream,
                    jsonservers.mcloud,
                    jsonservers.mp4upload,
                    jsonservers.streamtape
                ).mapNotNull {
                    val epserver = app.get("$mainUrl/ajax/episode/info?id=$it").text
                    (if (epserver.contains("url")) {
                        parseJson<Links>(epserver)
                    } else null)?.url?.let { it1 -> getLink(it1.replace("=", "")) }
                        ?.replace("/embed/", "/e/")?.replace(Regex("(\\?sub.info.*)"),"")
                }.apmap { url ->
                    loadExtractor(
                        url, data, callback
                    )
                }
                //Apparently any server works, I haven't found any diference
                val sublink = app.get("$mainUrl/ajax/episode/subtitles/${jsonservers.vidstream}").text
                val jsonsub = parseJson<List<Subtitles>>(sublink)
                jsonsub.forEach { subtitle ->
                    subtitleCallback(
                        SubtitleFile(subtitle.label, subtitle.file)
                    )
                }
            }

        return true
    }
}