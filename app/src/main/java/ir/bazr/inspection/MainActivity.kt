package ir.bazr.inspection

import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher

/** Native Android inspector client. Server remains the source of authority. */
class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: SecureStore
    private lateinit var api: ApiClient
    private lateinit var keys: DeviceKeys
    private lateinit var evidenceVault: EvidenceVault
    private lateinit var outbox: SubmissionQueue
    private var accountToken: String? = null  // Web login token: never persisted.
    private var deviceToken: String? = null   // In-memory after normal or biometric unlock.
    private var sessionToken: String? = null  // Short-lived mobile bearer.
    private var serverSnapshot = JSONObject()
    private var selectedMission: JSONObject? = null
    private var selectedMissionKey: String? = null
    private var openDraft: InspectionDraft? = null
    private var pickerQuestion: String? = null
    private var busy = false
    private lateinit var content: LinearLayout
    private lateinit var shell: LinearLayout
    private var loadingBar: LinearProgressIndicator? = null
    private var activeTab = "home"
    private var currentQuestionIndex = 0
    // Official institutional palette; the accents are intentionally restrained.
    private val navy = Color.rgb(8, 31, 56)
    private val teal = Color.rgb(170, 130, 56) // legacy accent identifier; now ceremonial gold
    private val gold = Color.rgb(197, 161, 90)
    private val muted = Color.rgb(103, 117, 135)
    private val ink = Color.rgb(20, 45, 71)
    private val pageBg = Color.rgb(245, 247, 250)
    private val line = Color.rgb(220, 227, 234)
    private val evidencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val question = store.load("pending_picker_question")
        store.clear("pending_picker_question")
        if (uri != null && !question.isNullOrBlank()) importEvidence(uri, question, false)
    }
    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val pending = store.load("pending_capture")?.let { runCatching { JSONObject(it) }.getOrNull() }
        store.clear("pending_capture")
        if (pending != null) {
            val name = pending.optString("name")
            val question = pending.optString("question")
            if (!name.matches(Regex("capture-[a-f0-9-]{36}\\.jpg"))) return@registerForActivityResult
            val file = File(File(cacheDir, "camera"), name)
            if (success && file.isFile && question.isNotBlank()) {
                // FileProvider hands a camera application a temporary, app-private content Uri.
                importEvidence(FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file), question, true, file)
            } else file.delete()
        }
    }

    private fun importEvidence(uri: Uri, question: String, camera: Boolean, temporary: File? = null) {
        work({
            try { evidenceVault.importUri(contentResolver, uri, camera) }
            finally { temporary?.delete() }
        }) { reference ->
            val draft = openDraft
            if (draft == null || selectedMission == null || outbox.get(draft.key) != null) {
                evidenceVault.delete(reference)
                notifyShort("مأموریت انتخاب‌شده تغییر کرده؛ مستند اضافه نشد")
            } else {
                val previous = draft.attachments.optString(question)
                draft.attachments.put(question, reference)
                draft.save()
                if (previous.isNotBlank()) evidenceVault.delete(previous)
                selectedMission?.let { showMission(it, currentQuestionIndex) }
                notifyShort("مستند به‌صورت رمزنگاری‌شده ذخیره شد")
            }
        }
    }

    private fun launchCamera(question: String) {
        val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
        val name = "capture-${UUID.randomUUID()}.jpg"
        val photo = File(cameraDir, name)
        store.save("pending_capture", JSONObject().put("name", name).put("question", question).toString())
        try {
            cameraCapture.launch(FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", photo))
        } catch (_: Exception) {
            store.clear("pending_capture")
            photo.delete()
            notifyShort("برنامه دوربین در این دستگاه در دسترس نیست")
        }
    }

    private fun chooseEvidence(question: String) {
        AlertDialog.Builder(this).setTitle("افزودن مستند بازرسی")
            .setItems(arrayOf("ثبت عکس با دوربین", "انتخاب عکس یا PDF از گوشی")) { _, which ->
                if (which == 0) launchCamera(question)
                else {
                    store.save("pending_picker_question", question)
                    evidencePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf"))
                }
            }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        keys = DeviceKeys()
        api = ApiClient(BuildConfig.API_BASE_URL)
        evidenceVault = EvidenceVault(this, store)
        outbox = SubmissionQueue(store)
        // Remove unreferenced, temporary plaintext camera files left by interrupted captures.
        val pendingName = store.load("pending_capture")?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }
        File(cacheDir, "camera").listFiles()?.filter { it.name != pendingName && it.lastModified() < System.currentTimeMillis() - 86_400_000L }
            ?.forEach { it.delete() }
        store.getString("device_id") ?: store.putString("device_id", UUID.randomUUID().toString())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedMission != null) showHome("missions")
                else if (activeTab == "outbox") showHome("drafts")
                else if (activeTab != "home" && ::content.isInitialized) showHome("home")
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        val cached = store.load("mobile_sync")
        if (cached != null) try { serverSnapshot = JSONObject(cached) } catch (_: Exception) { }
        when {
            store.biometricEnabled() -> showBiometricLock()
            store.load("device_token") != null -> {
                deviceToken = store.load("device_token")
                showHome()
                connectAndSync()
            }
            store.getString("request_id") != null -> showPending()
            else -> showLogin()
        }
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun deviceId() = store.getString("device_id") ?: error("missing device ID")
    private fun notifyShort(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(text: String, size: Int, bold: Boolean = false, color: Int = ink): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            includeFontPadding = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun gap(height: Int = 12) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    /** Native app shell: fixed toolbar, scrollable content and native bottom navigation. */
    private fun screen(title: String, subtitle: String, withNav: Boolean = false): LinearLayout {
        window.statusBarColor = navy
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pageBg)
        }
        loadingBar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setIndicatorColor(gold)
            trackColor = Color.rgb(230, 236, 243)
            visibility = if (busy) View.VISIBLE else View.GONE
        }
        shell.addView(loadingBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        shell.addView(appTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))
        shell.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(17), dp(18), dp(28))
        }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (withNav) shell.addView(bottomNavigation())
        setContentView(shell)
        if (title.isNotBlank()) label(title, 21, true, navy)
        if (subtitle.isNotBlank()) label(subtitle, 12, false, muted)
        if (title.isNotBlank() || subtitle.isNotBlank()) gap(10)
        return content
    }

    /** Compact institutional app bar. The full-width website banner is intentionally not used. */
    private fun appTopBar(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val mark = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = 0f
            setCardBackgroundColor(navy)
        }
        mark.addView(icon(R.drawable.ic_shield, gold, 25),
            FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
        row.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        names.addView(text("سامانه بازرسی ادارات", 15, true, navy))
        names.addView(text("فرمانداری شهرستان جوانرود", 10, false, muted))
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = TextView(this).apply {
            text = "بازرس"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(navy)
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(Color.rgb(248, 243, 232), 10)
        }
        row.addView(badge)
        wrapper.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        wrapper.addView(View(this).apply { setBackgroundColor(Color.rgb(226, 216, 194)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrapper
    }

    /** Subtly raised two-tone icon, consistent across dashboard and missions. */
    private fun iconBadge(drawable: Int, size: Int = 24): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(13).toFloat()
        strokeWidth = dp(1)
        strokeColor = Color.rgb(222, 200, 151)
        cardElevation = dp(1).toFloat()
        setCardBackgroundColor(Color.rgb(251, 247, 238))
        val image = icon(drawable, navy, size).apply {
            layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER)
            contentDescription = null // descriptive text immediately follows the icon
        }
        addView(image)
    }

    private fun label(value: String, size: Int = 16, bold: Boolean = false, color: Int = ink): TextView {
        val view = text(value, size, bold, color)
        view.setPadding(0, dp(3), 0, dp(6))
        content.addView(view)
        return view
    }
    private fun field(hint: String, value: String = "", password: Boolean = false, numeric: Boolean = false): EditText {
        val wrapper = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = Color.WHITE
            boxStrokeColor = gold
            setBoxCornerRadii(dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat())
            this.hint = hint
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (password) {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                startIconDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_shield)
            } else if (hint.contains("نام کاربری")) {
                startIconDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_person)
            }
            if (startIconDrawable != null) setStartIconTintList(ColorStateList.valueOf(muted))
        }
        val input = TextInputEditText(this).apply {
            setText(value)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint.contains("توضیح") -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (hint.contains("توضیح")) { minLines = 3; maxLines = 5; gravity = Gravity.TOP or Gravity.RIGHT }
        }
        wrapper.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(10)
        content.addView(wrapper, params)
        return input
    }
    private fun makeButton(caption: String, primary: Boolean = true, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = caption
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(13)
            minHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(if (primary) navy else Color.WHITE)
            setTextColor(if (primary) Color.WHITE else navy)
            strokeColor = ColorStateList.valueOf(if (primary) gold else line)
            strokeWidth = dp(1)
            elevation = if (primary) dp(1).toFloat() else 0f
            setOnClickListener { if (!busy) action() }
        }
    private fun button(caption: String, action: () -> Unit): MaterialButton {
        val b = makeButton(caption, true, action)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        params.bottomMargin = dp(9)
        content.addView(b, params)
        return b
    }
    private fun secondaryButton(caption: String, action: () -> Unit): MaterialButton {
        val b = makeButton(caption, false, action)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        params.bottomMargin = dp(9)
        content.addView(b, params)
        return b
    }
    private fun icon(res: Int, color: Int = teal, size: Int = 22): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        contentDescription = null // text is supplied by the adjacent accessible label
    }
    private fun card(textValue: String, action: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1); strokeColor = line
            setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) action() }
        }
        val label = text(textValue, 15, true)
        label.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.addView(label)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        content.addView(card, lp)
    }
    private fun chip(caption: String, background: Int, foreground: Int): TextView =
        text(caption, 12, true, foreground).apply {
            setBackground(rounded(background, 100))
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
    private fun sectionTitle(caption: String, actionLabel: String? = null, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(caption, 18, true, navy), LinearLayout.LayoutParams(0, dp(44), 1f))
        if (actionLabel != null && onClick != null) {
            val link = text(actionLabel, 13, true, teal)
            link.setPadding(dp(8), 0, dp(8), 0)
            link.isClickable = true; link.isFocusable = true
            link.setOnClickListener { onClick() }
            row.addView(link)
        }
        content.addView(row)
    }
    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(5), dp(8), dp(8))
            elevation = dp(8).toFloat()
        }
        val items = listOf(
            Triple("home", "خانه", R.drawable.ic_home),
            Triple("missions", "مأموریت‌ها", R.drawable.ic_clipboard),
            Triple("drafts", "پیش‌نویس‌ها", R.drawable.ic_notes),
            Triple("account", "حساب", R.drawable.ic_person)
        )
        for ((id, caption, drawable) in items) {
            val chosen = activeTab == id
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
                isClickable = true
                isFocusable = true
                contentDescription = caption
                setOnClickListener { if (!busy) showHome(id) }
            }
            item.addView(View(this).apply {
                setBackgroundColor(if (chosen) gold else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(30), dp(3)).apply { bottomMargin = dp(5) })
            val iconHolder = FrameLayout(this).apply {
                background = rounded(if (chosen) Color.rgb(248, 243, 232) else Color.WHITE, 11)
                addView(icon(drawable, if (chosen) navy else muted, 21),
                    FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
            }
            item.addView(iconHolder, LinearLayout.LayoutParams(dp(42), dp(32)))
            val captionView = text(caption, 10, chosen, if (chosen) navy else muted)
            captionView.gravity = Gravity.CENTER
            item.addView(captionView)
            bar.addView(item, LinearLayout.LayoutParams(0, dp(61), 1f))
        }
        return bar
    }

    private fun statCard(number: String, caption: String, drawable: Int, background: Int, foreground: Int): View {
        val outer = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
        }
        val badge = iconBadge(drawable, 23)
        inner.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        inner.addView(text(number, 27, true, navy).apply { setPadding(0, dp(6), 0, 0) })
        inner.addView(text(caption, 12, false, muted))
        outer.addView(inner)
        return outer
    }
    private fun statsRow(left: View, right: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val a = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
        val b = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
        row.addView(left, a); row.addView(right, b)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        content.addView(row, lp)
    }
    private fun hero(profileName: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(19).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(48, 72, 96)
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(navy)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(8, 31, 56), Color.rgb(23, 57, 86))).apply {
                cornerRadius = dp(19).toFloat()
            }
        }
        val name = profileName.trim().ifBlank { "بازرس" }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text("میز کار سازمانی", 11, true, gold))
        copy.addView(text("سلام، $name", 21, true, Color.WHITE).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        copy.addView(text("مأموریت‌ها و گزارش‌های شما", 12, false, Color.rgb(215, 225, 235)))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seal = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(179, 145, 82)
            setCardBackgroundColor(Color.rgb(20, 48, 72))
            cardElevation = 0f
        }
        seal.addView(icon(R.drawable.ic_shield, gold, 29),
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        row.addView(seal, LinearLayout.LayoutParams(dp(54), dp(54)))
        card.addView(row)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(14)
    }

    private fun emptyState(title: String, detail: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(23), dp(20), dp(23))
            setBackground(rounded(Color.WHITE, 16))
        }
        box.addView(icon(R.drawable.ic_clipboard, muted, 32))
        val titleView = text(title, 16, true, navy).apply { gravity = Gravity.CENTER }
        titleView.setPadding(0,dp(10),0,dp(6))
        box.addView(titleView)
        val detailView = text(detail, 13, false, muted).apply { gravity = Gravity.CENTER }
        box.addView(detailView)
        content.addView(box)
    }
    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        loadingBar?.visibility = View.VISIBLE
        io.execute {
            try {
                val result = task()
                runOnUiThread {
                    busy = false
                    loadingBar?.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) done(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    loadingBar?.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) notifyShort(e.message?.take(280) ?: "خطا در ارتباط")
                }
            }
        }
    }

    private fun showLogin() {
        selectedMission = null
        activeTab = "home"
        screen("ورود بازرس", "دسترسی امن به مأموریت‌های سازمانی")

        val intro = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val introBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(15), dp(15), dp(15), dp(15))
        }
        val seal = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            setCardBackgroundColor(navy)
            cardElevation = 0f
        }
        seal.addView(icon(R.drawable.ic_shield, gold, 27),
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        introBody.addView(seal, LinearLayout.LayoutParams(dp(52), dp(52)))
        val introText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        introText.addView(text("ورود به میز کار بازرس", 15, true, navy))
        introText.addView(text("ثبت و پیگیری مأموریت‌های محوله", 11, false, muted).apply {
            setPadding(0, dp(5), 0, 0)
        })
        introBody.addView(introText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        intro.addView(introBody)
        content.addView(intro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(18)
        sectionTitle("اطلاعات ورود")
        val username = field("نام کاربری")
        val password = field("رمز عبور", password = true)
        button("ورود امن و ثبت دستگاه") {
            val name = username.text.toString().trim()
            val pass = password.text.toString()
            if (name.isBlank() || pass.isBlank()) {
                notifyShort("نام کاربری و رمز عبور لازم است")
                return@button
            }
            work({
                val login = api.post("login.php", JSONObject().put("kind", "inspector").put("username", name).put("password", pass))
                val token = login.getString("token")
                val req = JSONObject()
                    .put("device_id", deviceId())
                    .put("public_key_pem", keys.publicKeyPem())
                    .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(190))
                    .put("app_version", BuildConfig.VERSION_NAME)
                val result = api.post("mobile_activate_authenticated.php", req, token)
                accountToken = token
                result
            }) { response ->
                store.putString("request_id", response.getString("request_id"))
                showPending()
                if (response.optString("status") == "approved") checkApproval()
            }
        }
        val securityNote = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(Color.rgb(239, 244, 249), 12)
        }
        securityNote.addView(icon(R.drawable.ic_shield, navy, 20))
        securityNote.addView(text("رمز عبور روی دستگاه ذخیره نمی‌شود؛ ورود اولیه به اینترنت نیاز دارد.", 11, false, muted).apply {
            setPadding(dp(9), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(securityNote)
        if (store.getString("request_id") != null) {
            gap(10)
            secondaryButton("پیگیری درخواست فعال‌سازی") { showPending() }
        }
    }

    private fun showPending() {
        selectedMission = null
        screen("تأیید دستگاه", "یک مرحله تا دسترسی به مأموریت‌ها باقی مانده است")
        gap(12)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),dp(20),dp(18),dp(20))
            setBackground(rounded(Color.WHITE,18))
        }
        panel.addView(icon(R.drawable.ic_shield, teal, 30))
        val title = text("در انتظار تأیید مدیر سامانه", 17, true, navy)
        title.setPadding(0,dp(12),0,dp(8))
        panel.addView(title)
        panel.addView(text("برای حفظ امنیت اطلاعات بازرسی، این دستگاه باید توسط مدیر تأیید شود.",13,false,muted))
        panel.addView(text("کد درخواست: ${store.getString("request_id") ?: "ثبت نشده"}",12,false,muted).apply {
            setPadding(0,dp(12),0,0)
        })
        val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=dp(16)
        content.addView(panel,lp)
        button("بررسی وضعیت تأیید") { checkApproval() }
        secondaryButton("بازگشت به ورود") { showLogin() }
    }

    private fun signedBody(prefix: String, requestId: String? = null): JSONObject {
        val ts = Instant.now().toString()
        val nonce = keys.nonce()
        val id = deviceId()
        val message = if (prefix == "session") "session|$id|$ts|$nonce"
            else "activation-status|$requestId|$id|$ts|$nonce"
        val json = JSONObject().put("device_id", id).put("timestamp", ts)
            .put("nonce", nonce).put("signature", keys.signed(message))
        if (requestId != null) json.put("request_id", requestId)
        return json
    }
    private fun checkApproval() {
        val id=store.getString("request_id") ?: return
        work({ api.post("mobile_activate_status.php", signedBody("activation-status", id)) }) { response ->
            when (response.optString("status")) {
                "approved" -> {
                    val fresh = response.getString("device_token")
                    // Status checks rotate the device token; discard any old biometric binding.
                    store.clearBio()
                    store.save("device_token", fresh)
                    deviceToken = fresh
                    notifyShort("دستگاه تأیید شد")
                    showHome("home")
                    connectAndSync()
                }
                "revoked" -> { store.clear("device_token"); store.clearBio(); showPending(); notifyShort("دستگاه لغو شده است") }
                else -> notifyShort("در انتظار تأیید مدیر")
            }
        }
    }

    private fun sessionFor(token: String): String {
        val body = signedBody("session").put("device_token", token)
        return api.post("mobile_session.php", body).getString("token")
    }
    private fun connectAndSync() {
        val token=deviceToken ?: return
        work({
            val session = sessionFor(token)
            val payload = api.get("mobile_sync.php", session)
            Pair(session, payload)
        }) { (session, payload) ->
            sessionToken = session
            serverSnapshot = payload
            store.save("mobile_sync", payload.toString())
            store.putString("last_sync", Instant.now().toString())
            showHome(activeTab)
        }
    }

    private fun missionKey(mission: JSONObject): String =
        mission.optString("_server_key", mission.optString("id"))

    private fun missionDraft(mission: JSONObject): Boolean =
        missionKey(mission).let { it.isNotBlank() && store.load("draft:$it") != null }

    private fun reportCount(): Int = (serverSnapshot.optJSONArray("reports") ?: JSONArray()).length()

    /** Reading a draft never changes server-owned mission information. */
    private fun answeredQuestions(mission: JSONObject): Pair<Int, Int> {
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val total = questions.length()
        if (!missionDraft(mission)) return Pair(0, total)
        val draft = InspectionDraft(missionKey(mission), store)
        val answered = (0 until total).count { index ->
            val key = questions.optJSONObject(index)?.optString("id") ?: ""
            val value = draft.answers.optString(key).toDoubleOrNull()
            key.isNotBlank() && value != null && value in 0.0..100.0
        }
        return Pair(answered, total)
    }

    private fun lastSyncDescription(): String {
        val raw = store.getString("last_sync") ?: return "هنوز همگام‌سازی انجام نشده است"
        return try {
            val time = Instant.parse(raw).atZone(ZoneId.systemDefault())
            "آخرین همگام‌سازی: " + DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale("fa")).format(time)
        } catch (_: Exception) { "اطلاعات آخرین همگام‌سازی ثبت شده است" }
    }

    private fun online(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun openAttachedEvidence(reference: String) {
        try {
            val evidence = evidenceVault.read(reference, contentResolver)
            if (evidence.mime.startsWith("image/")) {
                val bitmap = evidenceVault.imagePreview(reference, contentResolver)
                    ?: throw IllegalStateException("پیش‌نمایش عکس ایجاد نشد")
                val preview = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(dp(12),dp(12),dp(12),dp(12))
                }
                AlertDialog.Builder(this).setTitle("پیش‌نمایش مستند")
                    .setView(preview).setPositiveButton("بستن", null).show()
            } else {
                AlertDialog.Builder(this).setTitle("مستند PDF")
                    .setMessage("${evidenceVault.metadata(reference)}\n\nفایل به‌صورت رمزنگاری‌شده در خود برنامه نگهداری و هنگام ارسال بارگذاری می‌شود.")
                    .setPositiveButton("تأیید",null).show()
            }
        } catch (e: Exception) {
            notifyShort(e.message ?: "مستند قابل خواندن نیست؛ دوباره پیوست کنید")
        }
    }

    private fun missionItem(mission: JSONObject, destination: LinearLayout = content) {
        val key = missionKey(mission)
        val name = mission.optString("orgName", "دستگاه اجرایی").ifBlank { "دستگاه اجرایی" }
        val date = mission.optString("date", "").trim()
        val available = mission.optBoolean("can_execute", false)
        val state = mission.optString("schedule_label", "").ifBlank { if (available) "قابل انجام" else "زمان‌بندی مأموریت" }
        val isDraft = key.isNotBlank() && missionDraft(mission)
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) showMission(mission) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(15),dp(14),dp(15))
        }
        val symbol = iconBadge(R.drawable.ic_clipboard, 24)
        root.addView(symbol, LinearLayout.LayoutParams(dp(49), dp(49)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12),0,dp(9),0)
        }
        info.addView(text(name,15,true,navy))
        info.addView(text(listOf(date,state).filter { it.isNotBlank() }.joinToString("  •  "),12,false,muted))
        if (isDraft) {
            val (answered, total) = answeredQuestions(mission)
            info.addView(text("پیش‌نویس · $answered از $total پاسخ",12,true,teal).apply {
                setPadding(0, dp(5), 0, dp(4))
            })
            if (total > 0) {
                val progress = LinearProgressIndicator(this).apply {
                    isIndeterminate = false
                    max = total
                    setProgressCompat(answered, false)
                    trackColor = Color.rgb(235, 239, 244)
                    setIndicatorColor(gold)
                }
                info.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
            }
        }
        root.addView(info,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        root.addView(icon(R.drawable.ic_chevron, muted, 19))
        card.addView(root)
        val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=dp(10)
        destination.addView(card,lp)
    }

    private fun showHome(tab: String = "home") {
        selectedMission = null
        openDraft = null
        activeTab = tab
        val profile = serverSnapshot.optJSONObject("profile")
        val profileName = profile?.optString("name", "بازرس") ?: "بازرس"
        val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
        val all = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }
        val available = all.filter { it.optBoolean("can_execute",false) }
        val drafted = all.filter { missionDraft(it) }
        val queued = outbox.entries()
        when (tab) {
            "home" -> {
                screen("", "", withNav = true)
                hero(profileName)
                val indicators = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                indicators.addView(chip(if (sessionToken != null) "دریافت از سرور" else "حالت اطلاعات ذخیره‌شده", 
                    if (sessionToken != null) Color.rgb(228,245,237) else Color.rgb(255,246,228),
                    if (sessionToken != null) Color.rgb(25,124,79) else Color.rgb(154,104,27)))
                val lpChip = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT)
                lpChip.marginStart=dp(7)
                indicators.addView(chip(if (store.getString("last_sync") != null) "سوابق همگام‌سازی موجود" else "نیازمند همگام‌سازی",
                    Color.rgb(235,242,251),navy),lpChip)
                content.addView(indicators)
                gap(8)
                label(lastSyncDescription(), 12, false, muted)
                if (sessionToken == null && all.isNotEmpty()) {
                    gap(8)
                    val caution = text("این مأموریت‌ها از حافظه امن دستگاه نمایش داده شده‌اند. برای دریافت تغییرات جدید همگام‌سازی کنید.", 12, false, Color.rgb(131, 90, 35)).apply {
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = rounded(Color.rgb(255, 247, 230), 12)
                    }
                    content.addView(caution)
                }
                gap(15)
                sectionTitle("خلاصه کار من")
                statsRow(
                    statCard(all.size.toString(), "کل مأموریت‌ها", R.drawable.ic_clipboard, Color.rgb(231,241,252),navy),
                    statCard(available.size.toString(), "قابل انجام", R.drawable.ic_check, Color.rgb(228,247,240),Color.rgb(21,116,77))
                )
                statsRow(
                    statCard(drafted.size.toString(), "پیش‌نویس‌ها", R.drawable.ic_notes, Color.rgb(243,238,252),Color.rgb(99,69,150)),
                    statCard(reportCount().toString(), "گزارش‌های همگام‌شده", R.drawable.ic_chart, Color.rgb(255,245,226),Color.rgb(156,99,27))
                )
                gap(5)
                button("شروع بازرسی") {
                    if (available.isNotEmpty()) showMission(available.first())
                    else showHome("missions")
                }
                if (queued.isNotEmpty()) secondaryButton("صف ارسال گزارش‌ها (${queued.size})") { showOutbox() }
                secondaryButton("همگام‌سازی مأموریت‌ها") {
                    if (deviceToken == null) notifyShort("نشست دستگاه فعال نیست") else connectAndSync()
                }
                gap(12)
                val nextMission = available.firstOrNull { missionDraft(it) } ?: available.firstOrNull()
                if (nextMission != null) {
                    sectionTitle("اقدام بعدی")
                    label(if (missionDraft(nextMission)) "ادامه بازرسی نیمه‌تمام" else "مأموریت قابل انجام", 12, false, muted)
                    missionItem(nextMission)
                    gap(5)
                }
                sectionTitle("مأموریت‌های من", "نمایش همه") { showHome("missions") }
                if (all.isEmpty()) emptyState("مأموریتی دریافت نشده", "اگر به شما مأموریت داده شده، با اتصال اینترنت همگام‌سازی کنید.")
                else all.filterNot { nextMission != null && missionKey(it) == missionKey(nextMission) }.take(3).forEach { missionItem(it) }
                gap(8)
                label("پیش‌نویس‌ها بدون اینترنت هم ذخیره می‌شوند؛ ارسال نهایی نیازمند اینترنت است.",12,false,muted)
            }
            "missions" -> {
                screen("مأموریت‌های من", "جست‌وجو و پیگیری مأموریت‌های ابلاغ‌شده", withNav = true)
                val search = field("جست‌وجوی نام اداره")
                search.setSingleLine(true)
                search.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0)
                search.compoundDrawablePadding = dp(8)
                val options = listOf(
                    Triple("all", "همه (${all.size})", all.size),
                    Triple("ready", "قابل انجام (${available.size})", available.size),
                    Triple("draft", "پیش‌نویس (${drafted.size})", drafted.size)
                )
                var chosenFilter = "all"
                val filterRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val buttons = mutableMapOf<String, MaterialButton>()
                val resultArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val counter = text("", 12, false, muted)
                fun renderList() {
                    val query = search.text?.toString()?.trim().orEmpty()
                    val candidates = all.filter { mission ->
                        val matchesStatus = when (chosenFilter) {
                            "ready" -> mission.optBoolean("can_execute", false)
                            "draft" -> missionDraft(mission)
                            else -> true
                        }
                        val matchesText = query.isBlank() ||
                            mission.optString("orgName", "").contains(query, ignoreCase = true) ||
                            mission.optString("date", "").contains(query, ignoreCase = true)
                        matchesStatus && matchesText
                    }
                    counter.text = "${candidates.size} مأموریت نمایش داده می‌شود"
                    resultArea.removeAllViews()
                    if (candidates.isEmpty()) {
                        val notice = text("مأموریتی با این فیلتر پیدا نشد.", 14, false, muted).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(12), dp(28), dp(12), dp(28))
                            background = rounded(Color.WHITE, 14)
                        }
                        resultArea.addView(notice)
                    } else candidates.forEach { missionItem(it, resultArea) }
                    buttons.forEach { (id, button) ->
                        val active = id == chosenFilter
                        button.backgroundTintList = ColorStateList.valueOf(if (active) navy else Color.WHITE)
                        button.setTextColor(if (active) Color.WHITE else muted)
                        button.strokeColor = ColorStateList.valueOf(if (active) gold else line)
                    }
                }
                options.forEachIndexed { index, (id, title, _) ->
                    val button = makeButton(title, false) {
                        chosenFilter = id
                        renderList()
                    }.apply {
                        textSize = 11f
                        minHeight = dp(44)
                        setPadding(dp(3), 0, dp(3), 0)
                    }
                    buttons[id] = button
                    filterRow.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        if (index > 0) marginStart = dp(6)
                    })
                }
                content.addView(filterRow)
                gap(8)
                content.addView(counter)
                gap(10)
                content.addView(resultArea)
                gap(12)
                secondaryButton("دریافت آخرین مأموریت‌ها") {
                    if (deviceToken == null) notifyShort("برای همگام‌سازی ابتدا وارد حساب شوید")
                    else connectAndSync()
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderList()
                    override fun afterTextChanged(s: Editable?) {}
                })
                renderList()
            }
            "drafts" -> {
                screen("پیش‌نویس‌های من", "کارهای ذخیره‌شده روی همین دستگاه",withNav=true)
                label("${drafted.size} پیش‌نویس موجود",18,true,navy)
                if (queued.isNotEmpty()) {
                    gap(6)
                    button("صف ارسال گزارش‌ها (${queued.size})") { showOutbox() }
                }
                gap(10)
                if (drafted.isEmpty()) emptyState("پیش‌نویسی ندارید","هنگام پاسخ به چک‌لیست، اطلاعات به‌صورت خودکار ذخیره می‌شود.")
                else drafted.forEach { missionItem(it) }
                gap(12)
                label("برای جلوگیری از حذف اطلاعات، داده‌های برنامه را از تنظیمات گوشی پاک نکنید.",12,false,muted)
            }
            "account" -> {
                screen("حساب بازرس", "تنظیمات امنیت و ارتباط",withNav=true)
                val panel = LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(18),dp(20),dp(18),dp(20))
                    setBackground(rounded(Color.WHITE,18))
                }
                panel.addView(iconBadge(R.drawable.ic_person, 28), LinearLayout.LayoutParams(dp(48), dp(48)))
                panel.addView(text(profileName,21,true,navy).apply { setPadding(0,dp(12),0,dp(4)) })
                panel.addView(text("حساب سازمانی بازرس",13,false,muted))
                content.addView(panel)
                gap(20)
                sectionTitle("امنیت حساب")
                if (deviceToken != null && !store.biometricEnabled()) {
                    button("فعال‌سازی ورود با اثرانگشت") { enableBiometric() }
                } else if (store.biometricEnabled()) {
                    label("ورود با اثرانگشت برای این دستگاه فعال است.",14,false,teal)
                }
                sectionTitle("ارتباط با سامانه")
                secondaryButton("همگام‌سازی اطلاعات") { connectAndSync() }
                secondaryButton("خروج از حساب") { confirmLogout() }
                gap(12)
                label("نسخه ${BuildConfig.VERSION_NAME}  •  اتصال امن HTTPS",12,false,muted)
            }
            else -> showHome("home")
        }
    }

    private fun showMission(mission: JSONObject, questionIndex: Int = 0) {
        val key=missionKey(mission)
        if (key.isBlank()) { notifyShort("شناسه مأموریت نامعتبر است"); return }
        if (outbox.get(key) != null) {
            showOutbox()
            notifyShort("این گزارش در صف ارسال است؛ برای ویرایش، ابتدا از صف خارج کنید")
            return
        }
        selectedMission=mission; selectedMissionKey=key
        val draft=if(openDraft?.key == key) openDraft!! else InspectionDraft(key,store)
        openDraft=draft
        val enabled=mission.optBoolean("can_execute",false)
        val questions=mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val count=questions.length()
        currentQuestionIndex=questionIndex.coerceIn(0,(count-1).coerceAtLeast(0))
        screen("ثبت بازرسی", mission.optString("orgName", "دستگاه اجرایی"))
        secondaryButton("بازگشت به مأموریت‌ها") { showHome("missions") }
        val summary=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(14),dp(16),dp(14))
            setBackground(rounded(Color.WHITE,16))
        }
        summary.addView(text(mission.optString("orgName", "بازرسی"),16,true,navy))
        summary.addView(text(listOf(mission.optString("date"),mission.optString("schedule_label")).filter { it.isNotBlank() }.joinToString("  •  "),12,false,muted))
        content.addView(summary)
        gap(14)
        if (!enabled) label("این مأموریت فعلاً قابل ارسال نیست؛ امکان ثبت پیش‌نویس وجود دارد.",13,true,Color.rgb(157,99,29))
        if (count == 0) {
            emptyState("چک‌لیست خالی است","برای دریافت اطلاعات جدید، مأموریت‌ها را همگام‌سازی کنید.")
            secondaryButton("بازگشت") { showHome("missions") }
            return
        }
        val validAnswers = (0 until count).count { idx ->
            val q=questions.optJSONObject(idx)
            val number=draft.answers.optString(q?.optString("id") ?: "").toDoubleOrNull()
            number != null && number in 0.0..100.0
        }
        val stepTitle=label("سؤال ${currentQuestionIndex+1} از $count",15,true,navy)
        val progressLabel=label("$validAnswers از $count سؤال امتیازدهی شده",12,false,muted)
        val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=count; this.progress=validAnswers
            progressTintList=ColorStateList.valueOf(gold)
            progressBackgroundTintList=ColorStateList.valueOf(line)
        }
        val progressParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6))
        progressParams.bottomMargin=dp(18)
        content.addView(progress,progressParams)
        val q=questions.optJSONObject(currentQuestionIndex) ?: JSONObject()
        val id=q.optString("id")
        if (id.isBlank()) {
            label("شناسه سؤال نامعتبر است؛ اطلاعات چک‌لیست را با مدیر بررسی کنید.",14,true)
            return
        }
        val questionText=q.optString("text",q.optString("question",q.optString("title",q.optString("label",id))))
        val questionCard=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(20),dp(16),dp(18))
            setBackground(rounded(Color.WHITE,18))
        }
        questionCard.addView(text("عنوان سؤال",12,true,teal))
        questionCard.addView(text(questionText,18,true,navy).apply { setPadding(0,dp(9),0,dp(4)) })
        content.addView(questionCard)
        gap(18)
        label("امتیاز و مستندات",16,true,navy)
        val score=field("امتیاز از ۰ تا ۱۰۰",draft.answers.optString(id),numeric=true)
        val note=field("توضیح در صورت نیاز",draft.notes.optString(id))
        fun refreshProgress() {
            val filled=(0 until count).count { idx ->
                val qid=questions.optJSONObject(idx)?.optString("id") ?: ""
                val value=draft.answers.optString(qid).toDoubleOrNull()
                value != null && value in 0.0..100.0
            }
            progress.progress=filled
            progressLabel.text="$filled از $count سؤال امتیازدهی شده"
        }
        score.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.answers.put(id,s?.toString() ?: ""); draft.save(); refreshProgress()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        note.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.notes.put(id,s?.toString() ?: ""); draft.save()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val evidenceAction = secondaryButton(if(draft.attachments.optString(id).isBlank()) "ثبت عکس یا افزودن مستند" else "جایگزینی مستند") {
            chooseEvidence(id)
        }
        evidenceAction.setIconResource(R.drawable.ic_camera)
        evidenceAction.iconTint = ColorStateList.valueOf(navy)
        val existingAttachment = draft.attachments.optString(id)
        if (existingAttachment.isNotBlank()) {
            label(evidenceVault.metadata(existingAttachment), 12, false, muted)
            val attachmentBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val viewFile = makeButton("مشاهده مستند", false) { openAttachedEvidence(existingAttachment) }
            val removeFile = makeButton("حذف پیوست", false) {
                AlertDialog.Builder(this)
                    .setTitle("حذف مستند")
                    .setMessage("این فایل فقط از پیش‌نویس این سؤال جدا می‌شود؛ فایل اصلی گوشی حذف نمی‌شود.")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف پیوست") { _, _ ->
                        draft.attachments.remove(id)
                        draft.save()
                        evidenceVault.delete(existingAttachment)
                        showMission(mission, currentQuestionIndex)
                    }.show()
            }
            attachmentBar.addView(viewFile, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            attachmentBar.addView(removeFile, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            content.addView(attachmentBar)
        }
        gap(13)
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        if (currentQuestionIndex > 0) {
            val prev=makeButton("سؤال قبلی",false) { showMission(mission,currentQuestionIndex-1) }
            nav.addView(prev,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginEnd=dp(5) })
        }
        if (currentQuestionIndex < count-1) {
            val next=makeButton("سؤال بعدی",true) { showMission(mission,currentQuestionIndex+1) }
            nav.addView(next,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginStart=dp(5) })
        }
        content.addView(nav)
        if (currentQuestionIndex == count - 1 && validAnswers < count) {
            gap(9)
            secondaryButton("بازگشت به اولین سؤال تکمیل‌نشده") {
                val firstMissing = (0 until count).firstOrNull { idx ->
                    val questionId = questions.optJSONObject(idx)?.optString("id") ?: ""
                    val value = draft.answers.optString(questionId).toDoubleOrNull()
                    questionId.isBlank() || value == null || value !in 0.0..100.0
                }
                if (firstMissing == null) notifyShort("امتیاز همه سؤال‌ها تکمیل شده است")
                else showMission(mission, firstMissing)
            }
        }
        gap(15)
        secondaryButton("ذخیره پیش‌نویس") { draft.save(); notifyShort("پیش‌نویس روی دستگاه ذخیره شد") }
        label("پاسخ‌ها هنگام تایپ به‌صورت خودکار و رمزنگاری‌شده ذخیره می‌شوند.",12,false,muted)
        if (enabled && currentQuestionIndex == count-1) {
            gap(8)
            button("مرور گزارش نهایی") { showReportReview(mission, draft) }
            label("ارسال نهایی تنها با اتصال اینترنت انجام می‌شود.",12,false,muted)
        }
    }

    /** A dedicated native review screen before the user intentionally queues a report. */
    private fun showReportReview(mission: JSONObject, draft: InspectionDraft) {
        val issues = draft.validate(mission)
        if (issues.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("گزارش نیازمند تکمیل است")
                .setMessage(issues.take(8).joinToString("\n\n"))
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        selectedMission = mission
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        screen("بازبینی گزارش", "پیش از قرار گرفتن در صف ارسال، اطلاعات را مرور کنید")
        val total = questions.length()
        label(mission.optString("orgName", "دستگاه اجرایی"), 19, true, navy)
        gap(4)
        content.addView(chip("$total پاسخ · ${draft.attachments.length()} مستند", Color.rgb(234, 242, 251), navy))
        gap(16)
        for (i in 0 until questions.length()) {
            val q = questions.optJSONObject(i) ?: continue
            val id = q.optString("id")
            val title = q.optString("text", q.optString("question", q.optString("title", id)))
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 14)
                setPadding(dp(14), dp(13), dp(14), dp(13))
            }
            panel.addView(text("${i + 1}. $title", 14, true, navy))
            panel.addView(text("امتیاز: ${draft.answers.optString(id)} از ۱۰۰", 13, true, teal))
            val note = draft.notes.optString(id)
            if (note.isNotBlank()) panel.addView(text("توضیح: $note", 12, false, muted))
            val attachment = draft.attachments.optString(id)
            if (attachment.isNotBlank()) panel.addView(text("مستند: ${evidenceVault.metadata(attachment)}", 12, false, muted))
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            })
        }
        gap(10)
        val onlineNow = online()
        label(if (onlineNow) "اتصال اینترنت برقرار است؛ می‌توانید بعد از ثبت در صف، گزارش را ارسال کنید."
            else "اینترنت در دسترس نیست. گزارش رمزنگاری‌شده در صف دستگاه می‌ماند تا خودتان آن را ارسال کنید.",
            12, false, muted)
        gap(10)
        button("تأیید و ثبت در صف ارسال") {
            // The queue record and its attachment references are retained until server receipt.
            draft.save()
            outbox.enqueue(mission, draft)
            showOutbox()
            notifyShort("گزارش در صف ارسال ذخیره شد")
        }
        secondaryButton("بازگشت به ویرایش") { showMission(mission, currentQuestionIndex) }
    }

    private fun showOutbox() {
        selectedMission = null
        openDraft = null
        activeTab = "outbox"
        screen("صف ارسال گزارش‌ها", "ارسال کنترل‌شده و قابل پیگیری", withNav = true)
        val entries = outbox.entries()
        if (entries.isEmpty()) {
            emptyState("صف ارسال خالی است", "گزارش‌های تأییدشده در این قسمت قرار می‌گیرند.")
            secondaryButton("بازگشت به خانه") { showHome() }
            return
        }
        label("${entries.size} گزارش در انتظار اقدام", 17, true, navy)
        gap(5)
        label(if (online()) "اتصال شبکه برقرار است" else "حالت آفلاین؛ برای ارسال به اینترنت نیاز دارید",
            12, false, if (online()) Color.rgb(24, 121, 80) else Color.rgb(155, 105, 32))
        gap(12)
        entries.forEach { item ->
            val key = item.optString("mission_key")
            val mission = item.optJSONObject("mission") ?: JSONObject()
            val state = item.optString("state")
            val status = when (state) {
                "failed" -> "نیازمند تلاش مجدد"
                "uploading" -> "ارسال ناتمام؛ قابل تکرار"
                else -> "در انتظار ارسال"
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 16)
                setPadding(dp(15), dp(16), dp(15), dp(16))
            }
            panel.addView(text(item.optString("organization", "دستگاه اجرایی"), 16, true, navy))
            panel.addView(text(status, 13, true, if (state == "failed") Color.rgb(166, 82, 35) else teal))
            if (item.optString("error").isNotBlank()) panel.addView(text("آخرین خطا: ${item.optString("error")}", 11, false, muted))
            val launch = makeButton("ارسال اکنون", true) { sendQueued(key) }
            launch.setIconResource(R.drawable.ic_send)
            launch.iconTint = ColorStateList.valueOf(Color.WHITE)
            panel.addView(launch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(47)).apply { topMargin = dp(13) })
            val edit = makeButton("خروج از صف و ویرایش", false) {
                AlertDialog.Builder(this).setTitle("بازگشت به پیش‌نویس")
                    .setMessage("گزارش از صف خارج می‌شود اما پاسخ‌ها و مستندات در دستگاه باقی می‌مانند.")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("بازگشت به ویرایش") { _, _ ->
                        outbox.remove(key)
                        showMission(mission)
                    }.show()
            }
            panel.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(7) })
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        label("ارسال خودکار در پس‌زمینه فعال نیست؛ هر گزارش فقط پس از ورود امن بازرس و انتخاب «ارسال اکنون» فرستاده می‌شود.", 12, false, muted)
        secondaryButton("بازگشت به مأموریت‌ها") { showHome("missions") }
    }

    private fun sendQueued(key: String) {
        if (!online()) { notifyShort("برای ارسال گزارش، اتصال معتبر اینترنت لازم است"); return }
        val token = deviceToken ?: run { notifyShort("ابتدا با حساب امن بازرس وارد شوید"); return }
        val record = outbox.get(key) ?: run { notifyShort("رکورد صف پیدا نشد"); return }
        val mission = record.optJSONObject("mission") ?: run { notifyShort("اطلاعات مأموریت ناقص است"); return }
        if (!mission.optBoolean("can_execute", false)) {
            notifyShort("مجوز اجرای مأموریت در اطلاعات ذخیره‌شده وجود ندارد؛ با مدیر بررسی کنید")
            return
        }
        val draft = InspectionDraft(key, store)
        val issues = draft.validate(mission)
        if (issues.isNotEmpty()) { notifyShort("گزارش ناقص است؛ آن را به ویرایش بازگردانید"); return }
        work({
            try {
                outbox.setState(key, "uploading")
                val session = sessionFor(token)
                val uploads = JSONArray()
                val saved = outbox.get(key)?.optJSONObject("uploaded") ?: JSONObject()
                val hashes = mutableSetOf<String>()
                val ids = draft.attachments.keys()
                while (ids.hasNext()) {
                    val question = ids.next()
                    val attachment = evidenceVault.read(draft.attachments.getString(question), contentResolver)
                    val sha = MessageDigest.getInstance("SHA-256").digest(attachment.bytes)
                        .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
                    if (!hashes.add(sha)) throw IOException("یک فایل یکسان به چند سؤال پیوست شده است؛ مستند مجزا انتخاب کنید")
                    val existingId = saved.optInt(question, 0)
                    val fileId = if (existingId > 0) existingId else {
                        val response = api.uploadEvidence(session, attachment.bytes, attachment.mime, attachment.name, key, question)
                        val id = response.getInt("file_id")
                        outbox.rememberUpload(key, question, id)
                        id
                    }
                    uploads.put(JSONObject().put("file_id", fileId).put("question_id", question))
                }
                val request = JSONObject().put("mission_key", key).put("answers", draft.answers)
                    .put("notes", draft.notes).put("uploads", uploads)
                    .put("started_at", draft.startedAt)
                    .put("finished_at", record.optString("finished_at", Instant.now().toString()))
                val response = api.post("mobile_submit.php", request, session, draft.submissionKey)
                if (!response.optBoolean("ok")) throw IllegalStateException("تأیید نهایی سرور دریافت نشد")
                Pair(response, null as Exception?)
            } catch (e: Exception) {
                outbox.setState(key, "failed", e.message ?: "خطای ارتباط")
                Pair(null as JSONObject?, e)
            }
        }) { result ->
            val response = result.first
            if (response != null) {
                store.save("receipt:$key", response.toString())
                val ids = draft.attachments.keys()
                while (ids.hasNext()) evidenceVault.delete(draft.attachments.optString(ids.next()))
                draft.clear()
                outbox.remove(key)
                AlertDialog.Builder(this).setTitle("گزارش با موفقیت ثبت شد")
                    .setMessage("کد رسید: ${response.optString("receipt_code", "ثبت‌شده")}\nرسید در حافظه امن برنامه نگهداری شد.")
                    .setPositiveButton("تأیید") { _, _ -> showOutbox() }.show()
            } else {
                showOutbox()
                notifyShort("ارسال ناموفق بود؛ پیش‌نویس و مستندات حفظ شدند")
            }
        }
    }

    private fun biometricsAvailable(): Boolean = BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricPrompt(title:String, cipher:Cipher, callback:(Cipher)->Unit) {
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authorized=result.cryptoObject?.cipher
                if (authorized != null) callback(authorized) else notifyShort("تأیید بیومتریک ناقص بود")
            }
            override fun onAuthenticationError(errorCode:Int, errString:CharSequence) {
                super.onAuthenticationError(errorCode,errString)
                notifyShort(errString.toString())
            }
        })
        val info=BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle("سامانه بازرسی")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("انصراف").build()
        prompt.authenticate(info,BiometricPrompt.CryptoObject(cipher))
    }
    private fun enableBiometric() {
        val token=deviceToken ?: return
        if (!biometricsAvailable()) {notifyShort("اثر انگشت قوی در تنظیمات دستگاه فعال نشده است");return}
        try {
            biometricPrompt("فعال‌سازی ورود با اثرانگشت",store.bioEncryptCipher()) { cipher ->
                try { store.finalizeBioEncryption(cipher,token); notifyShort("اثر انگشت فعال شد");showHome() }
                catch (_:Exception) { notifyShort("ذخیره کلید بیومتریک ناموفق بود") }
            }
        }catch(_ :Exception) {notifyShort("فعالسازی اثرانگشت ممکن نشد")}
    }
    private fun showBiometricLock() {
        screen("ورود ایمن", "دسترسی با تأیید هویت دستگاه")
        label("برای مشاهده مأموریت‌ها، اثر انگشت خود را تأیید کنید.",14,false,muted)
        gap(18)
        button("ورود با اثرانگشت") {
            if(!biometricsAvailable()) {notifyShort("اثر انگشت قوی در دسترس نیست");return@button}
            try {
                val cipher=store.bioDecryptCipher() ?: error("token missing")
                biometricPrompt("ورود با اثرانگشت",cipher) { authorized ->
                    try { deviceToken=store.decryptBio(authorized);showHome();connectAndSync() }
                    catch (_:Exception) {notifyShort("کلید دستگاه نامعتبر است؛ ورود مجدد لازم است");showLogin()}
                }
            } catch (_: Exception) {notifyShort("اطلاعات بیومتریک نیازمند فعال‌سازی مجدد است");showLogin()}
        }
        secondaryButton("ورود با نام کاربری و رمز عبور") { showLogin() }
    }
    private fun confirmLogout() {
        AlertDialog.Builder(this).setTitle("خروج")
            .setMessage("از حساب خارج می‌شوید. پیش‌نویس‌های ذخیره‌شده روی دستگاه حذف نمی‌شوند. برای حذف کامل داده‌ها باید از تنظیمات اندروید اقدام کنید.")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("خروج") { _,_ ->
                deviceToken=null;sessionToken=null;accountToken=null
                store.clear("device_token");store.clearBio()
                showLogin()
            }.show()
    }
}
