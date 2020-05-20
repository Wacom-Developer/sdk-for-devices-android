package com.wacom.samples.cdlsampleapp;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.os.Environment;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.wacom.cdl.callbacks.FileTransferCallback;
import com.wacom.cdl.callbacks.OnCompleteCallback;
import com.wacom.cdl.exceptions.InvalidOperationException;
import com.wacom.cdl.InkDevice;
import com.wacom.cdl.deviceservices.DeviceServiceType;
import com.wacom.cdl.deviceservices.FileTransferDeviceService;
import com.wacom.cdlcore.InkDocument;
import com.wacom.cdlcore.InkGroup;
import com.wacom.cdlcore.InkNode;
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
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

public class FileTransferActivity extends AppCompatActivity {

    //region Fields
    private int canvasWidth = 0;
    private int canvasHeight = 0;
    private InkDevice inkDevice;

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
    private LinkedList<Stroke> strokesList = new LinkedList<Stroke>();

    private ArrayList<String> filesTitles = new ArrayList<>();
    private ArrayAdapter<String> filesAdapter;
    private int filesCount = 0;

    private boolean continuousTransfer = true;
    private ArrayList<InkDocument> documents = new ArrayList<>();

    private ListView filesListView;

    private FileTransferDeviceService fileTransferDeviceService;
    //endregion Fields

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_transfer);

        final MyApplication app = (MyApplication) getApplication();
        app.subscribeForEvents(FileTransferActivity.this);
        inkDevice = app.getInkDevice();

        fileTransferDeviceService = (FileTransferDeviceService) inkDevice.getDeviceService(DeviceServiceType.FILE_TRANSFER_DEVICE_SERVICE);


        //region willInk setup
        pathBuilder = new SpeedPathBuilder();
        pathBuilder.setNormalizationConfig(100.0f, 4000.0f);
        pathBuilder.setMovementThreshold(2.0f);
        pathBuilder.setPropertyConfig(PathBuilder.PropertyName.Width, 5f, 10f, Float.NaN, Float.NaN, PathBuilder.PropertyFunction.Power, 1.0f, false);
        pathStride = pathBuilder.getStride();
        //endregion willInk setup

        //Setup SurfaceView
        final SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {

            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

                if (inkCanvas!=null && !inkCanvas.isDisposed()){
                    releaseResources();
                }

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

                canvasWidth = width;
                canvasHeight = height;

                renderView();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

            }
        });


        //region ListView Logic
        filesAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                filesTitles);

        filesListView = (ListView) findViewById(R.id.filesList);
        filesListView.setAdapter(filesAdapter);

        filesListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {

                InkDocument inkDocument = documents.get(position);
                extractStrokes(inkDocument);
                drawStrokes();
                renderView();
            }
        });
        //endregion ListView Logic

        //region FileTransfer Logic
        try {
            fileTransferDeviceService.enable(continuousTransfer, fileTransferCallback, null);
        } catch (InvalidOperationException e) {
            e.printStackTrace();
        }

        final RelativeLayout layout = (RelativeLayout) findViewById(R.id.activity_file_transfer);
        final ViewTreeObserver observer= layout.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        float wScale = surfaceView.getWidth() / (float) app.noteWidth;
                        float hScale = surfaceView.getHeight() / (float) app.noteHeight;

                        float sf = wScale < hScale ? wScale : hScale;

                        Matrix matrix = new Matrix();
                        matrix.postScale(sf, sf);

                        fileTransferDeviceService.setTransformationMatrix(matrix);
                    }
                });
        //endregion FileTransfer Logic

        //region FloatingActionButton
        FloatingActionButton fab = (FloatingActionButton)findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(FileTransferActivity.this, "Saving to PNG",Toast.LENGTH_SHORT).show();
                saveBitmap();
            }
        });
        //endregion FloatingActionButton
    }

    private FileTransferCallback fileTransferCallback = new FileTransferCallback() {
        @Override
        public void onFileTransferStarted(int filesCount) {

        }

        @Override
        public void onFileTransferComplete() {

        }

        @Override
        public void onFileTransferFailed() {

        }

        @Override
        public boolean onFileTransferred(InkDocument inkDocument, int fileIndex, int remainingFilesCount) {
            strokesList.clear();
            filesCount++;

            int layersCount = inkDocument.getRoot().size();
            int totalStrokes = inkDocument.getStrokesCount();

            String message = "File " + filesCount + " (" + totalStrokes + " strokes in " + layersCount + " layers)";

            filesTitles.add(message);
            filesAdapter.notifyDataSetChanged();

            documents.add(inkDocument);

            int position = documents.size() - 1;

            filesListView.requestFocusFromTouch();
            filesListView.setSelection(position);

            extractStrokes(inkDocument);
            drawStrokes();
            renderView();

            return true;
        }
    };

    private void extractStrokes(InkDocument inkDocument) {
        strokesList.clear();
        Iterator<InkNode> iterator = inkDocument.iterator();

        while (iterator.hasNext()){
            InkNode node = iterator.next();
            if (node instanceof InkStroke) {
                InkStroke inkStroke = (InkStroke) node;
                Stroke stroke = new Stroke();
                stroke.copyPoints(inkStroke.getPoints(), 0, inkStroke.getSize());
                stroke.setStride(inkStroke.getStride());
                stroke.setWidth(inkStroke.getWidth());
                stroke.setBlendMode(inkStroke.getBlendMode());
                stroke.setInterval(inkStroke.getStartValue(), inkStroke.getEndValue());
                stroke.setColor(inkStroke.getColor());
//                    stroke.setPaintIndex(inkPath.getPaintIndex());
//                    stroke.setSeed(inkPath.getRandomSeed());
//                    stroke.setHasRandomSeed(inkPath.hasRandomSeed());
                strokesList.add(stroke);
            }
        }
    }

    private void saveBitmap(){
        Bitmap bmp = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
        inkCanvas.readPixels(currentFrameLayer, bmp, 0, 0, 0,0, canvasWidth, canvasHeight);

        String file = Environment.getExternalStorageDirectory().toString() + "/will-offscreen.png";

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


    //region WILL for Ink Helpers

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

    private void drawStrokes() {
        inkCanvas.setTarget(strokesLayer);
        inkCanvas.clearColor();

        for (Stroke stroke: strokesList){
            paint.setColor(Color.RED);
            strokeRenderer.setStrokePaint(paint);
            strokeRenderer.drawPoints(stroke.getPoints(), 0, stroke.getSize(), stroke.getStartValue(), stroke.getEndValue(),true);
            strokeRenderer.blendStroke(strokesLayer, stroke.getBlendMode());
        }

        inkCanvas.setTarget(currentFrameLayer);
        inkCanvas.clearColor(Color.WHITE);
        inkCanvas.drawLayer(strokesLayer, BlendMode.BLENDMODE_NORMAL);
    }
    //endregion WILL for Ink Helpers

    @Override
    public void onBackPressed()
    {
        if(continuousTransfer){
            try {
                fileTransferDeviceService.disable(new OnCompleteCallback() {
                    @Override
                    public void onComplete() {
                        FileTransferActivity.this.finish();
                    }
                });
            } catch (InvalidOperationException e) {
                e.printStackTrace();
            }
        } else {
            FileTransferActivity.this.finish();
        }
        super.onBackPressed();
    }

}
