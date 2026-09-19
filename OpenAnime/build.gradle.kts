import com.lagradost.cloudstream3.gradle.CloudstreamExtension

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

version = 1

cloudstream {
    authors = listOf("UmayTrade")
    language = "tr"
    description = "OpenAnime - Açık Kaynak Anime Platformu"
    status = 1
    tvTypes = listOf("Anime")
}
