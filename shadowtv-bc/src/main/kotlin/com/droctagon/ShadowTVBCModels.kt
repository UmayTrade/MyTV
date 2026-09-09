package com.droctagon

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowConfig(
    @JsonProperty("apiUrl")       val apiUrl: String? = null,
    @JsonProperty("aesiv")        val aesiv: String? = null,
    @JsonProperty("aespas")       val aespas: String? = null,
    @JsonProperty("appSignature") val appSignature: String? = null,
    @JsonProperty("asize")        val asize: String? = null,
    @JsonProperty("glg1Key")      val glg1Key: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowChannelResponse(
    @JsonProperty("ormoxChnlx") val channels: List<ShadowChannelItem>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowChannelItem(
    @JsonProperty("id")        val id: String? = null,
    @JsonProperty("isim")      val isim: String? = null,
    @JsonProperty("resim")     val resim: String? = null,
    @JsonProperty("link")      val link: String? = null,
    @JsonProperty("kategori")  val kategori: String? = null,
    @JsonProperty("player")    val player: String? = null,
    @JsonProperty("tip")       val tip: String? = null,
    @JsonProperty("userAgent") val userAgent: String? = null,
    @JsonProperty("h1Key")     val h1Key: String? = null,
    @JsonProperty("h1Val")     val h1Val: String? = null,
    @JsonProperty("h2Key")     val h2Key: String? = null,
    @JsonProperty("h2Val")     val h2Val: String? = null,
    @JsonProperty("h3Key")     val h3Key: String? = null,
    @JsonProperty("h3Val")     val h3Val: String? = null,
    @JsonProperty("h4Key")     val h4Key: String? = null,
    @JsonProperty("h4Val")     val h4Val: String? = null,
    @JsonProperty("h5Key")     val h5Key: String? = null,
    @JsonProperty("h5Val")     val h5Val: String? = null,
    @JsonProperty("cookie")    val cookie: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowSubPlaylistWrapper(
    @JsonProperty("list") val list: ShadowSubPlaylistContainer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowSubPlaylistContainer(
    @JsonProperty("service") val service: String? = null,
    @JsonProperty("title")   val title: String? = null,
    @JsonProperty("item")    val items: List<ShadowSubPlaylistItem>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowSubPlaylistItem(
    @JsonProperty("service")      val service: String? = null,
    @JsonProperty("title")        val title: String? = null,
    @JsonProperty("playlistURL")  val playlistURL: String? = null,
    @JsonProperty("media_url")    val media_url: String? = null,
    @JsonProperty("url")          val url: String? = null,
    @JsonProperty("thumb_square") val thumb_square: String? = null,
    @JsonProperty("group")        val group: String? = null,
    @JsonProperty("userAgent")    val userAgent: String? = null,
    @JsonProperty("player")       val player: String? = null,
    @JsonProperty("playervalue")  val playervalue: String? = null,
    @JsonProperty("tip")          val tip: String? = null,
    @JsonProperty("h1Key")        val h1Key: String? = null,
    @JsonProperty("h1Val")        val h1Val: String? = null,
    @JsonProperty("h2Key")        val h2Key: String? = null,
    @JsonProperty("h2Val")        val h2Val: String? = null,
    @JsonProperty("h3Key")        val h3Key: String? = null,
    @JsonProperty("h3Val")        val h3Val: String? = null,
    @JsonProperty("h4Key")        val h4Key: String? = null,
    @JsonProperty("h4Val")        val h4Val: String? = null,
    @JsonProperty("h5Key")        val h5Key: String? = null,
    @JsonProperty("h5Val")        val h5Val: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowVodItem(
    @JsonProperty("title")     val title: String? = null,
    @JsonProperty("image")     val image: String? = null,
    @JsonProperty("imdb")      val imdb: String? = null,
    @JsonProperty("dil")       val dil: String? = null,
    @JsonProperty("year")      val year: String? = null,
    @JsonProperty("detailUrl") val detailUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowVodDetail(
    @JsonProperty("film_name")        val film_name: String? = null,
    @JsonProperty("film_img")         val film_img: String? = null,
    @JsonProperty("film_description") val film_description: String? = null,
    @JsonProperty("imdb_rating")      val imdb_rating: String? = null,
    @JsonProperty("tur")              val tur: String? = null,
    @JsonProperty("iframe_url")       val iframe_url: String? = null,
    @JsonProperty("dizi_adi")         val dizi_adi: String? = null,
    @JsonProperty("dizi_resim")       val dizi_resim: String? = null,
    @JsonProperty("dizi_aciklama")    val dizi_aciklama: String? = null,
    @JsonProperty("dizi_bolumler")    val dizi_bolumler: List<ShadowDiziBolum>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowDiziBolum(
    @JsonProperty("bolum_adi") val bolum_adi: String? = null,
    @JsonProperty("isim")      val isim: String? = null,
    @JsonProperty("bolum_url") val bolum_url: String? = null,
    @JsonProperty("bolum")     val bolum: String? = null,
    @JsonProperty("sezon")     val sezon: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowStreamResponse(
    @JsonProperty("stream") val stream: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ShadowPassData(
    @JsonProperty("type")     val type: String, // "channel", "sub_item", "vod_film", "vod_dizi"
    @JsonProperty("channel")  val channel: ShadowChannelItem? = null,
    @JsonProperty("subItem")  val subItem: ShadowSubPlaylistItem? = null,
    @JsonProperty("vodItem")  val vodItem: ShadowVodItem? = null,
    @JsonProperty("directUrl") val directUrl: String? = null
)
