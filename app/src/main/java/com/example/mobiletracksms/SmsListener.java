package com.example.mobiletracksms;


import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;

public class SmsListener extends BroadcastReceiver {
    final static double minimalAccuracy = 75.0;

    Location location = null;
    int attempt = 0;
    String phone = null;
    Context context = null;
    Storage storage = null ;
    boolean coarse = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;
        storage = Storage.getInstance(context);
        String action = intent.getAction();
        /*
            New SMS
         */
        if(action.equals("android.provider.Telephony.SMS_RECEIVED")){
            SmsMessage[] messages = extractSmsFromIntent(intent);
            for(SmsMessage m : messages){
                if (parseMessage(m)){

                    if (storage.hasRequestFromPhone(phone) && getActivePendingIntent() != null) {
                        sendSMS(context.getString(R.string.already_processed));
                    }

                    Location approxLocation = sendApproxLocationSms();
                    if (! coarse || approxLocation == null) {

                        if (! MainActivity.isGpsEnabled(context)){
                            sendSMS(context.getString(R.string.gps_network_not_enabled));
                        }
                        tryGetFineLocation();
                    }
                    coarse = false;
                }
            }
        }

        /*
            Request from activity after getting permission
        */
        if(intent.getAction().equals("com.example.SEND_LOCATION")){
            readIntentExtras(intent); // Contain only phone
            if (phone != null && ! phone.isEmpty()){
                tryGetFineLocation();
            }
        }

        /*
            Request from GPS when it determine current location
        */
        if (intent.getAction().equals("com.example.LOCATION_UPDATE")){
            readIntentExtras(intent);
            if (location != null && ! storage.getCurrentRequests().isEmpty()) {
                if (location.hasAccuracy() && location.getAccuracy() < minimalAccuracy) {
                    //Log.d("SMSListener", "Location updated " + location.getLatitude() + " " + location.getLongitude());
                    for (String s : storage.getCurrentRequests()) {
                        phone = s;
                        sendSMS(composeSmsText(location));
                    }
                    storage.clearRequests();
                    stopService();
                }
                else{
                    Log.d("SMSListener", "Location updated but accuracy is bad:" + location.getAccuracy());
                    if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        // In Android 8+ location periodically determining in foreground service
                        tryGetFineLocation();
                    }
                }
            }else {
                Log.d("SMSListener", "Location updated but empty");
            }

        }

        if (intent.getAction().equals("android.intent.action.BOOT_COMPLETED")){
            Log.d("SMSListener", "Start after boot");
            if (! storage.getCurrentRequests().isEmpty()){
                tryGetFineLocation();
            }
        }
    }


    private PendingIntent getActivePendingIntent(){
        // https://stackoverflow.com/questions/4556670/how-to-check-if-alarmmanager-already-has-an-alarm-set
        return PendingIntent.getBroadcast(context, 0,
                new Intent("com.example.LOCATION_UPDATE"),
                PendingIntent.FLAG_NO_CREATE);
    }

    private boolean parseMessage(SmsMessage sms){
        String clearMessage = sms.getMessageBody().toLowerCase().trim();
        String[] parts = clearMessage.split(":");
        String passphrase = storage.getCurrentPassphrase();
        if (parts.length == 0){
            return false;
        }

        String messagePassphrase = parts[0];
        if (! passphrase.toLowerCase().equals(messagePassphrase)){
            return false;
        }

        this.phone = sms.getOriginatingAddress();

        // Parse params

        for(int i=1;i<parts.length;i++){

            if (parts[i].toLowerCase().equals("reset")){
                storage.removeRequest(phone);
                sendSMS("Request canceled");
                continue;
            }

            if (parts[i].toLowerCase().equals("coarse")){
                coarse = true;
                continue;
            }

            if (parts[i].toLowerCase().equals("requests")){
                sendListOfCurrentrRequests();
                // Only send list of phones waiting for location
                return false;
            }
        }

        return true;
    }

    private void sendListOfCurrentrRequests(){
        String text = "";
        if (storage.getCurrentRequests().isEmpty()){
            text = context.getString(R.string.sms_no_requests);
        }else{
            for(String ph : storage.getCurrentRequests()){
                text += ph + "\n";
            }
        }
        sendSMS("Current requests: \n" + text);
    }



    private Location sendApproxLocationSms(){
        String smsText;
        Location approxLocation = tryGetLastKnownLocation();
        if (approxLocation != null) {
            smsText = context.getString(R.string.sms_last_known)+" "+composeSmsText(approxLocation);
        }else{
            smsText = context.getString(R.string.sms_welcome);
        }
        sendSMS(smsText);
        return approxLocation;
    }

    private void readIntentExtras(Intent intent){
        String key = LocationManager.KEY_LOCATION_CHANGED;
        location = (Location) intent.getExtras().get(key);
        phone = intent.getExtras().getString("phone");
        attempt = intent.getExtras().getInt("attempt",0);
    }

    public SmsMessage[] extractSmsFromIntent(Intent intent){
        SmsMessage[] msgs = null;
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT){
            msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        } else{
            // For ver. prior 19
            Bundle bundle = intent.getExtras();
            if (bundle != null){
                //---retrieve the SMS message received---
                Object[] pdus = (Object[]) bundle.get("pdus");
                msgs = new SmsMessage[pdus.length];
                for(int i=0; i<msgs.length; i++){
                    msgs[i] = SmsMessage.createFromPdu((byte[])pdus[i]);
                }
            }
        }
        return msgs;
    }

    public void tryGetFineLocation(){
        try {
            cancelOldPendingIntent();
            sendLocationRequest();
            if (phone != null) {
                storage.addRequest(phone);
            }
        } catch ( SecurityException e ) {
            openActivityToRequestPermission();
        }
    }

    private void cancelOldPendingIntent(){
        PendingIntent currentRequest = getActivePendingIntent();
        if (currentRequest != null){
            currentRequest.cancel();
        }
    }

    public Location tryGetLastKnownLocation(){
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch ( SecurityException e ) {
            return null;
        }
    }

    private void sendLocationRequest() throws SecurityException {

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            Intent i = new Intent(context, ForegroundServiceForAndroid8.class);
            i.putExtra("phone", phone);
            context.startService(i);
        }else {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Intent intentp = new Intent("com.example.LOCATION_UPDATE");
            intentp.putExtra("phone", phone);
            intentp.putExtra("attempt", attempt++);
            intentp.putExtra("satellites", 7);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intentp, PendingIntent.FLAG_UPDATE_CURRENT);
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, pendingIntent);
        }
    }

    private void openActivityToRequestPermission(){
        Intent i = new Intent();
        i.putExtra("phone",phone);
        i.setClassName(context.getPackageName(), MainActivity.class.getName());
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    /**
     New 2017 api  for google maps
     https://developers.google.com/maps/documentation/urls/guide#universal-cross-platform-syntax
     */
    public String composeSmsText(Location loc){
        Float accuracy = loc.getAccuracy();
        String url = "https://www.google.com/maps/search/?api=1&zoom="+getZoom(accuracy)+"&query="+loc.getLatitude()+","+loc.getLongitude();


        //String url = "http://maps.google.com/?geo="+loc.getLatitude()+","+loc.getLongitude();
        //url += "\n "+"yandexmaps://maps.yandex.ru/?pt="+loc.getLatitude()+","+loc.getLongitude();
        //url += "\n "+"yandexmaps://maps.yandex.ru/?pt="+loc.getLatitude()+","+loc.getLongitude();
        //url += "\n "+"http://maps.google.com/?ll="+loc.getLatitude()+","+loc.getLongitude();
        //url = "intent://geo:"+loc.getLatitude()+","+loc.getLongitude()+"?q="+loc.getLatitude()+","+loc.getLongitude();
        //return url+"\n accuracy:"+accuracy+"\n attempt: "+attempt;
        return url+"\n accuracy:"+accuracy+" tm: "+getTime(loc);
    }

    public int getZoom(Float accuracy){
        return 20;
    }

    public String getTime(Location loc){
        Calendar calendar = new GregorianCalendar();
        calendar.setTimeInMillis(loc.getTime());

        SimpleDateFormat format = new SimpleDateFormat("hh:mm");
        return format.format(calendar.getTime());


        //return calendar.get(Calendar.DAY_OF_MONTH)+" "+calendar.get(Calendar.HOUR)+calendar.get(Calendar.MINUTE);

    }

    public void sendSMS(String text){
        SmsManager sms = SmsManager.getDefault();
        sms.sendTextMessage(phone, null, text, null, null);
    }

    public void stopService(){
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            Intent i = new Intent(context, ForegroundServiceForAndroid8.class);
            context.stopService(i);
        }


    }
}
