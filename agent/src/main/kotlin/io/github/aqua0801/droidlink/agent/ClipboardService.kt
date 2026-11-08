package io.github.aqua0801.droidlink.agent

import android.content.ClipData
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private const val LISTENER_INTERFACE = "android.content.IOnPrimaryClipChangedListener"

/** Receives the framework's one-way clipboard change callback. */
private class ClipboardChangeBinder(
    private val onChanged: () -> Unit,
) : Binder() {
    init {
        attachInterface(null, LISTENER_INTERFACE)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.FIRST_CALL_TRANSACTION) {
            onChanged()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }
}

/**
 * Reads, writes and observes the system clipboard through the framework's clipboard service
 * by reflection. The agent runs as the shell user, which may access the clipboard while no
 * app is in the foreground. The service method shapes differ across Android releases, so
 * arguments are filled in from each method's parameter types.
 */
internal class ClipboardService private constructor(
    private val service: Any,
    private val getPrimaryClip: Method,
    private val setPrimaryClip: Method,
    private val newPlainText: Method,
    private val getItemCount: Method,
    private val getItemAt: Method,
    private val itemGetText: Method,
) {
    /** Returns the current text, or null if it is unavailable, sensitive or too large. */
    fun getText(): String? {
        val clip = runCatching {
            getPrimaryClip.invoke(service, *argsFor(getPrimaryClip, null))
        }.getOrNull() ?: return null

        if (isSensitive(clip)) return null

        val count = runCatching { getItemCount.invoke(clip) as Int }.getOrDefault(0)
        if (count <= 0) return null

        val item = runCatching { getItemAt.invoke(clip, 0) }.getOrNull() ?: return null
        val text =
            runCatching { itemGetText.invoke(item) as? CharSequence }.getOrNull() ?: return null
        if (text.length > MAX_TEXT_CHARS) return null

        return text.toString()
    }

    fun setText(text: String): Boolean {
        if (text.length > MAX_TEXT_CHARS) return false

        val clip = runCatching {
            newPlainText.invoke(null, "DroidLink", text)
        }.getOrNull() ?: return false

        return runCatching {
            setPrimaryClip.invoke(service, *argsFor(setPrimaryClip, clip))
        }.isSuccess
    }

    fun createListener(onChanged: () -> Unit): Any? = runCatching {
        val listenerClass = Class.forName(LISTENER_INTERFACE)
        val binder = ClipboardChangeBinder(onChanged)
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "dispatchPrimaryClipChanged" -> {
                    onChanged()
                    null
                }

                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ClipboardChangeListener"
                else -> null
            }
        }

        Proxy.newProxyInstance(
            ClipboardService::class.java.classLoader,
            arrayOf(listenerClass),
            handler,
        )
    }.getOrNull()

    fun addListener(listener: Any): Boolean =
        invokeListenerMethod("addPrimaryClipChangedListener", listener)

    fun removeListener(listener: Any): Boolean =
        invokeListenerMethod("removePrimaryClipChangedListener", listener)

    private fun invokeListenerMethod(name: String, listener: Any): Boolean = runCatching {
        val method = service.javaClass.methods.first { it.name == name }
        method.invoke(service, *argsFor(method, listener))
    }.isSuccess

    private fun isSensitive(clip: Any): Boolean = runCatching {
        (clip as ClipData).description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
    }.getOrDefault(false)

    /**
     * Fills arguments from the method's parameter types, which vary across releases:
     * the target object (ClipData or listener), the caller package in the first String slot
     * (the rest, e.g. attributionTag, left null), 0 for ids, false for flags.
     */
    private fun argsFor(method: Method, target: Any?): Array<Any?> {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        var packageSet = false

        for (i in types.indices) {
            val type = types[i]
            args[i] = when {
                target != null && type.isInstance(target) -> target
                type == String::class.java && !packageSet -> {
                    packageSet = true
                    CALLER
                }

                type == Int::class.javaPrimitiveType -> 0
                type == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }

        return args
    }

    companion object {
        private const val CALLER = "com.android.shell"
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val MAX_TEXT_CHARS = 200_000

        fun create(): ClipboardService? = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, "clipboard")
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val iBinder = Class.forName("android.os.IBinder")
            val service = stub.getMethod("asInterface", iBinder).invoke(null, binder)

            if (service == null) {
                null
            } else {
                val serviceClass = service.javaClass
                val clipData = Class.forName("android.content.ClipData")
                val item = Class.forName("android.content.ClipData\$Item")

                ClipboardService(
                    service = service,
                    getPrimaryClip = serviceClass.methods.first { it.name == "getPrimaryClip" },
                    setPrimaryClip = serviceClass.methods.first { it.name == "setPrimaryClip" },
                    newPlainText = clipData.getMethod(
                        "newPlainText",
                        CharSequence::class.java,
                        CharSequence::class.java,
                    ),
                    getItemCount = clipData.getMethod("getItemCount"),
                    getItemAt = clipData.getMethod("getItemAt", Int::class.javaPrimitiveType),
                    itemGetText = item.getMethod("getText"),
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}