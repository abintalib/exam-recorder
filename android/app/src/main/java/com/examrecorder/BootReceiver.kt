package com.examrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Start listening for the admin as soon as the tablet boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ControlService.start(ctx)
    }
}
