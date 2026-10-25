package com.stripe1.xmouse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.view.MotionEventCompat;
import com.stripe1.xmouse.session.InputGestureToken;

/**
 * Created by bradley on 12/15/15.
 */
public class MyMouseView extends View {
    //TODO: two finger scroll, pinch to zoom

    /** Keep this view's input bound to its creating screen, even after another Activity attaches. */
    private final MainActivity activity;
    private InputGestureToken gestureToken;
    private IDoTool gestureTool;

    //private final float MINP = 0.25f;
    //private final float MAXP = 0.75f;
    private Paint mPaint;
    private Bitmap mBitmap;
    //private Canvas  mCanvas;
    private Path mPath;
    private Paint mBitmapPaint;
    private Paint stripPaint;
    private Paint stripTrackPaint;
    private Paint hintTitlePaint;
    private Paint hintDetailPaint;
    private Paint hintIconPaint;
    private final Path scrollStripPath = new Path();
    private final RectF scrollStripBounds = new RectF();
    private final float[] scrollStripRadii = new float[8];
    private float scrollStart; // physical width of the right-hand scroll strip
    final int CLICK = 3;
    private int w=0;
    private int h=0;
    Canvas canvas;
    PointF start = new PointF();
    long downStart = 0;
    long downEnd = 0;
    boolean dragging = false;
    boolean draggable = true;
    boolean touching = false;

    // coordinate rounding errors
    private float reX = 0;
    private float reY = 0;

    enum ClickType {

        Left_click,
        Right_click,
        Drag_Down,
        Drag_up,
        Zoom_in,
        Zoom_out
    }

    public MyMouseView(Context c) {
        this(c, (MainActivity) c);
    }

    /** Draw-capable fixture constructor; a null Activity intentionally makes input inert. */
    MyMouseView(Context c, MainActivity activity) {
        super(c);
        this.activity = activity;

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeCap(Paint.Cap.ROUND);


        //mEmboss = new EmbossMaskFilter(new float[] { 1, 1, 1 }, 0.4f, 6, 3.5f);

        //mBlur = new BlurMaskFilter(8, BlurMaskFilter.Blur.NORMAL);
        mPath = new Path();
        mBitmapPaint = new Paint(Paint.DITHER_FLAG);
        stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripPaint.setStyle(Paint.Style.FILL);
        stripPaint.setColor(c.getColor(R.color.ng_surface_soft));
        stripTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripTrackPaint.setColor(c.getColor(R.color.ng_border));
        stripTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        hintTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintTitlePaint.setColor(c.getColor(R.color.ng_text_secondary));
        hintTitlePaint.setTextAlign(Paint.Align.CENTER);
        hintTitlePaint.setTextSize(sp(14));
        hintDetailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintDetailPaint.setColor(c.getColor(R.color.ng_text_secondary));
        hintDetailPaint.setTextAlign(Paint.Align.CENTER);
        hintDetailPaint.setTextSize(sp(11));
        hintIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintIconPaint.setColor(c.getColor(R.color.ng_accent));
        hintIconPaint.setStyle(Paint.Style.STROKE);
        hintIconPaint.setStrokeCap(Paint.Cap.ROUND);
        hintIconPaint.setStrokeWidth(dp(1.5f));
        scrollStart = dp(48);
        yX = scrollStart;
        float cornerRadius = dp(18);
        scrollStripRadii[2] = cornerRadius;
        scrollStripRadii[3] = cornerRadius;
        scrollStripRadii[4] = cornerRadius;
        scrollStripRadii[5] = cornerRadius;
        setContentDescription(c.getString(R.string.ng_touchpad_accessibility_hint));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBitmap = w > 0 && h > 0 ? Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) : null;
        this.w=w;
        this.h=h;

        //Log.d("MyMouseView", String.valueOf(w + ',' + h));
        //mCanvas = new Canvas(mBitmap);
    }

    @Override
    protected void onDraw(Canvas c) {
        canvas = c;
        if (mBitmap != null) canvas.drawBitmap(mBitmap, 0, 0, mBitmapPaint);

        float stripLeft = Math.max(0f, w - scrollStart);
        scrollStripBounds.set(stripLeft, 0f, w, h);
        scrollStripPath.reset();
        scrollStripPath.addRoundRect(scrollStripBounds, scrollStripRadii, Path.Direction.CW);
        canvas.drawPath(scrollStripPath, stripPaint);
        stripTrackPaint.setColor(getResources().getColor(R.color.ng_border, getContext().getTheme()));
        stripTrackPaint.setStrokeWidth(dp(1));
        canvas.drawLine(stripLeft, 0f, stripLeft, h, stripTrackPaint);
        stripTrackPaint.setStrokeWidth(dp(3));
        float trackTop = Math.min(dp(64), h * 0.28f);
        float trackBottom = Math.max(trackTop, h - trackTop);
        if (trackBottom > trackTop) {
            float trackX = stripLeft + (w - stripLeft) / 2f;
            canvas.drawLine(trackX, trackTop, trackX, trackBottom, stripTrackPaint);
        }

        if (!touching) drawTouchpadHint(canvas, stripLeft, h);

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(getResources().getColor(R.color.ng_accent, getContext().getTheme()));
        mPaint.setStrokeWidth(dp(dragging ? 4 : 2));
        canvas.drawPath(mPath, mPaint);
        if (touching) {
            if (!scrolling && !zooming) canvas.drawCircle(mX, mY, dp(12), mPaint);
            if (zooming || scrolling) {
                if (curr != null) canvas.drawCircle(curr.x, curr.y, dp(12), mPaint);
                if (yX < scrollStart || twoFingerScroll) canvas.drawCircle(yX, yY, dp(12), mPaint);
            }
        }
    }

    private void drawTouchpadHint(Canvas canvas, float stripLeft, float height) {
        float centerX = stripLeft / 2f;
        float centerY = height / 2f;
        float iconWidth = dp(16);
        float iconHeight = dp(11);
        canvas.drawRoundRect(centerX - iconWidth / 2f, centerY - dp(36), centerX + iconWidth / 2f,
                centerY - dp(36) + iconHeight, dp(2), dp(2), hintIconPaint);
        canvas.drawLine(centerX, centerY - dp(36), centerX, centerY - dp(30), hintIconPaint);
        canvas.drawText(getResources().getString(R.string.ng_touchpad_move_hint), centerX,
                centerY + dp(4), hintTitlePaint);

        String instruction = getResources().getString(R.string.ng_touchpad_gesture_hint);
        float availableWidth = Math.max(0f, stripLeft - dp(16));
        if (hintDetailPaint.measureText(instruction) <= availableWidth) {
            canvas.drawText(instruction, centerX, centerY + dp(29), hintDetailPaint);
        } else {
            canvas.drawText(getResources().getString(R.string.ng_touchpad_tap_hint), centerX,
                    centerY + dp(25), hintDetailPaint);
            canvas.drawText(getResources().getString(R.string.ng_touchpad_scroll_hint), centerX,
                    centerY + dp(43), hintDetailPaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private boolean twoFingerScroll = false;
    private int zoomCounter = 0;
    private int zoomOverFlow = 10;
    private float mX=0, mY=0, yX=scrollStart, yY=0;
    private final float TOUCH_TOLERANCE = 4;
    private final float SCROLL_TOLERANCE = 20;
    private boolean scrolling = false;
    private double scaleFactor = 1;
    private boolean firstTouch= true;
    private boolean zooming =false;
    private double dist = 0;
    private PointF curr;

    private void touch_start(float x, float y) {
        touching=true;
        if((w-x)<=scrollStart){

            scrolling=true;
            x = w-scrollStart/2;
            mPaint.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));


        }else{
            scrolling=false;
            mPaint.setPathEffect(null);

        }

        mPath.reset();
        mPath.moveTo(x, y);
        mX = x;
        mY = y;

    }
    private void touch_move(float x, float y, boolean silent) {

        if (scrolling) {
            x = w - scrollStart / 2;
        }


        float rx = x - mX;
        float ry = y - mY;
        float dx = Math.abs(rx);
        float dy = Math.abs(ry);


        if (!zooming) {

            if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                if(!silent) mPath.quadTo(mX, mY, (x+mX)/2, (y+mY)/2);
                mX = x;
                mY = y;

                OnXMouseMoved(rx, ry, scrolling);
            }
        }else{
            //zooming

            mPath.reset();
            mPath.setLastPoint(curr.x,curr.y);
            if(!silent) mPath.lineTo(yX,yY);
            //Log.d("current points","curr.x="+curr.x+" curr.y="+curr.y+" yX="+yX+"yY="+yY);

        }

    }
    private void touch_up(boolean linger) {
        mPath.lineTo(mX, mY);
        // commit the path to our offscreen
        //mCanvas.drawPath(mPath, mPaint);
        // kill this so we don't double draw
        if(!linger) mPath.reset();
        scrolling=false;
        touching=false;
    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (activity == null) return false;
        // Short screens can scroll the surrounding controls, but a gesture begun on
        // the pointer belongs to this surface until UP/CANCEL, not to that ScrollView.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        float x = event.getX();
        float y = event.getY();
        curr = new PointF(event.getX(), event.getY());
        double newDist = 0;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (gestureToken != null) activity.cancelControlInputGesture(gestureToken);
                gestureToken = activity.beginPointerInputGesture();
                gestureTool = activity.createDoToolForInput(gestureToken);
                if (gestureToken == null || gestureTool == null) {
                    resetLocalState(true);
                    return true;
                }
                downStart = System.currentTimeMillis();
                if (downStart - downEnd < MainActivity.setting_mdelay) {
                    touch_move(x, y, true);
                    mPath.moveTo(x, y);
                    OnXMouseClicked(ClickType.Drag_Down);
                    dragging = true;
                } else {
                    start.set(curr);
                    touch_start(x, y);
                }
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!hasLiveGesture()) return true;

                int xDiff1 = (int) Math.abs(curr.x - start.x);
                int yDiff1 = (int) Math.abs(curr.y - start.y);

                if (event.getPointerCount() > 1) {

                    //more than 1 finger
                    yX = (float) MotionEventCompat.getX(event, 1);
                    yY = (float) MotionEventCompat.getY(event, 1);

                    newDist = Math.sqrt(Math.pow(yX - x, 2) + Math.pow(yY - y, 2));
                    if (firstTouch) {
                        dist = newDist;
                        firstTouch = false;
                    }

                    scaleFactor = (newDist - dist) / dist;
                    //Log.d("diffmon","newDist="+newDist+" scalefactor=" + scaleFactor);

                    if (Math.abs(scaleFactor) > 0.5) {
                        zooming = true;
                        zoomCounter++;
                        if (zoomCounter > zoomOverFlow) {

                            if (scaleFactor > 0) {
                                OnXMouseClicked(ClickType.Zoom_in);
                            } else {
                                OnXMouseClicked(ClickType.Zoom_out);
                            }
                            zoomCounter = 0;
                        }

                    } else if (!zooming) {
                        twoFingerScroll = true;
                        scrolling = true;

                    }
                }

                //Log.d("onTouchEvent",xDiff1+" "+yDiff1+" "+CLICK);
                long thisTime = System.currentTimeMillis() - downStart;
                if (xDiff1 < CLICK * 6 && yDiff1 < CLICK * 6) {
                    //Log.d("onTouchEvent","move inside click");
                    if (draggable && dragging == false && thisTime > MainActivity.setting_delay) {
                        //Log.d("onTouchEvent","start drag");
                        OnXMouseClicked(ClickType.Drag_Down);
                        dragging = true;
                    }


                } else {
                    //Log.d("onTouchEvent","move outside click");
                    draggable = false;
                }
                touch_move(x, y, false);

                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                if (!hasLiveGesture()) {
                    resetLocalState(true);
                    return true;
                }

                int xDiff = (int) Math.abs(curr.x - start.x);
                int yDiff = (int) Math.abs(curr.y - start.y);
                if (xDiff < CLICK && yDiff < CLICK) {
                    //Log.d("onTouchEvent", "up click");
                    if (scrolling) {
                        OnXMouseClicked(ClickType.Right_click);
                    } else {
                        OnXMouseClicked(ClickType.Left_click);
                    }

                } else {
                    //Log.d("onTouchEvent","up outside click");
                }
                if (dragging) {
                    dragging = false;
                    OnXMouseClicked(ClickType.Drag_up);
                    if (MainActivity.setting_mdelay > 0) {
                        downEnd = System.currentTimeMillis();
                    }
                } else {
                    downEnd = 0;
                }


                firstTouch = true;
                zooming = false;
                draggable = true;
                twoFingerScroll = false;
                touch_up(downEnd > 0);
                invalidate();

                // Legacy background-image SCP was disabled. Do not dispatch network work from this View.
                activity.finishControlInputGesture(gestureToken);
                gestureToken = null;
                gestureTool = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                if (gestureToken != null) activity.cancelControlInputGesture(gestureToken);
                resetLocalState(true);
                break;
        }
        return true;
    }

    private boolean hasLiveGesture() {
        if (gestureToken != null && activity.isControlInputTokenActive(gestureToken)) return true;
        resetLocalState(true);
        return false;
    }

    /** Lifecycle/focus loss resets local recognition without fabricating a click or release command. */
    public void resetForInactiveSurface() {
        resetLocalState(true);
    }

    private void resetLocalState(boolean clearDoubleTap) {
        gestureToken = null;
        gestureTool = null;
        downStart = 0;
        if (clearDoubleTap) downEnd = 0;
        dragging = false;
        draggable = true;
        touching = false;
        scrolling = false;
        zooming = false;
        firstTouch = true;
        twoFingerScroll = false;
        zoomCounter = 0;
        dist = 0;
        scaleFactor = 1;
        mX = 0;
        mY = 0;
        yX = scrollStart;
        yY = 0;
        reX = 0;
        reY = 0;
        mPath.reset();
        invalidate();
    }


    public void OnXMouseMoved(float dx, float dy, boolean scroll) {


        dx=dx*MainActivity.setting_sensitivity;
        dy=dy*MainActivity.setting_sensitivity;

        dx += reX;
        dy += reY;
        reX = dx - Math.round(dx);
        reY = dy - Math.round(dy);
        dx -= reX;
        dy -= reY;

        if(dx <0 || dy <0){
            if(scroll){
                if(MainActivity.setting_invert_scroll){
                    if (gestureTool != null) gestureTool.mouseWheelUp();
                }else {
                    if (gestureTool != null) gestureTool.mouseWheelDown();
                }
            } else {
                if (gestureTool != null) gestureTool.mouseMoveRelative(dx, dy);
            }

        }else{
            if(scroll){
                if(MainActivity.setting_invert_scroll) {
                    if (gestureTool != null) gestureTool.mouseWheelDown();
                }else{
                    if (gestureTool != null) gestureTool.mouseWheelUp();
                }
            } else {
                if (gestureTool != null) gestureTool.mouseMoveRelative(dx, dy);
            }
        }
    }


    public void OnXMouseClicked(ClickType type) {
        switch(type){
            case Left_click:
                if (gestureTool != null) gestureTool.mouseClick(IDoTool.MOUSE_LEFT);
                break;
            case Right_click:
                if (gestureTool != null) gestureTool.mouseClick(IDoTool.MOUSE_RIGHT);
                break;
            case Drag_Down:
                if (gestureTool != null) gestureTool.mouseDown(IDoTool.MOUSE_LEFT);
                break;
            case Drag_up:
                if (gestureTool != null) gestureTool.mouseUp(IDoTool.MOUSE_LEFT);
                break;
            case Zoom_in:
                if (gestureTool != null) gestureTool.key("Ctrl+plus");
                break;
            case Zoom_out:
                if (gestureTool != null) gestureTool.key("Ctrl+minus");
                break;
            default:
                break;
        }
    }
}
