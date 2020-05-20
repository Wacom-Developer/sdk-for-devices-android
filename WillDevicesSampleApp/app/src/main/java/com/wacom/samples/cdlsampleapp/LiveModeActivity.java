package com.wacom.samples.cdlsampleapp;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import com.wacom.cdl.callbacks.LiveModeCallback;
import com.wacom.cdl.callbacks.OnCompleteCallback;
import com.wacom.cdl.exceptions.InvalidOperationException;
import com.wacom.cdl.InkDevice;
import com.wacom.cdl.deviceservices.DeviceServiceType;
import com.wacom.cdl.deviceservices.LiveModeDeviceService;
import com.wacom.cdlcore.InkStroke;
import com.wacom.ink.path.PathBuilder;
import com.wacom.ink.path.PathChunk;
import com.wacom.ink.path.PathUtils;
import com.wacom.ink.path.SpeedPathBuilder;
import com.wacom.ink.rasterization.BlendMode;
import com.wacom.ink.rasterization.InkCanvas;
import com.wacom.ink.rasterization.Layer;
import com.wacom.ink.rasterization.SolidColorBrush;
import com.wacom.ink.rasterization.StrokePaint;
import com.wacom.ink.rasterization.StrokeRenderer;
import com.wacom.ink.rendering.EGLRenderingContext;
import com.wacom.ink.smooth.MultiChannelSmoothener;
import com.wacom.samples.cdlsampleapp.model.Stroke;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

@SuppressWarnings("ALL")
public class LiveModeActivity extends AppCompatActivity {

    private int canvasWidth = 0;
    private int canvasHeight = 0;

    //region Fields
    // WILL for Devices
    private InkDevice inkDevice;
    private Timer timer = new Timer();
    private TimerTask hideHoverPointTask;
    private ImageView hoverPoint;
    private LiveModeDeviceService liveModeDeviceService;

    // WILL for Ink
    private InkCanvas inkCanvas;
    private Layer strokesLayer;
    private Layer currentFrameLayer;
    private Layer viewLayer;
    private StrokePaint paint;
    private SolidColorBrush brush;
    private MultiChannelSmoothener smoothener;
    private StrokeRenderer strokeRenderer;
    private SpeedPathBuilder pathBuilder;
    private int pathStride;
    private LinkedList<Stroke> strokeList = new LinkedList<Stroke>();

    long time = 0;
    LinkedList<PathChunk> chunks = new LinkedList<>();
    LinkedList<PathUtils.Phase> phases = new LinkedList<>();

    //Views
    private SurfaceView surfaceView;
    //endregion Fields

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_real_time);

        final MyApplication app = (MyApplication) getApplication();
        app.subscribeForEvents(LiveModeActivity.this);
        inkDevice = app.getInkDevice();

        liveModeDeviceService = (LiveModeDeviceService) inkDevice.getDeviceService(DeviceServiceType.LIVE_MODE_DEVICE_SERVICE);

        //region Setup SurfaceView
        surfaceView = findViewById(R.id.surfaceView);
        hoverPoint = (ImageView) findViewById(R.id.hoverPoint);

        pathBuilder = new SpeedPathBuilder();
        pathBuilder.setNormalizationConfig(100.0f, 4000.0f);
        pathBuilder.setMovementThreshold(2.0f);
        pathBuilder.setPropertyConfig(PathBuilder.PropertyName.Width, 1f, 5f, Float.NaN, Float.NaN, PathBuilder.PropertyFunction.Power, 1.0f, false);
        pathStride = pathBuilder.getStride();

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (inkCanvas!=null && !inkCanvas.isDisposed()){
                    releaseResources();
                }

                canvasWidth = width;
                canvasHeight = height;

                float wScale = width / (float) app.noteWidth;
                float hScale = height / (float) app.noteHeight;

                float sf = wScale < hScale ? wScale : hScale;

                Matrix matrix = new Matrix();
                matrix.postScale(sf, sf);

                liveModeDeviceService.setTransformationMatrix(matrix);

                inkCanvas = InkCanvas.create(holder, new EGLRenderingContext.EGLConfiguration());

                viewLayer = inkCanvas.createViewLayer(width, height);
                strokesLayer = inkCanvas.createLayer(width, height);
                currentFrameLayer = inkCanvas.createLayer(width, height);

                inkCanvas.clearLayer(currentFrameLayer, Color.WHITE);

                brush = new SolidColorBrush();

                paint = new StrokePaint();
                paint.setStrokeBrush(brush);	// Solid color brush.
                paint.setColor(Color.BLUE);		// Blue color.
                paint.setWidth(Float.NaN);		// Expected variable width.

                smoothener = new MultiChannelSmoothener(pathStride);
                smoothener.enableChannel(2);

                strokeRenderer = new StrokeRenderer(inkCanvas, paint, pathStride, width, height);

                drawStrokes();
                renderView();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                releaseResources();
            }
        });

        //region LiveMode Logic
        try {
            liveModeDeviceService.enable(liveModeCallback, null);
        } catch (InvalidOperationException e) {
            e.printStackTrace();
        }

        //endregion LiveMode Logic
    }

    private void resetHoverPointTimer(){
        if (hideHoverPointTask != null){
            hideHoverPointTask.cancel();
        }

        hideHoverPointTask = new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hoverPoint.setVisibility(View.GONE);
                    }
                });
            }
        };

        timer.schedule(hideHoverPointTask, 50);
    }

    private LiveModeCallback liveModeCallback =  new LiveModeCallback() {
        @Override
        public void onStrokeStart(PathChunk pathChunk) {
            hoverPoint.setVisibility(View.GONE);
            handleLiveModeEvent(pathChunk, PathUtils.Phase.BEGIN);

        }

        @Override
        public void onStrokeMove(PathChunk pathChunk) {
            handleLiveModeEvent(pathChunk, PathUtils.Phase.MOVE);
        }

        @Override
        public void onStrokeEnd(PathChunk pathChunk, InkStroke inkStroke) {
            handleLiveModeEvent(pathChunk, PathUtils.Phase.END);
        }

        @Override
        public void onHover(int x, int y) {
            hoverPoint.setVisibility(View.VISIBLE);
            hoverPoint.setX(x + hoverPoint.getWidth() / 2);
            hoverPoint.setY(y + hoverPoint.getHeight() / 2);
            resetHoverPointTimer();
        }

        @Override
        public void onNewLayerCreated() {
            Toast.makeText(LiveModeActivity.this, "New layer created", Toast.LENGTH_SHORT).show();
        }
    };

    private void handleLiveModeEvent(PathChunk chunk, PathUtils.Phase phase) {
        long now = System.currentTimeMillis();

        chunks.add(chunk.clone());
        phases.add(phase);

        if (time + 16 < now) { // Render in 60 Hz
            for (int i = 0; i < chunks.size(); i++) {
                drawPathChunk(chunks.get(i), phases.get(i));
            }
            renderView();
            chunks.clear();
            phases.clear();
            time = System.currentTimeMillis();
        }

        // we save the stroke, in case we need to repaint the canvas
        if (phase == PathUtils.Phase.END) {
            Stroke stroke = new Stroke(chunk.totalPointsSize());
            stroke.copyPoints(chunk.points(), 0, chunk.totalPointsSize());
            stroke.setStride(chunk.stride());
            stroke.setWidth(Float.NaN);
            stroke.setColor(Color.BLUE);
            stroke.setInterval(0.0f, 1.0f);
            stroke.setBlendMode(BlendMode.BLENDMODE_NORMAL);
            strokeList.add(stroke);
        }
    }

    @Override
    public void onBackPressed()
    {
        Log.d("lifecycle", "onBackPressed");

        //save the bitmap when going back
        saveBitmap();

        try {
            liveModeDeviceService.disable(new OnCompleteCallback() {
                @Override
                public void onComplete() {
                    LiveModeActivity.this.finish();
                }
            });
        } catch (InvalidOperationException e) {
            e.printStackTrace();
        }
    }

    //TODO rename region
    //region WILL for Ink Helpers
    private void drawPathChunk(PathChunk pathChunk, PathUtils.Phase phase) {
        strokeRenderer.drawPath(pathChunk, phase == PathUtils.Phase.END);

        if (phase == PathUtils.Phase.BEGIN || phase == PathUtils.Phase.MOVE) {
            inkCanvas.setTarget(currentFrameLayer, strokeRenderer.getStrokeUpdatedArea());
            inkCanvas.clearColor(Color.WHITE);
            inkCanvas.drawLayer(strokesLayer, BlendMode.BLENDMODE_NORMAL);
            strokeRenderer.blendStrokeUpdatedArea(currentFrameLayer, BlendMode.BLENDMODE_NORMAL);
        } else {
            strokeRenderer.blendStroke(strokesLayer, BlendMode.BLENDMODE_NORMAL);
            inkCanvas.setTarget(currentFrameLayer);
            inkCanvas.clearColor(Color.WHITE);
            inkCanvas.drawLayer(strokesLayer, BlendMode.BLENDMODE_NORMAL);
        }
    }

    private void drawStrokes() {
        inkCanvas.setTarget(strokesLayer);
        inkCanvas.clearColor();

        for (Stroke stroke: strokeList){
            paint.setColor(stroke.getColor());
            strokeRenderer.setStrokePaint(paint);
            strokeRenderer.drawPoints(stroke.getPoints(), 0, stroke.getSize(), stroke.getStartValue(), stroke.getEndValue(), true);
            strokeRenderer.blendStroke(strokesLayer, stroke.getBlendMode());
        }

        inkCanvas.setTarget(currentFrameLayer);
        inkCanvas.clearColor(Color.WHITE);
        inkCanvas.drawLayer(strokesLayer, BlendMode.BLENDMODE_NORMAL);
    }

    private void renderView() {
        inkCanvas.setTarget(viewLayer);
        // Copy the current frame layer in the view layer to present it on the screen.
        inkCanvas.drawLayer(currentFrameLayer, BlendMode.BLENDMODE_OVERWRITE);
        inkCanvas.invalidate();
    }

    private void releaseResources(){
        strokeRenderer.dispose();
        inkCanvas.dispose();
    }
    //endregion WILL for Ink Helpers

    //region Export
    private void saveBitmap(){
        if (canvasWidth == 0 || canvasHeight == 0) return;

        Bitmap bmp = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
        inkCanvas.readPixels(currentFrameLayer, bmp, 0, 0, 0,0, canvasWidth, canvasHeight);

        String file = Environment.getExternalStorageDirectory().toString() + "/will-export.png";

        FileOutputStream out = null;

        try {
            out = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }
    //endregion Export

}
