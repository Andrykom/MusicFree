package com.mf.musicfree.plugin

class PluginManager {
    private val _plugins = mutableListOf<SourcePlugin>()
    val plugins: List<SourcePlugin> get() = _plugins.toList()

    fun register(plugin: SourcePlugin) {
        _plugins.add(plugin)
    }

    fun getPlugin(name: String): SourcePlugin? {
        return _plugins.find { it.name == name }
    }
}