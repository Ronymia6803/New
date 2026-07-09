package com.example.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.AppDatabase
import com.example.model.BlockerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class ReelsAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: BlockerRepository
    private var blockedWebsitesList: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(this)
        repository = BlockerRepository(db.blockerDao())
        
        // Load and collect the website domains reactively
        serviceScope.launch {
            repository.allBlockedWebsites.collect { websites ->
                blockedWebsitesList = websites.map { it.domain.lowercase(Locale.ROOT).trim() }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        val packageName = event.packageName?.toString() ?: ""

        // 1. Social Media Reels/Shorts Block check
        if (packageName == "com.instagram.android" ||
            packageName == "com.facebook.katana" ||
            packageName == "com.facebook.lite" ||
            packageName == "com.google.android.youtube" ||
            packageName == "com.zhiliaoapp.musically" ||
            packageName == "com.ss.android.ugc.aweme") {
            
            if (shouldBlockReelsOrShorts(rootNode)) {
                Log.d("ReelsBlocker", "Reels/Shorts content detected! Triggering backing out.")
                performGlobalAction(GLOBAL_ACTION_BACK)
                rootNode.recycle()
                return
            }
        }

        // 2. Website URL Blocking in well-known browsers
        if (packageName == "com.android.chrome" ||
            packageName == "com.microsoft.emmx" ||
            packageName == "org.mozilla.firefox" ||
            packageName == "com.brave.browser" ||
            packageName == "com.duckduckgo.mobile.android" ||
            packageName == "com.opera.browser" ||
            packageName == "com.sec.android.app.sbrowser") {
            checkAndBlockWebsites(rootNode)
        }

        rootNode.recycle()
    }

    override fun onInterrupt() {
        // Required accessibility method
    }

    private fun shouldBlockReelsOrShorts(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

        // Check if text or description matches keywords
        if (text.contains("reels") || text.contains("shorts") || 
            contentDesc.contains("reels") || contentDesc.contains("shorts")) {
            return true
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (shouldBlockReelsOrShorts(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun checkAndBlockWebsites(node: AccessibilityNodeInfo) {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        
        // Address bar usually is EditText or viewId containing 'url' or 'address'
        if (className.contains("EditText", ignoreCase = true) || 
            node.viewIdResourceName?.contains("url", ignoreCase = true) == true ||
            node.viewIdResourceName?.contains("address", ignoreCase = true) == true) {
            
            val urlString = text.lowercase(Locale.ROOT).trim()
            if (urlString.isNotEmpty()) {
                for (blockedDbDomain in blockedWebsitesList) {
                    if (urlString.contains(blockedDbDomain) || blockedDbDomain.contains(urlString)) {
                        Log.d("WebsiteBlocker", "Blocked domain '$blockedDbDomain' detected in URL string '$urlString'. Blocking!")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        break
                    }
                }
            }
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            checkAndBlockWebsites(child)
            child.recycle()
        }
    }
}
