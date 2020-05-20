package com.wacom.samples.cdlsampleapp;

import com.wacom.cdl.InkDeviceInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Created by Alexander Petrov on 1/25/17.
 */

public class InkDeviceSerializer {
    private String serializationDir;
    private String fileName = "inkDevice.bin";

    public InkDeviceSerializer(String serializationDir){
        this.serializationDir = serializationDir;
    }

    public void saveInkDevice(InkDeviceInfo inkDeviceInfo){
        try {
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(
                    new File(serializationDir + "/" + fileName)));
            oos.writeObject(inkDeviceInfo);
            oos.flush();
            oos.close();
        }
        catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    public InkDeviceInfo readInkDevice() {
        File file = new File(serializationDir + "/" + fileName);
        if(file.exists()) {
            try {
                ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file));
                Object o = ois.readObject();
                if (o instanceof InkDeviceInfo) {
                    return (InkDeviceInfo) o;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return null;
    }

    public boolean forgetInkDevice(){
        File file = new File(serializationDir + "/" + fileName);
        return file.delete();
    }
}
