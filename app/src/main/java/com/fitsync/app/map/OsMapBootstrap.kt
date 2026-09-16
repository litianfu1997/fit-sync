package com.fitsync.app.map

import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

/**
 * osmdroid 全局配置。必须在任何 MapView 创建之前调用 [initialize]。
 *
 * 两个关键点：
 * 1. 瓦片缓存放 cacheDir——可被系统在存储紧张时回收、不参与备份，且避免 API 26-28
 *    走 osmdroid 默认的外部存储路径（那样需要存储权限）。
 * 2. 必须设置 userAgentValue：OSM 的瓦片使用政策里 `TileSourceFactory.MAPNIK`
 *    带 FLAG_USER_AGENT_MEANINGFUL，UA 缺失或形似默认值时 osmdroid 会**直接拒绝下载**。
 */
class OsMapBootstrap(private val context: Context) {

    /** 幂等，只做配置赋值与建目录，可以在启动路径上安全调用。 */
    fun initialize() {
        val config = Configuration.getInstance()
        val base = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
        val tileCache = File(base, TILES_DIR_NAME).also { it.mkdirs() }

        // 必须在 load() 之前赋值：load() 取 pref 默认值时会顺带调用 getOsmdroidBasePath(context)，
        // 此时字段为空的话 osmdroid 会在 filesDir 下建一套默认目录（位置不对，且会留下空目录）。
        config.osmdroidBasePath = base
        config.osmdroidTileCache = tileCache

        // 再把路径写进 osmdroid 自己的 prefs：后续启动 load() 直接从 prefs 读，不再走默认路径推导
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_BASE_PATH, base.absolutePath)
            .putString(PREF_CACHE_PATH, tileCache.absolutePath)
            .commit()

        config.load(context, prefs)
        // load() 会把 UA 重置成包名；OSM 政策要求可识别且带版本的 UA，必须在其后覆盖
        config.userAgentValue = buildUserAgent()
        config.tileFileSystemCacheMaxBytes = MAX_TILE_CACHE_BYTES
        config.tileFileSystemCacheTrimBytes = TRIM_TILE_CACHE_BYTES
    }

    /** 清空瓦片缓存。调用方保证在 IO 线程执行。 */
    fun clearTileCache() {
        runCatching { File(context.cacheDir, CACHE_DIR_NAME).deleteRecursively() }
        // 目录被删掉后必须重建并重新指向，否则 osmdroid 会拒绝写入瓦片
        initialize()
    }

    private fun buildUserAgent(): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return if (version.isNullOrBlank()) context.packageName else "${context.packageName}/$version"
    }

    private companion object {
        const val PREFS_NAME = "osmdroid"
        const val CACHE_DIR_NAME = "osmdroid"
        const val TILES_DIR_NAME = "tiles"

        /** osmdroid DefaultConfigurationProvider 使用的 prefs 键，6.x 起稳定。 */
        const val PREF_BASE_PATH = "osmdroid.basePath"
        const val PREF_CACHE_PATH = "osmdroid.cachePath"

        /** OSM 政策要求克制抓取：缓存上限收窄到 100MB，超出后回收到 80MB。 */
        const val MAX_TILE_CACHE_BYTES = 100L * 1024 * 1024
        const val TRIM_TILE_CACHE_BYTES = 80L * 1024 * 1024
    }
}
