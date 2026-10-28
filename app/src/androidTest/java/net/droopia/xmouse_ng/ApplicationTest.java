package net.droopia.xmouse_ng;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class ApplicationTest {
    @Test
    public void applicationContextHasExpectedPackage() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("net.droopia.xmouse_ng", context.getPackageName());
        assertEquals("net.droopia.xmouse_ng.test",
                InstrumentationRegistry.getInstrumentation().getContext().getPackageName());
    }
}
