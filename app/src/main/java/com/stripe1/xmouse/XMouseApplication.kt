package com.stripe1.xmouse

import android.app.Application
import com.stripe1.xmouse.session.AndroidSessionOwner
import com.stripe1.xmouse.session.SessionOwnerRegistry

/** Process owner for session state; contains no Activity, View, or notification UI reference. */
class XMouseApplication : Application() {
    val sessionOwners: SessionOwnerRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SessionOwnerRegistry { AndroidSessionOwner.create(applicationContext) }
    }
}
