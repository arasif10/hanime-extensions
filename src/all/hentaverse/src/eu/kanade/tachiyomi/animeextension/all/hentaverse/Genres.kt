/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaverse

// Categories from GET /api/v1/content/categories, sorted A-Z. Multi-select is
// OR: the extension fetches /categories/{slug}/series per ticked box and merges.
internal val CATEGORY_NAMES = arrayOf(
    "3D", "Ahegao", "Anal", "Bdsm", "Big boobs",
    "Blackmail", "Blow job", "Bondage", "Boob job", "Bukkake",
    "Censored", "Comedy", "Cosplay", "Creampie", "Dark skin",
    "Doggy Style", "Dominatrix", "Double penetration", "Facial", "Fantasy",
    "Female student", "Filmed", "Foot Job", "Futanari", "Gangbang",
    "Glasses", "Hand job", "Harem", "Horror", "Incest",
    "Inflation", "Lactation", "Lingerie", "Loli", "Maid",
    "Masturbation", "milf", "Mind break", "Mind control", "Monster",
    "Mother and son", "Nekomimi", "ntr", "Nurse", "Oral",
    "Orgy", "Plot", "pov", "Pregnant", "Public sex",
    "Rape", "Rimjob", "Romance", "Scat", "School girl",
    "Shota", "Slaves", "Softcore", "Submission", "Swimsuit",
    "Teacher", "Tentacle", "Threesome", "Throat fucking", "Toys",
    "Trap", "Tsundere", "Ugly bastard", "Uncensored", "Urination",
    "Vanilla", "Virgin", "Watersports", "x-ray", "Yaoi",
    "yuri",
)

internal val CATEGORY_SLUGS = arrayOf(
    "3d", "ahegao", "anal", "bdsm", "big-boobs",
    "blackmail", "blow-job", "bondage", "boob-job", "bukkake",
    "censored", "comedy", "cosplay", "creampie", "dark-skin",
    "doggy-style", "dominatrix", "double-penetration", "facial", "fantasy",
    "female-student", "filmed", "foot-job", "futanari", "gangbang",
    "glasses", "hand-job", "harem", "horror", "incest",
    "inflation", "lactation", "lingerie", "loli", "maid",
    "masturbation", "milf", "mind-break", "mind-control", "monster",
    "mother-and-son", "nekomimi", "ntr", "nurse", "oral",
    "orgy", "plot", "pov", "pregnant", "public-sex",
    "rape", "rimjob", "romance", "scat", "school-girl",
    "shota", "slaves", "softcore", "submission", "swimsuit",
    "teacher", "tentacle", "threesome", "throat-fucking", "toys",
    "trap", "tsundere", "ugly-bastard", "uncensored", "urination",
    "vanilla", "virgin", "watersports", "x-ray", "yaoi",
    "yuri",
)

// ?sort= is honoured by the series endpoint.
internal val SORT_NAMES = arrayOf(
    "Default",
    "Popular",
)

internal val SORT_SLUGS = arrayOf(
    "",
    "popular",
)
