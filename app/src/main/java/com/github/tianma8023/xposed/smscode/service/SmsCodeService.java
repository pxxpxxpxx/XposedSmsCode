package com.github.tianma8023.xposed.smscode.service;

import android.Manifest;
import android.app.IntentService;
import android.app.Notification;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Telephony;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.widget.Toast;

import com.crossbowffs.remotepreferences.RemotePreferences;
import com.github.tianma8023.xposed.smscode.BuildConfig;
import com.github.tianma8023.xposed.smscode.R;
import com.github.tianma8023.xposed.smscode.constant.INotificationConstants;
import com.github.tianma8023.xposed.smscode.constant.IPrefConstants;
import com.github.tianma8023.xposed.smscode.entity.SmsMessageData;
import com.github.tianma8023.xposed.smscode.service.accessibility.SmsCodeAutoInputService;
import com.github.tianma8023.xposed.smscode.utils.AccessibilityUtils;
import com.github.tianma8023.xposed.smscode.utils.ClipboardUtils;
import com.github.tianma8023.xposed.smscode.utils.RemotePreferencesUtils;
import com.github.tianma8023.xposed.smscode.utils.ShellUtils;
import com.github.tianma8023.xposed.smscode.utils.StringUtils;
import com.github.tianma8023.xposed.smscode.utils.VerificationUtils;
import com.github.tianma8023.xposed.smscode.utils.XLog;

import java.util.concurrent.TimeUnit;

import static com.github.tianma8023.xposed.smscode.utils.RemotePreferencesUtils.getBooleanPref;

/**
 * 处理验证码的Service
 */
public class SmsCodeService extends IntentService {

    private static final String SERVICE_NAME = "SmsCodeService";

    private static final int NOTIFY_ID_FOREGROUND_SVC = 0xff;

    private static final int JOB_ID = 0x100;
    public static final String EXTRA_KEY_SMS_INTENT = "key_sms_intent";

    private static final int MSG_COPY_TO_CLIPBOARD = 0xff;
    private static final int MSG_MARK_AS_READ = 0xfe;

    private RemotePreferences mPreferences;

    public SmsCodeService() {
        this(SERVICE_NAME);
    }

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     *
     * @param name Used to name the worker thread, important only for debugging.
     */
    public SmsCodeService(String name) {
        super(name);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPreferences = RemotePreferencesUtils.getDefaultRemotePreferences(this.getApplicationContext());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Show a notification for the foreground service.
            Notification notification = new NotificationCompat.Builder(this, INotificationConstants.CHANNEL_ID_FOREGROUND_SERVICE)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_notification))
                    .setWhen(System.currentTimeMillis())
                    .setContentText(getString(R.string.sms_code_notification_title))
                    .setAutoCancel(true)
                    .setColor(getColor(R.color.ic_launcher_background))
                    .build();
            startForeground(NOTIFY_ID_FOREGROUND_SVC, notification);
        }
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent != null) {
            Intent smsIntent = intent.getParcelableExtra(EXTRA_KEY_SMS_INTENT);
            doWork(smsIntent);
        }
    }

    private void doWork(Intent mSmsIntent) {
        if (!getBooleanPref(mPreferences, IPrefConstants.KEY_ENABLE, IPrefConstants.KEY_ENABLE_DEFAULT)) {
            XLog.i("SmsCode disabled, exiting");
            return;
        }

        SmsMessageData smsMessageData = SmsMessageData.fromIntent(mSmsIntent);

        String sender = smsMessageData.getSender();
        String msgBody = smsMessageData.getBody();
        XLog.i("Received a new SMS message");
        if (BuildConfig.DEBUG) {
            XLog.i("Sender: %s", sender);
            XLog.i("Body: %s", msgBody);
        } else {
            XLog.i("Sender: %s", StringUtils.escape(sender));
            XLog.i("Body: %s", StringUtils.escape(msgBody));
        }

        if (TextUtils.isEmpty(msgBody))
            return;
        String verificationCode = VerificationUtils.parseVerificationCodeIfExists(this, msgBody);

        if (TextUtils.isEmpty(verificationCode)) { // Not verification code msg.
            return;
        }

        if (getBooleanPref(mPreferences, IPrefConstants.KEY_AUTO_INPUT_MODE_ROOT, IPrefConstants.KEY_AUTO_INPUT_MODE_ROOT_DEFAULT)) {
            // Root auto-input mode
            boolean enabled = ShellUtils.enableAccessibilityService(AccessibilityUtils.getAccessibilityServiceName(SmsCodeAutoInputService.class));
            XLog.d("Accessibility enabled " + (enabled ? "true" : "false"));
            if (enabled) {
                sleep(1);
            }
        }

        XLog.i("Verification code: %s", verificationCode);
        Message copyMsg = new Message();
        copyMsg.obj = verificationCode;
        copyMsg.what = MSG_COPY_TO_CLIPBOARD;
        innerHandler.sendMessage(copyMsg);

        // mark sms as read or not.
//        if (getBooleanPref(mPreferences, IPrefConstants.KEY_MARK_AS_READ, IPrefConstants.KEY_MARK_AS_READ_DEFAULT)) {
////            sleep(8);
//            Message markMsg = new Message();
//            markMsg.obj = smsMessageData;
//            markMsg.what = MSG_MARK_AS_READ;
//            innerHandler.sendMessageDelayed(markMsg, 8000);
//        }
    }

    private Handler innerHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_COPY_TO_CLIPBOARD:
                    copyToClipboardOnMainThread((String) msg.obj);
                    break;
                case MSG_MARK_AS_READ:
                    SmsMessageData smsMessageData = (SmsMessageData) msg.obj;
                    String sender = smsMessageData.getSender();
                    String body = smsMessageData.getBody();
                    markSmsAsRead(sender, body);
                    break;
            }
        }
    };

    /**
     * 在主线程上执行copy操作
     */
    private void copyToClipboardOnMainThread(String verificationCode) {
        ClipboardUtils.copyToClipboard(this, verificationCode);
        if (getBooleanPref(mPreferences, IPrefConstants.KEY_SHOW_TOAST, IPrefConstants.KEY_SHOW_TOAST_DEFAULT)) {
            String text = this.getString(R.string.cur_verification_code, verificationCode);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        }

        // start auto input
        Intent intent = new Intent(SmsCodeAutoInputService.ACTION_START_AUTO_INPUT);
        intent.putExtra(SmsCodeAutoInputService.EXTRA_KEY_SMS_CODE, verificationCode);
        sendBroadcast(intent); //
    }

    private void markSmsAsRead(String sender, String body) {
        Cursor cursor = null;
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                XLog.e("Don't have permission read sms");
                return;
            }
            Uri uri = Telephony.Sms.Inbox.CONTENT_URI;
            cursor = this.getContentResolver().query(uri, null, null, null, null);
            if (cursor == null)
                return;
            while (cursor.moveToNext()) {
                String curAddress = cursor.getString(cursor.getColumnIndex("address"));
                int curRead = cursor.getInt(cursor.getColumnIndex("read"));
                String curBody = cursor.getString(cursor.getColumnIndex("body"));
                XLog.d("curBody = %s", curBody);
                if (curAddress.equals(sender) && curRead == 0 && curBody.startsWith(body)) {
                    String smsMessageId = cursor.getString(cursor.getColumnIndex("_id"));
                    ContentValues values = new ContentValues();
                    values.put("read", true);
                    int rows = this.getContentResolver().update(uri, values, "_id = ?", new String[]{smsMessageId});
                    XLog.d("Updates rows %d", rows);
                }
            }
            XLog.i("Mark as read succeed");
        } catch (Exception e) {
            XLog.e("Mark as read failed: ", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}