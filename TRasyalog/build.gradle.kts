version = 65

cloudstream {
    authors     = listOf("UmayTrade")
    language    = "tr"
    description = "TRasyalog izleme sitesi."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * 4: Local only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=asyalog.co&sz=%size%"
}
