package com.example.kouboteleprompter

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.kouboteleprompter.domain.CaptionCard
import com.example.kouboteleprompter.domain.CaptionProject
import com.example.kouboteleprompter.domain.CaptionProjectBuilder
import com.example.kouboteleprompter.domain.CaptionStylePreset
import com.example.kouboteleprompter.domain.CaptionTextMetrics
import com.example.kouboteleprompter.domain.CaptionTimelineEditor
import com.example.kouboteleprompter.domain.DEFAULT_VIDEO_SPEED
import com.example.kouboteleprompter.domain.MAX_CAPTION_POSITION
import com.example.kouboteleprompter.domain.MAX_CAPTION_FONT_SIZE_SP
import com.example.kouboteleprompter.domain.MAX_VIDEO_SPEED
import com.example.kouboteleprompter.domain.MIN_CAPTION_FONT_SIZE_SP
import com.example.kouboteleprompter.domain.MIN_VIDEO_SPEED
import com.example.kouboteleprompter.domain.FillerKind
import com.example.kouboteleprompter.domain.normalizedVideoSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private val CaptionInk = Color(0xFF111214)
private val CaptionOrange = Color(0xFFFF5B36)
private val CaptionGreen = Color(0xFF16C66A)
private val CaptionCanvas = Color(0xFFF5F5F3)
private val CaptionWordSurface = Color(0xFFF0F1EF)

private enum class WordEditMode { CLEANUP, EMPHASIS }

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun CaptionEditorScreen(
    videoFile: File,
    durationMs: Long,
    back: () -> Unit,
    saved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember(videoFile.absolutePath) {
        mutableStateOf(CaptionProjectStore.load(videoFile).getOrNull())
    }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableIntStateOf(0) }
    var editMode by remember { mutableStateOf(WordEditMode.EMPHASIS) }
    val previewVoiceEnhancement = remember(videoFile.absolutePath) {
        VoiceEnhancementAudioProcessor(enabled = false)
    }
    val player = remember(videoFile.absolutePath) {
        ExoPlayer.Builder(
            context,
            CaptionPreviewRenderersFactory(context, previewVoiceEnhancement)
        ).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            prepare()
        }
    }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var activeEditorCardId by remember(videoFile.absolutePath) { mutableStateOf<Int?>(null) }
    var playbackPositionMs by remember(player) { mutableLongStateOf(0L) }
    var playbackDurationMs by remember(player) {
        mutableLongStateOf(durationMs.coerceAtLeast(1L))
    }
    val previewRemovedRanges = remember(project, playbackDurationMs) {
        project?.let { CaptionTimelineEditor.decide(it, playbackDurationMs).removedRanges }.orEmpty()
    }

    LaunchedEffect(player, project?.videoSpeed) {
        player.setPlaybackSpeed(normalizedVideoSpeed(project?.videoSpeed ?: DEFAULT_VIDEO_SPEED))
    }
    LaunchedEffect(player, project?.noiseReductionEnabled) {
        val position = player.currentPosition.coerceAtLeast(0L)
        previewVoiceEnhancement.setEnabled(project?.noiseReductionEnabled == true)
        // Flush already-buffered PCM so switching the comparison is audible immediately.
        if (player.playbackState != Player.STATE_IDLE) player.seekTo(position)
    }

    LaunchedEffect(player, previewRemovedRanges) {
        while (true) {
            isPlaying = player.isPlaying
            var position = player.currentPosition.coerceAtLeast(0L)
            val removedRange = previewRemovedRanges.firstOrNull {
                position >= it.startMs && position < it.endMs
            }
            if (player.isPlaying && removedRange != null) {
                position = removedRange.endMs
                player.seekTo(position)
            }
            playbackPositionMs = position
            player.duration.takeIf { it > 0L }?.let { playbackDurationMs = it }
            delay(120.milliseconds)
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val currentProject = project
    Scaffold(
        containerColor = CaptionCanvas,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回视频详情")
                    }
                },
                title = { Text("AI 字幕", fontWeight = FontWeight.Bold) },
                actions = {
                    currentProject?.let {
                        Text(
                            "${it.cards.size} 句",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = {
            if (currentProject != null && currentProject.cards.isNotEmpty()) {
                CaptionEditorActions(
                    saveMessage = saveMessage,
                    isSaving = isSaving,
                    isExporting = isExporting,
                    exportProgress = exportProgress,
                    onSave = {
                        val snapshot = project ?: return@CaptionEditorActions
                        isSaving = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                CaptionProjectStore.save(videoFile, snapshot)
                            }
                            isSaving = false
                            result.onSuccess {
                                saveMessage = "已保存字幕方案"
                                saved()
                            }.onFailure {
                                saveMessage = "字幕保存失败，请重试"
                            }
                        }
                    },
                    onExport = {
                        val snapshot = project ?: return@CaptionEditorActions
                        val saveResult = CaptionProjectStore.save(videoFile, snapshot)
                        if (saveResult.isFailure) {
                            saveMessage = "字幕保存失败，请重试"
                            return@CaptionEditorActions
                        }
                        isExporting = true
                        exportProgress = 0
                        saveMessage = null
                        CaptionBurnInProcessor.export(
                            context = context,
                            inputFile = videoFile,
                            project = snapshot,
                            sourceDurationMs = playbackDurationMs,
                            progress = { value -> exportProgress = value },
                            completed = { result ->
                                result.onSuccess { exportedFile ->
                                    scope.launch {
                                        val galleryResult = withContext(Dispatchers.IO) {
                                            publishVideoToGallery(context, exportedFile)
                                        }
                                        galleryResult.onSuccess { uri ->
                                            rememberGalleryPublication(context, exportedFile, uri)
                                        }
                                        isExporting = false
                                        saveMessage = if (galleryResult.isSuccess) {
                                            "已导出带字幕视频并保存到相册"
                                        } else {
                                            "已导出到应用内，相册保存失败"
                                        }
                                    }
                                }.onFailure {
                                    isExporting = false
                                    saveMessage = "带字幕视频生成失败，请重试"
                                }
                            }
                        )
                    }
                )
            }
        }
    ) { padding ->
        if (currentProject == null || currentProject.cards.isEmpty()) {
            EmptyCaptionProject(
                modifier = Modifier.fillMaxSize().padding(padding),
                back = back
            )
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(CaptionCanvas)
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "preview") {
                CaptionVideoPreview(
                    player = player,
                    project = currentProject,
                    playbackPositionMs = playbackPositionMs,
                    playbackDurationMs = playbackDurationMs,
                    isPlaying = isPlaying,
                    onSeek = { value ->
                        playbackPositionMs = value
                        player.seekTo(value)
                    },
                    onTogglePlayback = {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onFontSizeChanged = { fontSizeSp ->
                        val latestProject = project ?: return@CaptionVideoPreview
                        project = latestProject.copy(
                            fontSizeSp = fontSizeSp.coerceIn(
                                MIN_CAPTION_FONT_SIZE_SP,
                                MAX_CAPTION_FONT_SIZE_SP
                            )
                        )
                    },
                    onCaptionPositionChanged = { x, y ->
                        val latestProject = project ?: return@CaptionVideoPreview
                        project = latestProject.copy(
                            captionPositionX = x.coerceIn(-MAX_CAPTION_POSITION, MAX_CAPTION_POSITION),
                            captionPositionY = y.coerceIn(-MAX_CAPTION_POSITION, MAX_CAPTION_POSITION)
                        )
                    }
                )
            }

            item(key = "toolbar") {
                VideoProcessingToolbar(
                    project = currentProject,
                    sourceDurationMs = playbackDurationMs,
                    onNoiseReductionChanged = { enabled ->
                        val latestProject = project ?: return@VideoProcessingToolbar
                        project = latestProject.copy(noiseReductionEnabled = enabled)
                    },
                    onSpeedChanged = { speed ->
                        val latestProject = project ?: return@VideoProcessingToolbar
                        project = latestProject.copy(videoSpeed = normalizedVideoSpeed(speed))
                    }
                )
            }

            item(key = "caption-toolbar") {
                CaptionEditingToolbar(
                    project = currentProject,
                    mode = editMode,
                    onModeChanged = { editMode = it },
                    onCleanupChanged = { enabled ->
                        project = CaptionProjectBuilder.withAutoCleanup(currentProject, enabled)
                    },
                    onEmphasisChanged = { enabled ->
                        project = CaptionProjectBuilder.withAutoEmphasis(currentProject, enabled)
                    },
                    onStyleSelected = { style ->
                        project = currentProject.copy(style = style)
                    },
                    onFontSizeChanged = { fontSizeSp ->
                        project = currentProject.copy(
                            fontSizeSp = fontSizeSp.coerceIn(
                                MIN_CAPTION_FONT_SIZE_SP,
                                MAX_CAPTION_FONT_SIZE_SP
                            )
                        )
                    }
                )
            }

            itemsIndexed(
                items = currentProject.cards,
                key = { _, card -> card.id }
            ) { cardIndex, card ->
                CaptionCardEditor(
                    card = card,
                    mode = editMode,
                    canMergeWithPrevious = cardIndex > 0,
                    canMergeWithNext = cardIndex < currentProject.cards.lastIndex,
                    onActivate = {
                        val targetMs = card.visibleWords.firstOrNull()?.startMs ?: card.startMs
                        val isAlreadyPreviewing = activeEditorCardId == card.id &&
                            player.currentPosition >= card.startMs &&
                            player.currentPosition < card.endMs
                        activeEditorCardId = card.id
                        if (!isAlreadyPreviewing) {
                            playbackPositionMs = targetMs
                            player.seekTo(targetMs)
                        }
                        player.play()
                    },
                    onTextChanged = { value ->
                        val replacement = CaptionProjectBuilder.replaceCardText(card, value)
                        project = currentProject.copy(
                            cards = currentProject.cards.toMutableList().apply {
                                this[cardIndex] = when {
                                    !currentProject.autoCleanupEnabled -> replacement.copy(
                                        words = replacement.words.map { word ->
                                            if (word.fillerKind != FillerKind.NONE) {
                                                word.copy(removed = false)
                                            } else word
                                        }
                                    )
                                    else -> replacement
                                }
                            }
                        ).let { updated ->
                            if (updated.autoEmphasisEnabled) updated else {
                                CaptionProjectBuilder.withAutoEmphasis(updated, false)
                            }
                        }
                    },
                    onSplit = { displayOffset ->
                        val latestProject = project ?: return@CaptionCardEditor
                        project = CaptionProjectBuilder.splitCard(
                            latestProject,
                            cardIndex,
                            displayOffset
                        )
                    },
                    onSplitBeforeWord = { wordIndex ->
                        val latestProject = project ?: return@CaptionCardEditor
                        project = CaptionProjectBuilder.splitCardBeforeWord(
                            latestProject,
                            cardIndex,
                            wordIndex
                        )
                    },
                    onMergeWithNext = {
                        val latestProject = project ?: return@CaptionCardEditor
                        project = CaptionProjectBuilder.mergeCardWithNext(
                            latestProject,
                            cardIndex
                        )
                    },
                    onMergeWithPrevious = {
                        val latestProject = project ?: return@CaptionCardEditor
                        project = CaptionProjectBuilder.mergeCardWithPrevious(
                            latestProject,
                            cardIndex
                        )
                    },
                    onWordSelectionChanged = { wordIndex, selected ->
                        val latestProject = project ?: return@CaptionCardEditor
                        val latestCard = latestProject.cards.getOrNull(cardIndex)
                            ?: return@CaptionCardEditor
                        val latestWord = latestCard.words.getOrNull(wordIndex)
                            ?: return@CaptionCardEditor
                        val alreadySelected = when (editMode) {
                            WordEditMode.CLEANUP -> latestWord.removed
                            WordEditMode.EMPHASIS -> latestWord.emphasized
                        }
                        if (alreadySelected != selected) {
                            val updatedWords = latestCard.words.toMutableList().apply {
                                this[wordIndex] = when (editMode) {
                                    WordEditMode.CLEANUP -> latestWord.copy(removed = selected)
                                    WordEditMode.EMPHASIS -> latestWord.copy(emphasized = selected)
                                }
                            }
                            project = latestProject.copy(
                                cards = latestProject.cards.toMutableList().apply {
                                    this[cardIndex] = latestCard.copy(words = updatedWords)
                                }
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyCaptionProject(modifier: Modifier, back: () -> Unit) {
    Column(
        modifier.background(Color(0xFFF5F5F3)).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.TextFields, null, tint = CaptionOrange, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text("没有检测到可用语音", fontWeight = FontWeight.Bold, fontSize = 21.sp)
        Text(
            "字幕来自录制时的本地离线识音。请确认口播清晰、麦克风未被系统静音后重新录制。",
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 10.dp)
        )
        OutlinedButton(onClick = back) { Text("返回视频详情") }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun CaptionVideoPreview(
    player: ExoPlayer,
    project: CaptionProject,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onCaptionPositionChanged: (Float, Float) -> Unit
) {
    val activeCard = project.cards.firstOrNull {
        playbackPositionMs >= it.startMs && playbackPositionMs < it.endMs
    }
    val latestFontSize by rememberUpdatedState(project.fontSizeSp)
    val latestFontSizeChanged by rememberUpdatedState(onFontSizeChanged)
    val latestCaptionPosition by rememberUpdatedState(
        Offset(project.captionPositionX, project.captionPositionY)
    )
    val latestCaptionPositionChanged by rememberUpdatedState(onCaptionPositionChanged)
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    val density = LocalDensity.current
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        }
        player.addListener(listener)
        videoSize = player.videoSize
        onDispose { player.removeListener(listener) }
    }
    val displayedVideoSize = remember(videoSize) {
        // Media3 reports dimensions in the orientation presented by PlayerView.
        videoSize.width * videoSize.pixelWidthHeightRatio to videoSize.height.toFloat()
    }
    val previewTextSizePx = CaptionTextMetrics.previewTextSizePx(
        fontSizeSp = project.fontSizeSp,
        videoWidthPx = displayedVideoSize.first,
        videoHeightPx = displayedVideoSize.second,
        viewportWidthPx = viewportSize.width.toFloat(),
        viewportHeightPx = viewportSize.height.toFloat()
    )
    val previewFontSize = with(density) { previewTextSizePx.toSp() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.background(CaptionInk).padding(bottom = 6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 10f)
                    .background(Color.Black)
                    .onGloballyPositioned { viewportSize = it.size }
            ) {
                AndroidView(
                    factory = { playerContext ->
                        PlayerView(playerContext).apply {
                            this.player = player
                            useController = false
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize()
                )
                activeCard?.takeIf { previewTextSizePx > 0f }?.let { card ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset {
                                IntOffset(
                                    (viewportSize.width * latestCaptionPosition.x / 2f).toInt(),
                                    (viewportSize.height * latestCaptionPosition.y / 2f).toInt()
                                )
                            }
                            .padding(horizontal = 16.dp)
                            .border(
                                width = 1.dp,
                                color = CaptionOrange.copy(alpha = .72f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .pointerInput(card.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    if (zoom != 1f) {
                                        latestFontSizeChanged(
                                            (latestFontSize * zoom).coerceIn(
                                                MIN_CAPTION_FONT_SIZE_SP,
                                                MAX_CAPTION_FONT_SIZE_SP
                                            )
                                        )
                                    }
                                    if (pan != Offset.Zero && viewportSize != IntSize.Zero) {
                                        latestCaptionPositionChanged(
                                            latestCaptionPosition.x + pan.x / (viewportSize.width / 2f),
                                            latestCaptionPosition.y + pan.y / (viewportSize.height / 2f)
                                        )
                                    }
                                }
                            }
                    ) {
                        Text(
                            "拖动移动 · 双指缩放",
                            color = Color.White.copy(alpha = .82f),
                            fontSize = 9.sp,
                            modifier = Modifier
                                .align(Alignment.End)
                                .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                        PreviewCaption(
                            card = card,
                            style = project.style,
                            fontSize = previewFontSize
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onTogglePlayback,
                    modifier = Modifier.size(36.dp).background(CaptionOrange, CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "暂停" else "播放",
                        tint = Color.White
                    )
                }
                Text(formatCaptionTime(playbackPositionMs), color = Color.LightGray, fontSize = 11.sp)
                Slider(
                    value = playbackPositionMs.coerceAtMost(playbackDurationMs).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..playbackDurationMs.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.weight(1f)
                )
                Text(formatCaptionTime(playbackDurationMs), color = Color.LightGray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PreviewCaption(
    card: CaptionCard,
    style: CaptionStylePreset,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    val emphasisColor = when (style) {
        CaptionStylePreset.BOLD_ORANGE -> Color(0xFFFFB766)
        CaptionStylePreset.FRESH_GREEN -> Color(0xFF66F29A)
        CaptionStylePreset.CLEAN_WHITE -> Color(0xFFFFD75E)
    }
    val baseColor = when (style) {
        CaptionStylePreset.FRESH_GREEN -> Color.White
        else -> Color.White
    }
    val text = buildAnnotatedString {
        card.visibleWords.forEach { word ->
            withStyle(
                SpanStyle(
                    color = if (word.emphasized) emphasisColor else baseColor,
                    fontWeight = if (word.emphasized) FontWeight.Black else FontWeight.Bold
                )
            ) {
                append(word.text)
            }
        }
    }
    val background = when (style) {
        CaptionStylePreset.CLEAN_WHITE -> Color.Black.copy(alpha = .56f)
        else -> Color.Transparent
    }
    Text(
        text = text,
        modifier = modifier.background(background, RoundedCornerShape(8.dp)).padding(8.dp),
        color = Color.White,
        textAlign = TextAlign.Center,
        fontSize = fontSize,
        lineHeight = fontSize * 1.24f,
        fontWeight = FontWeight.Bold,
        style = TextStyle(
            shadow = Shadow(Color.Black, blurRadius = 7f)
        )
    )
}

@Composable
private fun VideoProcessingToolbar(
    project: CaptionProject,
    sourceDurationMs: Long,
    onNoiseReductionChanged: (Boolean) -> Unit,
    onSpeedChanged: (Float) -> Unit
) {
    val speed = normalizedVideoSpeed(project.videoSpeed)
    val speedEnabled = speed != DEFAULT_VIDEO_SPEED
    val effectiveSpeed = if (speedEnabled) speed else MIN_VIDEO_SPEED
    val estimatedOutputDurationMs = remember(project, sourceDurationMs) {
        val keptDurationMs = CaptionTimelineEditor.decide(project, sourceDurationMs)
            .keptRanges
            .sumOf { it.durationMs }
        (keptDurationMs / normalizedVideoSpeed(project.videoSpeed)).toLong()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("成片处理", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("预览与导出同步", color = Color.Gray, fontSize = 11.sp)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = project.noiseReductionEnabled,
                onClick = { onNoiseReductionChanged(!project.noiseReductionEnabled) },
                label = {
                    Text(if (project.noiseReductionEnabled) "人声降噪 · 开" else "人声降噪")
                }
            )
            Text(
                text = if (project.noiseReductionEnabled) {
                    "过滤持续底噪，突出说话声"
                } else {
                    "关闭，不改变原声音质"
                },
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = Color(0xFFEAEAE7))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = speedEnabled,
                onClick = {
                    onSpeedChanged(if (speedEnabled) DEFAULT_VIDEO_SPEED else MIN_VIDEO_SPEED)
                },
                label = {
                    Text(if (speedEnabled) "视频变速 ${formatVideoSpeed(speed)}" else "视频变速")
                }
            )
            Text(
                if (speedEnabled) "导出约 ${formatCaptionTime(estimatedOutputDurationMs)}" else "原速",
                color = if (speedEnabled) CaptionOrange else Color.Gray,
                fontWeight = if (speedEnabled) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formatVideoSpeed(MIN_VIDEO_SPEED), color = Color.Gray, fontSize = 11.sp)
            Slider(
                value = effectiveSpeed,
                onValueChange = { value ->
                    val steppedSpeed = (value * 20f).roundToInt() / 20f
                    onSpeedChanged(steppedSpeed)
                },
                enabled = speedEnabled,
                valueRange = MIN_VIDEO_SPEED..MAX_VIDEO_SPEED,
                steps = 7,
                modifier = Modifier.weight(1f)
            )
            Text(formatVideoSpeed(MAX_VIDEO_SPEED), color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CaptionEditingToolbar(
    project: CaptionProject,
    mode: WordEditMode,
    onModeChanged: (WordEditMode) -> Unit,
    onCleanupChanged: (Boolean) -> Unit,
    onEmphasisChanged: (Boolean) -> Unit,
    onStyleSelected: (CaptionStylePreset) -> Unit,
    onFontSizeChanged: (Float) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = mode == WordEditMode.EMPHASIS,
                onClick = { onModeChanged(WordEditMode.EMPHASIS) },
                label = { Text("划重点") },
                leadingIcon = { Icon(Icons.Default.TextFields, null, Modifier.size(17.dp)) }
            )
            FilterChip(
                selected = mode == WordEditMode.CLEANUP,
                onClick = { onModeChanged(WordEditMode.CLEANUP) },
                label = { Text("口水词") }
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when (mode) {
                    WordEditMode.EMPHASIS -> "点按或横滑字块划重点"
                    WordEditMode.CLEANUP -> "点按字块删除或恢复"
                },
                color = Color.Gray,
                fontSize = 12.sp
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = project.autoEmphasisEnabled,
                onClick = { onEmphasisChanged(!project.autoEmphasisEnabled) },
                label = { Text("自动重点") }
            )
            FilterChip(
                selected = project.autoCleanupEnabled,
                onClick = { onCleanupChanged(!project.autoCleanupEnabled) },
                label = { Text("自动精简 ${project.automaticallyRemovedCount}处") }
            )
            Spacer(Modifier.width(2.dp))
            Text("样式", color = Color.Gray, fontSize = 12.sp)
            CaptionStylePreset.entries.forEach { preset ->
                val label = when (preset) {
                    CaptionStylePreset.BOLD_ORANGE -> "醒目橙"
                    CaptionStylePreset.FRESH_GREEN -> "清新绿"
                    CaptionStylePreset.CLEAN_WHITE -> "简洁白"
                }
                FilterChip(
                    selected = project.style == preset,
                    onClick = { onStyleSelected(preset) },
                    label = { Text(label) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.TextFields, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            Text("字幕 ${project.fontSizeSp.roundToInt()}sp", fontSize = 12.sp, color = Color.Gray)
            Slider(
                value = project.fontSizeSp.coerceIn(
                    MIN_CAPTION_FONT_SIZE_SP,
                    MAX_CAPTION_FONT_SIZE_SP
                ),
                onValueChange = { onFontSizeChanged(it.roundToInt().toFloat()) },
                valueRange = MIN_CAPTION_FONT_SIZE_SP..MAX_CAPTION_FONT_SIZE_SP,
                steps = (MAX_CAPTION_FONT_SIZE_SP - MIN_CAPTION_FONT_SIZE_SP).toInt() - 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CaptionEditorActions(
    saveMessage: String?,
    isSaving: Boolean,
    isExporting: Boolean,
    exportProgress: Int,
    onSave: () -> Unit,
    onExport: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        HorizontalDivider(color = Color(0xFFE5E5E3))
        saveMessage?.let { message ->
            Text(
                message,
                color = if (message.startsWith("已")) Color(0xFF278B55) else CaptionOrange,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp
            )
        }
        if (isExporting) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("正在生成带字幕视频", color = Color.Gray, fontSize = 12.sp)
                Text("$exportProgress%", color = CaptionOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            LinearProgressIndicator(
                progress = { exportProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = CaptionOrange
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                enabled = !isSaving && !isExporting,
                onClick = onSave,
                modifier = Modifier.weight(0.38f)
            ) {
                Text(if (isSaving) "保存中…" else "保存")
            }
            Button(
                enabled = !isSaving && !isExporting,
                onClick = onExport,
                modifier = Modifier.weight(0.62f),
                colors = ButtonDefaults.buttonColors(containerColor = CaptionOrange)
            ) {
                Text(if (isExporting) "正在导出 $exportProgress%" else "导出视频")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionCardEditor(
    card: CaptionCard,
    mode: WordEditMode,
    canMergeWithPrevious: Boolean,
    canMergeWithNext: Boolean,
    onActivate: () -> Unit,
    onTextChanged: (String) -> Unit,
    onSplit: (Int) -> Unit,
    onSplitBeforeWord: (Int) -> Unit,
    onMergeWithPrevious: () -> Unit,
    onMergeWithNext: () -> Unit,
    onWordSelectionChanged: (Int, Boolean) -> Unit
) {
    var isEditingText by remember(card.id) { mutableStateOf(false) }
    var splitAnchorWordIndex by remember(card.id, mode) { mutableStateOf<Int?>(null) }
    var editingValue by remember(card.id) {
        mutableStateOf(
            TextFieldValue(
                text = card.displayText,
                selection = TextRange(card.displayText.length)
            )
        )
    }
    LaunchedEffect(card.displayText) {
        if (editingValue.text != card.displayText) {
            editingValue = TextFieldValue(
                text = card.displayText,
                selection = TextRange(
                    editingValue.selection.start.coerceAtMost(card.displayText.length)
                )
            )
        }
    }
    val displayedWords = card.words
        .mapIndexed { index, word -> index to word }
        .filter { (_, word) -> mode == WordEditMode.CLEANUP || !word.removed }
    val displayedWordIndices = displayedWords.map { it.first }
    val wordBounds = remember(card.id, mode, displayedWordIndices) {
        mutableMapOf<Int, Rect>()
    }
    val selectionByIndex = displayedWords.associate { (index, word) ->
        index to when (mode) {
            WordEditMode.CLEANUP -> word.removed
            WordEditMode.EMPHASIS -> word.emphasized
        }
    }
    val latestSelectionByIndex by rememberUpdatedState(selectionByIndex)
    val latestSelectionChanged by rememberUpdatedState(onWordSelectionChanged)
    val latestActivate by rememberUpdatedState(onActivate)
    val rawSplitOffset = editingValue.selection.start.coerceIn(0, editingValue.text.length)
    val splitOffset = CaptionProjectBuilder.replaceCardText(
        card,
        editingValue.text.substring(0, rawSplitOffset)
    ).displayText.length
    val canSplit = editingValue.text.substring(0, rawSplitOffset).isNotBlank() &&
        editingValue.text.substring(rawSplitOffset).isNotBlank() &&
        splitOffset in 1 until card.displayText.length
    val selectedWordIndex = splitAnchorWordIndex
        ?.takeIf(displayedWordIndices::contains)
        ?: displayedWords.firstOrNull { (index, _) -> selectionByIndex[index] == true }?.first
    val canQuickSplit = selectedWordIndex?.let { wordIndex ->
        CaptionProjectBuilder.canSplitBeforeWord(card, wordIndex)
    } == true

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable(onClick = onActivate)
            .padding(start = 10.dp, top = 7.dp, end = 6.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = if (isEditingText) canSplit else canQuickSplit,
                    onClick = {
                        onActivate()
                        if (isEditingText) {
                            onSplit(splitOffset)
                        } else if (selectedWordIndex != null) {
                            onSplitBeforeWord(selectedWordIndex)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(if (isEditingText) "光标处分句" else "快速分句", fontSize = 12.sp)
                }
                TextButton(
                    enabled = canMergeWithPrevious,
                    onClick = {
                        onActivate()
                        onMergeWithPrevious()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("合并上句", fontSize = 12.sp)
                }
                TextButton(
                    enabled = canMergeWithNext,
                    onClick = {
                        onActivate()
                        onMergeWithNext()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("合并下句", fontSize = 12.sp)
                }
            }

            if (displayedWords.isEmpty()) {
                Text(
                    "本句内容已全部删除，切换到「口水词」可恢复",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 5.dp)
                )
            } else {
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(card.id, mode, displayedWordIndices) {
                            if (mode != WordEditMode.EMPHASIS) return@pointerInput
                            val visitedIndices = mutableSetOf<Int>()
                            var selectionTarget: Boolean? = null
                            var previousPosition: Offset? = null

                            fun applySelectionAlongDrag(position: Offset) {
                                val positions = sampledDragPositions(previousPosition, position)
                                val indices = positions.mapNotNull { sample ->
                                    wordBounds.entries.firstOrNull { (_, bounds) ->
                                        bounds.contains(sample)
                                    }?.key
                                }.distinct()

                                if (selectionTarget == null) {
                                    val firstIndex = indices.firstOrNull() ?: return
                                    splitAnchorWordIndex = firstIndex
                                    selectionTarget = !(latestSelectionByIndex[firstIndex] ?: false)
                                }
                                indices.forEach { index ->
                                    if (visitedIndices.add(index)) {
                                        latestSelectionChanged(index, selectionTarget == true)
                                    }
                                }
                                previousPosition = position
                            }

                            detectHorizontalDragGestures(
                                onDragStart = { position ->
                                    latestActivate()
                                    visitedIndices.clear()
                                    selectionTarget = null
                                    previousPosition = position
                                    applySelectionAlongDrag(position)
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    applySelectionAlongDrag(change.position)
                                },
                                onDragEnd = {
                                    selectionTarget = null
                                    previousPosition = null
                                },
                                onDragCancel = {
                                    selectionTarget = null
                                    previousPosition = null
                                }
                            )
                        },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    displayedWords.forEach { (index, word) ->
                        val selected = when (mode) {
                            WordEditMode.CLEANUP -> word.removed
                            WordEditMode.EMPHASIS -> word.emphasized
                        }
                        val accentColor = when {
                            mode == WordEditMode.EMPHASIS -> CaptionGreen
                            else -> CaptionOrange
                        }
                        val idleTextColor = when {
                            mode == WordEditMode.CLEANUP && word.fillerKind != FillerKind.NONE -> accentColor
                            else -> Color(0xFF565B5F)
                        }
                        Text(
                            text = word.text.ifBlank { "·" },
                            color = if (selected) Color.White else idleTextColor,
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            textDecoration = if (mode == WordEditMode.CLEANUP && word.removed) {
                                TextDecoration.LineThrough
                            } else null,
                            modifier = Modifier
                                .onGloballyPositioned { coordinates ->
                                    wordBounds[index] = coordinates.boundsInParent()
                                }
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (selected) accentColor
                                    else if (mode == WordEditMode.CLEANUP && word.fillerKind != FillerKind.NONE) {
                                        accentColor.copy(alpha = .10f)
                                    } else CaptionWordSurface
                                )
                                .clickable {
                                    onActivate()
                                    splitAnchorWordIndex = index
                                    onWordSelectionChanged(index, !selected)
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            if (isEditingText) {
                OutlinedTextField(
                    value = editingValue,
                    onValueChange = { value ->
                        val textChanged = value.text != editingValue.text
                        editingValue = value
                        if (textChanged) onTextChanged(value.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3,
                    textStyle = TextStyle(fontSize = 15.sp),
                    shape = RoundedCornerShape(9.dp),
                    placeholder = { Text("输入字幕文案") }
                )
            }
        }

        Column(
            modifier = Modifier.width(38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            IconButton(
                onClick = {
                    isEditingText = !isEditingText
                    if (isEditingText) {
                        editingValue = TextFieldValue(
                            text = card.displayText,
                            selection = TextRange(card.displayText.length)
                        )
                        onActivate()
                    }
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (isEditingText) Icons.Default.Done else Icons.Default.Edit,
                    contentDescription = if (isEditingText) "完成文案编辑" else "编辑整句文案",
                    tint = if (isEditingText) CaptionGreen else Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                "${formatCompactCaptionTime(card.startMs)}\n${formatCompactCaptionTime(card.endMs)}",
                color = CaptionOrange,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onActivate)
                    .padding(horizontal = 1.dp, vertical = 2.dp)
            )
        }
    }
}

private fun sampledDragPositions(start: Offset?, end: Offset): List<Offset> {
    if (start == null || start == end) return listOf(end)
    val distance = (end - start).getDistance()
    val steps = (distance / 4f).toInt().coerceAtLeast(1)
    return (0..steps).map { step ->
        val progress = step.toFloat() / steps
        start + (end - start) * progress
    }
}

private fun formatCaptionTime(ms: Long): String = String.format(
    Locale.US,
    "%02d:%02d",
    ms / 60_000,
    (ms / 1_000) % 60
)

private fun formatVideoSpeed(speed: Float): String =
    String.format(Locale.US, "%.2f×", speed)

private fun formatCompactCaptionTime(ms: Long): String = String.format(
    Locale.US,
    "%d:%02d",
    ms / 60_000,
    (ms / 1_000) % 60
)
