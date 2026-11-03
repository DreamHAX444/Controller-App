package com.livetracker.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.livetracker.controller.MainActivity
import com.livetracker.controller.R
import com.livetracker.controller.TraceApplication
import com.livetracker.controller.alert.ActiveAlarmState
import com.livetracker.controller.core.tracker.alert.domain.AlertType
import com.livetracker.controller.core.tracker.geofence.domain.AlertSoundProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TraceFleetMonitoringService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundServiceInternal()
        observeActiveAlarms()
        observeAlertRepository()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS_ALARM -> {
                val app = application as? TraceApplication
                app?.sirenAlertEngine?.dismissAlarm()
                notificationManager.cancel(NOTIFICATION_ID_BREACH)
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        val notification = buildPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_PERSISTENT,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID_PERSISTENT, notification)
        }
    }

    private fun observeActiveAlarms() {
        val app = application as? TraceApplication ?: return
        serviceScope.launch {
            app.sirenAlertEngine.activeAlarm.collectLatest { alarm ->
                if (alarm != null) {
                    showBreachNotification(alarm)
                } else {
                    notificationManager.cancel(NOTIFICATION_ID_BREACH)
                }
            }
        }
    }

    private fun observeAlertRepository() {
        val app = application as? TraceApplication ?: return
        serviceScope.launch {
            app.alertRepository.observeUnacknowledgedAlerts().collectLatest { alerts ->
                val latestBreach = alerts.firstOrNull {
                    it.type == AlertType.GEOFENCE_ENTER || it.type == AlertType.GEOFENCE_EXIT
                }
                if (latestBreach != null && !app.sirenAlertEngine.isPlaying()) {
                    val profile = try {
                        latestBreach.soundProfile?.let { AlertSoundProfile.valueOf(it) } ?: AlertSoundProfile.TACTICAL_SIREN
                    } catch (_: Exception) {
                        AlertSoundProfile.TACTICAL_SIREN
                    }
                    app.sirenAlertEngine.triggerAlarm(
                        alertId = latestBreach.id,
                        geofenceId = latestBreach.geofenceId,
                        geofenceName = latestBreach.geofenceName ?: "Perimeter",
                        deviceId = latestBreach.deviceId.value,
                        soundProfile = profile,
                        durationSeconds = latestBreach.durationSeconds,
                        enableVibration = latestBreach.isVibrationEnabled
                    )
                }
            }
        }
    }

    private fun buildPersistentNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_PERSISTENT)
            .setContentTitle("Trace Fleet Active")
            .setContentText("Monitoring fleet devices & perimeter breaches in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showBreachNotification(alarm: ActiveAlarmState) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, TraceFleetMonitoringService::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPendingIntent = PendingIntent.getService(
            this,
            2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_BREACH)
            .setContentTitle("🚨 Perimeter Breach: ${alarm.geofenceName}")
            .setContentText("Device ${alarm.deviceId} breached boundary. Alarm: ${alarm.timeRemainingSeconds}s remaining")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "DISMISS ALARM",
                dismissPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_BREACH, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val persistentChannel = NotificationChannel(
                CHANNEL_ID_PERSISTENT,
                "Fleet Background Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows continuous controller fleet background connectivity status"
                setShowBadge(false)
            }

            val breachChannel = NotificationChannel(
                CHANNEL_ID_BREACH,
                "Perimeter Breach Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent geofence boundary alerts and alarms"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(persistentChannel)
            notificationManager.createNotificationChannel(breachChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID_PERSISTENT = "trace_fleet_monitoring_channel"
        const val CHANNEL_ID_BREACH = "trace_perimeter_breach_channel"
        const val NOTIFICATION_ID_PERSISTENT = 9001
        const val NOTIFICATION_ID_BREACH = 9002

        const val ACTION_DISMISS_ALARM = "com.livetracker.controller.action.DISMISS_ALARM"
        const val ACTION_STOP_SERVICE = "com.livetracker.controller.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, TraceFleetMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
