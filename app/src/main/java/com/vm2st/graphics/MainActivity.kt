package com.vm2st.graphics

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.window.OnBackInvokedDispatcher
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

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
    val freq get() = if (isRu) "Частота ЦП: " else "CPU Freq: "
    val ram get() = if (isRu) "Объем ОЗУ: " else "RAM Size: "
    val test2d get() = if (isRu) "ТЕСТ 2D-ГРАФИКИ" else "2D GRAPHICS TEST"
    val test3d get() = if (isRu) "ТЕСТ 3D-ГРАФИКИ" else "3D GRAPHICS TEST"
    val load2d get() = if (isRu) "Нагрузка 2D: " else "2D Load: "
    val color3d get() = if (isRu) "Цвет 3D куба" else "3D Cube Color"
    val gb get() = if (isRu) "ГБ" else "GB"
    val ghz get() = if (isRu) "ГГц" else "GHz"
    val pressBackAgain get() = if (isRu) "Нажмите НАЗАД еще раз для выхода" else "Press BACK again to exit"
}

// --- СОСТОЯНИЯ ЭКРАНОВ ---
enum class AppScreen { MAIN, TEST_2D, TEST_3D_GL }

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var contentLayout: FrameLayout
    private lateinit var overlayLayout: LinearLayout
    private lateinit var fpsText: TextView
    private lateinit var tempText: TextView
    private lateinit var backButton: Button

    private var currentScreen = AppScreen.MAIN
    private var frames = 0
    private var lastFpsTime = 0L
    private var backPressedTime: Long = 0

    // Колбэк для подсчета FPS
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frames++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                fpsText.text = "${Loc.fps}: $frames"
                val temp = getCpuTemp()
                tempText.visibility = if (temp > 0f) View.VISIBLE else View.GONE
                tempText.text = "${Loc.cpu}: ${temp}°C"
                frames = 0
                lastFpsTime = now
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
            setOnClickListener { switchScreen(AppScreen.MAIN) }
        }

        overlayLayout.addView(fpsText)
        overlayLayout.addView(tempText)
        overlayLayout.addView(backButton)

        rootLayout.addView(contentLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootLayout.addView(overlayLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.START })

        setContentView(rootLayout)
        switchScreen(AppScreen.MAIN)

        lastFpsTime = System.currentTimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCallback)

        // Инициализация современной системы "Назад" для Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= 33) {
            registerModernBackHandler()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // --- НОВАЯ СИСТЕМА ОБРАБОТКИ "НАЗАД" ---

    // Для Android 13+ (API 33 и новее, включая Android 16)
    @TargetApi(33)
    private fun registerModernBackHandler() {
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
            handleBackAction()
        }
    }

    // Для старых устройств (до API 33)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) {
            handleBackAction()
        } else {
            super.onBackPressed() // На новых устройствах это перехватывается диспетчером
        }
    }

    // Единая логика возврата
    private fun handleBackAction() {
        if (currentScreen != AppScreen.MAIN) {
            switchScreen(AppScreen.MAIN)
        } else {
            if (System.currentTimeMillis() - backPressedTime < 2000) {
                finish() // Закрываем приложение
            } else {
                backPressedTime = System.currentTimeMillis()
                Toast.makeText(this, Loc.pressBackAgain, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchScreen(screen: AppScreen) {
        currentScreen = screen
        contentLayout.removeAllViews()
        backButton.visibility = if (screen == AppScreen.MAIN) View.GONE else View.VISIBLE

        when (screen) {
            AppScreen.MAIN -> showMainMenu()
            AppScreen.TEST_2D -> show2DTest()
            AppScreen.TEST_3D_GL -> show3DGLTest()
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

            if (maxFreqKHz > 0L) {
                "%.2f ${Loc.ghz}".format(maxFreqKHz / 1000000.0)
            } else {
                Loc.noAccess
            }
        } catch (_: Exception) {
            Loc.noAccess
        }
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
        val menuLayout = LinearLayout(this).apply {
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
        infoCard.addView(TextView(this).apply { text = "${Loc.freq}${getCpuFrequency()}"; setTextColor(Color.WHITE); textSize = 16f })
        infoCard.addView(TextView(this).apply { text = "${Loc.ram}${getDeviceRam()}"; setTextColor(Color.WHITE); textSize = 16f })

        val btn2D = Button(this).apply {
            text = Loc.test2d
            setBackgroundColor("#1E88E5".toColorInt())
            setTextColor(Color.WHITE)
            setOnClickListener { switchScreen(AppScreen.TEST_2D) }
        }

        val btn3D = Button(this).apply {
            text = Loc.test3d
            setBackgroundColor("#43A047".toColorInt())
            setTextColor(Color.WHITE)
            setOnClickListener { switchScreen(AppScreen.TEST_3D_GL) }
        }

        menuLayout.addView(infoCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 64 })
        menuLayout.addView(btn2D, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150).apply { bottomMargin = 16 })
        menuLayout.addView(btn3D, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150))

        contentLayout.addView(menuLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun show2DTest() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var loadPercent = 1f
        val testView = Test2DView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
        }

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
        container.addView(testView)
        container.addView(controlPanel)
        contentLayout.addView(container)
    }

    private fun show3DGLTest() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var colorHue = 0f
        var rotX = 0f
        var rotY = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f

        val glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(MyGLRenderer { rotX to rotY to colorHue })
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

        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
        }

        val colorText = TextView(this).apply { text = Loc.color3d; setTextColor(Color.WHITE) }
        val seekBar = SeekBar(this).apply {
            max = 360
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    colorHue = progress.toFloat()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        controlPanel.addView(colorText)
        controlPanel.addView(seekBar)
        container.addView(glView)
        container.addView(controlPanel)
        contentLayout.addView(container)
    }
}

// --- КЛАСС ДЛЯ 2D РЕНДЕРА ---
class Test2DView(context: Context) : View(context) {
    private val paint = Paint().apply { isAntiAlias = true }
    private var time = 0f
    private var currentLoadPercent = 1f
    private val maxObjects = 50000

    fun setLoad(percent: Float) {
        currentLoadPercent = percent
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        time += 0.05f

        val w = width.toFloat()
        val h = height.toFloat()
        val currentObjects = ((currentLoadPercent / 100f) * maxObjects).toInt()

        for (i in 0 until currentObjects) {
            val x = sin(time + i) * w / 2 + w / 2
            val y = cos(time * 0.8f + i) * h / 2 + h / 2
            val objSize = 20f + (i % 30f)

            paint.setARGB(200, i % 255, (i * 2) % 255, (i * 3) % 255)

            canvas.withRotation(degrees = time * 50f + i, pivotX = x, pivotY = y) {
                drawRect(x, y, x + objSize, y + objSize, paint)
            }
        }

        invalidate()
    }
}

// --- OpenGL ES 2.0 РЕНДЕР И ЛОГИКА ---
class MyGLRenderer(private val getParams: () -> Pair<Pair<Float, Float>, Float>) : GLSurfaceView.Renderer {
    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val scratch = FloatArray(16)
    private lateinit var cube: Cube
    private lateinit var shadowPlane: ShadowPlane

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        cube = Cube()
        shadowPlane = ShadowPlane()
    }

    override fun onDrawFrame(unused: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 2f, 6f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        val params = getParams()
        val rotX = params.first.first
        val rotY = params.first.second
        val hue = params.second

        shadowPlane.draw(vPMatrix)
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.rotateM(rotationMatrix, 0, rotX, 1f, 0f, 0f)
        Matrix.rotateM(rotationMatrix, 0, rotY, 0f, 1f, 0f)
        Matrix.multiplyMM(scratch, 0, vPMatrix, 0, rotationMatrix, 0)

        val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f

        cube.draw(scratch, rotationMatrix, floatArrayOf(r, g, b, 1.0f))
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio: Float = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 2f, 15f)
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
            vec3 lightDir = normalize(vec3(1.0, 1.5, 2.0));
            float diff = max(dot(transformedNormal, lightDir), 0.3);
            fColor = vec4(vColor.rgb * diff, vColor.a);
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 fColor;
        void main() {
            gl_FragColor = fColor;
        }
    """.trimIndent()

    private val vertexBuffer: FloatBuffer
    private val mProgram: Int

    private val cubeCoords = floatArrayOf(
        -1f, -1f,  1f,   0f, 0f, 1f,
        1f, -1f,  1f,   0f, 0f, 1f,
        1f,  1f,  1f,   0f, 0f, 1f,
        -1f, -1f,  1f,   0f, 0f, 1f,
        1f,  1f,  1f,   0f, 0f, 1f,
        -1f,  1f,  1f,   0f, 0f, 1f,

        1f, -1f,  1f,   1f, 0f, 0f,
        1f, -1f, -1f,   1f, 0f, 0f,
        1f,  1f, -1f,   1f, 0f, 0f,
        1f, -1f,  1f,   1f, 0f, 0f,
        1f,  1f, -1f,   1f, 0f, 0f,
        1f,  1f,  1f,   1f, 0f, 0f,

        1f, -1f, -1f,   0f, 0f, -1f,
        -1f, -1f, -1f,   0f, 0f, -1f,
        -1f,  1f, -1f,   0f, 0f, -1f,
        1f, -1f, -1f,   0f, 0f, -1f,
        -1f,  1f, -1f,   0f, 0f, -1f,
        1f,  1f, -1f,   0f, 0f, -1f,

        -1f, -1f, -1f,  -1f, 0f, 0f,
        -1f, -1f,  1f,  -1f, 0f, 0f,
        -1f,  1f,  1f,  -1f, 0f, 0f,
        -1f, -1f, -1f,  -1f, 0f, 0f,
        -1f,  1f,  1f,  -1f, 0f, 0f,
        -1f,  1f, -1f,  -1f, 0f, 0f,

        -1f,  1f,  1f,   0f, 1f, 0f,
        1f,  1f,  1f,   0f, 1f, 0f,
        1f,  1f, -1f,   0f, 1f, 0f,
        -1f,  1f,  1f,   0f, 1f, 0f,
        1f,  1f, -1f,   0f, 1f, 0f,
        -1f,  1f, -1f,   0f, 1f, 0f,

        -1f, -1f, -1f,   0f, -1f, 0f,
        1f, -1f, -1f,   0f, -1f, 0f,
        1f, -1f,  1f,   0f, -1f, 0f,
        -1f, -1f, -1f,   0f, -1f, 0f,
        1f, -1f,  1f,   0f, -1f, 0f,
        -1f, -1f,  1f,   0f, -1f, 0f
    )

    init {
        val bb = ByteBuffer.allocateDirect(cubeCoords.size * 4).apply { order(ByteOrder.nativeOrder()) }
        vertexBuffer = bb.asFloatBuffer().apply { put(cubeCoords); position(0) }
        mProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, ShaderHelper.loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode))
            GLES20.glAttachShader(it, ShaderHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode))
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(mvpMatrix: FloatArray, modelMatrix: FloatArray, color: FloatArray) {
        GLES20.glUseProgram(mProgram)

        val positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
        val normalHandle = GLES20.glGetAttribLocation(mProgram, "vNormal")

        val stride = 6 * 4

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        GLES20.glUniform4fv(GLES20.glGetUniformLocation(mProgram, "vColor"), 1, color, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(mProgram, "uMVPMatrix"), 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(mProgram, "uModelMatrix"), 1, false, modelMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }
}

class ShadowPlane {
    private val vertexShaderCode = "uniform mat4 uMVPMatrix; attribute vec4 vPosition; void main() { gl_Position = uMVPMatrix * vPosition; }"
    private val fragmentShaderCode = "precision mediump float; void main() { gl_FragColor = vec4(0.1, 0.1, 0.1, 0.5); }"
    private val vertexBuffer: FloatBuffer
    private val mProgram: Int
    private val coords = floatArrayOf(-2.5f, -1.5f, -2.5f,  2.5f, -1.5f, -2.5f,  2.5f, -1.5f, 2.5f,  -2.5f, -1.5f, 2.5f)

    init {
        val bb = ByteBuffer.allocateDirect(coords.size * 4).apply { order(ByteOrder.nativeOrder()) }
        vertexBuffer = bb.asFloatBuffer().apply { put(coords); position(0) }
        mProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, ShaderHelper.loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode))
            GLES20.glAttachShader(it, ShaderHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode))
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(mProgram)
        val positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(mProgram, "uMVPMatrix"), 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}