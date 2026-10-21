package com.spritzflip.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class WheelControlMode {
    AUTO_SMART,  // In Play: cambia WPM | In Pausa: scorre le parole
    ALWAYS_WPM,  // Tasti Volume e Jog-Wheel cambiano sempre i WPM
    ALWAYS_SCRUB // Tasti Volume e Jog-Wheel scorrono sempre le parole
}

enum class FlipPosture {
    COVER_SCREEN, // Schermo esterno FlexWindow 4.1" (quasi quadrato, altezza < 520dp)
    FLEX_HALF,    // Telefono piegato a 90° (Half-Opened / Tabletop)
    UNFOLDED_FULL // Schermo interno aperto 6.9"
}

private data class WordSpan(
    val globalIndex: Int,
    val startChar: Int,
    val endChar: Int
)

class MainActivity : ComponentActivity() {

    // Flusso per inviare gli scatti dei tasti fisici Volume (+1 / -1) alla UI Compose
    private val volumeKeyStepFlow = MutableSharedFlow<Int>(extraBufferCapacity = 16)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SpritzFlipApp(volumeKeyStepFlow = volumeKeyStepFlow)
            }
        }
    }

    /**
     * Intercetta i tasti fisici Volume Su / Volume Giù per usarli come la corona rotante
     * quando il Galaxy Z Flip 7 / Z Flip 8 è chiuso sulla FlexWindow.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeKeyStepFlow.tryEmit(1)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeKeyStepFlow.tryEmit(-1)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

@Composable
fun SpritzFlipApp(volumeKeyStepFlow: MutableSharedFlow<Int>) {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("spritz_flip_prefs", Context.MODE_PRIVATE) }

    var currentBook by remember { mutableStateOf(BookParser.getDemoBook()) }
    var wordIndex by remember { mutableIntStateOf(prefs.getInt("saved_index_${currentBook.id}", 0)) }
    var wpm by remember { mutableIntStateOf(prefs.getInt("saved_wpm", 320)) }
    var isPlaying by remember { mutableStateOf(false) }
    var isTtsEnabled by remember { mutableStateOf(false) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var wheelMode by remember { mutableStateOf(WheelControlMode.AUTO_SMART) }
    var showLibraryDialog by remember { mutableStateOf(false) }

    // Safe-Zone per il doppio obiettivo fotocamera della FlexWindow 4.1" di Z Flip 7 / Z Flip 8
    var cameraCutoutSafePadding by remember { mutableStateOf(true) }
    var wordsReadInSession by remember { mutableIntStateOf(0) }

    // Rilevamento postura pieghevole (Cerniera Flex Mode vs Cover Screen 4.1" vs Aperto 6.9")
    val windowLayoutInfo by remember(context) {
        WindowInfoTracker.getOrCreate(context).windowLayoutInfo(context as ComponentActivity)
    }.collectAsState(initial = null)

    val flipPosture = remember(configuration.screenHeightDp, configuration.screenWidthDp, windowLayoutInfo) {
        val foldingFeature = windowLayoutInfo?.displayFeatures
            ?.filterIsInstance<FoldingFeature>()
            ?.firstOrNull()

        when {
            // Se l'altezza della finestra è compatta (< 520dp), siamo sulla FlexWindow esterna da 4.1"
            configuration.screenHeightDp < 520 -> FlipPosture.COVER_SCREEN
            foldingFeature?.state == FoldingFeature.State.HALF_OPENED -> FlipPosture.FLEX_HALF
            else -> FlipPosture.UNFOLDED_FULL
        }
    }

    val activeControlIsWpm = when (wheelMode) {
        WheelControlMode.AUTO_SMART -> isPlaying
        WheelControlMode.ALWAYS_WPM -> true
        WheelControlMode.ALWAYS_SCRUB -> false
    }

    // Motore Text-To-Speech sincronizzato parola per parola
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    val currentChunkSpans = remember { mutableStateListOf<WordSpan>() }
    var currentUtteranceDone by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var rangeCallbackTriggered by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.ITALIAN
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        rangeCallbackTriggered = true
                        val matched = currentChunkSpans.firstOrNull { span ->
                            start in span.startChar..span.endChar || span.startChar in start..end
                        }
                        if (matched != null) {
                            wordIndex = matched.globalIndex
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        currentUtteranceDone?.complete(Unit)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        currentUtteranceDone?.complete(Unit)
                    }
                })
                ttsEngine = engine
                isTtsReady = true
            }
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }

    // Funzione centralizzata per applicare uno scatto (da Tasti Volume o da Jog-Wheel touch)
    val applyStepInput: (Int) -> Unit = { steps ->
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (activeControlIsWpm) {
            wpm = (wpm + steps * 10).coerceIn(100, 1000)
            if (isTtsEnabled && isPlaying) {
                ttsEngine?.setSpeechRate((wpm / 180f).coerceIn(0.5f, 3.0f))
            }
        } else {
            isPlaying = false
            ttsEngine?.stop()
            wordIndex = (wordIndex + steps).coerceIn(0, currentBook.words.lastIndex)
        }
    }

    // Ascolta i tasti fisici Volume Su / Volume Giù
    LaunchedEffect(volumeKeyStepFlow, activeControlIsWpm, currentBook) {
        volumeKeyStepFlow.collect { step ->
            applyStepInput(step)
        }
    }

    LaunchedEffect(wordIndex, wpm, currentBook.id) {
        prefs.edit()
            .putInt("saved_index_${currentBook.id}", wordIndex)
            .putInt("saved_wpm", wpm)
            .apply()
    }

    // Loop di riproduzione (Visivo puro o Ibrido TTS + Spritz)
    LaunchedEffect(isPlaying, isTtsEnabled, currentBook) {
        if (!isPlaying) {
            ttsEngine?.stop()
            currentUtteranceDone?.complete(Unit)
            wordsReadInSession = 0
            return@LaunchedEffect
        }

        if (isTtsEnabled && isTtsReady && ttsEngine != null) {
            while (isPlaying && wordIndex <= currentBook.words.lastIndex) {
                val startIdx = wordIndex
                val spans = mutableListOf<WordSpan>()
                val sb = StringBuilder()
                var idx = startIdx

                while (idx <= currentBook.words.lastIndex && spans.size < 18) {
                    val w = currentBook.words[idx]
                    val s = sb.length
                    sb.append(w)
                    val e = sb.length
                    spans.add(WordSpan(idx, s, e))
                    sb.append(" ")
                    idx++
                    if (w.endsWith(".") || w.endsWith("!") || w.endsWith("?") || w.endsWith(";")) break
                }

                currentChunkSpans.clear()
                currentChunkSpans.addAll(spans)
                rangeCallbackTriggered = false

                val speechRate = (wpm / 180f).coerceIn(0.5f, 3.0f)
                ttsEngine?.setSpeechRate(speechRate)

                val doneDeferred = CompletableDeferred<Unit>()
                currentUtteranceDone = doneDeferred

                ttsEngine?.speak(
                    sb.toString().trim(),
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "FLIP_UTTERANCE_$startIdx"
                )

                for (span in spans) {
                    if (!isPlaying || doneDeferred.isCompleted) break
                    if (!rangeCallbackTriggered) wordIndex = span.globalIndex
                    val spritz = BookParser.toSpritzWord(currentBook.words[span.globalIndex])
                    val effectiveTtsWpm = (180f * speechRate).toInt()
                    delay(BookParser.calculateDelayMs(effectiveTtsWpm, spritz.delayMultiplier))
                }

                doneDeferred.await()
                if (isPlaying) {
                    val nextIdx = spans.last().globalIndex + 1
                    if (nextIdx <= currentBook.words.lastIndex) wordIndex = nextIdx else isPlaying = false
                }
            }
        } else {
            while (isPlaying && wordIndex <= currentBook.words.lastIndex) {
                val currentRawWord = currentBook.words[wordIndex]
                val spritz = BookParser.toSpritzWord(currentRawWord)
                val ramp = (0.6f + (wordsReadInSession.coerceAtMost(12) / 12f) * 0.4f)
                delay(BookParser.calculateDelayMs(wpm, spritz.delayMultiplier, ramp))

                if (wordIndex < currentBook.words.lastIndex) {
                    wordIndex++
                    wordsReadInSession++
                } else {
                    isPlaying = false
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                isLoading = true
                isPlaying = false
                ttsEngine?.stop()
                val name = it.lastPathSegment?.substringAfterLast('/') ?: "Libro.epub"
                val loaded = withContext(Dispatchers.IO) {
                    BookParser.importUriToInternalStorage(context, it, name)
                }
                currentBook = loaded
                wordIndex = prefs.getInt("saved_index_${loaded.id}", 0).coerceIn(0, loaded.words.lastIndex)
                isLoading = false
                showLibraryDialog = false
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFE53935))
        }
        return
    }

    if (showLibraryDialog) {
        FlipLibraryScreen(
            onSelectFile = { file ->
                scope.launch {
                    isLoading = true
                    isPlaying = false
                    ttsEngine?.stop()
                    val loaded = withContext(Dispatchers.IO) { BookParser.parseFile(file) }
                    currentBook = loaded
                    wordIndex = prefs.getInt("saved_index_${loaded.id}", 0).coerceIn(0, loaded.words.lastIndex)
                    isLoading = false
                    showLibraryDialog = false
                }
            },
            onSelectDemo = {
                ttsEngine?.stop()
                currentBook = BookParser.getDemoBook()
                wordIndex = 0
                isPlaying = false
                showLibraryDialog = false
            },
            onOpenSystemPicker = {
                filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*"))
            },
            onClose = { showLibraryDialog = false }
        )
        return
    }

    val progress = if (currentBook.words.size > 1) {
        wordIndex.toFloat() / (currentBook.words.size - 1).toFloat()
    } else 1f
    val remainingWords = (currentBook.words.size - 1 - wordIndex).coerceAtLeast(0)
    val remainingMinutes = (remainingWords.toFloat() / wpm.toFloat()).toInt()
    val currentChapterNum = currentBook.chapterIndices.count { it <= wordIndex }.coerceAtLeast(1)

    // Calcola il margine inferiore per evitare il doppio obiettivo fotocamera sulla FlexWindow da 4.1"
    val bottomCameraCutoutPadding = if (flipPosture == FlipPosture.COVER_SCREEN && cameraCutoutSafePadding) {
        66.dp
    } else {
        12.dp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                top = if (flipPosture == FlipPosture.COVER_SCREEN) 10.dp else 28.dp,
                bottom = bottomCameraCutoutPadding,
                start = 16.dp,
                end = 16.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Barra superiore: Titolo, Capitolo, WPM, Stato Postura Z Flip e pulsante Safe-Zone Fotocamera
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentBook.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (flipPosture == FlipPosture.COVER_SCREEN) {
                    // Toggle Safe-Zone per evitare i fori delle fotocamere su FlexWindow 4.1"
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (cameraCutoutSafePadding) Color(0xFF263238) else Color(0xFF1A1A1A))
                            .clickable { cameraCutoutSafePadding = !cameraCutoutSafePadding }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Safe Zone Fotocamere",
                            tint = if (cameraCutoutSafePadding) Color(0xFF80CBC4) else Color.Gray,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (cameraCutoutSafePadding) "CAM SAFE" else "FULL",
                            color = Color.White,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (isTtsEnabled) Color(0xFF4CAF50) else Color(0xFFE53935),
                trackColor = Color(0xFF222222)
            )

            Spacer(modifier = Modifier.height(4.dp))

            val postureBadge = when (flipPosture) {
                FlipPosture.COVER_SCREEN -> "FLEXWINDOW 4.1\""
                FlipPosture.FLEX_HALF -> "FLEX MODE 90°"
                FlipPosture.UNFOLDED_FULL -> "SCHERMO INTERNO"
            }
            Text(
                text = "$postureBadge • Cap.$currentChapterNum • $wpm WPM • -${remainingMinutes}m",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp
            )
        }

        // PALCO CENTRALE: Mirino Spritz fedele al video + Jog-Wheel tattile
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SpritzFlipReticleStage(
                words = currentBook.words,
                wordIndex = wordIndex,
                isPlaying = isPlaying,
                isCompactCover = (flipPosture == FlipPosture.COVER_SCREEN),
                onTogglePlay = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    isPlaying = !isPlaying
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // JOG-WHEEL TATTILE ORIZZONTALE (Sostituisce la corona rotante col pollice)
            TouchJogWheelBar(
                activeControlIsWpm = activeControlIsWpm,
                wpm = wpm,
                wordIndex = wordIndex,
                totalWords = currentBook.words.size,
                onStep = { step -> applyStepInput(step) }
            )
        }

        // Se il telefono è aperto (6.9") o in Flex Mode (90°), mostra anche il testo sincronizzato e lo slider WPM
        if (flipPosture != FlipPosture.COVER_SCREEN) {
            ExpandedFlipDeck(
                words = currentBook.words,
                wordIndex = wordIndex,
                wpm = wpm,
                onWpmChange = { newWpm ->
                    wpm = newWpm
                    if (isTtsEnabled && isPlaying) {
                        ttsEngine?.setSpeechRate((wpm / 180f).coerceIn(0.5f, 3.0f))
                    }
                },
                onWordClick = { clickedIndex ->
                    isPlaying = false
                    ttsEngine?.stop()
                    wordIndex = clickedIndex
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.9f)
                    .padding(vertical = 8.dp)
            )
        }

        // PLANCIA CONTROLLI INFERIORE
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Selettore funzione Tasti Volume + Jog-Wheel
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (activeControlIsWpm) Color(0xFF3E1818) else Color(0xFF1A2936))
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        wheelMode = when (wheelMode) {
                            WheelControlMode.AUTO_SMART -> WheelControlMode.ALWAYS_WPM
                            WheelControlMode.ALWAYS_WPM -> WheelControlMode.ALWAYS_SCRUB
                            WheelControlMode.ALWAYS_SCRUB -> WheelControlMode.AUTO_SMART
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (activeControlIsWpm) Icons.Default.Speed else Icons.Default.Swipe,
                    contentDescription = "Modalità Tasti Volume e Jog-Wheel",
                    tint = if (activeControlIsWpm) Color(0xFFFF5252) else Color(0xFF64B5F6),
                    modifier = Modifier.size(13.dp)
                )
                val label = when (wheelMode) {
                    WheelControlMode.AUTO_SMART -> if (isPlaying) "VOL / WHEEL: AUTO (WPM)" else "VOL / WHEEL: AUTO (PAROLE)"
                    WheelControlMode.ALWAYS_WPM -> "VOL / WHEEL: FISSO WPM"
                    WheelControlMode.ALWAYS_SCRUB -> "VOL / WHEEL: FISSO PAROLE"
                }
                Text(text = label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlipRoundButton(
                    icon = Icons.Default.FolderOpen,
                    contentDesc = "Libreria EPUB/TXT",
                    onClick = {
                        isPlaying = false
                        ttsEngine?.stop()
                        showLibraryDialog = true
                    }
                )

                FlipRoundButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDesc = "Capitolo Precedente",
                    onClick = {
                        isPlaying = false
                        ttsEngine?.stop()
                        wordIndex = currentBook.chapterIndices.lastOrNull { it < wordIndex - 5 } ?: 0
                    }
                )

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isTtsEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (wordIndex >= currentBook.words.lastIndex) wordIndex = 0
                            isPlaying = !isPlaying
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pausa",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                FlipRoundButton(
                    icon = Icons.Default.SkipNext,
                    contentDesc = "Capitolo Successivo",
                    onClick = {
                        isPlaying = false
                        ttsEngine?.stop()
                        wordIndex = currentBook.chapterIndices.firstOrNull { it > wordIndex }
                            ?: currentBook.words.lastIndex
                    }
                )

                FlipRoundButton(
                    icon = if (isTtsEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDesc = "Voce TTS",
                    tint = if (isTtsEnabled) Color(0xFF81C784) else Color.White,
                    bgColor = if (isTtsEnabled) Color(0xFF1B5E20) else Color(0xFF262626),
                    onClick = {
                        isTtsEnabled = !isTtsEnabled
                        if (!isTtsEnabled) ttsEngine?.stop()
                    }
                )
            }
        }
    }
}

/**
 * Riproduce il mirino circolare con le guide bianche e la lettera focale rossa (ORP)
 * esattamente come mostrato nel video di riferimento.
 */
@Composable
fun SpritzFlipReticleStage(
    words: List<String>,
    wordIndex: Int,
    isPlaying: Boolean,
    isCompactCover: Boolean,
    onTogglePlay: () -> Unit
) {
    val word = words.getOrElse(wordIndex) { "" }
    val spritz = remember(word) { BookParser.toSpritzWord(word) }

    val baseFontSize = if (isCompactCover) 30 else 36
    val dynamicFontSize = when {
        word.length <= 9 -> baseFontSize.sp
        word.length in 10..13 -> (baseFontSize - 5).sp
        word.length in 14..17 -> (baseFontSize - 9).sp
        else -> (baseFontSize - 13).sp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePlay() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Linea guida superiore con il mirino circolare al centro
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            val circleRadius = 6.dp.toPx()

            drawLine(
                color = Color.White,
                start = Offset(12f, centerY),
                end = Offset(centerX - circleRadius, centerY),
                strokeWidth = 2.dp.toPx()
            )
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.dp.toPx())
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX + circleRadius, centerY),
                end = Offset(size.width - 12f, centerY),
                strokeWidth = 2.dp.toPx()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Parola tripartita: la lettera ORP rossa cade sempre sull'asse del mirino circolare
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = spritz.prefix,
                color = Color.White,
                fontSize = dynamicFontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = spritz.pivot,
                color = Color(0xFFE53935),
                fontSize = dynamicFontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Text(
                text = spritz.suffix,
                color = Color.White,
                fontSize = dynamicFontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Linea guida inferiore con tacca rossa
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            drawLine(
                color = Color.White,
                start = Offset(12f, centerY),
                end = Offset(size.width - 12f, centerY),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = Color(0xFFE53935),
                start = Offset(centerX, centerY - 4.dp.toPx()),
                end = Offset(centerX, centerY),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Anteprima contesto frase in Pausa sulla Cover Screen
        if (!isPlaying && isCompactCover && words.isNotEmpty()) {
            val startCtx = (wordIndex - 5).coerceAtLeast(0)
            val endCtx = (wordIndex + 5).coerceAtMost(words.lastIndex)
            val annotated = buildAnnotatedString {
                for (i in startCtx..endCtx) {
                    if (i == wordIndex) {
                        withStyle(SpanStyle(color = Color(0xFFE53935), fontWeight = FontWeight.Bold)) {
                            append("${words[i]} ")
                        }
                    } else {
                        withStyle(SpanStyle(color = Color(0xFF888888))) {
                            append("${words[i]} ")
                        }
                    }
                }
            }
            Text(
                text = annotated,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
    }
}

/**
 * Ruota Tattile Orizzontale (Jog-Wheel) per scorrere le parole o cambiare WPM col pollice
 * sulla FlexWindow da 4.1".
 */
@Composable
fun TouchJogWheelBar(
    activeControlIsWpm: Boolean,
    wpm: Int,
    wordIndex: Int,
    totalWords: Int,
    onStep: (Int) -> Unit
) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF161616))
            .pointerInput(activeControlIsWpm) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    dragAccumulator += dragAmount
                    val stepThreshold = 26f
                    if (abs(dragAccumulator) >= stepThreshold) {
                        val steps = (dragAccumulator / stepThreshold).toInt()
                        dragAccumulator %= stepThreshold
                        onStep(steps)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val hintText = if (activeControlIsWpm) {
            "◀ Trascina Jog-Wheel o Tasti Vol: $wpm WPM ▶"
        } else {
            "◀ Trascina Jog-Wheel o Tasti Vol: Parola ${wordIndex + 1}/$totalWords ▶"
        }
        Text(
            text = hintText,
            color = Color(0xFFBDBDBD),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Plancia aggiuntiva mostrata automaticamente quando il Galaxy Z Flip 7/8 viene aperto a 90° (Flex Mode)
 * o completamente aperto: mostra il paragrafo interattivo e lo slider diretto dei WPM.
 */
@Composable
fun ExpandedFlipDeck(
    words: List<String>,
    wordIndex: Int,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
    onWordClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val startWindow = (wordIndex - 25).coerceAtLeast(0)
    val endWindow = (wordIndex + 45).coerceAtMost(words.lastIndex)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121212))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = buildAnnotatedString {
                for (i in startWindow..endWindow) {
                    if (i == wordIndex) {
                        withStyle(
                            SpanStyle(
                                color = Color(0xFFFF5252),
                                fontWeight = FontWeight.Bold,
                                background = Color(0xFF3E1818)
                            )
                        ) {
                            append(" ${words[i]} ")
                        }
                    } else {
                        withStyle(SpanStyle(color = Color(0xFFB0B0B0))) {
                            append("${words[i]} ")
                        }
                    }
                }
            },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .clickable { onWordClick(wordIndex) }
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("100", color = Color.Gray, fontSize = 10.sp)
            Slider(
                value = wpm.toFloat(),
                onValueChange = { onWpmChange((it / 10).toInt() * 10) },
                valueRange = 100f..1000f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFE53935),
                    activeTrackColor = Color(0xFFE53935)
                ),
                modifier = Modifier.weight(1f)
            )
            Text("1000 WPM", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

@Composable
fun FlipRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    tint: Color = Color.White,
    bgColor: Color = Color(0xFF262626),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun FlipLibraryScreen(
    onSelectFile: (File) -> Unit,
    onSelectDemo: () -> Unit,
    onOpenSystemPicker: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var savedFiles by remember { mutableStateOf(BookParser.listSavedBooks(context)) }
    var wifiUrl by remember { mutableStateOf<String?>(null) }

    val wifiServer = remember {
        WifiBookServer(context) { uploadedFile ->
            savedFiles = BookParser.listSavedBooks(context)
            onSelectFile(uploadedFile)
        }
    }

    DisposableEffect(Unit) {
        onDispose { wifiServer.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "LIBRERIA Z FLIP (.EPUB / .TXT)",
            color = Color(0xFFE53935),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E2A38))
                .clickable { onOpenSystemPicker() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Apri File .EPUB o .TXT dal Telefono", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1B3B2B))
                .clickable {
                    wifiUrl = if (wifiUrl == null) wifiServer.start(8080) else {
                        wifiServer.stop()
                        null
                    }
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                Text(wifiUrl ?: "Ricevi via Wi-Fi dal PC", color = Color.White, fontSize = 12.sp)
            }
        }

        savedFiles.take(3).forEach { file ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1F1F1F))
                    .clickable { onSelectFile(file) }
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(file.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onSelectDemo() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Libro Demo", color = Color.LightGray, fontSize = 12.sp)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onClose() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Chiudi", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}
