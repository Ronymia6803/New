package com.example

import android.app.AppOpsManager
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.model.AppDatabase
import com.example.model.BlockedApp
import com.example.model.BlockedWebsite
import com.example.model.BlockerRepository
import com.example.service.AppBlockerService
import com.example.service.ReelsAccessibilityService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.*

// Model for App Selection UI
data class DeviceApp(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val config: BlockedApp? = null
)

class BlockerViewModel(private val repository: BlockerRepository, private val context: Context) : ViewModel() {
    private val _installedApps = MutableStateFlow<List<DeviceApp>>(emptyList())
    val installedApps: StateFlow<List<DeviceApp>> = _installedApps.asStateFlow()

    val blockedApps: StateFlow<List<BlockedApp>> = repository.allBlockedApps.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val blockedWebsites: StateFlow<List<BlockedWebsite>> = repository.allBlockedWebsites.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Social Quick Guard States
    private val _reelsInstagram = MutableStateFlow(false)
    val reelsInstagram = _reelsInstagram.asStateFlow()

    private val _reelsFacebook = MutableStateFlow(false)
    val reelsFacebook = _reelsFacebook.asStateFlow()

    private val _reelsYoutube = MutableStateFlow(false)
    val reelsYoutube = _reelsYoutube.asStateFlow()

    private val _reelsTiktok = MutableStateFlow(false)
    val reelsTiktok = _reelsTiktok.asStateFlow()

    init {
        loadInstalledApps()
        loadSocialSettings()
        
        // Listen for database changes to update our configured app listings
        viewModelScope.launch {
            blockedApps.collect {
                loadInstalledApps()
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            val pm = context.packageManager
            val apps = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }
            val list = mutableListOf<DeviceApp>()
            val blockedMap = blockedApps.value.associateBy { it.packageName }
            val addedPackages = mutableSetOf<String>()

            for (app in apps) {
                val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val label = pm.getApplicationLabel(app).toString()
                val packageName = app.packageName
                if (packageName == context.packageName) continue

                list.add(
                    DeviceApp(
                        label = label,
                        packageName = packageName,
                        isSystem = isSystem,
                        config = blockedMap[packageName]
                    )
                )
                addedPackages.add(packageName)
            }

            // Fallback for custom added apps that might not be in standard installed applications list
            for (blockedApp in blockedApps.value) {
                if (!addedPackages.contains(blockedApp.packageName)) {
                    val fallbackLabel = blockedApp.packageName.substringAfterLast(".")
                    val formattedLabel = fallbackLabel.replaceFirstChar { 
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
                    }
                    list.add(
                        DeviceApp(
                            label = formattedLabel,
                            packageName = blockedApp.packageName,
                            isSystem = false,
                            config = blockedApp
                        )
                    )
                }
            }

            list.sortBy { it.label.lowercase() }
            _installedApps.value = list
        }
    }

    private fun loadSocialSettings() {
        val prefs = context.getSharedPreferences("reels_block_settings", Context.MODE_PRIVATE)
        _reelsInstagram.value = prefs.getBoolean("block_instagram_reels", false)
        _reelsFacebook.value = prefs.getBoolean("block_facebook_reels", false)
        _reelsYoutube.value = prefs.getBoolean("block_youtube_shorts", false)
        _reelsTiktok.value = prefs.getBoolean("block_tiktok", false)
    }

    fun setSocialSetting(key: String, value: Boolean) {
        val prefs = context.getSharedPreferences("reels_block_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
        when (key) {
            "block_instagram_reels" -> _reelsInstagram.value = value
            "block_facebook_reels" -> _reelsFacebook.value = value
            "block_youtube_shorts" -> _reelsYoutube.value = value
            "block_tiktok" -> _reelsTiktok.value = value
        }
    }

    fun blockAppPermanently(packageName: String, block: Boolean) {
        viewModelScope.launch {
            if (block) {
                repository.insertBlockedApp(BlockedApp(packageName = packageName, isPermanent = true))
            } else {
                repository.deleteBlockedAppByPackage(packageName)
            }
        }
    }

    fun saveAppConfig(packageName: String, isPermanent: Boolean, startTime: String?, endTime: String?, limitMinutes: Int) {
        viewModelScope.launch {
            repository.insertBlockedApp(
                BlockedApp(
                    packageName = packageName,
                    isPermanent = isPermanent,
                    startTime = startTime,
                    endTime = endTime,
                    dailyLimitMinutes = limitMinutes
                )
            )
        }
    }

    fun removeAppBlock(packageName: String) {
        viewModelScope.launch {
            repository.deleteBlockedAppByPackage(packageName)
        }
    }

    fun blockAllApps() {
        viewModelScope.launch {
            val list = _installedApps.value
            for (app in list) {
                repository.insertBlockedApp(BlockedApp(packageName = app.packageName, isPermanent = true))
            }
        }
    }

    fun unblockAllApps() {
        viewModelScope.launch {
            val list = _installedApps.value
            for (app in list) {
                repository.deleteBlockedAppByPackage(app.packageName)
            }
        }
    }

    fun addWebsite(domain: String) {
        viewModelScope.launch {
            if (domain.isNotBlank()) {
                repository.insertBlockedWebsite(domain.trim().lowercase())
            }
        }
    }

    fun removeWebsite(domain: String) {
        viewModelScope.launch {
            repository.deleteBlockedWebsiteByDomain(domain)
        }
    }
}

class BlockerViewModelFactory(
    private val repository: BlockerRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BlockerViewModel::class.java)) {
            return BlockerViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = AppDatabase.getDatabase(applicationContext)
        val repository = BlockerRepository(db.blockerDao())
        val factory = BlockerViewModelFactory(repository, applicationContext)

        // Start background app checker service
        val serviceIntent = Intent(this, AppBlockerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            MyApplicationTheme {
                val viewModel: BlockerViewModel = ViewModelProvider(this, factory)[BlockerViewModel::class.java]
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BlockerViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("App Blocker", "Social Guard", "Web Filter")

    val context = LocalContext.current
    var isUsagePermissionGranted by remember { mutableStateOf(hasUsageAccessPermission(context)) }
    var isOverlayPermissionGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context, ReelsAccessibilityService::class.java)) }

    val blockedAppsList by viewModel.blockedApps.collectAsStateWithLifecycle()
    val blockedWebsitesList by viewModel.blockedWebsites.collectAsStateWithLifecycle()
    val igReels by viewModel.reelsInstagram.collectAsStateWithLifecycle()
    val fbReels by viewModel.reelsFacebook.collectAsStateWithLifecycle()
    val ytShorts by viewModel.reelsYoutube.collectAsStateWithLifecycle()
    val ttBlocks by viewModel.reelsTiktok.collectAsStateWithLifecycle()

    val activeAppsCount = blockedAppsList.size
    val activeWebsitesCount = blockedWebsitesList.size
    val activeSocialCount = (if (igReels) 1 else 0) + (if (fbReels) 1 else 0) + (if (ytShorts) 1 else 0) + (if (ttBlocks) 1 else 0)
    val totalActiveFilters = activeAppsCount + activeWebsitesCount + activeSocialCount

    // Periodically refresh permission status when user returns
    LaunchedEffect(Unit) {
        while (true) {
            isUsagePermissionGranted = hasUsageAccessPermission(context)
            isOverlayPermissionGranted = Settings.canDrawOverlays(context)
            isAccessibilityEnabled = isAccessibilityServiceEnabled(context, ReelsAccessibilityService::class.java)
            kotlinx.coroutines.delay(2000)
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F4F9))
                    .statusBarsPadding()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Kahf Guard",
                        fontWeight = FontWeight.Medium,
                        fontSize = 26.sp,
                        letterSpacing = (-0.5).sp,
                        color = Color(0xFF1C1B1F)
                    )
                    Text(
                        text = if (isAccessibilityEnabled) "Protection is active" else "Setup required",
                        fontSize = 14.sp,
                        color = Color(0xFF49454F)
                    )
                }

                // Top Tab Selector matching the Design HTML exactly
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val strokeWidth = 1.dp.toPx()
                            val y = size.height - strokeWidth / 2
                            drawLine(
                                color = Color(0xFFCAC4D0),
                                start = androidx.compose.ui.geometry.Offset(0f, y),
                                end = androidx.compose.ui.geometry.Offset(size.width, y),
                                strokeWidth = strokeWidth
                            )
                        }
                        .padding(horizontal = 24.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = index }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when (index) {
                                        0 -> "Apps"
                                        1 -> "Social"
                                        2 -> "Websites"
                                        else -> title
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelected) Color(0xFF6750A4) else Color(0xFF49454F)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.5f)
                                            .height(2.dp)
                                            .background(Color(0xFF6750A4), RoundedCornerShape(1.dp))
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                windowInsets = WindowInsets.navigationBars,
                containerColor = Color.White,
                tonalElevation = 0.dp,
                modifier = Modifier.drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    drawLine(
                        color = Color(0xFFCAC4D0),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = strokeWidth
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    val icon = when (index) {
                        0 -> Icons.Default.AppBlocking
                        1 -> Icons.Default.SmartScreen
                        2 -> Icons.Default.Public
                        else -> Icons.Default.Block
                    }
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = index },
                        icon = { 
                            Icon(
                                imageVector = icon, 
                                contentDescription = title,
                                tint = if (isSelected) Color(0xFF1D192B) else Color(0xFF49454F)
                            ) 
                        },
                        label = { 
                            Text(
                                text = when (index) {
                                    0 -> "Blocker"
                                    1 -> "Social"
                                    2 -> "Websites"
                                    else -> title
                                },
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color(0xFF1D192B) else Color(0xFF49454F)
                            ) 
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF1D192B),
                            selectedTextColor = Color(0xFF1D192B),
                            unselectedIconColor = Color(0xFF49454F),
                            unselectedTextColor = Color(0xFF49454F),
                            indicatorColor = Color(0xFFE8DEF8)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF3F4F9))
                .padding(innerPadding)
        ) {
            // High Density Status Card - matches the layout and design style of "Usage Today"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DEF8)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "GUARD COVERAGE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = Color(0xFF1D192B).copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (totalActiveFilters > 0) "$totalActiveFilters Rules" else "Active",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Light,
                            color = Color(0xFF1D192B)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (totalActiveFilters > 0) {
                                "Blocked $totalActiveFilters potential distractions today"
                            } else {
                                "Protection is active. No rules added yet."
                            },
                            fontSize = 12.sp,
                            color = Color(0xFF49454F)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xFF6750A4), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Permissions Checklist Overlay (if setup is incomplete)
            if (!isUsagePermissionGranted || !isOverlayPermissionGranted || !isAccessibilityEnabled) {
                PermissionChecklistCard(
                    isUsage = isUsagePermissionGranted,
                    isOverlay = isOverlayPermissionGranted,
                    isAccess = isAccessibilityEnabled,
                    onGrantUsage = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    },
                    onGrantOverlay = {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                    onGrantAccess = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> AppBlockerTab(viewModel)
                    1 -> SocialGuardTab(viewModel)
                    2 -> WebsiteBlockerTab(viewModel)
                }
            }
        }
    }
}

@Composable
fun PermissionChecklistCard(
    isUsage: Boolean,
    isOverlay: Boolean,
    isAccess: Boolean,
    onGrantUsage: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantAccess: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Permissions Required",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF1C1B1F)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "These services are required to run on-device blocking:",
                fontSize = 12.sp,
                color = Color(0xFF49454F)
            )
            Spacer(modifier = Modifier.height(12.dp))

            PermissionRow(
                title = "Usage Access",
                subtitle = "To identify when blocked apps are opened.",
                isGranted = isUsage,
                onClick = onGrantUsage
            )
            Spacer(modifier = Modifier.height(8.dp))
            PermissionRow(
                title = "Overlay Drawing",
                subtitle = "To display the block screen over other apps.",
                isGranted = isOverlay,
                onClick = onGrantOverlay
            )
            Spacer(modifier = Modifier.height(8.dp))
            PermissionRow(
                title = "Accessibility Guard",
                subtitle = "To filter Reels, Shorts, and browser URLs.",
                isGranted = isAccess,
                onClick = onGrantAccess
            )
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isGranted) Color(0xFFE8F5E9).copy(alpha = 0.5f) else Color(0xFFF1F3F4))
            .clickable(enabled = !isGranted, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isGranted) Color(0xFF2E7D32) else Color(0xFFC62828),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1C1B1F))
            Text(subtitle, fontSize = 11.sp, color = Color(0xFF49454F))
        }
        if (!isGranted) {
            Button(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
            ) {
                Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

// Helper to generate distinct pastel colors for apps
fun getPastelColorsForApp(label: String): Pair<Color, Color> {
    val char = label.firstOrNull()?.uppercaseChar() ?: 'A'
    return when (char) {
        'Y' -> Color(0xFFFFEBEE) to Color(0xFFC62828) // YT red
        'I' -> Color(0xFFFCE4EC) to Color(0xFFC2185B) // IG pink
        'F' -> Color(0xFFE3F2FD) to Color(0xFF1565C0) // FB blue
        'G' -> Color(0xFFE8F5E9) to Color(0xFF2E7D32) // Google green
        'T' -> Color(0xFFE0F7FA) to Color(0xFF00838F) // Twitter cyan
        'W' -> Color(0xFFE8F5E9) to Color(0xFF2E7D32) // WhatsApp green
        'S' -> Color(0xFFFFF3E0) to Color(0xFFEF6C00) // Snapchat orange
        else -> Color(0xFFE8DEF8) to Color(0xFF6750A4) // Default purple
    }
}

// Tab 1: App Blocker UI
@Composable
fun AppBlockerTab(viewModel: BlockerViewModel) {
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    var selectedAppForConfig by remember { mutableStateOf<DeviceApp?>(null) }

    // Search and filter states
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    // Custom manually added app states
    var showCustomAddDialog by remember { mutableStateOf(false) }
    var customAppName by remember { mutableStateOf("") }
    var customAppPackage by remember { mutableStateOf("") }

    // Partition apps into active blocks and inactive ones
    val activeApps = apps.filter { it.config != null }
    val otherApps = apps.filter { it.config == null }

    // Filter active apps by search
    val filteredActiveApps = remember(activeApps, searchQuery) {
        activeApps.filter { app ->
            searchQuery.isEmpty() || 
            app.label.contains(searchQuery, ignoreCase = true) || 
            app.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    // Filter other apps by search and system-app preference
    val filteredOtherApps = remember(otherApps, searchQuery, showSystemApps) {
        otherApps.filter { app ->
            val matchesSearch = app.label.contains(searchQuery, ignoreCase = true) || 
                               app.packageName.contains(searchQuery, ignoreCase = true)
            
            val isCoreOrSpecial = !app.isSystem || 
                    app.packageName == "com.android.chrome" || 
                    app.packageName == "com.instagram.android" || 
                    app.packageName == "com.facebook.katana" || 
                    app.packageName == "com.google.android.youtube" || 
                    app.packageName.contains("browser") || 
                    app.packageName.contains("social")
            
            val matchesSystemFilter = showSystemApps || isCoreOrSpecial
            
            matchesSearch && matchesSystemFilter
        }
    }

    val context = LocalContext.current
    val globalPrefs = remember { context.getSharedPreferences("global_guard_settings", Context.MODE_PRIVATE) }

    var isGlobalScheduleEnabled by remember { mutableStateOf(globalPrefs.getBoolean("global_schedule_enabled", false)) }
    var globalStartTime by remember { mutableStateOf(globalPrefs.getString("global_start_time", "22:00") ?: "22:00") }
    var globalEndTime by remember { mutableStateOf(globalPrefs.getString("global_end_time", "07:00") ?: "07:00") }

    var isGlobalLimitEnabled by remember { mutableStateOf(globalPrefs.getBoolean("global_limit_enabled", false)) }
    var globalLimitMinutesText by remember { mutableStateOf(globalPrefs.getInt("global_limit_minutes", 60).toString()) }

    fun saveGlobalSettings() {
        globalPrefs.edit().apply {
            putBoolean("global_schedule_enabled", isGlobalScheduleEnabled)
            putString("global_start_time", globalStartTime)
            putString("global_end_time", globalEndTime)
            putBoolean("global_limit_enabled", isGlobalLimitEnabled)
            putInt("global_limit_minutes", globalLimitMinutesText.toIntOrNull() ?: 60)
            apply()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF6750A4))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scanning installed applications...", color = Color(0xFF49454F))
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search and Filter Bar Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search apps by name or package...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF6750A4)) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color(0xFF79747E))
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { showSystemApps = !showSystemApps }
                                ) {
                                    Checkbox(
                                        checked = showSystemApps,
                                        onCheckedChange = { showSystemApps = it }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Show System Apps",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF49454F)
                                    )
                                }
                                
                                Button(
                                    onClick = { showCustomAddDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Custom Package", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Section 0: Global Guard Settings Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFF6750A4),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Global Guard Controls",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF1C1B1F)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Global Schedule Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Global Bedtime Schedule", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1C1B1F))
                                    Text("Block ALL apps during sleep hours.", fontSize = 11.sp, color = Color(0xFF49454F))
                                }
                                Switch(
                                    checked = isGlobalScheduleEnabled,
                                    onCheckedChange = { 
                                        isGlobalScheduleEnabled = it
                                        saveGlobalSettings()
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF6750A4))
                                )
                            }
                            
                            if (isGlobalScheduleEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedTextField(
                                        value = globalStartTime,
                                        onValueChange = { 
                                            globalStartTime = it
                                            saveGlobalSettings()
                                        },
                                        label = { Text("Start Time", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                    )
                                    OutlinedTextField(
                                        value = globalEndTime,
                                        onValueChange = { 
                                            globalEndTime = it
                                            saveGlobalSettings()
                                        },
                                        label = { Text("End Time", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Color(0xFFCAC4D0).copy(alpha = 0.3f))
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Global Limit Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Global Daily Usage Limit", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1C1B1F))
                                    Text("Set max minutes limit for used apps.", fontSize = 11.sp, color = Color(0xFF49454F))
                                }
                                Switch(
                                    checked = isGlobalLimitEnabled,
                                    onCheckedChange = { 
                                        isGlobalLimitEnabled = it
                                        saveGlobalSettings()
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF6750A4))
                                )
                            }
                            
                            if (isGlobalLimitEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = globalLimitMinutesText,
                                    onValueChange = { 
                                        globalLimitMinutesText = it.filter { char -> char.isDigit() }
                                        saveGlobalSettings()
                                    },
                                    label = { Text("Global Limit (minutes)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                )
                            }
                        }
                    }
                }

                // Bulk Actions
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.blockAllApps() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Block All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        OutlinedButton(
                            onClick = { viewModel.unblockAllApps() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                            border = BorderStroke(1.dp, Color(0xFFC62828)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Unblock All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Section 1: Managed Apps (Configured)
                if (filteredActiveApps.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Blocked / Managed Apps",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color(0xFF49454F)
                            )
                            Text(
                                text = "${filteredActiveApps.size} Active",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750A4)
                            )
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column {
                                filteredActiveApps.forEachIndexed { index, app ->
                                    AppBlockRow(
                                        app = app,
                                        onConfigure = { selectedAppForConfig = app },
                                        onQuickTogglePermanent = { block ->
                                            viewModel.blockAppPermanently(app.packageName, block)
                                        },
                                        onRemoveBlock = {
                                            viewModel.removeAppBlock(app.packageName)
                                        }
                                    )
                                    if (index < filteredActiveApps.lastIndex) {
                                        HorizontalDivider(
                                            color = Color(0xFFE0E0E0),
                                            thickness = 1.dp,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 2: Other Apps (Unconfigured)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Apps on Your Phone",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color(0xFF49454F)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFCAC4D0).copy(alpha = 0.3f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${filteredOtherApps.size}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1C1B1F)
                            )
                        }
                    }
                }

                item {
                    if (filteredOtherApps.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotEmpty()) "No apps found matching \"$searchQuery\"" else "No matching apps found",
                                    fontSize = 13.sp,
                                    color = Color(0xFF79747E),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column {
                                filteredOtherApps.take(100).forEachIndexed { index, app ->
                                    AppBlockRow(
                                        app = app,
                                        onConfigure = { selectedAppForConfig = app },
                                        onQuickTogglePermanent = { block ->
                                            viewModel.blockAppPermanently(app.packageName, block)
                                        },
                                        onRemoveBlock = null
                                    )
                                    if (index < minOf(filteredOtherApps.lastIndex, 99)) {
                                        HorizontalDivider(
                                            color = Color(0xFFE0E0E0),
                                            thickness = 1.dp,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Custom App Manual Insertion Dialog
    if (showCustomAddDialog) {
        AlertDialog(
            onDismissRequest = { 
                showCustomAddDialog = false
                customAppName = ""
                customAppPackage = ""
            },
            title = { Text("Add Custom Package to Block", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Enter the package name of any app on your phone to add it directly to the blocker guard list.",
                        fontSize = 12.sp,
                        color = Color(0xFF49454F)
                    )
                    OutlinedTextField(
                        value = customAppName,
                        onValueChange = { customAppName = it },
                        label = { Text("App Label (e.g. Candy Crush)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customAppPackage,
                        onValueChange = { customAppPackage = it.trim().lowercase() },
                        label = { Text("Package Name (e.g. com.king.candycrush)") },
                        placeholder = { Text("com.example.app") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customAppPackage.isNotBlank()) {
                            val name = if (customAppName.isBlank()) customAppPackage.substringAfterLast(".") else customAppName
                            viewModel.saveAppConfig(customAppPackage, isPermanent = true, startTime = null, endTime = null, limitMinutes = 0)
                            showCustomAddDialog = false
                            customAppName = ""
                            customAppPackage = ""
                        }
                    },
                    enabled = customAppPackage.isNotBlank()
                ) {
                    Text("Add and Block")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showCustomAddDialog = false
                        customAppName = ""
                        customAppPackage = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    selectedAppForConfig?.let { app ->
        AppConfigDialog(
            app = app,
            onDismiss = { selectedAppForConfig = null },
            onSave = { isPermanent, start, end, limit ->
                viewModel.saveAppConfig(app.packageName, isPermanent, start, end, limit)
                selectedAppForConfig = null
            },
            onRemove = {
                viewModel.removeAppBlock(app.packageName)
                selectedAppForConfig = null
            }
        )
    }
}

@Composable
fun AppBlockRow(
    app: DeviceApp,
    onConfigure: () -> Unit,
    onQuickTogglePermanent: (Boolean) -> Unit,
    onRemoveBlock: (() -> Unit)? = null
) {
    val (bgColor, textColor) = getPastelColorsForApp(app.label)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConfigure() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Icon Custom Initials matching the HTML's color design
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = bgColor, shape = RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.label.take(2).uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color(0xFF1C1B1F),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // Subtitle indicating block configurations
            val config = app.config
            if (config != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (config.isPermanent) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE8DEF8), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Locked", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6750A4))
                        }
                    }
                    if (config.startTime != null && config.endTime != null) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFFEBEE), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("${config.startTime} - ${config.endTime}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                        }
                    }
                    if (config.dailyLimitMinutes > 0) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFFF3E0), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Limit: ${config.dailyLimitMinutes}m", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                        }
                    }
                }
            } else {
                Text(
                    text = "Tap to configure limits/schedules",
                    fontSize = 12.sp,
                    color = Color(0xFF79747E)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (app.config != null && onRemoveBlock != null) {
            IconButton(
                onClick = onRemoveBlock,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove Block",
                    tint = Color(0xFFC62828)
                )
            }
        } else {
            Switch(
                checked = app.config != null,
                onCheckedChange = { onQuickTogglePermanent(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF6750A4),
                    uncheckedThumbColor = Color(0xFF79747E),
                    uncheckedTrackColor = Color(0xFFE0E0E0)
                )
            )
        }
    }
}

@Composable
fun AppConfigDialog(
    app: DeviceApp,
    onDismiss: () -> Unit,
    onSave: (isPermanent: Boolean, startTime: String?, endTime: String?, limitMinutes: Int) -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val config = app.config

    var isPermanent by remember { mutableStateOf(config?.isPermanent ?: true) }
    var startHourText by remember { mutableStateOf(config?.startTime ?: "09:00") }
    var endHourText by remember { mutableStateOf(config?.endTime ?: "17:00") }
    var limitMinutesText by remember { mutableStateOf(config?.dailyLimitMinutes?.toString() ?: "0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Lock option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Permanent Lock", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Block this app completely", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isPermanent, onCheckedChange = { isPermanent = it })
                }

                // If not permanently locked, show schedules & limits
                AnimatedVisibility(
                    visible = !isPermanent,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Divider()
                        Text("Custom Schedule Block", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    showTimePickerDialog(context, startHourText) { startHourText = it }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Start: $startHourText", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    showTimePickerDialog(context, endHourText) { endHourText = it }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("End: $endHourText", fontSize = 12.sp)
                            }
                        }

                        Divider()
                        Text("Daily Usage Limit (minutes)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("0 means no usage limit", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        OutlinedTextField(
                            value = limitMinutesText,
                            onValueChange = { limitMinutesText = it.filter { char -> char.isDigit() } },
                            label = { Text("Minutes limit per day") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limit = limitMinutesText.toIntOrNull() ?: 0
                    if (isPermanent) {
                        onSave(true, null, null, 0)
                    } else {
                        onSave(false, startHourText, endHourText, limit)
                    }
                }
            ) {
                Text("Save Guard")
            }
        },
        dismissButton = {
            Row {
                if (config != null) {
                    TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Remove Block")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

fun showTimePickerDialog(context: Context, initialTime: String, onTimeSelected: (String) -> Unit) {
    val calendar = Calendar.getInstance()
    var hour = calendar.get(Calendar.HOUR_OF_DAY)
    var minute = calendar.get(Calendar.MINUTE)

    try {
        val parts = initialTime.split(":")
        hour = parts[0].toInt()
        minute = parts[1].toInt()
    } catch (e: Exception) {
        // ignore
    }

    android.app.TimePickerDialog(context, { _, selectedHour, selectedMinute ->
        val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute)
        onTimeSelected(formattedTime)
    }, hour, minute, true).show()
}

// Tab 2: Social Guard (Reels/Shorts Blockers)
@Composable
fun SocialGuardTab(viewModel: BlockerViewModel) {
    val igReels by viewModel.reelsInstagram.collectAsStateWithLifecycle()
    val fbReels by viewModel.reelsFacebook.collectAsStateWithLifecycle()
    val ytShorts by viewModel.reelsYoutube.collectAsStateWithLifecycle()
    val ttBlocks by viewModel.reelsTiktok.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val isAccessibilityEnabled = remember { isAccessibilityServiceEnabled(context, ReelsAccessibilityService::class.java) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "Reels & Shorts Blocker",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF1C1B1F)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Blocks vertical video scroll windows inside social applications using the accessibility engine, without blocking the entire app itself.",
                fontSize = 13.sp,
                color = Color(0xFF49454F)
            )
        }

        // Accessibility service indicator card matching the spec
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F2FA)),
            border = BorderStroke(1.dp, Color(0xFFCAC4D0))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("🛡️", fontSize = 20.sp)
                Column {
                    Text(
                        text = "Accessibility Service",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color(0xFF1C1B1F)
                    )
                    Text(
                        text = if (isAccessibilityEnabled) {
                            "Running properly. Website and Reels detection active in Chrome and Instagram."
                        } else {
                            "Accessibility service is disabled. Tap on the dashboard overlay setup above to enable it for Reels, Shorts, and browser filtering."
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF49454F),
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Beautiful single card with dividers containing all Social settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                SocialGuardRow(
                    title = "Instagram Reels Guard",
                    description = "Blocks the reels viewer tab in Instagram",
                    checked = igReels,
                    iconChar = "IG",
                    onCheckedChange = { viewModel.setSocialSetting("block_instagram_reels", it) }
                )
                Divider(color = Color(0xFFE0E0E0), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SocialGuardRow(
                    title = "Facebook Reels & Watch",
                    description = "Instantly redirects back when clicking on watch feeds",
                    checked = fbReels,
                    iconChar = "FB",
                    onCheckedChange = { viewModel.setSocialSetting("block_facebook_reels", it) }
                )
                Divider(color = Color(0xFFE0E0E0), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SocialGuardRow(
                    title = "YouTube Shorts Guard",
                    description = "Closes shorts windows as soon as they appear",
                    checked = ytShorts,
                    iconChar = "YT",
                    onCheckedChange = { viewModel.setSocialSetting("block_youtube_shorts", it) }
                )
                Divider(color = Color(0xFFE0E0E0), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SocialGuardRow(
                    title = "TikTok Loop Guard",
                    description = "Closes TikTok application looping scrolling",
                    checked = ttBlocks,
                    iconChar = "TK",
                    onCheckedChange = { viewModel.setSocialSetting("block_tiktok", it) }
                )
            }
        }
    }
}

@Composable
fun SocialGuardRow(
    title: String,
    description: String,
    checked: Boolean,
    iconChar: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val (bgColor, textColor) = getPastelColorsForApp(iconChar)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pastel color icons for social apps
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = bgColor, shape = RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = iconChar,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color(0xFF1C1B1F)
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF79747E),
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF6750A4),
                uncheckedThumbColor = Color(0xFF79747E),
                uncheckedTrackColor = Color(0xFFE0E0E0)
            )
        )
    }
}

// Tab 3: Website Blocker UI
@Composable
fun WebsiteBlockerTab(viewModel: BlockerViewModel) {
    val websites by viewModel.blockedWebsites.collectAsStateWithLifecycle()
    var inputDomain by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "Website Blocker",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF1C1B1F)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Blocks specified domains in mobile browsers. Works fully offline.",
                fontSize = 13.sp,
                color = Color(0xFF49454F)
            )
        }

        // Add website row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputDomain,
                onValueChange = { inputDomain = it },
                placeholder = { Text("e.g. facebook.com", color = Color(0xFF79747E)) },
                label = { Text("Block Website Domain") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6750A4),
                    unfocusedBorderColor = Color(0xFFCAC4D0),
                    focusedLabelColor = Color(0xFF6750A4),
                    unfocusedLabelColor = Color(0xFF49454F)
                ),
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (inputDomain.isNotBlank() && inputDomain.contains(".")) {
                        viewModel.addWebsite(inputDomain.trim().lowercase())
                        inputDomain = ""
                        Toast.makeText(context, "Domain Added", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Please enter a valid domain", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Currently Blocked Websites",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color(0xFF49454F)
            )
            Box(
                modifier = Modifier
                    .background(Color(0xFFCAC4D0).copy(alpha = 0.3f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${websites.size}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1C1B1F)
                )
            }
        }

        if (websites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🌐", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No websites blocked yet. Add one above!",
                        color = Color(0xFF79747E),
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(websites, key = { web -> web.domain }) { web ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFF1F3F4), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = Color(0xFF5F6368),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = web.domain,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = Color(0xFF1C1B1F)
                            )
                            IconButton(onClick = { viewModel.removeWebsite(web.domain) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color(0xFFD32F2F)
                                )
                            }
                        }
                        if (web != websites.last()) {
                            Divider(
                                color = Color(0xFFE0E0E0),
                                thickness = 1.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// System Permission Helper Functions
fun hasUsageAccessPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    val expectedComponentName = ComponentName(context, service)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}
