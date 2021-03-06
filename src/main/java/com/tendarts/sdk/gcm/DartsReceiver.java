package com.tendarts.sdk.gcm;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.tendarts.sdk.Model.Notification;
import com.tendarts.sdk.Model.PersistentPush;
import com.tendarts.sdk.TendartsSDK;
import com.tendarts.sdk.client.TendartsClient;
import com.tendarts.sdk.common.Configuration;
import com.tendarts.sdk.common.Constants;
import com.tendarts.sdk.common.LogHelper;
import com.tendarts.sdk.common.PendingCommunicationController;
import com.tendarts.sdk.common.Util;
import com.tendarts.sdk.communications.Communications;
import com.tendarts.sdk.communications.ICommunicationObserver;
import com.tendarts.sdk.monitoring.IntentMonitorService;

import org.json.JSONObject;

import java.util.Map;

/**
 * Created by jorgearimany on 19/4/17.
 */

public class DartsReceiver extends BroadcastReceiver {

	private static final String TAG = "DartsReceiver";

	public static final String CLEAR_PUSHES = "com.darts.sdk.CLEAR_PUSHES";
	public static final String OPEN_PUSH = "com.darts.sdk.OPEN_PUSH";
	public static final String OPEN_LIST = "com.darts.sdk.OPEN_LIST";
	public static final String NOTIFICATION_ACTION = "com.darts.sdk.NOTIFICATION_ACTION";

	public static final String PARAM_ORIGIN = "sorg";

	public static final String PARAM_ACTION_ID = "action_id";
	public static final String PARAM_ACTION_COMMAND = "action_command";

	static Thread thread;

	@Override
	public void onReceive(final Context context, final Intent intent)  {

		if (intent != null) {

			String action = intent.getAction();

			Bundle extras = intent.getExtras();
			if (extras == null || !extras.containsKey(PARAM_ORIGIN)) {
				Log.e(TAG, "onReceive: no extras");
				return;
			}


			int origin = extras.getInt(PARAM_ORIGIN);
			LogHelper.logConsole(TAG, "onReceive:  origin: " + origin + " " + action);
			String accessToken = Configuration.instance(context).getAccessToken(context);
			if(accessToken == null) {
				LogHelper.logConsole(TAG,"onReceive: not access token");
				return;
			}
			if (origin != accessToken.hashCode() ) {
				LogHelper.logConsole(TAG,"onReceive:  not for me: " + origin + "   " + accessToken.hashCode() );
				return;
			}

			if(CLEAR_PUSHES.equalsIgnoreCase(action)) {

				PersistentPush.clear(context);
				TendartsClient.instance(context).onNotificationListCleared();
				dismissNotificationIfNeeded(context, intent);

				LogHelper.logEventPush(context, "onReceive","clear push list");

			} else if (OPEN_PUSH.equalsIgnoreCase(action)) {
				//open push
				if (Notification.canDeserialize(intent)) {

					thread = new Thread(new Runnable()
					{
						@Override
						public void run()
						{
							Notification push  = new Notification(intent);
							TendartsSDK.notificationClicked(push.getCode(), context);

							dismissNotificationIfNeeded(context,intent);
							TendartsClient.instance(context).onNotificationClicked(push);
							boolean opened =  TendartsClient.instance(context).openNotification(push, context);
							if (!opened) {
								String deepUrl = push.getDeepUrl();
								if (deepUrl != null) {
									try {
										Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(deepUrl.trim()));
										viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
										context.startActivity(viewIntent);

										//start monitoring service
										Intent service  = new Intent(context, IntentMonitorService.class);
										service.setData(Uri.parse(push.getCode()));
										context.startService(service);
									} catch (Exception e) {
										LogHelper.logException(e);
										LogHelper.logEvent(context, "onReceive","can't launch deep url",""+e.getMessage());
										TendartsClient.instance(context).remoteLogException(e);
									}
								}
							} else {
								LogHelper.logConsole(TAG,"opened by client");
							}
						}
					});
					thread.start();


				} else {
					LogHelper.logConsole(TAG,"onReceive: can't deserialize");
				}
			} else if(OPEN_LIST.equalsIgnoreCase(action)) {
				dismissNotificationIfNeeded(context, intent);

				TendartsClient.instance(context).openNotificationList(context);

			} else if (NOTIFICATION_ACTION.equalsIgnoreCase(action)) {
				// Get command and call developer intent associated with it

				String actionId = extras.getString(PARAM_ACTION_ID);
				String actionCommand = extras.getString(PARAM_ACTION_COMMAND);

                notificationActionClicked(context, actionId);

                if (!TextUtils.isEmpty(actionId) && !TextUtils.isEmpty(actionCommand)) {
					Map<String, String> replyActionsMap = Configuration.instance(context).getReplyActionsMap();
					String intentAction = replyActionsMap.get(actionCommand);

					if (!TextUtils.isEmpty(intentAction)) {
						// Send intent
						Intent actionIntent = new Intent();
						actionIntent.setAction(intentAction);
						actionIntent.putExtra(DartsReceiver.PARAM_ACTION_ID, actionId);
						actionIntent.putExtra(DartsReceiver.PARAM_ACTION_COMMAND, actionCommand);
						context.sendBroadcast(actionIntent);
					}
				}

			}

		}
	}

	private void dismissNotificationIfNeeded(Context context, Intent intent) {
		if (intent.hasExtra("dismiss")) {
			int id = intent.getIntExtra("dismiss", -1);
			if (id != -1) {
				LogHelper.logConsole(TAG,"dismissNotificationIfNeeded: "+id);

				NotificationManager manager =
						(NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
				manager.cancel(id);
				//manager.cancel(GCMListenerService.not_id);
			}
		}
	}

	private void notificationActionClicked(final Context context, String replyId) {

        String deviceJson = Util.getDeviceJson(context);
        String url = String.format(Constants.REPLIES_SELECTED, replyId);

		LogHelper.logConsoleNet("sending post: " + url + "\n" + deviceJson);

        Communications.postData(url, Util.getProvider(), 0, new ICommunicationObserver() {
            @Override
            public void onSuccess(int operationId, JSONObject data) {
            	LogHelper.logEventPush(context,"Replies","Notified selected reply");
				LogHelper.logConsole("Notified selected reply");
            }

            @Override
            public void onFail(int operationId, String reason,
                               Communications.PendingCommunication pending) {
                Util.checkUnauthorized(reason,context);
				PendingCommunicationController.addPending(pending, context);
				LogHelper.logEventPush(context,"Replies","Notifying selected reply failed: " + reason);
				LogHelper.logConsole("Notifying selected reply failed: " + reason);
            }
        }, deviceJson, false);

    }

}
