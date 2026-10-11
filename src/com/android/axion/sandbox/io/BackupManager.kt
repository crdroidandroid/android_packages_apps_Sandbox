/*
 * Copyright (C) 2025-2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.axion.sandbox.io

import android.app.AxSandboxManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class BackupManager(private val context: Context) {
    private val TAG = "BackupManager"
    private val VERSION = 1

    private val sandboxManager = context.getSystemService(AxSandboxManager::class.java)

    companion object {
        val SPOOF_KEYS = listOf(
            "adb_enabled",
            "development_settings_enabled",
            "adb_wifi_enabled",
            "package_verifier_user_consent",
            "verify_apps_over_usb",
            "accessibility_enabled",
            "enabled_accessibility_services",
            "accessibility_display_inversion_enabled",
            AxSandboxManager.SPOOF_SELINUX_ENFORCING,
            AxSandboxManager.SPOOF_ROOT_ENVIRONMENT
        )
    }

    suspend fun exportConfig(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (sandboxManager == null) return@withContext false

        try {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("timestamp", System.currentTimeMillis())

            val appsArray = JSONArray()
            val lockablePackages = sandboxManager.getLockablePackages() ?: emptyList()
            val hiddenPackages = sandboxManager.getHiddenPackages() ?: emptyList()
            val allPackages = (lockablePackages + hiddenPackages).toSet()

            for (packageName in allPackages) {
                val appObj = JSONObject()
                appObj.put("packageName", packageName)
                
                val isLocked = try { sandboxManager.getAppLockState(packageName).hasAppLock() } catch (e: Exception) { false }
                val isHidden = try { sandboxManager.isPackageHidden(packageName) ?: false } catch (e: Exception) { false }
                val isSandboxed = try { sandboxManager.isPackageSandboxed(packageName) ?: false } catch (e: Exception) { false }
                
                appObj.put("isLocked", isLocked)
                appObj.put("isHidden", isHidden)
                appObj.put("isSandboxed", isSandboxed)

                val gids = sandboxManager.getRestrictedGids(packageName)
                if (gids != null && gids.isNotEmpty()) {
                    val gidsArray = JSONArray()
                    gids.forEach { gidsArray.put(it) }
                    appObj.put("restrictedGids", gidsArray)
                }

                val isolation = sandboxManager.isSandboxDataIsolationEnabled(packageName)
                appObj.put("dataIsolation", isolation)

                val spoofObj = JSONObject()
                for (key in SPOOF_KEYS) {
                    if (sandboxManager.isSpoofSettingEnabled(packageName, key)) {
                        spoofObj.put(key, true)
                    }
                }
                if (spoofObj.length() > 0) {
                    appObj.put("spoofSettings", spoofObj)
                }
                appsArray.put(appObj)
            }

            root.put("apps", appsArray)

            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(root.toString(2).toByteArray())
            } ?: return@withContext false

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting config", e)
            false
        }
    }

    suspend fun importConfig(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (sandboxManager == null) return@withContext false

        try {
            val content = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { isStream ->
                BufferedReader(InputStreamReader(isStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        content.append(line)
                    }
                }
            } ?: return@withContext false

            val root = JSONObject(content.toString())
            val appsArray = root.optJSONArray("apps") ?: return@withContext false

            val pm = context.packageManager

            for (i in 0 until appsArray.length()) {
                try {
                    val appObj = appsArray.getJSONObject(i)
                    val packageName = appObj.getString("packageName")
                    
                    // Skip if the package is not installed on this device
                    try {
                        pm.getPackageInfo(packageName, 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping config for uninstalled package: $packageName")
                        continue
                    }
                    
                    val isLocked = appObj.optBoolean("isLocked", false)
                    val isHidden = appObj.optBoolean("isHidden", false)
                    val isSandboxed = appObj.optBoolean("isSandboxed", false)

                    if (isLocked) {
                        sandboxManager.addLockedApp(packageName)
                    } else {
                        sandboxManager.removeLockedApp(packageName)
                    }

                    sandboxManager.setPackageHidden(packageName, isHidden)

                    if (isSandboxed) {
                        sandboxManager.addSandboxedPackage(packageName)
                    } else {
                        sandboxManager.removeSandboxedPackage(packageName)
                    }

                    val gidsArray = appObj.optJSONArray("restrictedGids")
                    if (gidsArray != null) {
                        val gids = IntArray(gidsArray.length())
                        for (j in 0 until gidsArray.length()) {
                            gids[j] = gidsArray.getInt(j)
                        }
                        sandboxManager.setRestrictedGids(packageName, gids)
                    } else {
                        sandboxManager.setRestrictedGids(packageName, intArrayOf())
                    }

                    val isolation = appObj.optBoolean("dataIsolation", false)
                    sandboxManager.setSandboxDataIsolationEnabled(packageName, isolation)

                    val spoofObj = appObj.optJSONObject("spoofSettings")
                    for (key in SPOOF_KEYS) {
                        val enabled = spoofObj?.optBoolean(key, false) ?: false
                        sandboxManager.setSpoofSettingEnabled(packageName, key, enabled)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error importing config for an app entry", e)
                    // Continue with the next app
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing config", e)
            false
        }
    }
}
