package com.stripe1.xmouse;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class ApplicationTest {
    @Test
    public void applicationContextHasExpectedPackage() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("com.stripe1.xmouse", context.getPackageName());
    }
}
