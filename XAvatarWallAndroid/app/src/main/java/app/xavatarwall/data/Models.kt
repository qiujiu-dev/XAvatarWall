package app.xavatarwall.data

/** 采集到的一位粉丝 */
data class Fan(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String,
    val index: Int
)

/** 当前登录的账号 */
data class Me(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String
)

/** 一页粉丝数据 */
data class FollowersPage(
    val users: List<Fan>,
    val nextCursor: String?
)
