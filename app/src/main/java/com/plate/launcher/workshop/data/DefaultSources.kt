package com.plate.launcher.workshop.data

object DefaultSources {

    fun list(): List<WorkshopSource> = listOf(

        // ── Wallpapers ─────────────────────────────────────────────────────

        WorkshopSource(
            searchUrlTemplate = "https://motionbgs.com/tag:%s/",
            browseUrlTemplate = "https://motionbgs.com/",
            displayName       = "MotionBGs",
            contentType       = "WALLPAPER",
            isCustom          = false
        ),

        WorkshopSource(
            searchUrlTemplate = "https://www.desktophut.com/search/%s",
            browseUrlTemplate = "https://www.desktophut.com/",
            displayName       = "DesktopHut",
            contentType       = "WALLPAPER",
            isCustom          = false
        ),

        // Resolved issue: unnecessary sites. LiveWall and WallpaperFlare were removed from the
        // default sources based on an explicit request — they used to be added
        // automatically for every new user despite not being needed. Existing users
        // who already had these two sources are handled in WorkshopDatabase
        // .onUpgrade(), where we added an explicit step to delete them (only if
        // isCustom=0, i.e. the user didn't manually add them with the exact same
        // name) during the database upgrade.
        //
        // Resolved issue: moved to direct WebView. MyLiveWallpapers was removed from the
        // default sources since the app now uses a direct WebView browsing flow
        // instead of the custom scraping/ad-bypass workaround this site needed.
        // See WorkshopDatabase.onUpgrade() for the matching cleanup step for
        // existing users.

        WorkshopSource(
            searchUrlTemplate = "https://moewalls.com/?s=%s",
            browseUrlTemplate = "https://moewalls.com/",
            displayName       = "MoeWalls",
            contentType       = "WALLPAPER",
            isCustom          = false
        ),

        // ── Fonts ──────────────────────────────────────────────────────────

        WorkshopSource(
            searchUrlTemplate = "https://fonts.google.com/?query=%s",
            browseUrlTemplate = "https://fonts.google.com/",
            displayName       = "Google Fonts",
            contentType       = "FONT",
            isCustom          = false
        ),

        WorkshopSource(
            searchUrlTemplate = "https://www.dafont.com/search.php?q=%s",
            browseUrlTemplate = "https://www.dafont.com/",
            displayName       = "DaFont",
            contentType       = "FONT",
            isCustom          = false
        ),

        WorkshopSource(
            searchUrlTemplate = "https://fontesk.com/?s=%s",
            browseUrlTemplate = "https://fontesk.com/",
            displayName       = "Fontesk",
            contentType       = "FONT",
            isCustom          = false
        )
    )
}
