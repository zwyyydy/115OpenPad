package com.open115.pad.data.media.scrape

/**
 * 一套 AI 提示词模板：一个名字 + 一段系统提示词。[builtin] 的内置模板只读、不可删；
 * 用户自建的可改可删。id 稳定（写进"某场景默认用哪个模板"的设置里），别改。
 */
data class AiPromptTemplate(
    val id: String,
    val name: String,
    val prompt: String,
    val builtin: Boolean,
)

/**
 * 内置模板库 + 各场景的默认模板 id。都是"洗文件名"这一类，但目标不同：
 * 刮削只要一个能搜的片名；重命名要能直接当文件名用（保扩展名、去噪声、或规范成"片名 (年份)"）。
 */
object AiPromptTemplates {
    const val SCRAPE_QUERY = "builtin_scrape_query"
    const val RENAME_CLEAN = "builtin_rename_clean"
    const val RENAME_NORM = "builtin_rename_norm"
    const val RENAME_EPISODE = "builtin_rename_episode"

    val BUILTINS: List<AiPromptTemplate> = listOf(
        AiPromptTemplate(
            id = SCRAPE_QUERY,
            name = "刮削 · 检索词",
            builtin = true,
            prompt = "你是影视文件名清洗助手。用户会发来一个视频文件名或目录名，里面可能混着分辨率(1080p/4K)、" +
                "编码(x264/x265/HEVC)、HDR、音轨(AC3/DTS)、发布组(-GROUP)、站点前缀、季集号(S01E02)等噪声。" +
                "请只输出适合拿去 TMDB 搜索的**片名**（电影或剧集的正式名称，保持它原本的语言），" +
                "若从名字能确定上映/首播年份，就在片名后加一个空格和 (YYYY)。" +
                "只输出这一行文字，不要引号、不要解释、不要任何多余内容。",
        ),
        AiPromptTemplate(
            id = RENAME_CLEAN,
            name = "重命名 · 去噪",
            builtin = true,
            prompt = "你在帮用户重命名视频文件。用户会发来一个原始文件名（可能带扩展名）。" +
                "请去掉其中的噪声——分辨率(1080p/2160p/4K)、编码(x264/x265/HEVC/AVC)、HDR/SDR、" +
                "音轨(AC3/DTS/Atmos)、来源(BluRay/Web-DL/HDTV/Rip)、发布组(-GROUP)、站点前缀、多余的小数点与下划线——" +
                "只保留**干净的片名**（若是剧集可保留 SxxExx 集号）。" +
                "**保留原来的扩展名**并接在末尾。只输出新的文件名这一行，不要引号、不要解释。",
        ),
        AiPromptTemplate(
            id = RENAME_NORM,
            name = "重命名 · 规范化",
            builtin = true,
            prompt = "你在帮用户把视频文件规范命名。用户会发来一个原始文件名。" +
                "请输出**「片名 (年份)」**这种规范格式：片名用正式名称（保持原语言），" +
                "若名字里能确定年份就带上 (YYYY)，确定不了就只给片名。去掉分辨率/编码/发布组等一切噪声。" +
                "**保留原来的扩展名**接在最外层（年份括号之后）。只输出这一行，不要引号、不要解释。",
        ),
        AiPromptTemplate(
            id = RENAME_EPISODE,
            name = "重命名 · 剧集",
            builtin = true,
            prompt = "你在帮用户规范剧集文件名。用户会发来一个分集文件名（可能含 SxxExx 或 第N集 等信息）。" +
                "请输出**「剧名 SxxExx 集标题」**格式：剧名 + 规范化的季集号(S01E02) + 能识别出的集标题；" +
                "识别不出的部分就省略。去掉分辨率/编码/发布组等噪声。**保留原来的扩展名**。只输出这一行，不要引号、不要解释。",
        ),
    )

    /** 内置模板的默认提示词文本（找不到 id 时的兜底） */
    fun builtinPrompt(id: String): String =
        BUILTINS.firstOrNull { it.id == id }?.prompt ?: BUILTINS.first().prompt

    /** 内置 + 自定义合并（自定义排后面） */
    fun all(custom: List<AiPromptTemplate>): List<AiPromptTemplate> = BUILTINS + custom
}
