package com.remote.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("agent", Context.MODE_PRIVATE)
            val server = prefs.getString("server", null) ?: return
            val name = prefs.getString("deviceName", android.os.Build.MODEL) ?: android.os.Build.MODEL
            val svc = Intent(context, AgentService::class.java).apply {
                putExtra("server", server)
                putExtra("deviceName", name)
            }
            context.startForegroundService(svc)
        }
    }
}
