package com.wacom.samples.cdlsampleapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.wacom.bootstrap.lib.InvalidLicenseException;
import com.wacom.bootstrap.lib.LicenseBootstrap;
import com.wacom.bootstrap.lib.LicenseTokenException;
import com.wacom.cdl.InkDeviceScanner;
import com.wacom.cdl.callbacks.GetPropertiesCallback;
import com.wacom.cdl.exceptions.DeviceIsBusyException;
import com.wacom.cdl.enums.InkDeviceProperty;
import com.wacom.cdl.InkDeviceInfo;
import com.wacom.cdl.callbacks.ConnectionCallback;
import com.wacom.cdl.InkDevice;
import com.wacom.cdl.deviceservices.DeviceServiceType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {
    private final static int STATE_SCANNING = 0;
    private final static int STATE_CONNECTING = 1;
    private final static int STATE_CONNECTED = 2;

    private InkDevice inkDevice;
    private ArrayList<InkDeviceInfo> devicesData = new ArrayList<>();
    private ArrayList<String> devicesNames = new ArrayList<>();
    private ArrayAdapter<String> devicesAdapter;
    private ArrayList<String> deviceProperties = new ArrayList<>();
    private int state;
    private byte[] appId = new byte[]{(byte) 0x11, (byte) 0x34, (byte) 0x56, (byte) 0x78, (byte) 0x88, (byte) 0x05};
    private ListView scanListView;
    private Button scanBtn;
    private Button transferFilesBtn;
    private Button liveMode;
    private InkDeviceScanner inkDeviceScanner;
    private TextView topLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Request permissions
        ActivityCompat.requestPermissions(MainActivity.this,
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);

        AssetManager assetManager = getAssets();
        try {
            InputStream ims = assetManager.open("license.lic");
            LicenseBootstrap.initLicense(this.getApplicationContext(), LicenseBootstrap.readFully(ims));
        } catch (IOException | LicenseTokenException e) {
            e.printStackTrace();
        }

        devicesAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                devicesNames);

        scanBtn = (Button) findViewById(R.id.scanBtn);
        transferFilesBtn = (Button) findViewById(R.id.transferFilesBtn);
        liveMode = (Button) findViewById(R.id.liveModeBtn);
        scanListView = (ListView) findViewById(R.id.scanListView);
        topLabel = (TextView) findViewById(R.id.topLabel);

        scanListView.setAdapter(devicesAdapter);
        scanListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if(state == STATE_SCANNING) {
                    state = STATE_CONNECTING;
                    inkDeviceScanner.stop();

                    InkDeviceInfo inkDeviceInfo = devicesData.get(position);

                    if(hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        InkDeviceSerializer serializer = new InkDeviceSerializer(getApplicationInfo().dataDir);
                        serializer.saveInkDevice(inkDeviceInfo);
                        startInkDevice(inkDeviceInfo);
                    }
                }

            }
        });

        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!mBluetoothAdapter.isEnabled()) {
            topLabel.setText("Turning bluetooth on...");
            mBluetoothAdapter.enable();
        }

        checkForPairedDevice();
    }

    private void checkForPairedDevice(){
        if(!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) return;
        InkDeviceSerializer serializer = new InkDeviceSerializer(getApplicationInfo().dataDir);
        InkDeviceInfo inkDeviceInfo = serializer.readInkDevice();
        if(inkDeviceInfo != null){
            topLabel.setText("Current device: " + inkDeviceInfo.getName() + ". Tap to retrieve device info.");
            startInkDevice(inkDeviceInfo);
        } else {
            topLabel.setText("No device connected. Please tap on scan and connect to a device.");
        }
    }

    private void startInkDevice(final InkDeviceInfo inkDeviceInfo){
        final MyApplication app = (MyApplication) getApplication();

        try {
            inkDevice = app.createInkDeviceClient(inkDeviceInfo);
        } catch (InvalidLicenseException e) {
            Toast.makeText(MainActivity.this, "License is invalid", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return;
        }


        app.subscribeForEvents(MainActivity.this);
        app.subscribeForAlerts(MainActivity.this);

        ArrayList<DeviceServiceType> availableDeviceServices = inkDevice.getAvailableDeviceServices();

        if(availableDeviceServices.contains(DeviceServiceType.FILE_TRANSFER_DEVICE_SERVICE)){
            transferFilesBtn.setVisibility(View.VISIBLE);
        }

        if(availableDeviceServices.contains(DeviceServiceType.LIVE_MODE_DEVICE_SERVICE)){
            liveMode.setVisibility(View.VISIBLE);
        }

        inkDevice.connect(inkDeviceInfo, appId, new ConnectionCallback() {
            @Override
            public void onConnected() {
                topLabel.setText("Current device: " + inkDeviceInfo.getName());
                state = STATE_CONNECTED;

                devicesAdapter = new ArrayAdapter<>(MainActivity.this,
                        android.R.layout.simple_list_item_1,
                        deviceProperties);

                scanListView.setAdapter(devicesAdapter);

                /*
                    Ink devices have properties which describe some of there characteristics such as its name,
                    battery state, id and other. You can easily acquire them using the getProperties method,
                    passing a List of the properties you are interested in. As a result, in the callback, you should
                    receive a TreeMap with property type - value pairs. The tree map keeps the same order as
                    the list. However, if a parameter is not supported by the current Ink Device it will be omitted.
                */
                List<InkDeviceProperty> properties = Arrays.asList(InkDeviceProperty.values());

                try {
                    inkDevice.getProperties(properties, new GetPropertiesCallback() {
                        @Override
                        public void onPropertiesRetrieved(TreeMap<InkDeviceProperty, Object> properties) {
                            app.noteWidth = (int) properties.get(InkDeviceProperty.NOTE_WIDTH);
                            app.noteHeight = (int) properties.get(InkDeviceProperty.NOTE_HEIGHT);

                            for (Map.Entry<InkDeviceProperty, Object> property : properties.entrySet()) {
                                deviceProperties.add(property.getKey() + " = " + property.getValue());
                            }

                            devicesAdapter.notifyDataSetChanged();

                            transferFilesBtn.setEnabled(true);
                            liveMode.setEnabled(true);
                        }
                    });
                } catch (DeviceIsBusyException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void scanBtn_OnClick(View view) {
        if(!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            Toast.makeText(this, "Location permission not granted!", Toast.LENGTH_LONG).show();
            return;
        }

        state = STATE_SCANNING;
        scanBtn.setEnabled(false);

        if(inkDevice != null) {
            inkDevice.dispose();
        }

        devicesData.clear();
        devicesNames.clear();
        devicesAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                devicesNames);

        scanListView.setAdapter(devicesAdapter);

        /*
            In order to find the available Wacom Ink Devices that are around we need to scan for them.
            To do this we need to create a new instance of InkDeviceScanner. It will scan for Wacom Ink
            Devices over bluetooth. The devices should be in pairing mode in order to be found.

            *Note: To enter pairing mode on a Wacom SmartPad you need to press and hold the
             button on the device for 6 seconds.*
         */

        inkDeviceScanner = new InkDeviceScanner(this);

        /*
            To start scanning for devices, what you need to do is to call the scan() method of the
            InkDeviceScanner. It takes a single parameter - a callback through which it reports the
            devices that were found.

            When the scanner finds a device, it is reported as InkDevice. This object provides useful
            information such as devices's name and address.
         */

        inkDeviceScanner.scan(new InkDeviceScanner.Callback() {
            @Override
            public void onDeviceFound(InkDeviceInfo inkDeviceInfo) {
                if (!devicesNames.contains(inkDeviceInfo.toString())) {
                    devicesData.add(inkDeviceInfo);
                    devicesNames.add(inkDeviceInfo.toString());
                    devicesAdapter.notifyDataSetChanged();
                }
            }
        });

        topLabel.setText("Hold the button on the device for 6 seconds to find it during scan.");
    }

    private boolean hasPermission(String permission){
        return ContextCompat.checkSelfPermission(MainActivity.this,
                permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void transferFilesBtn_OnClick(View view){
        startActivity(new Intent(this, FileTransferActivity.class));
    }

    public void liveModeBtn_OnClick(View view){
        startActivity(new Intent(this, LiveModeActivity.class));
    }
}
