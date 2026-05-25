package com.example.searchfloat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.searchfloat.data.Question
import com.example.searchfloat.data.QuestionDatabase
import com.example.searchfloat.ui.theme.SearchFloatTheme
import com.example.searchfloat.util.ActiveLibrary
import com.example.searchfloat.util.QuestionMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class LiveScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SearchFloatTheme {
                LiveScanScreen(onFinish = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScanScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val activeLib = remember { ActiveLibrary.get(context) }
    val dao = remember { QuestionDatabase.getDatabase(context).questionDao() }

    var allQuestions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var ocrText by remember { mutableStateOf("") }
    var bestMatch by remember { mutableStateOf<MatchInfo?>(null) }
    var hasCamPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamPerm = it
    }

    LaunchedEffect(Unit) {
        allQuestions = withContext(Dispatchers.IO) { dao.getAllOnceByLibrary(activeLib) }
        if (!hasCamPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫描搜题  📚 $activeLib") },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val intent = android.content.Intent(context, QuickSearchActivity::class.java)
                        context.startActivity(intent)
                        onFinish()
                    }) {
                        Text("✍️ 手动搜题", fontSize = 13.sp)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (hasCamPerm) {
                CameraPreviewView(
                    onText = { text ->
                        ocrText = text
                        bestMatch = if (allQuestions.isNotEmpty() && text.length >= 8) {
                            val r = QuestionMatcher.findBestMatchScored(text, allQuestions)
                            if (r.question != null) {
                                MatchInfo(
                                    question = r.question!!,
                                    score = r.score,
                                    titleLen = r.titleLen,
                                    matched = r.matched,
                                    confident = r.confident
                                )
                            } else null
                        } else null
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 顶部识别状态条
                Surface(
                    color = Color(0xCC000000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val type = QuestionMatcher.detectQuestionType(ocrText)
                        val typeStr = type?.let { " · 📌 $it 题" } ?: ""
                        val status = when {
                            ocrText.isBlank() -> "🔍 对准考试屏幕..."
                            bestMatch == null -> "👀 识别到 ${ocrText.length} 字$typeStr · 题库无匹配"
                            bestMatch!!.confident ->
                                "✅ 已锁定 (命中 ${bestMatch!!.matched}/${bestMatch!!.titleLen} · 综合分 ${bestMatch!!.score})$typeStr"
                            else ->
                                "⚠️ 仅弱匹配 (命中 ${bestMatch!!.matched}/${bestMatch!!.titleLen})$typeStr · 已隐藏答案"
                        }
                        Text(status, color = Color.White, fontSize = 13.sp)
                    }
                }

                // 底部答案面板：只有 confident 时才显示完整答案
                bestMatch?.let { m ->
                    if (m.confident) {
                        Surface(
                            color = Color(0xEE1B2D1F),
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                if (m.question.category.isNotBlank()) {
                                    Text(
                                        m.question.category,
                                        color = Color(0xFFAAAAAA),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    m.question.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    m.question.content,
                                    color = Color(0xFFD0FFDA),
                                    fontSize = 14.sp,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        // 弱匹配：只显示一个简短提示，不暴露答案，避免误导
                        Surface(
                            color = Color(0xEE2E2A1B),
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "⚠️ 题库未找到可信匹配",
                                    color = Color(0xFFFFE08A),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "最接近：${m.question.title}",
                                    color = Color(0xFFCCCCCC),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "（命中 ${m.matched}/${m.titleLen} 字，覆盖率不足，请用「✍️ 手动搜题」）",
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("需要相机权限")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予权限")
                    }
                }
            }
        }
    }
}

data class MatchInfo(
    val question: Question,
    val score: Int,
    val titleLen: Int,
    val matched: Int,
    val confident: Boolean
)

@Composable
fun CameraPreviewView(
    onText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    var lastAnalyzeMs = 0L
                    var processing = false
                    analyzer.setAnalyzer(analyzerExecutor) { proxy ->
                        val now = System.currentTimeMillis()
                        if (processing || now - lastAnalyzeMs < 600) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        lastAnalyzeMs = now
                        processing = true
                        processFrame(proxy, recognizer) { text ->
                            previewView.post { onText(text) }
                            processing = false
                        }
                    }
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
                } catch (e: Throwable) {
                    Log.e("LiveScan", "camera fail", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )

    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processFrame(
    proxy: ImageProxy,
    recognizer: TextRecognizer,
    onComplete: (String) -> Unit
) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        onComplete("")
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    recognizer.process(input)
        .addOnSuccessListener { vis -> onComplete(vis.text) }
        .addOnFailureListener { onComplete("") }
        .addOnCompleteListener { proxy.close() }
}
