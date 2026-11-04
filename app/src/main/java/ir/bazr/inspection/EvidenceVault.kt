package ir.bazr.inspection

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Evidence imported from camera/SAF is copied into an app-private, AES-GCM encrypted vault.
 * Photos are never written into public gallery storage. Android backup is disabled for this app.
 * Legacy content:// references from 0.6 drafts remain readable when their grant still exists.
 */
class EvidenceVault(private val context: Context, private val store: SecureStore) {
    data class Evidence(val bytes: ByteArray, val mime: String, val name: String)
    private val folder = File(context.filesDir, "inspection_evidence").apply { mkdirs() }
    private val keyAlias = "bazr_evidence_aes_v1"
    private val allowed = setOf("image/jpeg", "image/png", "image/webp", "application/pdf")
    private val maxSize = 12 * 1024 * 1024
    private val idPattern = Regex("[a-f0-9-]{36}")

    private fun encryptionKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private fun readLimited(resolver: ContentResolver, uri: Uri): ByteArray {
        return resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                if (n == 0) continue
                if (output.size() + n > maxSize) throw IOException("مستند باید کمتر از ۱۲ مگابایت باشد")
                output.write(buf, 0, n)
            }
            output.toByteArray()
        } ?: throw IOException("فایل انتخابی قابل خواندن نیست")
    }

    fun importUri(resolver: ContentResolver, uri: Uri, camera: Boolean = false): String {
        val mime = if (camera) "image/jpeg" else (resolver.getType(uri)?.lowercase() ?: "")
        if (mime !in allowed) throw IOException("فقط JPEG، PNG، WEBP یا PDF پشتیبانی می‌شود")
        val bytes = readLimited(resolver, uri)
        if (bytes.isEmpty()) throw IOException("فایل خالی است")
        var name = if (camera) "inspection-${System.currentTimeMillis()}.jpg" else "evidence"
        if (!camera) resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        name = name.replace(Regex("[\\r\\n\\\\/]"), "_").take(90)
        val id = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val ciphertext = cipher.doFinal(bytes)
        val target = File(folder, "$id.bin")
        val temporary = File(folder, "$id.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(byteArrayOf(1, cipher.iv.size.toByte()))
                output.write(cipher.iv)
                output.write(ciphertext)
            }
            if (!temporary.renameTo(target)) throw IOException("ذخیره امن مستند ناموفق بود")
            store.save("evidence:$id", JSONObject().put("mime", mime).put("name", name)
                .put("size", bytes.size).toString())
            return "vault:$id"
        } catch (error: Exception) {
            temporary.delete(); target.delete(); throw error
        }
    }

    fun read(reference: String, resolver: ContentResolver): Evidence {
        if (!reference.startsWith("vault:")) {
            // Backward compatibility with drafts created by versions <= 0.6.
            val uri = Uri.parse(reference)
            val mime = resolver.getType(uri)?.lowercase() ?: throw IOException("نوع فایل قدیمی نامشخص است؛ دوباره انتخاب کنید")
            if (mime !in allowed) throw IOException("نوع مستند پشتیبانی نمی‌شود")
            return Evidence(readLimited(resolver, uri), mime, "evidence")
        }
        val id = reference.removePrefix("vault:")
        if (!idPattern.matches(id)) throw IOException("شناسه مستند نامعتبر است")
        val meta = store.load("evidence:$id")?.let(::JSONObject) ?: throw IOException("اطلاعات مستند موجود نیست")
        val file = File(folder, "$id.bin")
        if (!file.isFile || file.length() > maxSize + 64L) throw IOException("مستند رمزنگاری‌شده یافت نشد")
        val source = file.readBytes()
        if (source.size < 30 || source[0] != 1.toByte()) throw IOException("ساختار مستند نامعتبر است")
        val length = source[1].toInt() and 255
        if (length != 12 || source.size < 2 + length + 16) throw IOException("سرآیند مستند نامعتبر است")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, source.copyOfRange(2, 2 + length)))
        val decoded = try { cipher.doFinal(source.copyOfRange(2 + length, source.size)) }
            catch (_: Exception) { throw IOException("اعتبارسنجی رمزنگاری مستند ناموفق بود") }
        if (decoded.size > maxSize) throw IOException("حجم مستند بیش از حد مجاز است")
        return Evidence(decoded, meta.getString("mime"), meta.optString("name", "evidence"))
    }

    fun metadata(reference: String): String {
        if (!reference.startsWith("vault:")) return "پیوست نسخه قبلی · برای ارسال، دسترسی فایل باید معتبر باشد"
        val id = reference.removePrefix("vault:")
        val j = store.load("evidence:$id")?.let { runCatching { JSONObject(it) }.getOrNull() }
        return j?.let { "${it.optString("name", "مستند")} · ${it.optLong("size") / 1024} کیلوبایت" }
            ?: "مستند موجود نیست"
    }

    fun imagePreview(reference: String, resolver: ContentResolver, maxDimension: Int = 640): Bitmap? {
        val evidence = read(reference, resolver)
        if (!evidence.mime.startsWith("image/")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, options)
    }

    fun delete(reference: String) {
        if (!reference.startsWith("vault:")) return // Never modify a gallery or SAF file.
        val id = reference.removePrefix("vault:")
        if (!idPattern.matches(id)) return
        File(folder, "$id.bin").delete()
        store.clear("evidence:$id")
    }
}
