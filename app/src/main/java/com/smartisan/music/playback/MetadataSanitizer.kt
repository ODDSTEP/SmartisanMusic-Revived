package com.smartisan.music.playback

/**
 * [1.4.16·F5] 列表元数据清洗（就地取材，零 I/O）。
 *
 * 背景（用户 2026-11-18 录屏实证）：列表显示的歌曲名/歌手/专辑错乱，而「编辑歌曲信息」
 * 弹层里读文件标签却是对的。根因是列表走 MediaStore 索引，而 MediaStore 对 WAV/DFF/APE
 * 等冷格式的标签极脏：
 *  - TITLE 被写成「歌手 - 歌名」整串；ARTIST 恒为「未知艺术家」；
 *  - ALBUM 被乱填成歌手名、文件名 hash（如 74d7c6ee2f1d648d223f1…）或文件夹名。
 * 之前 album 缺失去回填 artist（"$artist - $album"），于是出现「未知艺术家 - 蔡徐坤」。
 *
 * 本清洗只做**高置信度**修正，宁可少改不误伤：
 *  1) ALBUM 明显脏（等于歌手 / 纯 hash / 未知占位 / 含路径）→ 留空（用户明确：专辑缺失就空着，
 *     不要乱填成歌手）。
 *  2) ARTIST 缺失或为未知占位、且 TITLE 形如「甲 - 乙」两侧都非空 → 拆成 artist=甲, title=乙。
 *  3) 其余情况原样保留。
 *
 * 注意：本函数只清洗索引字段；用户手动编辑过的曲目由 TagOverrideStore 在更上游覆盖，
 * 不受影响。
 */
internal object MetadataSanitizer {

    private val unknownTokens =
        setOf(
            "<unknown>",
            "unknown",
            "未知艺术家",
            "未知 artist",
            "未知歌手",
            "unknown artist",
            "<unknown artist>",
        )

    data class Cleaned(val title: String?, val artist: String?, val album: String?)

    fun clean(title: String?, artist: String?, album: String?): Cleaned {
        var t = title?.trim()?.takeIf { it.isNotBlank() }
        var a = artist?.trim()?.takeIf { it.isNotBlank() && !it.isUnknown() }
        var al = album?.trim()?.takeIf { it.isNotBlank() && !it.isUnknown() }

        // 1) 专辑明显脏 → 留空，绝不乱填成歌手（用户明确要求）
        if (al != null && isDirtyAlbum(al, a, t)) al = null

        // 2) 歌手未知/缺失 + 标题含「甲 - 乙」→ 拆出歌手与歌名
        if (a == null && t != null) {
            splitArtistTitle(t)?.let { (artistPart, titlePart) ->
                a = artistPart
                t = titlePart
                // 拆完后若专辑原值与新歌手相同，也属于乱填，清掉
                if (al != null && al.equals(a, ignoreCase = true)) al = null
            }
        }

        return Cleaned(title = t, artist = a, album = al)
    }

    private fun String.isUnknown(): Boolean =
        unknownTokens.any { equals(it, ignoreCase = true) }

    /** 纯 hash（16 位以上十六进制）/ 含路径分隔 / 纯数字下划线串 → 不是合法专辑名 */
    private fun isHexHash(s: String): Boolean =
        s.length >= 16 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * [1.4.25·R64] 供「文件内嵌标签兜底」复用同一套判脏规则：空白 / 未知占位 / 脏值都不可用。
     * 保证兜底读出来的专辑与 MediaStore 那条走同一口径，不会把另一份脏数据救回来。
     */
    internal fun isDirtyAlbumName(album: String?, artist: String?, title: String?): Boolean {
        val al = album?.trim()?.takeIf { it.isNotBlank() && !it.isUnknown() } ?: return true
        return isDirtyAlbum(
            al,
            artist?.trim()?.takeIf { it.isNotBlank() && !it.isUnknown() },
            title?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun isDirtyAlbum(album: String, artist: String?, title: String?): Boolean {
        if (album.equals(artist, ignoreCase = true)) return true
        if (album.equals(title, ignoreCase = true)) return true
        if (isHexHash(album)) return true
        if (album.contains('/') || album.contains('\\')) return true
        // 专辑是纯文件名式 hash（含数字+字母、无空格、很长）
        if (album.length >= 18 && album.none { it.isWhitespace() } &&
            album.count { it.isDigit() } >= 6
        ) return true
        return false
    }

    /**
     * 把「歌手 - 歌名」拆成 (歌手, 歌名)。分隔符支持半角/全角连字符，两侧都要非空。
     * 分隔符左右允许有空格。为避免误伤标题里正常的连字符（如「Guns N' Roses - 歌」也确实是
     * 歌手-歌名），仅当**歌手未知**时才会调用本函数，风险可控。
     */
    private fun splitArtistTitle(raw: String): Pair<String, String>? {
        val separators = listOf(" - ", " – ", " — ", " － ", "—", "–")
        for (sep in separators) {
            val idx = raw.indexOf(sep)
            if (idx <= 0) continue
            val left = raw.substring(0, idx).trim()
            val right = raw.substring(idx + sep.length).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                // 第二段若还含分隔符（多级），只取第一处拆分，避免把歌名切断
                return left to right
            }
        }
        return null
    }
}
