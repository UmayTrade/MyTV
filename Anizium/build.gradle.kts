version = 28

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "Türkçe Altyazılı Anime İzle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // Will be 3 if unspecified
    tvTypes = listOf("Anime")
    iconUrl = "https://img.utdstc.com/icon/f09/fe1/f09fe1b07c8cbb89291ebb2aeaf82ac0895befabeb8c93024a434d9dd1159358:600"
}
