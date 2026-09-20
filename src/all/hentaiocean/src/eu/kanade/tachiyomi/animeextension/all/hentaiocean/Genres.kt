/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiocean

// Genres from GET /api?action=genres, sorted A-Z; every slug is verified to
// render a /genre/{slug} page. Multi-select is OR: one request per ticked box,
// merged by URL.
internal val GENRE_NAMES = arrayOf(
    "Ahegao", "Anal", "Animal Ears", "Big Boobs", "Blowjob",
    "Boobjob", "Comedy", "Cosplay", "Creampie", "Dark Skin",
    "Facial", "Fantasy", "Footjob", "Futanari", "Gangbang",
    "Gyaru", "Handjob", "Harem", "Incest", "Lactation",
    "Maid", "Masturbation", "Milf", "Mind Break", "NTR",
    "Nurse", "Orgy", "POV", "Pregnant", "Public Sex",
    "Rape", "Rimjob", "Scat", "School Girl", "Shoutacon",
    "Softcore", "Swimsuit", "Teacher", "Tentacles", "Toys",
    "Tsundere", "Ugly Bastard", "Uncensored", "Vanilla", "Virgin",
    "X-Ray", "Yaoi", "Yuri",
)

internal val GENRE_SLUGS = arrayOf(
    "Ahegao", "Anal", "Animal%20Ears", "Big%20Boobs", "Blowjob",
    "Boobjob", "Comedy", "Cosplay", "Creampie", "Dark%20Skin",
    "Facial", "Fantasy", "Footjob", "Futanari", "Gangbang",
    "Gyaru", "Handjob", "Harem", "Incest", "Lactation",
    "Maid", "Masturbation", "Milf", "Mind%20Break", "NTR",
    "Nurse", "Orgy", "POV", "Pregnant", "Public%20Sex",
    "Rape", "Rimjob", "Scat", "School%20Girl", "Shoutacon",
    "Softcore", "Swimsuit", "Teacher", "Tentacles", "Toys",
    "Tsundere", "Ugly%20Bastard", "Uncensored", "Vanilla", "Virgin",
    "X-Ray", "Yaoi", "Yuri",
)
