/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiht

// Tag vocabulary harvested from the catalogue's own titles, then verified one by
// one against /api/v1/catalog?tag=<name>: every entry below returns results, so
// no ticked box can land on an empty list. A-Z.
internal val TAG_NAMES = arrayOf(
    "Age Gap", "Angels", "Anthology", "Band", "Body Swapping", "Cohabitation", "Cosplay",
    "Crossdressing", "Delinquents", "Demons", "Dragons", "Elf", "Ensemble Cast", "Episodic",
    "Female Protagonist", "Full CGI", "Ghost", "Gods", "Gyaru", "Heterosexual", "Idol", "Isekai",
    "Josei", "Kemonomimi", "Kuudere", "Love Triangle", "Magic", "Maids", "Male Protagonist",
    "Marriage", "Monster Girl", "Nekomimi", "Ninja", "Office Lady", "Photography", "Pirates",
    "Primarily Adult Cast", "Primarily Female Cast", "Rural", "School", "Seinen", "Shapeshifting",
    "Space", "Succubus", "Swordplay", "Teacher", "Time Manipulation", "Travel", "Tsundere",
    "Twins", "Urban Fantasy", "Vampire", "Video Games", "Virtual World", "Witch", "Work",
    "Writing", "Yandere",
)

// Studios printed on the catalogue rows (which is what lets an exclude tap be
// enforced on the parsed result), each verified against &studio=<name>. A-Z.
internal val STUDIO_NAMES = arrayOf(
    "AniMan", "Anime Antenna Iinkai", "BOOTLEG", "BREAKBOTTLE", "Bunny Walker",
    "Collaboration Works", "Cotton Doll", "EDGE", "Flavors Soft", "G-lam", "GOLD BEAR", "HiLLS",
    "Hoods Entertainment", "Hot Bear", "Jumondou", "Lune Pictures", "Magic Bus", "Majin",
    "Mary Jane", "Milky Animation Label", "Mitsu", "Mousou Senka", "NewGeneration", "Office 8-ban",
    "Office Take Off", "Office Takeout", "Pashmina", "Peak Hunt", "Pink Pineapple", "PoRO",
    "PoRO petit", "Queen Bee", "Rabbit Gate", "Ryuu M's", "Selfish", "Seven", "Shion",
    "Studio 1st", "Studio Houkiboshi", "studio LEO", "Studio Silver", "Studio SUNHAN", "T-REX",
    "TEATRO Nishi Tokyo Studio", "WHITE BEAR",
)

// Released year — the API filters with &year=<yyyy>. Newest first.
internal val YEAR_NAMES = arrayOf(
    "Any", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018", "2017", "2016",
    "2015", "2014", "2013", "2012", "2011", "2010",
)

internal val YEAR_SLUGS = arrayOf(
    "", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018", "2017", "2016",
    "2015", "2014", "2013", "2012", "2011", "2010",
)
