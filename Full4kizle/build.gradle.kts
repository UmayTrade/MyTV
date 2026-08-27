// ! Bu araç @SAKLImavi tarafından | @UmayTrade için yazılmıştır.
version = 9

cloudstream {
    authors     = listOf("UmayTrade")
    language    = "tr"
    description = "Filmci Baba, film izleme sitesi 4k Full film izle, 1080p ve 4k (yalan) kalite de sinema filmleri ve dizileri, tek parça hd kalitede türkçe dublajlı filmler seyret."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "AsianDrama")
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://full4kizle.cc/&size=128"
}
