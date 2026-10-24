package com.aicode.feature.settings.data.repository

/**
 * ChatGPT 多账号的选择与路由约束。
 *
 * 本机可保存多个账号（每个账号对应一个独立 provider，用户在聊天里显式选哪个就用哪个），
 * 但**绝不做自动轮换**：路由只走调用方显式指定的那个账号，账号之间不会互相顶替，也不会
 * 因为某个账号额度触顶就悄悄切到另一个。这是与"用多账号规避订阅套餐/速率限制"的界线。
 */
internal object OpenAIAccountSelectionPolicy {

    /** 本地档案数量上限：防止无限堆积失效账号；达到上限需先移除一个。 */
    const val MAX_ACCOUNTS = 10

    /**
     * 是否允许发起一次 OAuth 授权。
     *
     * @param reauthorizeAccountId 非空表示对既有账号重新授权（该账号必须已存在），空表示新增账号
     */
    fun canConnect(
        existingAccountIds: Set<String>,
        reauthorizeAccountId: String?,
        maxAccounts: Int = MAX_ACCOUNTS
    ): Boolean = when {
        reauthorizeAccountId == null -> existingAccountIds.size < maxAccounts
        reauthorizeAccountId !in existingAccountIds -> false
        else -> true
    }

    /**
     * 是否允许把请求路由到 [requestedAccountId]：只认调用方显式指定的账号。
     *
     * 存档里存在其它账号不影响路由（多账号并存），但**不会**回退到别的账号——指定账号
     * 不可用就是不可用，由调用方提示用户处理。
     */
    fun canRoute(existingAccountIds: Set<String>, requestedAccountId: String): Boolean =
        requestedAccountId in existingAccountIds
}
