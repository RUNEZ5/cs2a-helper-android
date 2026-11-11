package ru.cs2a.helper.net

/** Яйца фермы (boxId 915..925, tier 0..10) — см. комментарий в background.js расширения. */
data class FarmEgg(val tier: Int, val boxId: Int, val slug: String, val name: String)

val FARM_EGGS = listOf(
    FarmEgg(0, 915, "farm-egg-grey", "Серое"),
    FarmEgg(1, 916, "farm-egg-lightblue", "Голубое"),
    FarmEgg(2, 917, "farm-egg-blue", "Синее"),
    FarmEgg(3, 918, "farm-egg-purple", "Фиолетовое"),
    FarmEgg(4, 919, "farm-egg-pink", "Розовое"),
    FarmEgg(5, 920, "farm-egg-red", "Красное"),
    FarmEgg(6, 921, "farm-egg-amber", "Янтарное"),
    FarmEgg(7, 922, "farm-egg-emerald", "Изумрудное"),
    FarmEgg(8, 923, "farm-egg-diamond", "Бриллиантовое"),
    FarmEgg(9, 924, "farm-egg-holo", "Голографическое"),
    FarmEgg(10, 925, "farm-egg-gold", "Золотое"),
)

/** Каталог розыгрышей сайта — перенесено из LOTTERIES в background.js расширения. */
data class Lottery(val id: Int, val name: String, val price: String, val period: String, val cycleMinutes: Long)

val LOTTERIES = listOf(
    Lottery(180, "Glock-18 | Umbral Rabbit (Minimal Wear)", "1$", "hour", 60),
    Lottery(198, "Charm | Lil' Dumplin'", "1$", "hour", 60),
    Lottery(181, "USP-S | Alpine Camo (Minimal Wear)", "1$", "hour", 60),
    Lottery(182, "SSG 08 | Rapid Transit (Field-Tested)", "1$", "hour", 60),
    Lottery(183, "AWP | Capillary (Minimal Wear)", "1$", "hour", 60),
    Lottery(184, "AWP | Crakow! (Minimal Wear)", "100$", "day", 1440),
    Lottery(185, "Glock-18 | Fully Tuned (Well-Worn)", "80$", "day", 1440),
    Lottery(186, "AK-47 | Inheritance (Battle-Scarred)", "50$", "day", 1440),
    Lottery(187, "USP-S | Sleeping Potion (Factory New)", "20$", "day", 1440),
    Lottery(188, "Dual Berettas | Sweet Little Angels (Minimal Wear)", "10$", "day", 1440),
    Lottery(189, "★ Kukri Knife | Urban Masked (Minimal Wear)", "100$", "week", 10080),
    Lottery(190, "AWP | Queen's Gambit (Field-Tested)", "100$", "week", 10080),
    Lottery(199, "★ Hydra Gloves | Case Hardened (Field-Tested)", "100$", "week", 10080),
    Lottery(191, "AK-47 | The Oligarch (Field-Tested)", "100$", "week", 10080),
    Lottery(192, "M4A1-S | Party Animal (Factory New)", "100$", "week", 10080),
    Lottery(193, "★ Nomad Knife | Doppler (Factory New)", "500$", "month", 43200),
    Lottery(194, "★ Skeleton Knife | Slaughter (Minimal Wear)", "500$", "month", 43200),
    Lottery(195, "★ Talon Knife | Case Hardened (Field-Tested)", "500$", "month", 43200),
    Lottery(196, "★ Stiletto Knife | Slaughter (Factory New)", "500$", "month", 43200),
    Lottery(197, "★ Driver Gloves | Imperial Plaid (Minimal Wear)", "500$", "month", 43200),
    Lottery(200, "★ Nomad Knife | Doppler (Factory New)", "500$", "old", 43200),
    Lottery(201, "★ Skeleton Knife | Ultraviolet (Field-Tested)", "300$", "old", 43200),
    Lottery(202, "★ Nomad Knife | Damascus Steel (Factory New)", "200$", "old", 10080),
    Lottery(203, "★ Paracord Knife | Ultraviolet (Battle-Scarred)", "100$", "old", 10080),
    Lottery(204, "USP-S | Cyrex (Battle-Scarred)", "5$", "old", 240),
)
