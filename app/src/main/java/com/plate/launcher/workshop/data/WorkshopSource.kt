package com.plate.launcher.workshop.data

/**
 * WorkshopSource — one row = one source/site in the Workshop.
 *
 * Active fields:
 *   searchUrlTemplate  → PrimaryKey + the search URL (contains %s)
 *   browseUrlTemplate  → the URL opened in the WebView when the Chip is selected
 *   displayName        → the Chip's name
 *   contentType        → WALLPAPER / FONT / PLUGIN / any custom category
 *   isEnabled          → shows/hides the source
 *   isCustom           → protects user sources from being deleted on update
 *   addedAt            → the time it was added, for ordering
 *
 * We removed the 7 dead fields (sourceFormat, forceWebView, pageUrlTemplate,
 *    itemContainerSelector, titleSelector, previewImgSelector, downloadLinkSelector)
 *    since the app switched fully to WebView and nothing reads them anymore.
 */
data class WorkshopSource(
    val searchUrlTemplate : String,
    val displayName       : String,
    val contentType       : String,
    val browseUrlTemplate : String?  = null,
    val isCustom          : Boolean  = false,
    val isEnabled         : Boolean  = true,
    val addedAt           : Long     = System.currentTimeMillis()
)
