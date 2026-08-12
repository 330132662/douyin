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

    /** 评论页面特征文本（用于判断当前是否在评论区） */
    val COMMENT_PAGE_TEXTS: List<String> = listOf(
        "评论", "共", "条评论", "回复"
    )
}
