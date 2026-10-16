package com.youki.dex.livewallpaper.data

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * WallpaperConfigRepository — the exact same interface as before, no external code changes.
 *
 * FIX (real live updates): previously observeXxx() used to pass the DAO's
 * Flow directly, and that Flow only emitted its value once at first
 * subscription, then stayed open forever with no further notification — so
 * any settings edit (changing the video, saving a new wallpaper...) only
 * reflected on the actually running service after a Force Stop (since that
 * was the only thing that rebuilt the Engine from scratch and requested the
 * current value again).
 *
 * The fix: [changePulse] is an internal pulse (SharedFlow) that fires
 * automatically after any write (save/delete/setActive), and every
 * observeXxx() immediately re-queries the disk on every pulse via
 * flatMapLatest — so any new save becomes visible instantly on the actually
 * running live wallpaper, with no need to restart the app at all.
 */
class WallpaperConfigRepository private constructor(context: Context) {

    private val dao = WallpaperConfigDao(context)

    private val changePulse = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ).apply { tryEmit(Unit) } // an initial pulse so the first subscriber gets the current value immediately

    private fun notifyChanged() {
        changePulse.tryEmit(Unit)
    }

    suspend fun get(videoUri: String): WallpaperConfig =
        dao.get(videoUri) ?: WallpaperConfig.default(videoUri)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(videoUri: String): Flow<WallpaperConfig?> =
        changePulse.flatMapLatest { flow { emit(dao.get(videoUri)) } }

    suspend fun getActive(): WallpaperConfig? = dao.getActive()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActive(): Flow<WallpaperConfig?> =
        changePulse.flatMapLatest { flow { emit(dao.getActive()) } }

    suspend fun getActiveOrFallback(): WallpaperConfig? {
        dao.getActive()?.let { return it }
        val fallback = dao.getMostRecent() ?: return null
        setActive(fallback.videoUri)
        return fallback.copy(isActive = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<WallpaperConfig>> =
        changePulse.flatMapLatest { flow { emit(dao.getAll()) } }

    suspend fun save(config: WallpaperConfig) {
        dao.upsert(config)
        notifyChanged()
    }

    suspend fun delete(videoUri: String) {
        dao.delete(videoUri)
        notifyChanged()
    }

    suspend fun setActive(videoUri: String) {
        dao.setActive(videoUri)
        notifyChanged()
    }

    companion object {
        @Volatile private var instance: WallpaperConfigRepository? = null

        fun get(context: Context): WallpaperConfigRepository =
            instance ?: synchronized(this) {
                instance ?: WallpaperConfigRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
