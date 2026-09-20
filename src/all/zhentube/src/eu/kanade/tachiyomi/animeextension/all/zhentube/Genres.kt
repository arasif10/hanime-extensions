/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.zhentube

// Facets come from the catalogue cards' own `category-<slug>` / `actors-<slug>`
// classes, so include/exclude is enforced against the same tokens the site
// renders. Sorted A-Z; labels drop the site's trailing "-hentai".
internal val GENRE_NAMES = arrayOf(
    "3D", "Ahegao", "Anal", "BDSM", "Big Boobs",
    "Big Tits", "Blowjob", "Bondage", "Boob Job", "Censored",
    "Comedy", "Compilation", "Cosplay", "Creampie", "Dark Skin",
    "Facial", "Fantasy", "Filmed", "Gang Bang", "Glasses",
    "Hand Job", "Harem", "Incest", "Lesbian", "Maid",
    "Masturbation", "Milf", "Mind Break", "Mind Control", "Monster",
    "Netorare", "NTR", "Orgy", "POV", "Public Sex",
    "Rape", "Romance", "School Girl", "Sex Toy", "Shota",
    "Small Breasts", "Stocking", "Swimsuit", "Teacher", "Tentacle",
    "Three Some", "Toys", "Tsundere", "Ugly Bastard", "Uncensored",
    "Vanilla", "Virgin", "Water Sports", "X Ray", "Yuri",
)

internal val GENRE_SLUGS = arrayOf(
    "3d-hentai", "ahegao", "anal-hentai", "bdsm-hentai", "big-boobs-hentai",
    "big-tits", "blowjob-hentai", "bondage", "boob-job", "censored-hentai",
    "comedy", "compilation", "cosplay", "creampie", "dark-skin",
    "facial", "fantasy", "filmed", "gang-bang", "glasses",
    "hand-job", "harem", "incest", "lesbian", "maid",
    "masturbation", "milf", "mind-break", "mind-control", "monster",
    "netorare", "ntr", "orgy", "pov", "public-sex",
    "rape", "romance", "school-girl", "sex-toy", "shota",
    "small-breasts", "stocking", "swimsuit", "teacher", "tentacle",
    "three-some", "toys", "tsundere", "ugly-bastard", "uncensored-hentai",
    "vanilla", "virgin", "water-sports", "x-ray", "yuri",
)

internal val STUDIO_NAMES = arrayOf(
    "Bunnywalker", "Lune Pictures", "Magin Label", "Ms Pictures", "Pink Pineapple",
    "Poro", "Queen Bee", "Studio Hokiboshi", "Suiseisha", "T Rex",
)

internal val STUDIO_SLUGS = arrayOf(
    "bunnywalker", "lune-pictures", "magin-label", "ms-pictures", "pink-pineapple",
    "poro", "queen-bee", "studio-hokiboshi", "suiseisha", "t-rex-hentai",
)

internal val ACTOR_NAMES = arrayOf(
    "Airi", "Akihito Kuji", "Amelie", "Aya Hinata", "Chiaki",
    "Cless Gretchen", "D Va", "Jill Grantz", "Kamui", "Lunalie El Blanca",
    "Mai Hoshino", "Makoto Houjou", "Mamoru Sasaki", "Megumi Yano", "Mercy",
    "Nagisa", "Nao", "Sae Takatsuki", "Sana", "Shizuka Mimizuka",
    "Widowmaker", "Yayoi Nijihara", "Yoshiharu", "Yukiko",
)

internal val ACTOR_SLUGS = arrayOf(
    "airi", "akihito-kuji", "amelie", "aya-hinata", "chiaki",
    "cless-gretchen", "d-va", "jill-grantz", "kamui", "lunalie-el-blanca",
    "mai-hoshino", "makoto-houjou", "mamoru-sasaki", "megumi-yano", "mercy",
    "nagisa", "nao", "sae-takatsuki", "sana", "shizuka-mimizuka",
    "widowmaker", "yayoi-nijihara", "yoshiharu", "yukiko",
)

// The site's own sort options (`?filter=`).
internal val SORT_NAMES = arrayOf(
    "Most viewed",
    "Latest",
    "Longest",
    "Popular",
    "Random",
)

internal val SORT_SLUGS = arrayOf(
    "most-viewed",
    "latest",
    "longest",
    "popular",
    "random",
)
