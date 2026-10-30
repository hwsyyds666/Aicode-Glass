package com.aicode.feature.agent.domain.tool

import java.util.Collections
import javax.inject.Singleton

@Singleton
class ToolRegistry {
    // LinkedHashMap 保留注册顺序（内置工具固定顺序 + MCP 动态追加），避免工具增删后
    // 前缀顺序漂移打断隐式前缀缓存；synchronizedMap 保证并发注册/读取安全。
    private val tools = Collections.synchronizedMap(LinkedHashMap<String, AgentTool>())

    fun register(name: String, tool: AgentTool) {
        tools[name] = tool
    }

    fun getTool(name: String): AgentTool? {
        return tools[name]
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getToolNames(): Set<String> {
        return tools.keys.toSet()
    }

    /** 返回当前注册的完整工具集；是否允许调用由工具授权策略按会话模式和用户挡位裁决。 */
    fun getAvailableTools(): List<AgentTool> {
        synchronized(tools) {
            return tools.values.toList()
        }
    }

    fun hasTool(name: String): Boolean {
        return tools.containsKey(name)
    }
}
