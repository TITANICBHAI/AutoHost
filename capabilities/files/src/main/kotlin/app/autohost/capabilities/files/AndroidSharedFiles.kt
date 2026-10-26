package app.autohost.capabilities.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager

/**
 * P8-07: the Android implementation behind [FilesSharedProvider.System].
 *
 * SAF traversal uses DocumentsContract document IDs built from the picked tree + the relative
 * segments (the plan's honest model: the user picked the folder, the task operates inside it).
 * MediaStore rows are scoped to the well-known collection per media type; writes on API 29+
 * need no permission for the app's own contributions (the provider still gates on the scope
 * flag, the runtime check only guards reads of other apps' rows).
 */
class AndroidSharedFiles(private val context: Context) : FilesSharedProvider.System {

    private val resolver: ContentResolver = context.contentResolver

    override fun listChildren(treeUri: String, segments: List<String>): List<FilesSharedProvider.System.Entry>? =
        runCatching {
            val parent = resolveDir(treeUri, segments) ?: return null
            val children = resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent)),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            ) ?: return null
            children.use { cursor ->
                val rows = ArrayList<FilesSharedProvider.System.Entry>()
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2) ?: ""
                    rows += FilesSharedProvider.System.Entry(
                        name = cursor.getString(1) ?: "?",
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = cursor.getLong(3),
                    )
                }
                rows
            }
        }.getOrNull()

    override fun read(treeUri: String, segments: List<String>): ByteArray? = runCatching {
        val doc = resolveDocument(treeUri, segments) ?: return null
        resolver.openInputStream(doc)?.use { input -> input.readBytes() }
    }.getOrNull()

    override fun write(treeUri: String, segments: List<String>, bytes: ByteArray): Boolean = runCatching {
        val name = segments.last()
        val dirSegments = segments.dropLast(1)
        val parent = resolveDir(treeUri, dirSegments) ?: return false
        val existing = findChild(parent, name)
        val target = existing ?: DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: return false
        resolver.openOutputStream(target)?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: return false
        true
    }.getOrDefault(false)

    override fun delete(treeUri: String, segments: List<String>): Boolean = runCatching {
        val doc = resolveDocument(treeUri, segments) ?: return false
        DocumentsContract.deleteDocument(resolver, doc)
    }.getOrDefault(false)

    override fun mediaList(mediaType: String, limit: Int): List<FilesSharedProvider.System.MediaRow>? = runCatching {
        val collection = collectionFor(mediaType) ?: return null
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val rows = ArrayList<FilesSharedProvider.System.MediaRow>()
            while (cursor.moveToNext() && rows.size < limit) {
                rows += FilesSharedProvider.System.MediaRow(
                    id = cursor.getLong(0).toString(),
                    displayName = cursor.getString(1) ?: "?",
                    mediaType = mediaType,
                    size = cursor.getLong(2),
                )
            }
            rows
        }
    }.getOrNull()

    override fun mediaRead(id: String): ByteArray? = runCatching {
        val idLong = id.toLongOrNull() ?: return null
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns._ID}=?",
            arrayOf(idLong.toString()),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            resolver.openInputStream(collection.buildUpon().appendPath(idLong.toString()).build())
                ?.use { input -> input.readBytes() }
        }
    }.getOrNull()

    override fun mediaWrite(name: String, bytes: ByteArray, mediaType: String): String? = runCatching {
        val collection = collectionFor(mediaType) ?: return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(mediaType))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: return null
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri.lastPathSegment
    }.getOrNull()

    override fun mediaDelete(id: String): Boolean = runCatching {
        val idLong = id.toLongOrNull() ?: return false
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.delete(collection, "${MediaStore.MediaColumns._ID}=?", arrayOf(idLong.toString())) > 0
    }.getOrDefault(false)

    override fun hasMediaReadPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    // ---- traversal helpers -------------------------------------------------------------------

    private fun resolveDir(treeUri: String, segments: List<String>): Uri? {
        val root = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))
        return segments.fold(root) { parent, name ->
            val child = findChild(parent, name) ?: return null
            child
        }
    }

    private fun resolveDocument(treeUri: String, segments: List<String>): Uri? =
        if (segments.isEmpty()) {
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))
            }.getOrNull()
        } else {
            resolveDir(treeUri, segments)
        }

    private fun findChild(parent: Uri, name: String): Uri? {
        val children = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        ) ?: return null
        children.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun collectionFor(mediaType: String): Uri? = when (mediaType) {
        "images" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        "files" -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> null
    }

    private fun relativePathFor(mediaType: String): String = when (mediaType) {
        "images" -> Environment.DIRECTORY_PICTURES + "/"
        "video" -> Environment.DIRECTORY_MOVIES + "/"
        "audio" -> Environment.DIRECTORY_MUSIC + "/"
        else -> Environment.DIRECTORY_DOCUMENTS + "/"
    }
}
