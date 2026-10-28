package net.droopia.xmouse_ng

import android.app.Application
import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatDelegate
import net.droopia.xmouse_ng.session.AndroidSessionOwner
import net.droopia.xmouse_ng.session.SessionOwnerRegistry

/** Process owner for session state; contains no Activity, View, or notification UI reference. */
class XMouseApplication : Application() {
    val sessionOwners: SessionOwnerRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SessionOwnerRegistry { AndroidSessionOwner.create(applicationContext) }
    }

    override fun onCreate() {
        super.onCreate()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        AppearancePreferences.apply(AppearancePreferences.read(preferences))
    }
}
