package com.stripe1.xmouse;

import android.view.View;

import androidx.activity.OnBackPressedDispatcher;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class DrawerBackCallbackTest {
    private final DrawerLayout drawer = mock(DrawerLayout.class);
    private final Runnable fallback = mock(Runnable.class);
    private final OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(fallback);

    @Test
    public void closedDrawerLeavesOrdinaryBackFallbackEnabled() {
        DrawerBackCallback callback = new DrawerBackCallback(drawer);
        dispatcher.addCallback(callback);

        assertFalse(callback.isEnabled());
        assertFalse(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();

        verify(fallback).run();
        verify(drawer, never()).closeDrawer(GravityCompat.START);
    }

    @Test
    public void initiallyOpenDrawerConsumesBackOnlyToCloseDrawer() {
        when(drawer.isDrawerOpen(GravityCompat.START)).thenReturn(true);
        DrawerBackCallback callback = new DrawerBackCallback(drawer);
        dispatcher.addCallback(callback);

        assertTrue(callback.isEnabled());
        dispatcher.onBackPressed();

        verify(drawer).closeDrawer(GravityCompat.START);
        verifyNoInteractions(fallback);
    }

    @Test
    public void drawerEventsEnableThenDisableCallbackAndRestoreFallback() {
        DrawerBackCallback callback = new DrawerBackCallback(drawer);
        dispatcher.addCallback(callback);
        DrawerLayout.DrawerListener listener = drawerListener();

        when(drawer.isDrawerOpen(GravityCompat.START)).thenReturn(true);
        listener.onDrawerOpened(mock(View.class));
        assertTrue(callback.isEnabled());
        dispatcher.onBackPressed();

        when(drawer.isDrawerOpen(GravityCompat.START)).thenReturn(false);
        listener.onDrawerClosed(mock(View.class));
        assertFalse(callback.isEnabled());
        dispatcher.onBackPressed();

        verify(drawer).closeDrawer(GravityCompat.START);
        verify(fallback).run();
    }

    @Test
    public void anotherDrawerDoesNotEnableStartDrawerCallback() {
        DrawerBackCallback callback = new DrawerBackCallback(drawer);
        dispatcher.addCallback(callback);

        drawerListener().onDrawerOpened(mock(View.class));

        assertFalse(callback.isEnabled());
        dispatcher.onBackPressed();
        verify(fallback).run();
        verify(drawer, never()).closeDrawer(GravityCompat.START);
    }

    @Test
    public void firstLayoutSynchronizesRestoredDrawerWithoutOpenEvent() {
        DrawerBackCallback callback = new DrawerBackCallback(drawer);
        dispatcher.addCallback(callback);
        ArgumentCaptor<View.OnLayoutChangeListener> listener =
                ArgumentCaptor.forClass(View.OnLayoutChangeListener.class);
        verify(drawer).addOnLayoutChangeListener(listener.capture());

        when(drawer.isDrawerOpen(GravityCompat.START)).thenReturn(true);
        listener.getValue().onLayoutChange(drawer, 0, 0, 100, 100, 0, 0, 0, 0);
        assertTrue(callback.isEnabled());
        dispatcher.onBackPressed();

        when(drawer.isDrawerOpen(GravityCompat.START)).thenReturn(false);
        listener.getValue().onLayoutChange(drawer, 0, 0, 200, 100, 0, 0, 100, 100);
        assertFalse(callback.isEnabled());
        dispatcher.onBackPressed();

        verify(drawer).closeDrawer(GravityCompat.START);
        verify(fallback).run();
    }

    @Test
    public void lifecycleRegistrationStopsAndRemovesDrawerInterception() {
        TestOwner owner = new TestOwner();
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        when(drawer.isDrawerOpen(GravityCompat.START)).thenReturn(true);
        dispatcher.addCallback(owner, new DrawerBackCallback(drawer));

        assertFalse(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        assertTrue(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        assertFalse(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        assertTrue(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        assertFalse(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();

        verify(drawer, times(2)).closeDrawer(GravityCompat.START);
        verify(fallback, times(3)).run();
    }

    private DrawerLayout.DrawerListener drawerListener() {
        ArgumentCaptor<DrawerLayout.DrawerListener> listener =
                ArgumentCaptor.forClass(DrawerLayout.DrawerListener.class);
        verify(drawer).addDrawerListener(listener.capture());
        return listener.getValue();
    }

    private static final class TestOwner implements LifecycleOwner {
        private final LifecycleRegistry lifecycle = LifecycleRegistry.createUnsafe(this);

        @Override
        public Lifecycle getLifecycle() {
            return lifecycle;
        }
    }
}
