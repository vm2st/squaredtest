package com.vm2st.graphics

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// --- ЛОКАЛИЗАЦИЯ ---
object Loc {
    private val isRu: Boolean
        get() = Locale.getDefault().language == "ru"

    val fps get() = "FPS"
    val cpu get() = if (isRu) "ЦП" else "CPU"
    val back get() = if (isRu) "ОБРАТНО" else "BACK"
    val noAccess get() = if (isRu) "Нет доступа" else "No access"
    val sysInfo get() = if (isRu) "ИНФОРМАЦИЯ О СИСТЕМЕ" else "SYSTEM INFORMATION"
    val processor get() = if (isRu) "Процессор: " else "Processor: "
    val gpu get() = if (isRu) "Видеокарта: " else "GPU: "
    val freq get() = if (isRu) "Частота ЦП: " else "CPU Freq: "
    val gpuFreq get() = if (isRu) "Частота GPU: " else "GPU Freq: "
    val ram get() = if (isRu) "Объем ОЗУ: " else "RAM Size: "

    val cat2d get() = if (isRu) "2D-ГРАФИКА" else "2D GRAPHICS"
    val cat3d get() = if (isRu) "3D-ГРАФИКА" else "3D GRAPHICS"

    val test2dLight get() = if (isRu) "ЛЕГКИЙ ТЕСТ" else "LIGHT TEST"
    val test2dHeavy get() = if (isRu) "ЭКСТРИМ ТЕСТ" else "EXTREME TEST"
    val test3d get() = if (isRu) "OPENGL ES 2.0" else "OPENGL ES 2.0"

    val startTest get() = if (isRu) "НАЧАТЬ ТЕСТ!" else "START TEST!"
    val stopTest get() = if (isRu) "ОСТАНОВИТЬ ТЕСТ" else "STOP TEST"
    val autoStage get() = if (isRu) "АВТО-ТЕСТ: ЭТАП " else "AUTO-TEST: STAGE "
    val rainbow get() = if (isRu) "Радужные кубы" else "Rainbow Cubes"
    val resultTitle get() = if (isRu) "РЕЗУЛЬТАТЫ ТЕСТА" else "TEST RESULTS"
    val score get() = if (isRu) "Итоговый балл:" else "Final Score:"

    val load2d get() = if (isRu) "Нагрузка 2D: " else "2D Load: "
    val load3d get() = if (isRu) "Нагрузка 3D (Кубы): " else "3D Load (Cubes): "
    val color3d get() = if (isRu) "Цвет 3D кубов" else "3D Cubes Color"
    val gb get() = if (isRu) "ГБ" else "GB"
    val ghz get() = if (isRu) "ГГц" else "GHz"
    val pressBackAgain get() = if (isRu) "Нажмите НАЗАД еще раз для выхода" else "Press BACK again to exit"
}

// --- СОСТОЯНИЯ ЭКРАНОВ ---
enum class AppScreen { MAIN, TEST_2D_LIGHT, TEST_2D_HEAVY, TEST_3D_GL, RESULT }

data class RenderParams(val rotX: Float, val rotY: Float, val hue: Float, val load: Float)

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var contentLayout: FrameLayout
    private lateinit var overlayLayout: LinearLayout
    private lateinit var fpsText: TextView
    private lateinit var tempText: TextView
    private lateinit var backButton: Button

    private var currentScreen = AppScreen.MAIN
    private var uiFrames = 0
    private var lastUiFpsTime = 0L
    private var backPressedTime: Long = 0

    private var cachedGpuModel: String? = null

    // --- ПЕРЕМЕННЫЕ АВТО-ТЕСТА И ПОТОКОВ ---
    private var isAutoTest = false
    private var autoStage = 0
    private var currentAutoLoad = 1f
    private var totalScore = 0

    @Volatile private var lastRenderTime = 0L
    @Volatile private var lastReportedFps = 0
    private var lastAutoTickTime = 0L
    private var lowFpsSeconds = 0

    private var active2DView: Test2DSurfaceView? = null
    private var active3DView: GLSurfaceView? = null
    private var isRainbow3D = false

    private var autoLoadText: TextView? = null
    private var autoProgressBar: ProgressBar? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = System.currentTimeMillis()

            if (currentScreen == AppScreen.MAIN || currentScreen == AppScreen.RESULT) {
                uiFrames++
                if (now - lastUiFpsTime >= 1000) {
                    updateMainFpsDisplay(uiFrames)
                    uiFrames = 0
                    lastUiFpsTime = now
                }
            }

            if (isAutoTest && currentScreen != AppScreen.MAIN && currentScreen != AppScreen.RESULT) {
                if (now - lastAutoTickTime >= 1000) {
                    processAutoTestTick(now)
                    lastAutoTickTime = now
                }
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.DKGRAY)
            fitsSystemWindows = true
        }
        contentLayout = FrameLayout(this)

        overlayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        fpsText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 18f
            text = "${Loc.fps}: 0"
        }

        tempText = TextView(this).apply {
            setTextColor(Color.RED)
            textSize = 18f
            visibility = View.GONE
        }

        backButton = Button(this).apply {
            text = Loc.back
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener {
                isAutoTest = false
                switchScreen(AppScreen.MAIN)
            }
        }

        overlayLayout.addView(fpsText)
        overlayLayout.addView(tempText)
        overlayLayout.addView(backButton)

        rootLayout.addView(contentLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootLayout.addView(overlayLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.START })

        setContentView(rootLayout)
        switchScreen(AppScreen.MAIN)

        lastUiFpsTime = System.currentTimeMillis()
        lastAutoTickTime = System.currentTimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCallback)

        if (Build.VERSION.SDK_INT >= 33) {
            registerModernBackHandler()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun onFrameRendered(fps: Int?) {
        lastRenderTime = System.currentTimeMillis()
        if (fps != null) {
            lastReportedFps = fps
            if (!isAutoTest) {
                runOnUiThread { updateMainFpsDisplay(fps) }
            }
        }
    }

    private fun updateMainFpsDisplay(fps: Int) {
        fpsText.text = "${Loc.fps}: $fps"
        val temp = getCpuTemp()
        tempText.visibility = if (temp > 0f) View.VISIBLE else View.GONE
        tempText.text = "${Loc.cpu}: ${temp}°C"
    }

    private fun processAutoTestTick(now: Long) {
        val timeSinceLastRender = now - lastRenderTime
        val effectiveFps = if (timeSinceLastRender > 1500) 0 else lastReportedFps

        updateMainFpsDisplay(effectiveFps)

        val loadNorm = currentAutoLoad / 100f
        val curvedLoad = loadNorm * loadNorm * loadNorm
        val maxObjects = when (autoStage) { 1 -> 50000; 2 -> 40000; 3 -> 50000; else -> 10000 }
        val currentObjects = (curvedLoad * maxObjects).toInt().coerceAtLeast(1)

        val workDone = effectiveFps * currentObjects
        val stageMult = when (autoStage) { 1 -> 0.008f; 2 -> 0.025f; 3 -> 0.020f; else -> 0f }
        val pointsForSecond = (workDone * stageMult).toInt()

        totalScore += pointsForSecond
        autoProgressBar?.progress = currentAutoLoad.toInt()

        if (effectiveFps <= 1) {
            lowFpsSeconds++
            autoLoadText?.text = "Ожидание: ${5 - lowFpsSeconds}с... | Очки: $totalScore"
        } else {
            lowFpsSeconds = 0
            autoLoadText?.text = "Нагрузка: ${currentAutoLoad.toInt()}% | Очки: $totalScore"
        }

        if ((effectiveFps <= 1 && lowFpsSeconds >= 5) || currentAutoLoad >= 100f) {
            autoStage++
            lowFpsSeconds = 0
            if (autoStage > 3) {
                isAutoTest = false
                switchScreen(AppScreen.RESULT)
            } else {
                currentAutoLoad = 1f
                when (autoStage) {
                    2 -> switchScreen(AppScreen.TEST_2D_HEAVY)
                    3 -> switchScreen(AppScreen.TEST_3D_GL)
                }
            }
        } else if (effectiveFps > 1) {
            currentAutoLoad += 5f
            if (currentAutoLoad > 100f) currentAutoLoad = 100f
            active2DView?.setLoad(currentAutoLoad)
        }
    }

    @RequiresApi(33)
    private fun registerModernBackHandler() {
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
            handleBackAction()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT < 33) {
            handleBackAction()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleBackAction() {
        if (currentScreen != AppScreen.MAIN) {
            isAutoTest = false
            switchScreen(AppScreen.MAIN)
        } else {
            if (System.currentTimeMillis() - backPressedTime < 2000) {
                finish()
            } else {
                backPressedTime = System.currentTimeMillis()
                Toast.makeText(this, Loc.pressBackAgain, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startAutoTest() {
        isAutoTest = true
        autoStage = 1
        currentAutoLoad = 1f
        totalScore = 0
        lowFpsSeconds = 0
        lastReportedFps = 60
        lastRenderTime = System.currentTimeMillis()
        lastAutoTickTime = System.currentTimeMillis()
        isRainbow3D = true
        switchScreen(AppScreen.TEST_2D_LIGHT)
    }

    private fun switchScreen(screen: AppScreen) {
        currentScreen = screen

        active2DView?.stop()
        active3DView?.onPause()

        contentLayout.removeAllViews()
        backButton.visibility = if (screen == AppScreen.MAIN) View.GONE else View.VISIBLE
        active2DView = null
        active3DView = null

        when (screen) {
            AppScreen.MAIN -> {
                uiFrames = 0
                lastUiFpsTime = System.currentTimeMillis()
                showMainMenu()
            }
            AppScreen.TEST_2D_LIGHT -> show2DTest(isHeavy = false)
            AppScreen.TEST_2D_HEAVY -> show2DTest(isHeavy = true)
            AppScreen.TEST_3D_GL -> show3DGLTest()
            AppScreen.RESULT -> showResultScreen()
        }
    }

    // --- УТИЛИТЫ СИСТЕМЫ ---
    private fun getDeviceRam(): String {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return "%.2f ${Loc.gb}".format(memInfo.totalMem.toDouble() / (1024 * 1024 * 1024))
    }

    private fun getCpuModel(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manufacturer = Build.SOC_MANUFACTURER
            val model = Build.SOC_MODEL
            if (manufacturer != Build.UNKNOWN) "$manufacturer $model" else Build.HARDWARE
        } else {
            Build.HARDWARE
        }
    }

    private fun getGpuModel(): String {
        if (cachedGpuModel != null) return cachedGpuModel!!
        return try {
            val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)

            val configAttribs = intArrayOf(
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            android.opengl.EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)

            val config = configs[0]
            val contextAttribs = intArrayOf(
                android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                android.opengl.EGL14.EGL_NONE
            )
            val eglContext = android.opengl.EGL14.eglCreateContext(display, config, android.opengl.EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE)
            val eglSurface = android.opengl.EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)

            android.opengl.EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)

            val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)

            android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
            android.opengl.EGL14.eglDestroySurface(display, eglSurface)
            android.opengl.EGL14.eglDestroyContext(display, eglContext)
            android.opengl.EGL14.eglTerminate(display)

            cachedGpuModel = renderer ?: Loc.noAccess
            cachedGpuModel!!
        } catch (e: Exception) {
            cachedGpuModel = Loc.noAccess
            cachedGpuModel!!
        }
    }

    private fun getCpuFrequency(): String {
        return try {
            var maxFreqKHz = 0L
            val coresCount = Runtime.getRuntime().availableProcessors()

            for (i in 0 until coresCount) {
                val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (file.exists()) {
                    val freq = file.readText().trim().toLongOrNull() ?: 0L
                    if (freq > maxFreqKHz) {
                        maxFreqKHz = freq
                    }
                }
            }
            if (maxFreqKHz > 0L) "%.2f ${Loc.ghz}".format(maxFreqKHz / 1000000.0) else Loc.noAccess
        } catch (_: Exception) {
            Loc.noAccess
        }
    }

    private fun getGpuFrequency(): String {
        try {
            val paths = listOf(
                "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
                "/sys/class/kgsl/kgsl-3d0/gpuclk"
            )
            var maxFreqHz = 0L

            for (path in paths) {
                val file = File(path)
                if (file.exists()) {
                    val freq = file.readText().trim().toLongOrNull() ?: 0L
                    if (freq > maxFreqHz) maxFreqHz = freq
                }
            }

            val devfreqDir = File("/sys/class/devfreq")
            if (devfreqDir.exists()) {
                devfreqDir.listFiles()?.forEach { dir ->
                    val name = dir.name.lowercase(Locale.US)
                    if (name.contains("mali") || name.contains("gpu") || name.contains("kgsl")) {
                        listOf("max_freq", "cur_freq").forEach { fileName ->
                            val file = File(dir, fileName)
                            if (file.exists()) {
                                val freq = file.readText().trim().toLongOrNull() ?: 0L
                                if (freq > maxFreqHz) maxFreqHz = freq
                            }
                        }
                    }
                }
            }

            if (maxFreqHz > 0L) {
                if (maxFreqHz < 10000) maxFreqHz *= 1000000L
                else if (maxFreqHz < 10000000) maxFreqHz *= 1000L
                return "%.2f ${Loc.ghz}".format(maxFreqHz / 1000000000.0)
            }
        } catch (_: Exception) {}
        return Loc.noAccess
    }

    private fun getCpuTemp(): Float {
        return try {
            val temp = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toFloat()
            if (temp > 1000) temp / 1000f else temp
        } catch (_: Exception) {
            0f
        }
    }

    // --- ЭКРАНЫ ---
    private fun showMainMenu() {
        val menuLayout = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val innerLayout = LinearLayout(this).apply {
            // ФИКС РАЗМЕТКИ ДЛЯ СТАРЫХ УСТРОЙСТВ: Используем MATCH_PARENT для высоты
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#88000000".toColorInt())
            setPadding(32, 32, 32, 32)
        }
        infoCard.addView(TextView(this).apply { text = Loc.sysInfo; setTextColor(Color.LTGRAY); textSize = 12f; setPadding(0, 0, 0, 16) })
        infoCard.addView(TextView(this).apply { text = "${Loc.processor}${getCpuModel()}"; setTextColor(Color.WHITE); textSize = 16f })
        infoCard.addView(TextView(this).apply { text = "${Loc.gpu}${getGpuModel()}"; setTextColor(Color.WHITE); textSize = 16f })
        infoCard.addView(TextView(this).apply { text = "${Loc.freq}${getCpuFrequency()}"; setTextColor(Color.WHITE); textSize = 16f })
        infoCard.addView(TextView(this).apply { text = "${Loc.gpuFreq}${getGpuFrequency()}"; setTextColor(Color.WHITE); textSize = 16f })
        infoCard.addView(TextView(this).apply { text = "${Loc.ram}${getDeviceRam()}"; setTextColor(Color.WHITE); textSize = 16f })
        innerLayout.addView(infoCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 64 })

        innerLayout.addView(TextView(this).apply {
            text = Loc.cat2d
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })

        val row2D = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val btn2DLight = Button(this).apply {
            text = Loc.test2dLight
            setBackgroundColor("#42A5F5".toColorInt())
            setTextColor(Color.WHITE)
            setOnClickListener { isAutoTest = false; switchScreen(AppScreen.TEST_2D_LIGHT) }
        }
        val btn2DHeavy = Button(this).apply {
            text = Loc.test2dHeavy
            setBackgroundColor("#EF5350".toColorInt())
            setTextColor(Color.WHITE)
            setOnClickListener { isAutoTest = false; switchScreen(AppScreen.TEST_2D_HEAVY) }
        }
        row2D.addView(btn2DLight, LinearLayout.LayoutParams(0, 150, 1f).apply { rightMargin = 8 })
        row2D.addView(btn2DHeavy, LinearLayout.LayoutParams(0, 150, 1f).apply { leftMargin = 8 })
        innerLayout.addView(row2D, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 48 })

        innerLayout.addView(TextView(this).apply {
            text = Loc.cat3d
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, 0, 0, 8)
        })

        val btn3D = Button(this).apply {
            text = Loc.test3d
            setBackgroundColor("#66BB6A".toColorInt())
            setTextColor(Color.WHITE)
            setOnClickListener { isAutoTest = false; switchScreen(AppScreen.TEST_3D_GL) }
        }
        innerLayout.addView(btn3D, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150).apply { bottomMargin = 64 })

        val btnAutoTest = Button(this).apply {
            text = Loc.startTest
            setBackgroundColor(Color.parseColor("#FFD54F"))
            setTextColor(Color.BLACK)
            textSize = 18f
            setPadding(0, 32, 0, 32)
            setOnClickListener { startAutoTest() }
        }
        innerLayout.addView(btnAutoTest, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val tgLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/vm2_studios"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка открытия ссылки", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val tgIcon = ImageView(this).apply {
            val resId = resources.getIdentifier("tg_icon", "drawable", packageName)
            if (resId != 0) {
                setImageResource(resId)
            }
            val iconSize = (28 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }

        val tgText = TextView(this).apply {
            text = "vm2_studios"
            setTextColor(Color.parseColor("#2CA5E0"))
            textSize = 16f
            setPadding(16, 0, 0, 0)
        }

        tgLayout.addView(tgIcon)
        tgLayout.addView(tgText)
        innerLayout.addView(tgLayout)

        menuLayout.addView(innerLayout)
        contentLayout.addView(menuLayout)
    }

    private fun showResultScreen() {
        val resultLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        resultLayout.addView(TextView(this).apply {
            text = Loc.resultTitle
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        })

        resultLayout.addView(TextView(this).apply {
            text = Loc.score
            setTextColor(Color.LTGRAY)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })

        resultLayout.addView(TextView(this).apply {
            text = String.format(Locale.US, "%,d", totalScore)
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 64)
        })

        val btnMenu = Button(this).apply {
            text = Loc.back
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            setOnClickListener { switchScreen(AppScreen.MAIN) }
        }
        resultLayout.addView(btnMenu, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150))

        contentLayout.addView(resultLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun show2DTest(isHeavy: Boolean) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var loadPercent = if (isAutoTest) 1f else 1f
        val testView = Test2DSurfaceView(this, isHeavy) { fps ->
            onFrameRendered(fps)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        active2DView = testView

        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
        }

        if (isAutoTest) {
            controlPanel.addView(TextView(this).apply { text = "${Loc.autoStage}$autoStage / 3"; setTextColor(Color.parseColor("#FFD54F")); textSize = 18f })
            autoLoadText = TextView(this).apply { text = "Нагрузка: 1% | Очки: $totalScore"; setTextColor(Color.WHITE); setPadding(0, 16, 0, 8) }
            autoProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 1 }

            controlPanel.addView(autoLoadText)
            controlPanel.addView(autoProgressBar)

            val btnStop = Button(this).apply {
                text = Loc.stopTest
                setBackgroundColor(Color.parseColor("#EF5350"))
                setTextColor(Color.WHITE)
                setOnClickListener { isAutoTest = false; switchScreen(AppScreen.MAIN) }
            }
            controlPanel.addView(btnStop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150).apply { topMargin = 16 })
        } else {
            val loadText = TextView(this).apply { text = "${Loc.load2d}${loadPercent.toInt()}%"; setTextColor(Color.WHITE) }
            val seekBar = SeekBar(this).apply {
                max = 99
                progress = 0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        loadPercent = progress + 1f
                        loadText.text = "${Loc.load2d}${loadPercent.toInt()}%"
                        testView.setLoad(loadPercent)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            controlPanel.addView(loadText)
            controlPanel.addView(seekBar)
        }

        container.addView(testView)
        container.addView(controlPanel)
        contentLayout.addView(container)
    }

    private fun show3DGLTest() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var colorHue = 0f
        var loadPercent = if (isAutoTest) 1f else 1f
        var rotX = 0f
        var rotY = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f

        val glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(MyGLRenderer({
                val finalLoad = if (isAutoTest) currentAutoLoad else loadPercent
                val finalHue = if (isRainbow3D) ((System.currentTimeMillis() / 15) % 360).toFloat() else colorHue
                RenderParams(rotX, rotY, finalHue, finalLoad)
            }) { fps ->
                onFrameRendered(fps)
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        rotY += dx * 0.5f
                        rotX += dy * 0.5f
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
                true
            }
        }
        active3DView = glView

        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
        }

        if (isAutoTest) {
            controlPanel.addView(TextView(this).apply { text = "${Loc.autoStage}$autoStage / 3"; setTextColor(Color.parseColor("#FFD54F")); textSize = 18f })
            autoLoadText = TextView(this).apply { text = "Нагрузка: 1% | Очки: $totalScore"; setTextColor(Color.WHITE); setPadding(0, 16, 0, 8) }
            autoProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 1 }

            controlPanel.addView(autoLoadText)
            controlPanel.addView(autoProgressBar)

            val btnStop = Button(this).apply {
                text = Loc.stopTest
                setBackgroundColor(Color.parseColor("#EF5350"))
                setTextColor(Color.WHITE)
                setOnClickListener { isAutoTest = false; switchScreen(AppScreen.MAIN) }
            }
            controlPanel.addView(btnStop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150).apply { topMargin = 16 })
        } else {
            isRainbow3D = false

            val rainbowCheck = CheckBox(this).apply {
                text = Loc.rainbow
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, isChecked -> isRainbow3D = isChecked }
            }

            val colorText = TextView(this).apply { text = Loc.color3d; setTextColor(Color.WHITE); setPadding(0, 16, 0, 0) }
            val colorSeekBar = SeekBar(this).apply {
                max = 360
                progress = 0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        colorHue = progress.toFloat()
                        if (isRainbow3D) { rainbowCheck.isChecked = false }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            val loadText = TextView(this).apply { text = "${Loc.load3d}${loadPercent.toInt()}%"; setTextColor(Color.WHITE); setPadding(0, 16, 0, 0) }
            val loadSeekBar = SeekBar(this).apply {
                max = 99
                progress = 0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        loadPercent = progress + 1f
                        loadText.text = "${Loc.load3d}${loadPercent.toInt()}%"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            controlPanel.addView(rainbowCheck)
            controlPanel.addView(colorText)
            controlPanel.addView(colorSeekBar)
            controlPanel.addView(loadText)
            controlPanel.addView(loadSeekBar)
        }

        container.addView(glView)
        container.addView(controlPanel)
        contentLayout.addView(container)
    }
}

// --- УНИВЕРСАЛЬНЫЙ ДВИЖОК 2D РЕНДЕРА ---
class Test2DSurfaceView(
    context: Context,
    private val isHeavy: Boolean,
    private val onFpsUpdate: (Int?) -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {

    private var renderThread: Thread? = null
    @Volatile private var isRunning = false

    private val paint = Paint().apply {
        isAntiAlias = true
        if (isHeavy) setShadowLayer(25f, 0f, 0f, Color.RED)
    }

    private val rect = RectF()
    private var time = 0f
    private var currentLoadPercent = 1f

    private val maxObjects = if (isHeavy) 40000 else 50000

    private var frames = 0
    private var lastTime = System.currentTimeMillis()

    init {
        holder.addCallback(this)
    }

    fun setLoad(percent: Float) {
        currentLoadPercent = percent
    }

    fun stop() {
        isRunning = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        renderThread = Thread {
            while (isRunning) {
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }

                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.DKGRAY)
                        time += 0.05f

                        val w = width.toFloat()
                        val h = height.toFloat()

                        val loadNorm = currentLoadPercent / 100f
                        val curvedLoad = loadNorm * loadNorm * loadNorm
                        val currentObjects = (curvedLoad * maxObjects).toInt().coerceAtLeast(1)

                        for (i in 0 until currentObjects) {
                            if (!isRunning) break // Защита от долгого зависания при выходе!

                            if (isHeavy) {
                                val x = sin(time + i * 0.001f) * w / 2 + w / 2
                                val y = cos(time * 0.8f + i * 0.001f) * h / 2 + h / 2
                                val objSize = 10f + (i % 40f)

                                paint.setARGB(150, i % 255, (i * 2) % 255, (i * 3) % 255)

                                canvas.save()
                                canvas.rotate(time * 50f + (i % 360), x, y)
                                rect.set(x, y, x + objSize, y + objSize * 1.5f)
                                canvas.drawOval(rect, paint)
                                canvas.restore()
                            } else {
                                val x = sin(time + i) * w / 2 + w / 2
                                val y = cos(time * 0.8f + i) * h / 2 + h / 2
                                val objSize = 20f + (i % 30f)

                                paint.setARGB(200, i % 255, (i * 2) % 255, (i * 3) % 255)

                                canvas.save()
                                canvas.rotate(time * 50f + i, x, y)
                                rect.set(x, y, x + objSize, y + objSize)
                                canvas.drawRect(rect, paint)
                                canvas.restore()
                            }
                        }

                        onFpsUpdate(null)

                        frames++
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 1000) {
                            onFpsUpdate(frames)
                            frames = 0
                            lastTime = now
                        }
                    } finally {
                        try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) {}
                    }
                }
            }
        }
        renderThread?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        try {
            renderThread?.join(150)
        } catch (e: Exception) {}
    }
}

// --- OpenGL ES 2.0 РЕНДЕР И ЛОГИКА ---
class MyGLRenderer(
    private val getParams: () -> RenderParams,
    private val onFpsUpdate: (Int?) -> Unit
) : GLSurfaceView.Renderer {

    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val scratch = FloatArray(16)

    private lateinit var cube: Cube

    private val maxCubes = 50000
    private val positionsX = FloatArray(maxCubes)
    private val positionsY = FloatArray(maxCubes)
    private val positionsZ = FloatArray(maxCubes)
    private val rotAxesX = FloatArray(maxCubes)
    private val rotAxesY = FloatArray(maxCubes)
    private val rotAxesZ = FloatArray(maxCubes)
    private val rotSpeeds = FloatArray(maxCubes)
    private var globalTime = 0f

    private var frames = 0
    private var lastTime = System.currentTimeMillis()

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.15f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        cube = Cube()

        for (i in 0 until maxCubes) {
            positionsX[i] = (Random.nextFloat() - 0.5f) * 80f
            positionsY[i] = (Random.nextFloat() - 0.5f) * 80f
            positionsZ[i] = (Random.nextFloat() - 0.5f) * 80f
            rotAxesX[i] = Random.nextFloat()
            rotAxesY[i] = Random.nextFloat()
            rotAxesZ[i] = Random.nextFloat()
            rotSpeeds[i] = Random.nextFloat() * 5f + 1f
        }
    }

    override fun onDrawFrame(unused: GL10) {
        globalTime += 1f
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val params = getParams()

        val loadNorm = params.load / 100f
        val curvedLoad = loadNorm * loadNorm * loadNorm
        val currentCubes = (curvedLoad * maxCubes).toInt().coerceAtLeast(1)

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 50f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        Matrix.rotateM(viewMatrix, 0, params.rotX, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, params.rotY, 0f, 1f, 0f)
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        val color = Color.HSVToColor(floatArrayOf(params.hue, 1f, 1f))
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val colorArray = floatArrayOf(r, g, b, 1.0f)

        cube.bind(colorArray)

        for (i in 0 until currentCubes) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.rotateM(modelMatrix, 0, globalTime * 0.2f, 0f, 1f, 0f)
            Matrix.translateM(modelMatrix, 0, positionsX[i], positionsY[i], positionsZ[i])
            Matrix.rotateM(modelMatrix, 0, globalTime * rotSpeeds[i], rotAxesX[i], rotAxesY[i], rotAxesZ[i])
            Matrix.multiplyMM(scratch, 0, vPMatrix, 0, modelMatrix, 0)

            cube.drawInstance(scratch, modelMatrix)
        }

        cube.unbind()

        onFpsUpdate(null)

        frames++
        val now = System.currentTimeMillis()
        if (now - lastTime >= 1000) {
            onFpsUpdate(frames)
            frames = 0
            lastTime = now
        }
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio: Float = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 2f, 200f)
    }
}

object ShaderHelper {
    fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}

class Cube {
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uModelMatrix;
        uniform vec4 vColor;
        
        attribute vec4 vPosition;
        attribute vec3 vNormal;
        
        varying vec4 fColor;
        
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            vec3 transformedNormal = normalize((uModelMatrix * vec4(vNormal, 0.0)).xyz);
            vec3 lightDir = normalize(vec3(1.0, 1.0, 1.0));
            float diff = max(dot(transformedNormal, lightDir), 0.2);
            fColor = vec4(vColor.rgb * diff, vColor.a);
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 fColor;
        void main() {
            vec2 uv = gl_FragCoord.xy * 0.01;
            float heavyMath = 0.0;
            
            for(int i = 1; i <= 20; i++) {
                float fi = float(i);
                heavyMath += sin(uv.x * fi + uv.y) * cos(uv.y * fi - uv.x);
            }
            
            gl_FragColor = vec4(fColor.rgb * (0.8 + 0.2 * sin(heavyMath)), fColor.a);
        }
    """.trimIndent()

    private val vertexBuffer: FloatBuffer
    private val mProgram: Int
    private var positionHandle: Int = 0
    private var normalHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var modelMatrixHandle: Int = 0
    private val stride = 6 * 4

    private val cubeCoords = floatArrayOf(
        -0.5f, -0.5f,  0.5f,   0f, 0f, 1f,
        0.5f, -0.5f,  0.5f,   0f, 0f, 1f,
        0.5f,  0.5f,  0.5f,   0f, 0f, 1f,
        -0.5f, -0.5f,  0.5f,   0f, 0f, 1f,
        0.5f,  0.5f,  0.5f,   0f, 0f, 1f,
        -0.5f,  0.5f,  0.5f,   0f, 0f, 1f,

        0.5f, -0.5f,  0.5f,   1f, 0f, 0f,
        0.5f, -0.5f, -0.5f,   1f, 0f, 0f,
        0.5f,  0.5f, -0.5f,   1f, 0f, 0f,
        0.5f, -0.5f,  0.5f,   1f, 0f, 0f,
        0.5f,  0.5f, -0.5f,   1f, 0f, 0f,
        0.5f,  0.5f,  0.5f,   1f, 0f, 0f,

        0.5f, -0.5f, -0.5f,   0f, 0f, -1f,
        -0.5f, -0.5f, -0.5f,   0f, 0f, -1f,
        -0.5f,  0.5f, -0.5f,   0f, 0f, -1f,
        0.5f, -0.5f, -0.5f,   0f, 0f, -1f,
        -0.5f,  0.5f, -0.5f,   0f, 0f, -1f,
        0.5f,  0.5f, -0.5f,   0f, 0f, -1f,

        -0.5f, -0.5f, -0.5f,  -1f, 0f, 0f,
        -0.5f, -0.5f,  0.5f,  -1f, 0f, 0f,
        -0.5f,  0.5f,  0.5f,  -1f, 0f, 0f,
        -0.5f, -0.5f, -0.5f,  -1f, 0f, 0f,
        -0.5f,  0.5f,  0.5f,  -1f, 0f, 0f,
        -0.5f,  0.5f, -0.5f,  -1f, 0f, 0f,

        -0.5f,  0.5f,  0.5f,   0f, 1f, 0f,
        0.5f,  0.5f,  0.5f,   0f, 1f, 0f,
        0.5f,  0.5f, -0.5f,   0f, 1f, 0f,
        -0.5f,  0.5f,  0.5f,   0f, 1f, 0f,
        0.5f,  0.5f, -0.5f,   0f, 1f, 0f,
        -0.5f,  0.5f, -0.5f,   0f, 1f, 0f,

        -0.5f, -0.5f, -0.5f,   0f, -1f, 0f,
        0.5f, -0.5f, -0.5f,   0f, -1f, 0f,
        0.5f, -0.5f,  0.5f,   0f, -1f, 0f,
        -0.5f, -0.5f, -0.5f,   0f, -1f, 0f,
        0.5f, -0.5f,  0.5f,   0f, -1f, 0f,
        -0.5f, -0.5f,  0.5f,   0f, -1f, 0f
    )

    init {
        val bb = ByteBuffer.allocateDirect(cubeCoords.size * 4).apply { order(ByteOrder.nativeOrder()) }
        vertexBuffer = bb.asFloatBuffer().apply { put(cubeCoords); position(0) }
        mProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, ShaderHelper.loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode))
            GLES20.glAttachShader(it, ShaderHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode))
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
        normalHandle = GLES20.glGetAttribLocation(mProgram, "vNormal")
        colorHandle = GLES20.glGetUniformLocation(mProgram, "vColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        modelMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uModelMatrix")
    }

    fun bind(color: FloatArray) {
        GLES20.glUseProgram(mProgram)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        GLES20.glUniform4fv(colorHandle, 1, color, 0)
    }

    fun drawInstance(mvpMatrix: FloatArray, modelMatrix: FloatArray) {
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelMatrixHandle, 1, false, modelMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    fun unbind() {
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }
}