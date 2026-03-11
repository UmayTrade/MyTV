# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

from Kekik.cli    import konsol
from cloudscraper import CloudScraper
from parsel       import Selector
from re           import findall

oturum  = CloudScraper()

# ! Güncel domain
mainUrl = "https://dizipal2031.com"
pageUrl = f"{mainUrl}/diziler?kelime=&durum=&tur=1&type=&siralama="

try:
    istek   = oturum.get(pageUrl, timeout=10)
    istek.raise_for_status()
    secici  = Selector(istek.text)
except Exception as e:
    konsol.print(f"[red]Hata: {e}[/red]")
    exit(1)

def icerik_ver(secici: Selector):
    son_date = ""

    for icerik in secici.css("article.type2 ul li"):
        title = icerik.css("span.title::text").get()
        link  = icerik.css("a::attr(href)").get()
        img   = icerik.css("img::attr(src)").get()
        date  = icerik.css("a::attr(data-date)").get()
        
        if title:
            konsol.print(f"[cyan]{title}[/cyan]")
            konsol.print(f"[blue]{link}[/blue]")
            konsol.print(f"[green]{img}[/green]")
            konsol.print(f"[yellow]{date}[/yellow]")
            konsol.print("\n")
            son_date = date

    return son_date

son_date = icerik_ver(secici)

def devam_ver(son_date) -> str:
    if not son_date:
        return ""
        
    try:
        istek = oturum.post(
            url  = f"{mainUrl}/api/load-series",
            data = {
                "date"     : son_date,
                "tur"      : findall(r"tur=([\d]+)&", pageUrl)[0] if findall(r"tur=([\d]+)&", pageUrl) else "1",
                "durum"    : "",
                "kelime"   : "",
                "type"     : "",
                "siralama" : ""
            },
            headers = {
                "X-Requested-With": "XMLHttpRequest",
                "Referer": mainUrl
            }
        )
        istek.raise_for_status()
        veri = istek.json()
        
        if not veri.get("html"):
            return ""

        devam_html = "<article class='type2'><ul>" + veri["html"] + "</ul></article>"
        return icerik_ver(Selector(devam_html))
    except Exception as e:
        konsol.print(f"[red]API Hatası: {e}[/red]")
        return ""

while son_date:
    son_date = devam_ver(son_date)
