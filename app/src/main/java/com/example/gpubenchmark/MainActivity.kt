rootProject.name = "Gpu-Benchmark"
include ':app'
buildscript {
    repositories { google(); mavenCentral() }
}
allprojects { repositories { google(); mavenCentral() } }
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.gpubenchmark'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.gpubenchmark"
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }

    buildTypes {
        debug {
            // keep debug as-is
        }
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.activity:activity-compose:1.9.0'
    implementation 'androidx.compose.ui:ui:1.5.0'
    implementation 'androidx.compose.material:material:1.5.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0' // optional
}
<manifest package="com.example.gpubenchmark" xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28"/>
    <application
        android:allowBackup="true"
        android:label="GPU벤치마크"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">
        <activity android:name="com.example.gpubenchmark.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
  package com.example.gpubenchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen()
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    // GLBenchView has a constructor with Context
    val glView = remember { GLBenchView(context) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BenchResult?>(null) }
    var temps by remember { mutableStateOf<List<Float>>(emptyList()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        AndroidView(factory = { glView }, modifier = Modifier
            .fillMaxWidth()
            .height(320.dp))

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!running) {
                    result = null
                    temps = emptyList()
                    glView.startBenchmark(60) { r ->
                        result = r
                        // stop thermal monitor after finish
                        glView.thermalMonitor.stop()
                        running = false
                    }
                    glView.thermalMonitor.start()
                    glView.thermalMonitor.onTemperatureUpdate {
                        temps = glView.thermalMonitor.getResults()
                    }
                    running = true
                } else {
                    glView.stopBenchmark()
                    glView.thermalMonitor.stop()
                    running = false
                }
            }) {
                Text(if (!running) "Start Bench (60s)" else "Stop")
            }

            Button(onClick = {
                // TODO: export JSON or share intent (left as extension)
            }) {
                Text("Export (JSON)")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        result?.let {
            Text("GPU BENCH RESULT")
            Text("Avg FPS: %.2f".format(it.avgFps))
            Text("Min FPS: %.2f".format(it.minFps))
            Text("FPS Drop %: %.2f".format(it.fpsDropPercent))
            Text("Frames: %d".format(it.frames))
            Text("FPS stddev: %.2f".format(it.fpsStdDev))
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (temps.isNotEmpty()) {
            Text("Temperature samples: ${temps.size}")
            Text("Max temp: ${temps.maxOrNull() ?: 0f}°C")
        }
    }
}
package com.example.gpubenchmark

import android.content.Context
import android.util.AttributeSet
import android.opengl.GLSurfaceView

class GLBenchView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    private val renderer = GLBenchRenderer()
    val thermalMonitor = ThermalMonitor(context)

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun startBenchmark(durationSec: Int = 60, onFinish: (BenchResult) -> Unit) {
        renderer.startBenchmark(durationSec, onFinish)
    }

    fun stopBenchmark() {
        renderer.stopBenchmark()
    }
}
package com.example.gpubenchmark

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

class GLBenchRenderer : GLSurfaceView.Renderer {
    private var running = false
    private var startTimeNs = 0L
    private val frameTimestamps = mutableListOf<Long>()
    private var durationNs = 60_000_000_000L
    private var onFinish: ((BenchResult) -> Unit)? = null

    private val shader = SimpleShader()
    private var vertexBuffer: FloatBuffer? = null
    private var vertexCount = 0

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        shader.init()
        setupGeometry(2000) // triangle count - tweak for target devices
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        shader.setResolution(width, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        if (!running) return
        val now = System.nanoTime()
        if (startTimeNs == 0L) startTimeNs = now
        frameTimestamps.add(now)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        shader.use()

        vertexBuffer?.position(0)
        val positionHandle = shader.attribPosition
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)

        GLES30.glDisableVertexAttribArray(positionHandle)

        if (now - frameTimestamps.first() >= durationNs) {
            running = false
            val result = computeResult()
            onFinish?.invoke(result)
        }
    }

    fun startBenchmark(durationSec: Int, callback: (BenchResult) -> Unit) {
        frameTimestamps.clear()
        startTimeNs = 0L
        durationNs = durationSec * 1_000_000_000L
        onFinish = callback
        running = true
    }

    fun stopBenchmark() { running = false }

    private fun setupGeometry(triangleCount: Int) {
        val coords = FloatArray(triangleCount * 3 * 2)
        var i = 0
        for (t in 0 until triangleCount) {
            for (v in 0 until 3) {
                coords[i++] = (Math.random() * 2.0 - 1.0).toFloat()
                coords[i++] = (Math.random() * 2.0 - 1.0).toFloat()
            }
        }
        vertexCount = triangleCount * 3
        vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(coords); position(0)
        }
    }

    private fun computeResult(): BenchResult {
        if (frameTimestamps.size < 2) return BenchResult(0.0, 0.0, 0.0, 0, 0.0)
        val intervalsMs = frameTimestamps.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
        val fpsList = intervalsMs.map { 1000.0 / it }
        val avg = fpsList.average()
        val min = fpsList.minOrNull() ?: avg
        val std = sqrt(fpsList.map { (it - avg) * (it - avg) }.average())
        val dropPercent = if (avg > 0) (avg - min) / avg * 100.0 else 0.0
        return BenchResult(avg, min, dropPercent, fpsList.size, std)
    }
}
package com.example.gpubenchmark

import android.opengl.GLES30
import android.util.Log

class SimpleShader {
    private var program = 0
    var attribPosition = 0
    private var uResolution = -1

    fun init() {
        val vs = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            void main() {
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val fs = """
            #version 300 es
            precision highp float;
            out vec4 fragColor;
            uniform vec2 uResolution;
            void main() {
                vec2 uv = gl_FragCoord.xy / uResolution;
                float v = sin(uv.x * 200.0) * cos(uv.y * 200.0);
                float c = 0.0;
                for (int i=0; i<8; ++i) { c += abs(sin(v * float(i+1))); }
                fragColor = vec4(vec3(c * 0.125), 1.0);
            }
        """.trimIndent()

        val v = loadShader(GLES30.GL_VERTEX_SHADER, vs)
        val f = loadShader(GLES30.GL_FRAGMENT_SHADER, fs)
        program = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v)
            GLES30.glAttachShader(it, f)
            GLES30.glLinkProgram(it)
        }
        attribPosition = GLES30.glGetAttribLocation(program, "aPos")
        uResolution = GLES30.glGetUniformLocation(program, "uResolution")
    }

    fun setResolution(w: Int, h: Int) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uResolution, w.toFloat(), h.toFloat())
    }

    fun use() { GLES30.glUseProgram(program) }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("SimpleShader", "Could not compile shader: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
package com.example.gpubenchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.*
import java.io.File

class ThermalMonitor(private val context: Context) {
    private val temps = mutableListOf<Float>()
    private var job: Job? = null
    private var updateCallback: (() -> Unit)? = null

    private val sysPaths = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone2/temp"
    )

    fun start(pollMs: Long = 1000L) {
        stop()
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val t = readTemperature()
                if (t != null) temps.add(t)
                updateCallback?.invoke()
                delay(pollMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    fun onTemperatureUpdate(cb: () -> Unit) { updateCallback = cb }

    fun getResults(): List<Float> = temps.toList()

    private fun readTemperature(): Float? {
        for (p in sysPaths) {
            try {
                val f = File(p)
                if (f.exists()) {
                    val raw = f.readText().trim().toFloat()
                    return if (raw > 1000f) raw / 1000f else raw
                }
            } catch (_: Exception) {}
        }
        // fallback: battery temperature
        try {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) return temp / 10f
        } catch (_: Exception) {}
        return null
    }
}
package com.example.gpubenchmark

data class BenchResult(
    val avgFps: Double,
    val minFps: Double,
    val fpsDropPercent: Double,
    val frames: Int,
    val fpsStdDev: Double = 0.0
)
<resources>
    <string name="app_name">GPU벤치마크</string>
</resources>
