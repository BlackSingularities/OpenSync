package com.blacksingularities.opensync.sources

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.blacksingularities.opensync.BuildConfig
import com.blacksingularities.opensync.data.SourceType
import com.blacksingularities.opensync.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Dowolne foldery wybrane przez użytkownika (SAF), przeglądane rekurencyjnie. */
object FolderSource : Source {
    override val type = SourceType.FOLDERS
    override fun permissions() = emptyList<String>()

    override fun collect(c: CollectContext) {
        val cr = c.context.contentResolver
        val labels = HashSet<String>()
        for (spec in c.job.folders) {
            val tree = Uri.parse(spec.uri)
            var label = safeName(spec.label.ifBlank { "folder" })
            if (!labels.add(label)) label += "-" + labels.size
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            if (rootId == null) { c.warn("Folder „${spec.label}” jest niedostępny – wybierz go ponownie"); continue }
            val stack = ArrayDeque<Pair<String, String>>()
            stack += rootId to "folders/$label"
            var any = false
            while (stack.isNotEmpty()) {
                val (docId, path) = stack.removeLast()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val cursor = runCatching {
                    cr.query(
                        children,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        ),
                        null, null, null,
                    )
                }.getOrNull()
                if (cursor == null) { c.warn("Brak dostępu: $path"); continue }
                any = true
                cursor.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getString(0) ?: continue
                        val name = safeName(cur.getString(1) ?: continue)
                        val childPath = "$path/$name"
                        if (cur.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack += id to childPath
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            c.add(Item(childPath, cur.getLong(3), cur.getLong(4), "doc:$id") { cr.openInputStream(uri) ?: error("Plik niedostępny") })
                        }
                    }
                }
            }
            if (!any) c.warn("Folder „${spec.label}” jest pusty lub niedostępny")
        }
    }
}

/** Lista zainstalowanych aplikacji i – opcjonalnie – ich pliki APK (do ponownej instalacji). */
object AppsSource : Source {
    override val type = SourceType.APPS
    override fun permissions() = emptyList<String>()

    override fun collect(c: CollectContext) {
        val pm = c.context.packageManager
        val arr = JSONArray()
        val packages = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        for (p in packages.sortedBy { it.packageName }) {
            val ai = p.applicationInfo ?: continue
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            arr.put(
                JSONObject()
                    .put("package", p.packageName)
                    .put("label", runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(p.packageName))
                    .put("version", p.versionName ?: "")
                    .put("versionCode", p.longVersionCode)
                    .put("installed", p.firstInstallTime)
                    .put("updated", p.lastUpdateTime)
                    .put("system", system)
                    .put("installer", runCatching { pm.getInstallSourceInfo(p.packageName).installingPackageName }.getOrNull() ?: ""),
            )
            if (c.job.filters.includeApks && !system) {
                val files = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                for (path in files) {
                    val f = File(path)
                    if (!f.canRead()) continue
                    val apkPath = "apps/apk/${safeName(p.packageName)}/${safeName(f.name)}"
                    c.add(Item(apkPath, f.length(), f.lastModified(), "apk:$path:${p.longVersionCode}", false) { f.inputStream() })
                }
            }
        }
        c.addGenerated("apps/apps.json", JSONObject().put("format", "opensync-apps").put("version", 1).put("apps", arr).toString(2))
    }
}

object DeviceSource : Source {
    override val type = SourceType.DEVICE
    override fun permissions() = emptyList<String>()

    override fun collect(c: CollectContext) {
        val store = Store.get(c.context)
        val info = JSONObject()
            .put("device", store.deviceName)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("openSync", BuildConfig.VERSION_NAME)
            .put("time", System.currentTimeMillis())
        c.addGenerated("device/info.json", info.toString(2))
        c.addGenerated("device/opensync-settings.json", store.exportConfig())
    }
}
