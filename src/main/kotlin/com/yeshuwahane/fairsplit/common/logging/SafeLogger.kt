package com.yeshuwahane.fairsplit.common.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SafeLogger(private val delegate: Logger) {
    fun info(message: String, vararg args: Any?) {
        delegate.info(maskMessage(message), *args.map { maskArg(it) }.toTypedArray())
    }

    fun warn(message: String, vararg args: Any?) {
        delegate.warn(maskMessage(message), *args.map { maskArg(it) }.toTypedArray())
    }

    fun error(message: String, vararg args: Any?) {
        delegate.error(maskMessage(message), *args.map { maskArg(it) }.toTypedArray())
    }

    fun error(message: String, throwable: Throwable) {
        delegate.error(maskMessage(message), throwable)
    }

    companion object {
        fun getLogger(name: String): SafeLogger = SafeLogger(LoggerFactory.getLogger(name))
        fun getLogger(clazz: Class<*>): SafeLogger = SafeLogger(LoggerFactory.getLogger(clazz))

        fun maskMessage(msg: String): String {
            return msg
                .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9_.-]+"), "$1***MASKED***")
                .replace(Regex("(?i)(refresh_?token[=:]\\s*)[A-Za-z0-9_.-]+"), "$1***MASKED***")
        }

        fun maskArg(arg: Any?): Any? {
            if (arg == null) return null
            val str = arg.toString()
            return when {
                str.startsWith("eyJ") -> "***JWT_MASKED***"
                str.length == 64 && str.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> "${str.take(6)}...***"
                else -> str
            }
        }
    }
}
