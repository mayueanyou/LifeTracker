package io.github.mayueanyou.lifetracker.money

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import java.util.Currency
import java.util.Locale

/**
 * Which apps' notifications count as money. A bank counts without asking.
 * Any other app that sends something reading as a purchase is asked about
 * once -- by a notification and in Settings -- and counts only after a yes.
 */
object MoneyApps {
    private const val PREFS = "money"
    private const val KEY_ON = "apps_on"
    private const val KEY_OFF = "apps_off"
    private const val KEY_ASKED = "apps_asked"
    private const val KEY_VERSION = "version"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun set(context: Context, key: String) = prefs(context).getStringSet(key, emptySet())!!.toSet()

    fun on(context: Context) = set(context, KEY_ON)
    fun off(context: Context) = set(context, KEY_OFF)

    fun counts(context: Context, pkg: String): Boolean = counts(pkg, on(context), off(context))

    fun counts(pkg: String, on: Set<String>, off: Set<String>): Boolean = when {
        pkg in off -> false
        pkg in on -> true
        else -> KnownApps.role(pkg) == Role.BANK
    }

    /** Whether you have said yes or no -- or need not, it being a bank. */
    fun decided(context: Context, pkg: String) =
        pkg in on(context) || pkg in off(context) || KnownApps.role(pkg) == Role.BANK

    fun set(context: Context, pkg: String, count: Boolean) {
        val onSet = on(context).toMutableSet()
        val offSet = off(context).toMutableSet()
        if (count) { onSet += pkg; offSet -= pkg } else { onSet -= pkg; offSet += pkg }
        prefs(context).edit().putStringSet(KEY_ON, onSet).putStringSet(KEY_OFF, offSet).apply()
        changed(context)
    }

    fun asked(context: Context) = set(context, KEY_ASKED)

    fun markAsked(context: Context, pkgs: Collection<String>) {
        if (pkgs.isNotEmpty()) prefs(context).edit().putStringSet(KEY_ASKED, asked(context) + pkgs).apply()
    }

    /** True the first time only: an app is asked about once. */
    fun firstAsk(context: Context, pkg: String): Boolean {
        val asked = set(context, KEY_ASKED)
        if (pkg in asked) return false
        prefs(context).edit().putStringSet(KEY_ASKED, asked + pkg).apply()
        return true
    }

    /** Bumped by every decision, rule and verdict, so totals worked out earlier are worked out again. */
    fun version(context: Context) = prefs(context).getInt(KEY_VERSION, 0)
    fun changed(context: Context) { prefs(context).edit().putInt(KEY_VERSION, version(context) + 1).apply() }

    /**
     * [onChange] whenever money changes -- a purchase read, an app counted from
     * its notification -- until the returned stop is called. Android keeps the
     * listener only weakly; the stop holds it, so keep that.
     */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val p = prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_VERSION) onChange()
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        return { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Whether Android lets the app read notifications at all. */
    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NotificationReader::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** The currency typed costs are in, and that settles "$" and "¥": the phone's own. */
    fun homeCurrency(): String =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"

    /** An app's name: the known one, else what it calls itself. */
    fun name(pkg: String, label: String) = KnownApps.of(pkg)?.name ?: label
}
