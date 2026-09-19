plugins {
    id("com.android.library")
    kotlin("android")
}

version = 47

cloudstream {
    authors     = listOf("caca1403")
    language    = "tr"
    description = "Anime Siteleri | AnimeciX ve OpenAnime arşivini tek çatı altında toplayan birleşik anime eklentisi."
    status      = 1
    tvTypes     = listOf("Anime", "AnimeMovie")
    iconUrl     = "https://cdn-icons-png.flaticon.com/512/2281/2281832.png"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.1")
}