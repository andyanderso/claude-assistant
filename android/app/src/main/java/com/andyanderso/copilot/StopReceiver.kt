package com.andyanderso.copilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The notification's "Stop" button. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AssistantService.stop(context)
    }
}
