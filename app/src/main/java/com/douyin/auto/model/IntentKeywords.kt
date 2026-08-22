package com.douyin.auto.model

/**
 * 意向关键词配置数据模型
 *
 * 包含预设的意向关键词和广告过滤关键词列表
 */
object IntentKeywords {

    /** 预设的意向客户关键词 */
    val DEFAULT_INTENT_KEYWORDS: List<String> = listOf(
        // 购买意向类
        "怎么买", "多少钱", "链接", "怎么联系",
        "求购", "想要", "哪里买", "价格",
        "怎么卖", "怎么下单", "购买", "入手",
        "卖吗", "还有吗", "有货吗", "怎么拿",
        "来一个", "来一套", "搞一个", "整一个",
        // 咨询类
        "私我", "加我", "了解", "咨询",
        // 联系方式
        "微信", "VX", "vx", "加V",
        // 意向表达
        "好看想要", "喜欢", "想要同款",
        "这个好", "种草", "收藏了",
        // 交易类
        "包邮", "发哪里", "几天到",
        "货到付款", "支持验货", "正品吗"
    )

    /** 预设的广告/垃圾评论过滤关键词 */
    val DEFAULT_AD_KEYWORDS: List<String> = listOf(
        // 涨粉刷量类
        "互粉", "互关", "必回",
        "涨粉", "刷粉", "秒回",
        "有粉必回", "来粉", "诚信互粉",
        // 推广引流类
        "加微信", "免费领", "私信",
        "进群", "关注我", "主页",
        "点击链接", "下载", "注册送",
        // 垃圾广告类
        "兼职", "日结", "刷单",
        "代理", "招代理", "加盟",
        "致富", "躺赚", "日赚",
        // 色情引流类
        "约吗", "看片", "资源"
    )

    /** 预设的抖音评论列表相关的 resource-id 模式 */
    val DOUYIN_COMMENT_LIST_IDS: List<String> = listOf(
        "com.ss.android.ugc.aweme:id/comment_list",
        "com.ss.android.ugc.aweme:id/recycler_view",
        "com.ss.android.ugc.aweme:id/list"
    )

    /** 抖音评论项相关的 resource-id 模式 */
    val DOUYIN_COMMENT_ITEM_IDS: List<String> = listOf(
        "com.ss.android.ugc.aweme:id/comment_item",
        "com.ss.android.ugc.aweme:id/comment_item_layout",
        "com.ss.android.ugc.aweme:id/comment_root"
    )

    /** 抖音评论用户名相关的 resource-id 模式 */
    val DOUYIN_USERNAME_IDS: List<String> = listOf(
        "com.ss.android.ugc.aweme:id/user_name",
        "com.ss.android.ugc.aweme:id/nickname",
        "com.ss.android.ugc.aweme:id/author_name",
        "com.ss.android.ugc.aweme:id/title"
    )

    /** 抖音评论内容相关的 resource-id 模式 */
    val DOUYIN_CONTENT_IDS: List<String> = listOf(
        "com.ss.android.ugc.aweme:id/comment_text",
        "com.ss.android.ugc.aweme:id/content",
        "com.ss.android.ugc.aweme:id/desc",
        "com.ss.android.ugc.aweme:id/text"
    )

    /** 抖音关注按钮相关的 resource-id 模式 */
    val DOUYIN_FOLLOW_IDS: List<String> = listOf(
        "com.ss.android.ugc.aweme:id/follow_btn",
        "com.ss.android.ugc.aweme:id/btn_follow",
        "com.ss.android.ugc.aweme:id/follow_button"
    )

    /** 关注按钮可能显示的文本（已关注状态） */
    val FOLLOWED_TEXTS: List<String> = listOf(
        "已关注", "回关", "互相关注", "正在关注", "Following"
    )

    /** 关注按钮可能显示的文本（未关注状态） */
    val UNFOLLOW_TEXTS: List<String> = listOf(
        "关注", "Follow", "+ 关注"
    )

    // ---- 视频分析：点赞 / 收藏 按钮定位 ----

    /** 抖音视频页作者名 resource-id 候选（用于识别「当前是哪一个视频」） */
    val DOUYIN_VIDEO_AUTHOR_IDS: List<String> = listOf(
        "com.ss.android.ugc.aweme:id/author_name",
        "com.ss.android.ugc.aweme:id/user_name",
        "com.ss.android.ugc.aweme:id/nickname"
    )

    /** 抖音视频页文案 / 标题 resource-id 候选（用于识别「当前是哪一个视频」） */
    val DOUYIN_VIDEO_CAPTION_IDS: List<String> = listOf(
        "com.ss.android.ugc.aweme:id/desc",
        "com.ss.android.ugc.aweme:id/title"
    )

    /** 点赞按钮文本命中（含 contentDescription） */
    val LIKE_TEXTS: List<String> = listOf("点赞", "赞")

    /** 已点赞状态文本（用于跳过，避免重复点赞） */
    val LIKED_TEXTS: List<String> = listOf("已赞", "取消赞")

    /** 收藏按钮文本命中（含 contentDescription） */
    val COLLECT_TEXTS: List<String> = listOf("收藏")

    /** 已收藏状态文本（用于跳过，避免重复收藏） */
    val COLLECTED_TEXTS: List<String> = listOf("已收藏", "取消收藏")

    /** 直播间特征文本（命中即判定为直播，跳过视频分析以免误触）。
     *  以「点击进入直播间」预览卡片信号为主，兼顾已在直播间内的「直播中/正在直播」等。 */
    val LIVE_TEXTS: List<String> = listOf(
        "点击进入直播间", "进入直播间", "直播间",
        "直播中", "正在直播", "直播", "live"
    )

    /** 直播预览卡片强信号：仅当用户可见的「点击进入直播间」CTA 出现时才判定为直播预览，
     *  用于替代裸 "直播" 子串匹配，避免「直播广场」等文字误命中。 */
    val LIVE_CTA_TEXTS: List<String> = listOf("点击进入直播间")

    /** 直播间公屏聊天输入框特征（hint/描述命中即视为直播聊天框，非评论输入框）。
     *  用于防止把直播聊天框误判为评论输入框，导致评论内容被写进直播间并发送。 */
    val LIVE_INPUT_HINTS: List<String> = listOf(
        "主播", "公屏", "弹幕", "聊聊", "聊天"
    )

    /** 直播间界面强特征文本（评论面板内不会出现的直播间专属 UI 文案）。
     *  命中任一即判定当前处于直播间上下文。 */
    val LIVE_ROOM_TEXTS: List<String> = listOf(
        "粉丝团", "灯牌", "公屏"
    )

    /** 评论页面特征文本（用于判断当前是否在评论区） */
    val COMMENT_PAGE_TEXTS: List<String> = listOf(
        "评论", "共", "条评论", "回复"
    )

    /** 评论输入框 hint 文本（用于定位评论输入框） */
    val COMMENT_INPUT_HINTS: List<String> = listOf(
        "说点什么", "发表评论", "想说", "评论", "写评论", "输入评论"
    )

    /** 发送按钮文本（用于定位发送按钮） */
    val COMMENT_SEND_TEXTS: List<String> = listOf(
        "发送", "发布", "发送评论", "发表"
    )

    /** 自动评论内容池（正能量句子，随机抽取一条发送） */
    val POSITIVE_COMMENTS: List<String> = listOf(
        "拍得真好，看完心情都变好了",
        "太治愈了，感谢分享",
        "满满的正能量，收藏了",
        "内容很棒，越看越喜欢",
        "这才是生活的美好",
        "看完收获满满，加油",
        "用心记录生活的样子真美",
        "每天刷到这样的视频就很开心",
        "温暖又有力量，赞一个",
        "太喜欢这个氛围了，治愈满分"
    )

    /** 评论按钮排除文本（避免误匹配页面中的其他「评论」文字） */
    val COMMENT_BUTTON_EXCLUDE: List<String> = listOf(
        "评论列表", "评论内容", "评论数", "条评论", "评论中", "评论详情", "查看评论"
    )
}
