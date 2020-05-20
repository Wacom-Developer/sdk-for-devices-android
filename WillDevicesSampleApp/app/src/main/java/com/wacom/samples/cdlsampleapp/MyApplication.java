package com.wacom.samples.cdlsampleapp;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.Toast;

import com.wacom.bootstrap.lib.InvalidLicenseException;
import com.wacom.cdl.callbacks.AlertsCallback;
import com.wacom.cdl.enums.InkDeviceAlert;
import com.wacom.cdl.enums.InkDeviceEvent;
import com.wacom.cdl.InkDeviceInfo;
import com.wacom.cdl.enums.InkDeviceStatus;
import com.wacom.cdl.enums.UserAction;
import com.wacom.cdl.callbacks.EventCallback;
import com.wacom.cdl.InkDevice;
import com.wacom.cdl.InkDeviceFactory;
import com.wacom.cdl.deviceservices.EventDeviceService;
import com.wacom.cdl.deviceservices.DeviceServiceType;

import java.nio.charset.Charset;

public class MyApplication extends Application {
    //region Fields
    private InkDevice inkDevice;

    private AlertDialog alertDialog;
    private Context currentActivityContext;

    private boolean forgetDialogueDisplayed = false;

    public int noteWidth;
    public int noteHeight;
    //endregion Fields

    //region Getters and Setters
    public InkDevice getInkDevice() {
        return inkDevice;
    }

    public InkDevice createInkDeviceClient(InkDeviceInfo inkDeviceInfo) throws InvalidLicenseException {
        inkDevice = InkDeviceFactory.createClient(this, inkDeviceInfo);
        return inkDevice;
    }

    public void  switchActivityContext(Context activityContext){
        currentActivityContext = activityContext;
        alertDialog = new AlertDialog.Builder(activityContext).create();
    }

    public void subscribeForAlerts(final Context activityContext){
        currentActivityContext = activityContext;

        /*
        Alerts are unexpected events that may lead to different behavior of the InkDevice.
        Subscribe to them in order to be able to maintain a proper communication with the InkDevice.
         */
        inkDevice.subscribe(new AlertsCallback() {
            @Override
            public void onAlert(InkDeviceAlert alert) {
                if(alert == InkDeviceAlert.DEVICE_TRYING_TO_CONNECT_TO_ANOTHER_APPLICATION) {
                    new AlertDialog.Builder(currentActivityContext)
                            .setTitle("Pairing Requested")
                            .setMessage("Your device is trying to pair with another application. Do you want to allow it?")
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    //Dispose inkDevice
                                    inkDevice.dispose();

                                    //Forget the device
                                    InkDeviceSerializer serializer = new InkDeviceSerializer(getApplicationInfo().dataDir);
                                    serializer.forgetInkDevice();
                                    forgetDialogueDisplayed = false;

                                    //Get back to/refresh MainActivity
                                    Intent startIntent = new Intent(currentActivityContext, MainActivity.class);
                                    startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    currentActivityContext.startActivity(startIntent);
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    forgetDialogueDisplayed = false;
                                    alertDialog.setMessage("Please tap the button to confirm the connection.");
                                }
                            })
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();

                    forgetDialogueDisplayed = true;
                }
            }
        });
    }

    public boolean subscribeForEvents(Context activityContext){
        alertDialog = new AlertDialog.Builder(activityContext).create();
        alertDialog.setTitle("User Interaction Expected");

        if(inkDevice.getAvailableDeviceServices().contains(DeviceServiceType.EVENT_DEVICE_SERVICE)){
            ((EventDeviceService) inkDevice.getDeviceService(DeviceServiceType.EVENT_DEVICE_SERVICE)).subscribe(new EventCallback() {

                //Events are broadcast by the Ink device (e.g. change of the battery state).
                @Override
                public void onEvent(InkDeviceEvent event, Object value) {
                    String message = "Event: " + event;

                    switch (event){
                        case BATTERY_LEVEL_EVENT:
                            message += " = " + value;
                            break;
                        case IS_CHARGING_EVENT:
                            message += " = " + value;
                            break;
                        case CONNECTION_CONFIRMATION_TIMEOUT:
                            alertDialog.dismiss();
                            break;
                        case BARCODE_SCAN_RECORD:
                            byte[] barcodeData = (byte[]) value;
                            message += " = " + new String(barcodeData, Charset.defaultCharset());
                            break;
                        case STATUS_CHANGED:
                            InkDeviceStatus status = (InkDeviceStatus) value;
                            return;
                    }
                    Toast.makeText(MyApplication.this, message, Toast.LENGTH_SHORT).show();
                }

                //When a user action is expected by the user.
                @Override
                public void onUserActionExpected(UserAction userAction) {
                    if(forgetDialogueDisplayed) return;

                    switch(userAction){
                        case TAP_BUTTON_TO_CONFIRM_CONNECTION:
                            alertDialog.setMessage("Please tap the button to confirm the connection.");
                            break;
                        case TAP_BUTTON_TO_RESTORE_CONNECTION:
                            alertDialog.setMessage("Please tap the button to restore the connection.");
                            break;
                        case HOLD_BUTTON_TO_ENTER_USER_CONFIRMATION_MODE:
                            alertDialog.setMessage("Please hold the button for 6 seconds.");
                            break;
                        default:
                            alertDialog.setMessage("Unknown.");
                    }

                    alertDialog.show();
                }

                //UserActionCompleted tells you that the user action previously requested is now completed (either successfully or not).
                @Override
                public void onUserActionCompleted(UserAction userAction, boolean success) {
                    alertDialog.dismiss();
                }
            });
            return true;
        }
        return false;
    }
    //endregion Getters and Setters
}
