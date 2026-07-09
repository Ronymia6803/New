package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.R
import com.example.model.AppDatabase
import com.example.model.BlockedApp
import com.example.model.BlockerRepository
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AppBlockerService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: BlockerRepository
    private var handler = Handler(Looper.getMainLooper())

    private var cachedBlockedApps = mutableMapOf<String, BlockedApp>()
    private var overlayView: View? = null
    private var activeCheckRunnable: Runnable? = null

    // Tracking active app usage
    private var lastCheckedAppPackage: String? = null
    private var appActiveSecondsCounter = 0
    private var lastNotifiedAppPackage: String? = null

    companion object {
        private const val CHANNEL_ID = "app_blocker_guard_channel"
        private const val NOTIFICATION_ID = 2026
        private const val CHECK_INTERVAL_MS = 1000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val db = AppDatabase.getDatabase(this)
        repository = BlockerRepository(db.blockerDao())

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                getServiceNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, getServiceNotification())
        }

        // Reactively observe and cache the list of blocked apps
        serviceScope.launch {
            repository.allBlockedApps.collect { apps ->
                val newCache = mutableMapOf<String, BlockedApp>()
                apps.forEach { newCache[it.packageName] = it }
                cachedBlockedApps = newCache
            }
        }

        // Start active foreground checking
        startForegroundChecking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return START_STICKY
    }

    override fun onDestroy() {
        stopForegroundChecking()
        hideBlockOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "App Blocker Guard Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun getServiceNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Guard Mode Active")
        .setContentText("Monitoring social media and app usage schedules offline.")
        .setSmallIcon(android.R.drawable.ic_secure)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun startForegroundChecking() {
        activeCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    checkForegroundApp()
                } catch (e: Exception) {
                    Log.e("AppBlockerService", "Error checking foreground app", e)
                }
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        handler.post(activeCheckRunnable!!)
    }

    private fun stopForegroundChecking() {
        activeCheckRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun checkForegroundApp() {
        val currentApp = getForegroundAppPackage(this) ?: return
        
        // Skip checking if it is our own app or launcher/home screen
        if (currentApp == packageName || currentApp == "android" || currentApp.contains("launcher", ignoreCase = true)) {
            hideBlockOverlay()
            lastCheckedAppPackage = currentApp
            appActiveSecondsCounter = 0
            return
        }

        // Load Global Guard settings
        val globalPrefs = getSharedPreferences("global_guard_settings", Context.MODE_PRIVATE)
        val isGlobalScheduleEnabled = globalPrefs.getBoolean("global_schedule_enabled", false)
        val globalStartTime = globalPrefs.getString("global_start_time", "22:00") ?: "22:00"
        val globalEndTime = globalPrefs.getString("global_end_time", "07:00") ?: "07:00"
        val isGlobalLimitEnabled = globalPrefs.getBoolean("global_limit_enabled", false)
        val globalLimitMinutes = globalPrefs.getInt("global_limit_minutes", 60)

        val isGlobalScheduleActive = isGlobalScheduleEnabled && isTimeInBlockRange(globalStartTime, globalEndTime)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        var blockedConfig = cachedBlockedApps[currentApp]
        
        // If Global Limit is enabled and this app isn't already registered for tracking, register it dynamically
        if (blockedConfig == null && isGlobalLimitEnabled) {
            val newConfig = BlockedApp(
                packageName = currentApp,
                isPermanent = false,
                dailyLimitMinutes = globalLimitMinutes,
                usedMinutesToday = 0,
                lastUsedDate = todayStr
            )
            cachedBlockedApps[currentApp] = newConfig
            serviceScope.launch { repository.insertBlockedApp(newConfig) }
            blockedConfig = newConfig
        }

        if (blockedConfig != null || isGlobalScheduleActive) {
            // Check if block triggers:
            var appConfig = blockedConfig ?: BlockedApp(
                packageName = currentApp,
                isPermanent = false,
                dailyLimitMinutes = if (isGlobalLimitEnabled) globalLimitMinutes else 0,
                usedMinutesToday = 0,
                lastUsedDate = todayStr
            )

            // Reset daily counter if it is a new day
            if (appConfig.lastUsedDate != todayStr) {
                val updatedApp = appConfig.copy(usedMinutesToday = 0, lastUsedDate = todayStr)
                serviceScope.launch { repository.insertBlockedApp(updatedApp) }
                appConfig = updatedApp
            }

            // Evaluate rules
            val isScheduleTriggered = isTimeInBlockRange(appConfig.startTime, appConfig.endTime)
            val effectiveLimit = if (appConfig.dailyLimitMinutes > 0) appConfig.dailyLimitMinutes else if (isGlobalLimitEnabled) globalLimitMinutes else 0
            val isLimitExceeded = effectiveLimit > 0 && appConfig.usedMinutesToday >= effectiveLimit
            val isPermanentlyBlocked = appConfig.isPermanent

            val shouldBlock = isPermanentlyBlocked || isScheduleTriggered || isLimitExceeded || isGlobalScheduleActive

            if (shouldBlock) {
                // Determine reason text
                val reason = when {
                    isPermanentlyBlocked -> "This app is permanently locked."
                    isGlobalScheduleActive -> "Global Guard Schedule is active right now ($globalStartTime to $globalEndTime)."
                    isScheduleTriggered -> "Block schedule is active right now (${appConfig.startTime} to ${appConfig.endTime})."
                    isLimitExceeded -> "Your daily usage limit of ${effectiveLimit}m has been exceeded!"
                    else -> "Access restricted."
                }
                showBlockOverlay(currentApp, reason)
            } else {
                // Not blocked, track minutes if they are actively using it
                hideBlockOverlay()
                if (currentApp == lastCheckedAppPackage) {
                    appActiveSecondsCounter++
                    if (appActiveSecondsCounter >= 60) {
                        val updatedApp = appConfig.copy(
                            usedMinutesToday = appConfig.usedMinutesToday + 1,
                            lastUsedDate = todayStr
                        )
                        serviceScope.launch { repository.insertBlockedApp(updatedApp) }
                        appActiveSecondsCounter = 0
                    }
                } else {
                    appActiveSecondsCounter = 0
                }
            }
        } else {
            // Not blocked and no global schedule or global limit active
            hideBlockOverlay()
            appActiveSecondsCounter = 0
        }

        lastCheckedAppPackage = currentApp
    }

    private fun sendBlockedNotification(packageName: String, appLabel: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alertChannelId = "app_blocked_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                alertChannelId,
                "App Block Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when blocked apps are opened"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, alertChannelId)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("App Blocked")
            .setContentText("$appLabel has been blocked.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$appLabel has been blocked under Focus Guard rules."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(2027, builder.build())
    }

    private fun showBlockOverlay(appPackage: String, reason: String) {
        if (overlayView != null) return // Already showing

        if (!Settings.canDrawOverlays(this)) {
            Log.e("AppBlockerService", "Cannot draw overlay: Permission missing!")
            return
        }

        val appLabel = getAppNameFromPackage(appPackage)
        
        // Trigger notification when the overlay is first shown
        if (lastNotifiedAppPackage != appPackage) {
            sendBlockedNotification(appPackage, appLabel)
            lastNotifiedAppPackage = appPackage
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AppBlockerService)
            setViewTreeSavedStateRegistryOwner(this@AppBlockerService)
            setViewTreeViewModelStoreOwner(this@AppBlockerService)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFFEF5350), // Red focus accent
                        background = Color(0xFF121212), // Deep dark
                        surface = Color(0xFF1E1E1E)
                    )
                ) {
                    var countdown by remember { mutableStateOf(4) }
                    
                    // Auto-close countdown logic: redirects user to home screen automatically
                    LaunchedEffect(Unit) {
                        while (countdown > 0) {
                            delay(1000L)
                            countdown--
                        }
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        hideBlockOverlay()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // Customized App Logo Display
                                Surface(
                                    modifier = Modifier.size(80.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color.Transparent
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.img_app_logo),
                                        contentDescription = "Kahf Guard Logo",
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Text(
                                    text = "Your App is Blocked",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "Kahf Guard restricted access to $appLabel.",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.Black.copy(alpha = 0.3f)
                                      ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = reason,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(32.dp))

                                Button(
                                    onClick = {
                                        // Kick them out to launcher immediately
                                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                            addCategory(Intent.CATEGORY_HOME)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        startActivity(homeIntent)
                                        hideBlockOverlay()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                ) {
                                    Icon(Icons.Default.Home, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Return to Home (${countdown}s)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        try {
            windowManager.addView(composeView, params)
            overlayView = composeView
        } catch (e: Exception) {
            Log.e("AppBlockerService", "Failed to add block overlay view", e)
        }
    }

    private fun hideBlockOverlay() {
        val view = overlayView ?: return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.e("AppBlockerService", "Failed to remove overlay view", e)
        }
        overlayView = null
        lastNotifiedAppPackage = null
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun getForegroundAppPackage(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryEvents(time - 10000, time)
        val event = UsageEvents.Event()
        var lastApp: String? = null
        while (stats.hasNextEvent()) {
            stats.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastApp = event.packageName
            }
        }
        if (lastApp != null) return lastApp

        val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
        if (!usageStats.isNullOrEmpty()) {
            val sorted = usageStats.sortedBy { it.lastTimeUsed }
            return sorted.last().packageName
        }
        return null
    }

    private fun isTimeInBlockRange(start: String?, end: String?): Boolean {
        if (start.isNullOrEmpty() || end.isNullOrEmpty()) return false
        try {
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)
            val currentTimeInMinutes = currentHour * 60 + currentMinute

            val startParts = start.split(":")
            val startHour = startParts[0].toInt()
            val startMin = startParts[1].toInt()
            val startTimeInMinutes = startHour * 60 + startMin

            val endParts = end.split(":")
            val endHour = endParts[0].toInt()
            val endMin = endParts[1].toInt()
            val endTimeInMinutes = endHour * 60 + endMin

            return if (endTimeInMinutes >= startTimeInMinutes) {
                currentTimeInMinutes in startTimeInMinutes..endTimeInMinutes
            } else {
                // Overnight block e.g. 22:00 to 06:00
                currentTimeInMinutes >= startTimeInMinutes || currentTimeInMinutes <= endTimeInMinutes
            }
        } catch (e: Exception) {
            return false
        }
    }
}
