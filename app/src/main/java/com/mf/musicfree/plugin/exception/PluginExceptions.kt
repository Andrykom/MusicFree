package com.mf.musicfree.plugin.exception

sealed class PluginException(message: String) : Exception(message) {
    class NoConnection : PluginException("Нет подключения к интернету")
    class ServerUnavailable(val url: String) : PluginException("Сервер недоступен")
    class ParseError : PluginException("Не удалось обработать ответ")
}