package com.stripe1.xmouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import com.stripe1.xmouse.IDoTool;
import com.stripe1.xmouse.session.InputGestureToken;

public class MyJSView extends View {
    /** Preserve this view's originating command proxy across screen recreation. */
    private final MainActivity activity;
    private InputGestureToken gestureToken;
    private IDoTool gestureTool;
    private Paint base;
    private Paint dead_zone;
    private Paint hat;

    private int cx;
    private int cy;

    private int hx;
    private int hy;

    private int cs;
    private int dzs;
    private int hs;

    private float reX;
    private float reY;

    private float sensitivity;


    public MyJSView(Context c) {
        this(c, (MainActivity) c);
    }

    /** Draw-only constructor for isolated layout fixtures; runtime controls use the activity-bound constructor. */
    MyJSView(Context c, MainActivity activity) {
        super(c);
        this.activity = activity;
        
        base = new Paint();
        base.setAntiAlias(true);
        base.setColor(c.getColor(R.color.ng_text_secondary));
        base.setStrokeWidth(3);
        base.setStyle(Paint.Style.STROKE);
        base.setStrokeJoin(Paint.Join.MITER);
        base.setStrokeCap(Paint.Cap.SQUARE);
        
        dead_zone = new Paint();
        dead_zone.setAntiAlias(true);
        dead_zone.setColor(c.getColor(R.color.ng_surface_soft));
        dead_zone.setStrokeWidth(1);
        dead_zone.setStyle(Paint.Style.FILL_AND_STROKE);
        dead_zone.setStrokeJoin(Paint.Join.MITER);
        dead_zone.setStrokeCap(Paint.Cap.SQUARE);
        
        hat = new Paint();
        hat.setAntiAlias(true);
        hat.setColor(c.getColor(R.color.ng_accent));
        hat.setStrokeWidth(3);
        hat.setStyle(Paint.Style.FILL_AND_STROKE);
        hat.setStrokeJoin(Paint.Join.MITER);
        hat.setStrokeCap(Paint.Cap.SQUARE);

        sensitivity=MainActivity.setting_js_sensitivity;
        setContentDescription(c.getString(R.string.ng_joystick_accessibility_hint));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx=getWidth()/2;
        cy=getHeight()/2;
        hx=cx;
        hy=cy;
        int m=Math.min(w,h);
        cs= (int) (m*MainActivity.setting_js_size);
        dzs= (int) (m*MainActivity.setting_js_dead_zone);
        hs=(cs+dzs)/2;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        //super.onDraw(canvas);

        canvas.drawRect(cx-cs/2, cy-cs/2, cx+cs/2, cy+cs/2,base);
        canvas.drawRect(cx-dzs/2, cy-dzs/2, cx+dzs/2, cy+dzs/2,dead_zone);
        canvas.drawRect(hx-hs/2, hy-hs/2, hx+hs/2, hy+hs/2,hat);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (activity == null) return false;
        // Keep joystick movement out of the short-screen controls' parent scroller.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (gestureToken != null) activity.cancelControlInputGesture(gestureToken);
                gestureToken = activity.beginPointerInputGesture();
                gestureTool = activity.createDoToolForInput(gestureToken);
                if (gestureToken == null || gestureTool == null) {
                    resetForInactiveSurface();
                    return true;
                }
                moveHatTo(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!hasLiveGesture()) return true;
                moveHatTo(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_UP:
                if (hasLiveGesture()) activity.finishControlInputGesture(gestureToken);
                resetForInactiveSurface();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (gestureToken != null) activity.cancelControlInputGesture(gestureToken);
                resetForInactiveSurface();
                break;
        }

        return true;
    }

    private boolean hasLiveGesture() {
        if (gestureToken != null && activity.isControlInputTokenActive(gestureToken)) return true;
        resetForInactiveSurface();
        return false;
    }

    private void moveHatTo(float eventX, float eventY) {
        int ex = (int) eventX;
        int ey = (int) eventY;
        int maxX = cx + cs / 2;
        int minX = cx - cs / 2;
        int maxY = cy + cs / 2;
        int minY = cy - cs / 2;
        int x = ex > maxX ? maxX : ex < minX ? minX : ex;
        int y = ey > maxY ? maxY : ey < minY ? minY : ey;
        int maxDeadX = cx + dzs / 2;
        int minDeadX = cx - dzs / 2;
        int maxDeadY = cy + dzs / 2;
        int minDeadY = cy - dzs / 2;
        boolean deadZone = x < maxDeadX && x > minDeadX && y < maxDeadY && y > minDeadY;
        hx = deadZone ? cx : x;
        hy = deadZone ? cy : y;
        jsmove();
        invalidate();
    }

    /** Cancellation/lifecycle loss requires a new DOWN and discards fractional old movement. */
    public void resetForInactiveSurface() {
        gestureToken = null;
        gestureTool = null;
        hx = cx;
        hy = cy;
        reX = 0;
        reY = 0;
        invalidate();
    }


    public void jsmove() {

        float dx=((float) hx-cx)/cs*MainActivity.setting_js_sensitivity*100;
        float dy=((float) hy-cy)/cs*MainActivity.setting_js_sensitivity*100;

        dx += reX;
        dy += reY;
        reX = dx - Math.round(dx);
        reY = dy - Math.round(dy);
        dx -= reX;
        dy -= reY;

        if (gestureTool != null) gestureTool.mouseMoveRelative(dx, dy);
    }
}
