package com.aicode.feature.settings.data.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChatGPT 账号的会话亲和（软绑定）——照搬 cockpit-tools 的 `session_affinity` 默认机制。
 *
 * 作用：把 `(调用方, 模型, 会话/线程身份)` 绑定到具体账号，同一会话里的同一模型在 TTL 内
 * 始终路由到同一个账号。价值：
 *  - 上游续传/缓存状态按账号隔离，不会出现「同一会话前半段用 A 号、后半段用 B 号」的上下文割裂；
 *  - 显式选中的账号失效（令牌过期/被删/档位变更）时，先看绑定记录里同会话用过谁，再考虑能力回退，
 *    避免每次调用都重新挑账号造成额度碎片化；
 *  - 额度调度：免费档模型优先绑定免费账号，付费专属模型只绑定付费账号（由路由层保证）。
 *
 * 与 cockpit 的对应关系：
 *  - `remember` = `native_soft_binding_key` 绑定写入；
 *  - TTL 默认 1 小时（cockpit `DEFAULT_SESSION_AFFINITY_TTL_MS`），下限 60 秒；
 *  - `forget` = `native_soft_failure_unbinds`：凭据类失败（401 / invalid_grant / 模型不可用）
 *    由调用方软解绑，下一次请求可改路由到其它能服务的账号；
 *  - `forgetAccount`：账号被移除时清掉它的全部绑定，防止路由到不存在的账号。
 *
 * 纯内存态：进程重启后重新学习，不影响正确性（显式选择仍优先于绑定）。
 */
@Singleton
class ChatGptAccountAffinity @Inject constructor() {

    private data class Binding(val accountId: String, val updatedAtMs: Long)

    private val bindings = ConcurrentHashMap<String, Binding>()

    /**
     * 绑定键：把三元组编码成稳定且无歧义的字符串（长度前缀防分隔符碰撞——
     * 任一方不同即不同绑定：换会话、换模型都重新选账号）。
     */
    fun key(caller: String, model: String, identity: String): String =
        "${caller.length}:$caller|${model.length}:$model|${identity.length}:$identity"

    /** 记录一次成功路由：把 (caller, model, identity) 绑到 [accountId]。 */
    fun remember(caller: String, model: String, identity: String, accountId: String, nowMs: Long = System.currentTimeMillis()) {
        bindings[key(caller, model, identity)] = Binding(accountId, nowMs)
    }

    /**
     * 取 TTL 内有效的绑定账号；过期绑定顺手删除（cockpit 的 `native_prune_soft_bindings`）。
     * 没有绑定或已过期返回 null，由路由层按显式选择 → 能力回退重新决定。
     */
    fun preferredAccountId(
        caller: String,
        model: String,
        identity: String,
        ttlMs: Long = SESSION_AFFINITY_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val bindingKey = key(caller, model, identity)
        val binding = bindings[bindingKey] ?: return null
        if (nowMs - binding.updatedAtMs > ttlMs.coerceAtLeast(TTL_FLOOR_MS)) {
            bindings.remove(bindingKey)
            return null
        }
        return binding.accountId
    }

    /** 软解绑：凭据类失败后调用，下一次请求可改路由到其它账号。 */
    fun forget(caller: String, model: String, identity: String) {
        bindings.remove(key(caller, model, identity))
    }

    /** 账号被移除/重新授权后清掉它的全部绑定。 */
    fun forgetAccount(accountId: String) {
        bindings.entries.removeAll { it.value.accountId == accountId }
    }

    companion object {
        /** cockpit 默认会话亲和 TTL：1 小时。 */
        const val SESSION_AFFINITY_TTL_MS = 60 * 60 * 1000L
        /** TTL 下限，与 cockpit 的 clamp 一致，防止配置成 0 导致绑定永不生效/永不过期。 */
        const val TTL_FLOOR_MS = 60_000L
    }
}
