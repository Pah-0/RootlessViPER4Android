package com.llsl.viper4android.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.llsl.viper4android.utils.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class LibraryKind(
    val dirName: String,
    val extensions: Set<String>,
) {
    KERNEL("Kernel", setOf("wav", "irs")),
    VDC("DDC", setOf("vdc")),
}

// One selectable kernel or VDC. `key` is what the effect settings store: the
// file name (kernels) or name without extension (VDCs) for files imported into
// the app, as upstream does, or the document URI for files in a linked folder.
data class LibraryEntry(
    val kind: LibraryKind,
    val key: String,
    val label: String,
    // Where it is: "" for imported files, else the path within a linked folder.
    val folder: String,
) {
    val isLinked: Boolean get() = isLinkedKey(key)
}

fun isLinkedKey(key: String): Boolean = key.startsWith("content://")

// Kernels and VDCs, from the app's own folders and from folders the user links
// through the system picker, which are read in place rather than copied.
//
// Listing never reads file contents, and each folder takes one query (asking
// per file is what makes large libraries slow). The last listing is cached, so
// it shows at once on start while a rescan runs in the background.
class FileLibrary private constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "FileLibrary"
        private const val PREFS = "file_library"
        private const val PREF_FOLDERS = "linked_folders"
        private const val CACHE_FILE = "file_library.json"
        private const val MAX_DEPTH = 8

        @Volatile
        private var instance: FileLibrary? = null

        fun get(context: Context): FileLibrary =
            instance ?: synchronized(this) {
                instance ?: FileLibrary(context.applicationContext).also { instance = it }
            }

        // "primary:Music/IRS" -> "Music/IRS"
        fun folderLabel(treeUri: String): String =
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }
                .getOrNull()
                ?.substringAfter(':')
                ?.ifEmpty { "/" } ?: treeUri
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val entries: StateFlow<List<LibraryEntry>>
        field: MutableStateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList())

    val linkedFolders: StateFlow<List<String>>
        field: MutableStateFlow<List<String>> =
            MutableStateFlow(prefs.getStringSet(PREF_FOLDERS, emptySet()).orEmpty().sorted())

    val isScanning: StateFlow<Boolean>
        field: MutableStateFlow<Boolean> = MutableStateFlow(false)

    init {
        scope.launch {
            readCache()?.let { entries.value = it }
            scan()
        }
    }

    fun refresh() {
        scope.launch { scan() }
    }

    fun addFolder(treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Can't keep access to $treeUri", e)
            return
        }
        setFolders(linkedFolders.value + treeUri.toString())
        refresh()
    }

    fun removeFolder(treeUri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setFolders(linkedFolders.value - treeUri)
        entries.value = entries.value.filterNot { it.isLinked && it.key.startsWith(treeUri) }
        refresh()
    }

    fun label(
        kind: LibraryKind,
        key: String,
    ): String =
        entries.value.firstOrNull { it.kind == kind && it.key == key }?.label
            ?: if (isLinkedKey(key)) Uri.parse(key).lastPathSegment?.substringAfterLast('/') ?: key else key

    // Contents of an entry, or null if it's gone. Call off the main thread.
    fun read(
        kind: LibraryKind,
        key: String,
    ): ByteArray? =
        try {
            if (isLinkedKey(key)) {
                context.contentResolver.openInputStream(Uri.parse(key))?.use { it.readBytes() }
            } else {
                val name = if (kind == LibraryKind.VDC) "$key.vdc" else key
                File(localDir(kind), name).takeIf { it.isFile }?.readBytes()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Can't read $key", e)
            null
        }

    private fun localDir(kind: LibraryKind): File = File(context.getExternalFilesDir(null), kind.dirName).apply { mkdirs() }

    private fun setFolders(folders: List<String>) {
        val unique = folders.distinct().sorted()
        prefs.edit().putStringSet(PREF_FOLDERS, unique.toSet()).apply()
        linkedFolders.value = unique
    }

    private suspend fun scan() =
        scanLock.withLock {
            isScanning.value = true
            try {
                val found = mutableListOf<LibraryEntry>()
                for (kind in LibraryKind.entries) {
                    localDir(kind).listFiles()?.forEach { file ->
                        if (file.isFile && file.extension.lowercase() in kind.extensions) {
                            val key = if (kind == LibraryKind.VDC) file.nameWithoutExtension else file.name
                            found += LibraryEntry(kind, key, key, "")
                        }
                    }
                }
                for (tree in linkedFolders.value) {
                    val treeUri = Uri.parse(tree)
                    val rootId =
                        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
                    walk(treeUri, rootId, folderLabel(tree), 0, found)
                }
                val sorted = found.sortedWith(compareBy({ it.label.lowercase() }, { it.folder }))
                entries.value = sorted
                writeCache(sorted)
            } finally {
                isScanning.value = false
            }
        }

    private fun walk(
        tree: Uri,
        docId: String,
        path: String,
        depth: Int,
        out: MutableList<LibraryEntry>,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val subdirs = mutableListOf<Pair<String, String>>()
        try {
            context.contentResolver
                .query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            subdirs += id to name
                            continue
                        }
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val kind = LibraryKind.entries.firstOrNull { ext in it.extensions } ?: continue
                        val label = if (kind == LibraryKind.VDC) name.substringBeforeLast('.') else name
                        val key = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                        out += LibraryEntry(kind, key, label, path)
                    }
                }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Can't list $path: ${e.message}")
            return
        }
        if (depth >= MAX_DEPTH) return
        for ((id, name) in subdirs) walk(tree, id, "$path/$name", depth + 1, out)
    }

    private fun cacheFile() = File(context.filesDir, CACHE_FILE)

    private fun readCache(): List<LibraryEntry>? =
        try {
            val file = cacheFile()
            if (!file.isFile) {
                null
            } else {
                val array = JSONArray(file.readText())
                List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    LibraryEntry(
                        LibraryKind.valueOf(o.getString("kind")),
                        o.getString("key"),
                        o.getString("label"),
                        o.getString("folder"),
                    )
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Ignoring unreadable cache: ${e.message}")
            null
        }

    private fun writeCache(list: List<LibraryEntry>) {
        try {
            val array = JSONArray()
            for (e in list) {
                array.put(
                    JSONObject()
                        .put("kind", e.kind.name)
                        .put("key", e.key)
                        .put("label", e.label)
                        .put("folder", e.folder),
                )
            }
            val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(cacheFile())
        } catch (e: Exception) {
            FileLogger.w(TAG, "Can't write cache: ${e.message}")
        }
    }
}
