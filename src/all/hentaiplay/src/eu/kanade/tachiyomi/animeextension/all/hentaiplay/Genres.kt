/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiplay

// Facets harvested from the live site, sorted A-Z. Genre/studio slugs double as
// the post wrappers' `tag-<slug>` classes, which is what makes real
// include/exclude (TriState) filtering possible in searchAnimeParse.
internal val GENRE_NAMES = arrayOf(
    "3D", "Adventure", "Ahegao", "Ai Generated", "Anal",
    "Blackmail", "Blowjob", "Body Swithing", "Bomb Cute Bomb", "Bondage",
    "Bootleg", "Brainwashed", "Bukakke", "Cat Girl", "Comedy",
    "Cosplay", "Creampie", "Dark Skin", "Deepthroat", "Demons",
    "Double Penatration", "Double Penetration", "Ecchi", "Elf", "Facesitting",
    "Facial", "Fantasy", "Female Teacher", "Femdom", "Footjob",
    "Futanari", "Gangbang", "Goblins", "Gyaru", "Harem",
    "Hentai Previews", "Historical", "Horny Slut", "Housewife", "Humiliation",
    "Hypnosis", "Idols", "Incest", "Inflation", "Internal Cumshot",
    "Lactation", "Large Breasts", "Lolicon", "Magical Girls", "Maid",
    "Masturbation", "Megane", "Mild Nudity", "Milf", "Mind Break",
    "Monsters", "NTR", "Nuns", "Nurses", "Office Ladies",
    "Orgy", "POV", "Pregnant", "Princess", "Public Sex",
    "Rape", "Rim Job", "Romance", "Scat", "School Girls",
    "Sci Fi", "Sex Toy", "Shimapan", "Short Series", "Shoutacon",
    "Slaves", "Squirting", "Stocking", "Succubus", "Super Power",
    "Supernatural", "Swimsuit", "Tentacles", "Three Some", "Tits Fuck",
    "Toys", "Train Molestation", "Trap", "Tsundere", "Ugly Bastard",
    "Uncensored", "Vanilla", "Violence", "Virgins", "X Ray",
    "Yaoi", "Yuri",
)

internal val GENRE_SLUGS = arrayOf(
    "3d", "adventure", "ahegao", "ai-generated", "anal",
    "blackmail", "blowjob", "body-swithing", "bomb-cute-bomb", "bondage",
    "bootleg", "brainwashed", "bukakke", "cat-girl", "comedy",
    "cosplay", "creampie", "dark-skin", "deepthroat", "demons",
    "double-penatration", "double-penetration", "ecchi", "elf", "facesitting",
    "facial", "fantasy", "female-teacher", "femdom", "footjob",
    "futanari", "gangbang", "goblins", "gyaru", "harem",
    "hentai-previews", "historical", "horny-slut", "housewife", "humiliation",
    "hypnosis", "idols", "incest", "inflation", "internal-cumshot",
    "lactation", "large-breasts", "lolicon", "magical-girls", "maid",
    "masturbation", "megane", "mild-nudity", "milf", "mind-break",
    "monsters", "ntr", "nuns", "nurses", "office-ladies",
    "orgy", "pov", "pregnant", "princess", "public-sex",
    "rape", "rim-job", "romance", "scat", "school-girls",
    "sci-fi", "sex-toy", "shimapan", "short-series", "shoutacon",
    "slaves", "squirting", "stocking", "succubus", "super-power",
    "supernatural", "swimsuit", "tentacles", "three-some", "tits-fuck",
    "toys", "train-molestation", "trap", "tsundere", "ugly-bastard",
    "uncensored-hentai", "vanilla", "violence", "virgins", "x-ray",
    "yaoi", "yuri",
)

internal val STUDIO_NAMES = arrayOf(
    "Antechinus", "Bunnywalker", "Collaboration Works", "Majin", "Mary Jane",
    "Pashmina", "Pink Pineapple", "Queen Bee", "Showten", "Suzuki Mirano",
    "T Rex", "White Bear", "Ziz Entertainment",
)

internal val STUDIO_SLUGS = arrayOf(
    "antechinus", "bunnywalker", "collaboration-works", "majin", "mary-jane",
    "pashmina", "pink-pineapple", "queen-bee", "showten", "suzuki-mirano",
    "t-rex", "white-bear", "ziz-entertainment",
)

// Years live in the same taxonomy on the site, so they combine with the other
// selected terms in one archive path (/genre/2024+school-girls/).
internal val YEAR_NAMES = arrayOf(
    "All", "2026", "2025", "2024", "2023",
    "2022", "2021", "2020", "2019", "2018",
    "2017", "2016", "2015", "2014", "2013",
    "2010",
)

internal val SORT_NAMES = arrayOf(
    "Newest",
    "Most viewed",
    "Title A-Z",
    "Most comments",
    "Random",
    "Last updated",
)

internal val SORT_SLUGS = arrayOf(
    "date",
    "views",
    "title",
    "comment_count",
    "rand",
    "modified",
)
