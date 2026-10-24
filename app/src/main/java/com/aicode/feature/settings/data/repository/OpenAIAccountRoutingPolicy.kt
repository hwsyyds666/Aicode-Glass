package com.aicode.feature.settings.data.repository

/**
 * ChatGPT 多账号的选择与路由约束。
 *
 * 本机可保存多个账号（每个账号对应一个独立 provider，用户在聊天里显式选哪个就用哪个）。
 * 路由分两层：
 *
 *  1. **显式选择优先**：调用方指定的账号永远第一顺位，能服务就用它；
 *  2. **能力回退**：显式账号服务不了该模型（目录里没有 / 档位不匹配 / 令牌失效）时，才按
 *     [orderFallbackCandidates] 的额度调度顺序找其它能服务的账号，并通过会话亲和记住这次选择。
 *
 * 界线（与"用多账号规避订阅套餐/速率限制"的区别）：**额度暂停、provider 停用、未连接**这类
 * 用户可见状态绝不静默切换账号，一律报错让用户处理；只有"这个账号根本跑不了这个模型"才回退。
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

    /** 能力回退候选：账号 id + 是否免费档。 */
    data class FallbackCandidate(val accountId: String, val isFreePlan: Boolean)

    /**
     * 能力回退顺序（对齐 cockpit-tools 的 capability fallback + 额度调度语义）：
     *
     *  - 付费专属模型：只要付费账号——免费账号打过去必然被上游拒绝，回退给它只是浪费一次调用；
     *  - 免费档模型：免费账号优先（把付费额度留给付费模型），其后才是付费账号。
     *
     * 稳定排序：同优先级保持调用方给出的原始顺序。调用方需自行排除「用户可见状态」的账号
     * （额度暂停 / provider 停用 / 未连接）——那些不是能力问题，必须让用户自己处理。
     */
    fun orderFallbackCandidates(candidates: List<FallbackCandidate>, modelPaidOnly: Boolean): List<String> =
        candidates
            .asSequence()
            .filter { candidate -> !modelPaidOnly || !candidate.isFreePlan }
            .sortedBy { candidate -> if (modelPaidOnly) 0 else if (candidate.isFreePlan) 0 else 1 }
            .map(FallbackCandidate::accountId)
            .toList()
}
