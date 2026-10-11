package com.example.kouboteleprompter

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size as AndroidSize
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.kouboteleprompter.data.AppDatabase
import com.example.kouboteleprompter.data.ScriptEntity
import com.example.kouboteleprompter.domain.generateContentTitle
import com.example.kouboteleprompter.domain.isAutomaticScriptTitle
import com.example.kouboteleprompter.domain.isLegacyDefaultScriptTitle
import com.example.kouboteleprompter.domain.ReadingProgress
import com.example.kouboteleprompter.domain.TimedTranscriptSegment
import com.example.kouboteleprompter.domain.resolveScriptTitle
import com.example.kouboteleprompter.domain.videoBaseName
import com.example.kouboteleprompter.domain.videoDisplayTitle
import com.example.kouboteleprompter.domain.videoRecordedAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private val Ink = Color(0xFF111214)
private val Orange = Color(0xFFFF5B36)
private val SuccessGreen = Color(0xFF278B55)
private const val SPEECH_LOG_TAG = "KouboSpeech"
private const val TELEPROMPTER_PREFS = "teleprompter_display"
// V3 makes the script flush with the top of the camera viewport by default. A new key avoids
// restoring an older saved offset that would reintroduce the empty strip above the panel.
private const val TELEPROMPTER_TOP_KEY = "panel_top_dp_v3"
private const val TELEPROMPTER_FONT_SIZE_KEY = "font_size_sp"
private const val TELEPROMPTER_LINE_SPACING_KEY = "line_spacing_sp"
private const val GALLERY_PREFS = "gallery_publications"
private const val DEFAULT_TELEPROMPTER_FONT_SIZE = 24f
private const val DEFAULT_TELEPROMPTER_LINE_SPACING = 10f
private const val MIN_TELEPROMPTER_FONT_SIZE = 18f
private const val MAX_TELEPROMPTER_FONT_SIZE = 36f
private const val MIN_TELEPROMPTER_LINE_SPACING = 4f
private const val MAX_TELEPROMPTER_LINE_SPACING = 20f
private const val TARGET_VIDEO_BIT_RATE = 6_000_000
private const val GALLERY_COPY_BUFFER_BYTES = 1024 * 1024
private data class SavedVideo(
    val file: File,
    val durationMs: Long,
    val galleryUri: Uri? = null,
    val gallerySaveFailed: Boolean = false
)

private data class SavingState(
    val stage: String = "正在准备保存…",
    val percent: Int = 0,
    val startedAtMs: Long = 0L
)

private val videoThumbnailCache = object : LruCache<String, Bitmap>(12 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KouboApp() }
    }
}

class ScriptViewModel(private val db: AppDatabase) : ViewModel() {
    val scripts = db.scriptDao().observeAll()
        .map { scripts ->
            scripts.map { script ->
                if (isLegacyDefaultScriptTitle(script.title)) {
                    script.copy(title = generateContentTitle(script.content))
                } else {
                    script
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(
        existing: ScriptEntity?,
        title: String,
        content: String,
        onSaved: (Long) -> Unit
    ) = viewModelScope.launch {
        val resolvedTitle = resolveScriptTitle(title, content)
        val updated = existing?.copy(
            title = resolvedTitle,
            content = content,
            updatedAt = System.currentTimeMillis()
        ) ?: ScriptEntity(title = resolvedTitle, content = content)
        onSaved(db.scriptDao().upsert(updated))
    }
    fun delete(id: Long) = viewModelScope.launch { db.scriptDao().delete(id) }
}

@Composable
private fun KouboApp() {
    val context = LocalContext.current
    val vm: ScriptViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScriptViewModel(AppDatabase.create(context)) as T
    })
    val scripts by vm.scripts.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("home") }
    var editing by remember { mutableStateOf<ScriptEntity?>(null) }
    var editorReturnPage by remember { mutableStateOf("home") }
    var savedVideo by remember { mutableStateOf<SavedVideo?>(null) }
    var homeScrollTargetId by remember { mutableStateOf<Long?>(null) }
    var homeSelectedTab by rememberSaveable { mutableIntStateOf(0) }
    val defaultTypography = Typography()
    val largerTypography = defaultTypography.copy(
        bodyLarge = defaultTypography.bodyLarge.copy(fontSize = 18.sp),
        bodyMedium = defaultTypography.bodyMedium.copy(fontSize = 16.sp),
        titleMedium = defaultTypography.titleMedium.copy(fontSize = 18.sp),
        labelLarge = defaultTypography.labelLarge.copy(fontSize = 16.sp)
    )
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Orange), typography = largerTypography) {
        when (page) {
            "editor" -> EditorScreen(
                script = editing,
                save = { title, content ->
                    val current = editing
                    val resolvedTitle = resolveScriptTitle(title, content)
                    vm.save(current, resolvedTitle, content) { savedId ->
                        editing = current?.copy(
                            title = resolvedTitle,
                            content = content,
                            updatedAt = System.currentTimeMillis()
                        )
                        if (current == null && editorReturnPage == "home") {
                            homeScrollTargetId = savedId
                        }
                        page = editorReturnPage
                    }
                },
                back = { page = editorReturnPage }
            )
            "record" -> RecordScreen(
                script = editing ?: scripts.firstOrNull(),
                back = { page = "home" },
                edit = { script ->
                    editing = script
                    editorReturnPage = "record"
                    page = "editor"
                },
                saved = { video ->
                    savedVideo = video
                    homeSelectedTab = 1
                    page = "detail"
                }
            )
            "detail" -> savedVideo?.let {
                VideoDetailScreen(
                    video = it,
                    backHome = { page = "home" },
                    recordAgain = { page = "record" },
                    editCaptions = { page = "captions" }
                )
            }
            "captions" -> savedVideo?.let {
                CaptionEditorScreen(
                    videoFile = it.file,
                    durationMs = it.durationMs,
                    back = { page = "detail" },
                    saved = { page = "detail" }
                )
            }
            else -> HomeScreen(
                scripts = scripts,
                selectedTab = homeSelectedTab,
                onSelectedTabChange = { homeSelectedTab = it },
                scrollToTopForScriptId = homeScrollTargetId,
                onScrollToTopConsumed = { scriptId ->
                    if (homeScrollTargetId == scriptId) homeScrollTargetId = null
                },
                edit = {
                    editing = it
                    editorReturnPage = "home"
                    page = "editor"
                },
                record = { editing = it; page = "record" },
                create = {
                    editing = null
                    homeSelectedTab = 0
                    editorReturnPage = "home"
                    page = "editor"
                },
                delete = vm::delete,
                openVideo = { file ->
                    homeSelectedTab = 1
                    savedVideo = SavedVideo(
                        file = file,
                        durationMs = readVideoDuration(file),
                        galleryUri = publishedGalleryUri(context, file)
                    )
                    page = "detail"
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    scripts: List<ScriptEntity>,
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    scrollToTopForScriptId: Long?,
    onScrollToTopConsumed: (Long) -> Unit,
    edit: (ScriptEntity?) -> Unit,
    record: (ScriptEntity) -> Unit,
    create: () -> Unit,
    delete: (Long) -> Unit,
    openVideo: (File) -> Unit
) {
    val scriptListState = rememberLazyListState()
    val videoListState = rememberLazyListState()
    val isScrollTargetLoaded = scrollToTopForScriptId?.let { targetId ->
        scripts.any { it.id == targetId }
    } == true
    LaunchedEffect(scrollToTopForScriptId, isScrollTargetLoaded) {
        val targetId = scrollToTopForScriptId
        if (targetId != null && isScrollTargetLoaded) {
            onSelectedTabChange(0)
            scriptListState.scrollToItem(0)
            onScrollToTopConsumed(targetId)
        }
    }

    val recordings = LocalContext.current.filesDir.resolve("recordings")
        .listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) && !file.name.endsWith(".video.mp4") }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("口播提词器", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ink,
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = create,
                    containerColor = Orange,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, "新建台本")
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF7F7F5))
                .padding(padding)
        ) {
            HomeTabSwitcher(
                selectedTab = selectedTab,
                scriptCount = scripts.size,
                videoCount = recordings.size,
                onSelectedTabChange = onSelectedTabChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            when (selectedTab) {
                0 -> LazyColumn(
                    state = scriptListState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        top = 2.dp,
                        end = 16.dp,
                        bottom = 92.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        HomeListHeading(
                            title = "口播台本",
                            subtitle = "准备好台本，一键进入录制"
                        )
                    }
                    if (scripts.isEmpty()) {
                        item { EmptyState(create) }
                    } else {
                        items(scripts, key = { it.id }) { script ->
                            ScriptListItem(
                                script = script,
                                edit = { edit(script) },
                                record = { record(script) },
                                delete = { delete(script.id) }
                            )
                        }
                    }
                }

                else -> LazyColumn(
                    state = videoListState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        top = 2.dp,
                        end = 16.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        HomeListHeading(
                            title = "视频作品",
                            subtitle = "查看、播放和继续编辑已完成的口播"
                        )
                    }
                    if (recordings.isEmpty()) {
                        item { EmptyVideoState(onGoToScripts = { onSelectedTabChange(0) }) }
                    } else {
                        items(recordings, key = { it.absolutePath }) { file ->
                            SavedVideoItem(file = file, onClick = { openVideo(file) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTabSwitcher(
    selectedTab: Int,
    scriptCount: Int,
    videoCount: Int,
    onSelectedTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFE9E9E6), RoundedCornerShape(14.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("口播台本" to scriptCount, "视频作品" to videoCount).forEachIndexed { index, tab ->
            val selected = selectedTab == index
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) Color.White else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelectedTabChange(index) }
                    )
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tab.first,
                    color = if (selected) Ink else Color(0xFF686866),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tab.second.toString(),
                    color = if (selected) Color.White else Color(0xFF777775),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(
                            if (selected) Orange else Color(0xFFD7D7D3),
                            CircleShape
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeListHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(text = subtitle, color = Color(0xFF777775), fontSize = 14.sp)
    }
}

@Composable
private fun ScriptListItem(
    script: ScriptEntity,
    edit: () -> Unit,
    record: () -> Unit,
    delete: () -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = edit)) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(54.dp)
                    .background(Orange, RoundedCornerShape(4.dp))
            )
            Column(Modifier.weight(1f).padding(start = 11.dp, end = 4.dp)) {
                Text(
                    text = script.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = script.content.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" "),
                    color = Color(0xFF666664),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "创建 ${formatScriptCreatedAt(script.createdAt)} · " +
                        "${script.content.length} 字 · ${script.content.lineSequence().count()} 段",
                    color = Color(0xFF92928F),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = record, modifier = Modifier.size(42.dp)) {
                Box(
                    Modifier.size(34.dp).background(Orange, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "开始录制",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            IconButton(onClick = delete, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Default.Delete,
                    "删除",
                    tint = Color(0xFF999996),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SavedVideoItem(file: File, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(
                file = file,
                modifier = Modifier.width(104.dp).height(72.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = videoDisplayTitle(file.name),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = videoRecordedAt(file.name, file.lastModified()),
                    color = Color.Gray,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    text = formatFileSize(file.length()),
                    color = Color.Gray,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "查看视频详情",
                tint = Color.Gray,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun VideoThumbnail(file: File, modifier: Modifier = Modifier) {
    val cacheKey = "${file.absolutePath}:${file.lastModified()}"
    var thumbnail by remember(cacheKey) {
        mutableStateOf(videoThumbnailCache.get(cacheKey)?.asImageBitmap())
    }

    LaunchedEffect(cacheKey) {
        if (thumbnail == null) {
            thumbnail = withContext(Dispatchers.IO) {
                val bitmap = videoThumbnailCache.get(cacheKey) ?: extractVideoThumbnail(file)?.also {
                    videoThumbnailCache.put(cacheKey, it)
                }
                bitmap?.asImageBitmap()
            }
        }
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Ink),
        contentAlignment = Alignment.Center
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.size(32.dp).background(Color.Black.copy(alpha = .56f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable private fun EmptyState(create: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("还没有台本", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("把想说的话写下来，提词器会陪你完成录制", color = Color.Gray, modifier = Modifier.padding(8.dp))
        Button(onClick = create, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Orange)) { Icon(Icons.Default.Add, null); Text(" 新建台本") }
    }
}

@Composable
private fun EmptyVideoState(onGoToScripts: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.VideoLibrary,
            contentDescription = null,
            tint = Color(0xFFB0B0AC),
            modifier = Modifier.size(42.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有视频作品", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("从一份台本开始录制，你的成片会出现在这里", color = Color.Gray, modifier = Modifier.padding(8.dp))
        Button(
            onClick = onGoToScripts,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Ink)
        ) {
            Text("去选择台本")
        }
    }
}

private fun formatScriptCreatedAt(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestampMs))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(script: ScriptEntity?, save: (String, String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var title by remember(script?.id) {
        mutableStateOf(
            script?.title
                ?.takeUnless { isAutomaticScriptTitle(it, script.content) }
                .orEmpty()
        )
    }
    var content by remember(script?.id) { mutableStateOf(script?.content ?: "") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: content } }
    Scaffold(topBar = { TopAppBar(navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, title = { Text(if (script == null) "新建台本" else "编辑台本") }, actions = { IconButton({ picker.launch(arrayOf("text/plain")) }) { Icon(Icons.Default.FolderOpen, "导入 TXT") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                title,
                { title = it },
                Modifier.fillMaxWidth(),
                label = { Text("台本标题（可选）") },
                placeholder = { Text("留空将根据台本内容自动生成") },
                supportingText = if (title.isBlank() && content.isNotBlank()) {
                    { Text("将自动保存为：${generateContentTitle(content)}") }
                } else null,
                singleLine = true
            )
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("粘贴或输入台本") }, minLines = 12)
            Button({ save(title, content) }, Modifier.fillMaxWidth(), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Orange), enabled = content.isNotBlank()) { Text("保存台本") }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun RecordScreen(script: ScriptEntity?, back: () -> Unit, edit: (ScriptEntity) -> Unit, saved: (SavedVideo) -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val capturePermissions = remember {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
    val requestedPermissions = remember {
        buildList {
            addAll(capturePermissions)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
    var hasPermission by remember {
        mutableStateOf(capturePermissions.all { it.isGranted(context) })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Gallery access is deliberately not a precondition for recording. On Android 8/9,
        // denying legacy storage permission keeps the private recording and reports only the
        // gallery publication as failed.
        hasPermission = capturePermissions.all { it.isGranted(context) }
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableIntStateOf(0) }
    var recognitionBaseProgress by remember { mutableIntStateOf(0) }
    var keepListening by remember { mutableStateOf(false) }
    var recognitionStatus by remember { mutableStateOf("等待开始") }
    var recognitionSession by remember { mutableIntStateOf(-1) }
    var recognitionAudioSilenced by remember { mutableStateOf(false) }
    var audioLevelDb by remember { mutableStateOf(-120f) }
    var transcriptSegments by remember(script?.id) {
        mutableStateOf<Map<Int, TimedTranscriptSegment>>(emptyMap())
    }
    var isSaving by remember { mutableStateOf(false) }
    var savingState by remember { mutableStateOf(SavingState()) }
    var savingElapsedMs by remember { mutableLongStateOf(0L) }
    var cameraMessage by remember { mutableStateOf<String?>("正在连接摄像头…") }
    var requestedLensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var beautyEnabled by rememberSaveable { mutableStateOf(false) }
    var noiseReductionEnabled by rememberSaveable { mutableStateOf(false) }
    val noiseReductionAvailable = remember { NoiseSuppressor.isAvailable() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val cameraProvider = remember { ProcessCameraProvider.getInstance(context) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // Keep Android's back gesture consistent with the visible back button. During capture or
    // finalization, consume it so an accidental edge swipe cannot discard an in-flight video.
    BackHandler {
        if (recording == null && !isSaving) back()
    }

    val offlineSpeechEngine = remember(script?.id) {
        OfflineSpeechEngine(
            context = context.applicationContext,
            onStatus = { recognitionStatus = it },
            onResult = { result ->
                if (result.isFinal && result.tokens.isNotEmpty()) {
                    transcriptSegments = transcriptSegments + (
                        result.segment to TimedTranscriptSegment(result.segment, result.tokens)
                    )
                }
                val activeScript = script
                if (keepListening && activeScript != null && result.text.isNotBlank()) {
                    if (recognitionSession != result.segment) {
                        recognitionSession = result.segment
                        recognitionBaseProgress = progress
                    }
                    val match = ReadingProgress.match(
                        activeScript.content,
                        result.text,
                        recognitionBaseProgress
                    )
                    Log.d(
                        SPEECH_LOG_TAG,
                        "offline_match segment=${result.segment} base=$recognitionBaseProgress " +
                            "cursor=${match.cursor} accepted=${match.accepted} " +
                            "matched=${match.matchedCharacters}/${match.spokenCharacters} " +
                            "score=${"%.2f".format(match.score)} final=${result.isFinal} " +
                            "text=${result.text.takeLast(32)}"
                    )
                    progress = maxOf(progress, match.cursor)
                    recognitionStatus = if (result.isFinal) {
                        "已识别：${result.text.takeLast(14)}"
                    } else {
                        "正在识别：${result.text.takeLast(14)}"
                    }
                }
            },
            onSilenced = { silenced ->
                recognitionAudioSilenced = silenced
                if (silenced && keepListening) {
                    recognitionStatus = "识别音频被系统静音，请导出日志排查"
                }
            },
            onAudioLevel = { audioLevelDb = it }
        )
    }

    LaunchedEffect(hasPermission, requestedLensFacing) {
        if (hasPermission) {
            val requestedFacing = requestedLensFacing
            videoCapture = null
            cameraMessage = "正在连接${lensName(requestedFacing)}摄像头…"
            cameraProvider.addListener({
                if (requestedFacing != requestedLensFacing) return@addListener
                val lifecycleOwner = activity
                if (lifecycleOwner == null) {
                    cameraMessage = "无法连接当前页面的摄像头生命周期"
                    return@addListener
                }
                try {
                    val result = configureCamera(cameraProvider.get(), lifecycleOwner, previewView, requestedFacing)
                    videoCapture = result.videoCapture
                    cameraMessage = result.message
                } catch (_: Exception) {
                    videoCapture = null
                    cameraMessage = "摄像头启动失败，请检查模拟器的 Camera 设置后重试"
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }
    LaunchedEffect(recording) {
        while (recording != null) { elapsed = System.currentTimeMillis() - recordingStartedAt; delay(1000.milliseconds) }
    }
    LaunchedEffect(isSaving, savingState.startedAtMs) {
        while (isSaving) {
            savingElapsedMs = (System.currentTimeMillis() - savingState.startedAtMs).coerceAtLeast(0L)
            delay(500.milliseconds)
        }
    }
    LaunchedEffect(hasPermission, offlineSpeechEngine) {
        if (hasPermission) offlineSpeechEngine.prepare()
    }
    DisposableEffect(cameraProvider) {
        onDispose {
            if (cameraProvider.isDone) runCatching { cameraProvider.get().unbindAll() }
        }
    }
    DisposableEffect(offlineSpeechEngine) {
        onDispose { offlineSpeechEngine.close() }
    }

    if (!hasPermission) {
        Surface(Modifier.fillMaxSize(), color = Ink) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要录制权限", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    "用于预览、录音、同步提词，并把成片保存到系统相册"
                } else {
                    "用于预览、录音和同步提词；成片会自动保存到系统相册"
                },
                color = Color.LightGray,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            Button({ permissionLauncher.launch(requestedPermissions) }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Orange)) { Text("授权并继续") }
            Text("返回", color = Color.White, modifier = Modifier.padding(18.dp).clickable { back() })
        } }
        return
    }

    val scriptScrollState = rememberScrollState()
    var scriptTextLayout by remember(script?.id) { mutableStateOf<TextLayoutResult?>(null) }
    var scriptViewportHeight by remember(script?.id) { mutableIntStateOf(0) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val teleprompterPreferences = remember(context) {
        context.getSharedPreferences(TELEPROMPTER_PREFS, Context.MODE_PRIVATE)
    }
    var scriptFontSizeSp by rememberSaveable {
        mutableStateOf(
            teleprompterPreferences
                .getFloat(TELEPROMPTER_FONT_SIZE_KEY, DEFAULT_TELEPROMPTER_FONT_SIZE)
                .coerceIn(MIN_TELEPROMPTER_FONT_SIZE, MAX_TELEPROMPTER_FONT_SIZE)
        )
    }
    var scriptLineSpacingSp by rememberSaveable {
        mutableStateOf(
            teleprompterPreferences
                .getFloat(TELEPROMPTER_LINE_SPACING_KEY, DEFAULT_TELEPROMPTER_LINE_SPACING)
                .coerceIn(MIN_TELEPROMPTER_LINE_SPACING, MAX_TELEPROMPTER_LINE_SPACING)
        )
    }
    var showTeleprompterSettings by rememberSaveable { mutableStateOf(false) }
    var isDraggingScriptPanel by remember { mutableStateOf(false) }
    val scriptPanelHeightDp = (configuration.screenHeightDp * 0.27f).coerceIn(176f, 232f)
    val minScriptPanelTopDp = 0f
    val maxScriptPanelTopDp = (configuration.screenHeightDp - scriptPanelHeightDp - 218f)
        .coerceAtLeast(minScriptPanelTopDp)
    var scriptPanelTopDp by rememberSaveable {
        mutableStateOf(
            teleprompterPreferences.getFloat(TELEPROMPTER_TOP_KEY, minScriptPanelTopDp)
        )
    }
    LaunchedEffect(maxScriptPanelTopDp) {
        scriptPanelTopDp = scriptPanelTopDp.coerceIn(minScriptPanelTopDp, maxScriptPanelTopDp)
    }
    LaunchedEffect(progress, scriptTextLayout, scriptViewportHeight, scriptScrollState.maxValue) {
        val content = script?.content.orEmpty()
        val layout = scriptTextLayout
        if (layout != null && content.isNotEmpty() && scriptViewportHeight > 0) {
            val lineTop = if (progress >= content.length) {
                layout.size.height.toFloat()
            } else {
                val offset = progress.coerceIn(0, content.lastIndex)
                layout.getLineTop(layout.getLineForOffset(offset))
            }
            // Keep the active line on the top reading path so the entire viewport shows what
            // comes next. The first line therefore starts flush at the top instead of drifting
            // down into the panel.
            val target = lineTop.toInt()
            scriptScrollState.animateScrollTo(target.coerceIn(0, scriptScrollState.maxValue))
        }
    }

    val reportSaving: (String, Int) -> Unit = { stage, percent ->
        ContextCompat.getMainExecutor(context).execute {
            savingState = savingState.copy(
                stage = stage,
                percent = maxOf(
                    savingState.percent.coerceAtMost(99),
                    percent.coerceIn(0, 99)
                )
            )
        }
    }

    val finishSaving: (File, Long) -> Unit = { file, durationMs ->
        val galleryStartPercent = if (beautyEnabled) 85 else 70
        val transcriptSnapshot = transcriptSegments.values.sortedBy(TimedTranscriptSegment::index)
        val shouldRefineCaptions = transcriptSnapshot.isNotEmpty() &&
            BuildConfig.DEEPSEEK_API_KEY.isNotBlank()
        reportSaving(
            if (shouldRefineCaptions) "正在用 AI 校对字幕…" else "正在生成字幕…",
            galleryStartPercent
        )
        thread(start = true, isDaemon = true, name = "KouboGalleryPublish") {
            val refinement = if (shouldRefineCaptions) {
                DeepSeekCaptionRefiner.refine(
                    transcript = transcriptSnapshot,
                    scriptContext = script?.content.orEmpty()
                )
            } else {
                Result.success(transcriptSnapshot)
            }
            refinement.onFailure { error ->
                Log.w(SPEECH_LOG_TAG, "caption_ai_refinement_failed; using offline transcript", error)
            }
            val refinedTranscript = refinement.getOrDefault(transcriptSnapshot)
            val captionProject = CaptionProjectStore.createAndSave(file, refinedTranscript)
            captionProject.onFailure { error ->
                Log.w(SPEECH_LOG_TAG, "caption_project_save_failed file=${file.name}", error)
            }
            reportSaving("正在写入系统相册…", galleryStartPercent)
            val galleryResult = publishVideoToGallery(context, file) { galleryPercent ->
                reportSaving(
                    "正在写入系统相册…",
                    galleryStartPercent +
                        ((99 - galleryStartPercent) * galleryPercent / 100)
                )
            }
            galleryResult.onSuccess { uri ->
                rememberGalleryPublication(context, file, uri)
            }.onFailure { error ->
                Log.e(SPEECH_LOG_TAG, "gallery_publish_failed file=${file.name}", error)
            }
            ContextCompat.getMainExecutor(context).execute {
                savingState = savingState.copy(
                    stage = if (galleryResult.isSuccess) "保存完成" else "应用内成片已保存",
                    percent = 100
                )
                isSaving = false
                recognitionStatus = if (galleryResult.isSuccess) {
                    if (captionProject.getOrNull()?.cards.isNullOrEmpty()) {
                        "已保存到系统相册，未检测到可用语音字幕"
                    } else if (shouldRefineCaptions && refinement.isFailure) {
                        "已保存到系统相册，AI 校对不可用，已使用离线字幕"
                    } else {
                        "已保存到系统相册，${if (shouldRefineCaptions) "AI 字幕" else "字幕"}已生成"
                    }
                } else {
                    "录制已保存，但写入系统相册失败"
                }
                saved(
                    SavedVideo(
                        file = file,
                        durationMs = durationMs,
                        galleryUri = galleryResult.getOrNull(),
                        gallerySaveFailed = galleryResult.isFailure
                    )
                )
            }
        }
    }

    val toggleRecording = recordToggle@{
        if (recording == null && !isSaving) {
            val capture = videoCapture
            if (capture == null) {
                recognitionStatus = cameraMessage ?: "摄像头尚未准备好"
                return@recordToggle
            }
            if (!offlineSpeechEngine.isPrepared) {
                offlineSpeechEngine.prepare()
                recognitionStatus = "离线识音模型仍在加载，请稍后重试"
                return@recordToggle
            }

            val outputDirectory = File(context.filesDir, "recordings").apply { mkdirs() }
            val outputBaseName = videoBaseName(
                script?.title ?: generateContentTitle(script?.content.orEmpty())
            )
            val videoOnlyFile = outputDirectory.resolve("$outputBaseName.video.mp4")
            val pcmFile = outputDirectory.resolve("$outputBaseName.pcm")
            val outputFile = outputDirectory.resolve("$outputBaseName.mp4")

            recognitionBaseProgress = progress
            recognitionSession = -1
            transcriptSegments = emptyMap()
            recognitionAudioSilenced = false
            audioLevelDb = -120f
            keepListening = true
            recognitionStatus = "正在启动离线识音…"
            if (!offlineSpeechEngine.start(pcmFile, noiseReductionEnabled)) {
                keepListening = false
                return@recordToggle
            }

            val pending = capture.output.prepareRecording(
                context,
                FileOutputOptions.Builder(videoOnlyFile).build()
            )
            @android.annotation.SuppressLint("MissingPermission")
            recording = runCatching { pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Start) {
                    Log.i(
                        SPEECH_LOG_TAG,
                        "video_started mode=video_only audioState=${event.recordingStats.audioStats.audioState} " +
                            "offlineSilenced=$recognitionAudioSilenced audioDb=${"%.1f".format(audioLevelDb)}"
                    )
                }
                if (event is VideoRecordEvent.Finalize) {
                    Log.i(
                        SPEECH_LOG_TAG,
                        "video_finalized error=${event.error} audioState=${event.recordingStats.audioStats.audioState}"
                    )
                    recording = null
                    keepListening = false
                    offlineSpeechEngine.stop()
                    if (!event.hasError()) {
                        if (!isSaving) {
                            savingElapsedMs = 0L
                            savingState = SavingState(startedAtMs = System.currentTimeMillis())
                        }
                        isSaving = true
                        recognitionStatus = "正在合成录音与视频…"
                        reportSaving("正在合成音视频…", 5)
                        val muxedFile = if (beautyEnabled) {
                            outputDirectory.resolve("$outputBaseName.muxed.mp4")
                        } else {
                            outputFile
                        }
                        thread(start = true, isDaemon = true, name = "KouboVideoMux") {
                            val muxEndPercent = if (beautyEnabled) 40 else 70
                            val muxResult = VideoAudioMuxer.mux(
                                videoOnlyFile,
                                pcmFile,
                                muxedFile
                            ) { muxPercent ->
                                reportSaving(
                                    "正在合成音视频…",
                                    5 + ((muxEndPercent - 5) * muxPercent / 100)
                                )
                            }
                            ContextCompat.getMainExecutor(context).execute {
                                muxResult.onSuccess { muxDurationMs ->
                                    videoOnlyFile.delete()
                                    pcmFile.delete()
                                    val fallbackDuration =
                                        (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
                                    if (beautyEnabled) {
                                        recognitionStatus = "正在应用自然美颜…"
                                        reportSaving("正在应用自然美颜…", 40)
                                        VideoExportProcessor.applyBeauty(
                                            context,
                                            muxedFile,
                                            outputFile,
                                            progress = { beautyPercent ->
                                                reportSaving(
                                                    "正在应用自然美颜…",
                                                    40 + (45 * beautyPercent / 100)
                                                )
                                            }
                                        ) { beautyResult ->
                                            beautyResult.onSuccess { beautyDurationMs ->
                                                finishSaving(
                                                    outputFile,
                                                    beautyDurationMs.takeIf { it > 0L }
                                                        ?: muxDurationMs.takeIf { it > 0L }
                                                        ?: fallbackDuration
                                                )
                                            }.onFailure {
                                                // Never lose a successful recording just because a device's
                                                // video encoder cannot run the optional beauty pass.
                                                recognitionStatus = "美颜不可用，正在保留原始成片…"
                                                reportSaving("美颜不可用，正在保留原始成片…", 85)
                                                thread(
                                                    start = true,
                                                    isDaemon = true,
                                                    name = "KouboBeautyFallback"
                                                ) {
                                                    val restored = runCatching {
                                                        outputFile.delete()
                                                        muxedFile.renameTo(outputFile) || run {
                                                            muxedFile.copyTo(outputFile, overwrite = true)
                                                            muxedFile.delete()
                                                            true
                                                        }
                                                    }.getOrDefault(false)
                                                    ContextCompat.getMainExecutor(context).execute {
                                                        if (restored) {
                                                            recognitionStatus = "美颜处理不可用，已保存原始画质"
                                                            finishSaving(
                                                                outputFile,
                                                                muxDurationMs.takeIf { it > 0L }
                                                                    ?: fallbackDuration
                                                            )
                                                        } else {
                                                            isSaving = false
                                                            recognitionStatus =
                                                                "视频美颜与回退保存失败，请导出日志排查"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        finishSaving(
                                            outputFile,
                                            muxDurationMs.takeIf { it > 0L } ?: fallbackDuration
                                        )
                                    }
                                }.onFailure {
                                    isSaving = false
                                    recognitionStatus = "音视频合成失败，请导出日志排查"
                                }
                            }
                        }
                    } else {
                        isSaving = false
                        recognitionStatus = "视频保存失败，请重试"
                    }
                }
            } }.onFailure {
                Log.e(SPEECH_LOG_TAG, "video_start_failed", it)
                keepListening = false
                offlineSpeechEngine.stop()
                videoOnlyFile.delete()
                pcmFile.delete()
                recognitionStatus = "视频录制启动失败，请重试"
            }.getOrNull()
            if (recording == null) return@recordToggle
            recordingStartedAt = System.currentTimeMillis()
            elapsed = 0
        } else if (!isSaving) {
            isSaving = true
            savingElapsedMs = 0L
            savingState = SavingState(
                stage = "正在结束录制…",
                percent = 0,
                startedAtMs = System.currentTimeMillis()
            )
            keepListening = false
            offlineSpeechEngine.stop()
            recording?.stop()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        GuidanceGrid(Modifier.fillMaxSize())
        cameraMessage?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 174.dp)
                    .background(Color.Black.copy(alpha = .68f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        script?.let {
            val percent = ReadingProgress.completionPercent(it.content, progress)
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(scriptPanelHeightDp.dp)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (scriptPanelTopDp * density.density).roundToInt()
                        )
                    }
                    .padding(horizontal = 6.dp)
                    .pointerInput(minScriptPanelTopDp, maxScriptPanelTopDp, density.density) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                isDraggingScriptPanel = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                isDraggingScriptPanel = false
                                teleprompterPreferences.edit()
                                    .putFloat(TELEPROMPTER_TOP_KEY, scriptPanelTopDp)
                                    .apply()
                            },
                            onDragCancel = { isDraggingScriptPanel = false }
                        ) { change, dragAmount ->
                            change.consume()
                            scriptPanelTopDp = (scriptPanelTopDp + dragAmount.y / density.density)
                                .coerceIn(minScriptPanelTopDp, maxScriptPanelTopDp)
                        }
                    }
                    .background(Color.Black.copy(alpha = .64f), RoundedCornerShape(18.dp))
                    .border(
                        width = 2.dp,
                        color = if (isDraggingScriptPanel) Orange else Color.Transparent,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { size -> scriptViewportHeight = size.height }
                ) {
                    Text(
                        annotatedScript(it.content, progress),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().verticalScroll(scriptScrollState),
                        fontSize = scriptFontSizeSp.sp,
                        lineHeight = (scriptFontSizeSp + scriptLineSpacingSp).sp,
                        onTextLayout = { result -> scriptTextLayout = result }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .background(Color.Black.copy(alpha = .28f), RoundedCornerShape(10.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = back,
                        enabled = recording == null && !isSaving,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        it.title,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "$percent%",
                        color = if (percent >= 100) Color(0xFF8DE1A8) else Orange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    IconButton(
                        onClick = { showTeleprompterSettings = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.TextFields,
                            "调整字号和行间距",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { edit(it) },
                        enabled = recording == null && !isSaving,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            "编辑台本",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        if (showTeleprompterSettings) {
            TeleprompterSettingsDialog(
                fontSizeSp = scriptFontSizeSp,
                lineSpacingSp = scriptLineSpacingSp,
                onFontSizeChange = { scriptFontSizeSp = it },
                onFontSizeChangeFinished = {
                    teleprompterPreferences.edit()
                        .putFloat(TELEPROMPTER_FONT_SIZE_KEY, scriptFontSizeSp)
                        .apply()
                },
                onLineSpacingChange = { scriptLineSpacingSp = it },
                onLineSpacingChangeFinished = {
                    teleprompterPreferences.edit()
                        .putFloat(TELEPROMPTER_LINE_SPACING_KEY, scriptLineSpacingSp)
                        .apply()
                },
                onReset = {
                    scriptFontSizeSp = DEFAULT_TELEPROMPTER_FONT_SIZE
                    scriptLineSpacingSp = DEFAULT_TELEPROMPTER_LINE_SPACING
                    teleprompterPreferences.edit()
                        .putFloat(TELEPROMPTER_FONT_SIZE_KEY, DEFAULT_TELEPROMPTER_FONT_SIZE)
                        .putFloat(TELEPROMPTER_LINE_SPACING_KEY, DEFAULT_TELEPROMPTER_LINE_SPACING)
                        .apply()
                },
                onDismiss = { showTeleprompterSettings = false }
            )
        }
        if (isSaving) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 42.dp),
                color = Ink.copy(alpha = .94f),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 8.dp
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正在保存视频",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "${savingState.percent}%",
                            color = Orange,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    LinearProgressIndicator(
                        progress = { savingState.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Orange,
                        trackColor = Color.White.copy(alpha = .16f)
                    )
                    Text(savingState.stage, color = Color.White.copy(alpha = .86f))
                    Text(
                        "录制已结束 · 已处理 ${formatDuration(savingElapsedMs)} · 请保持应用开启",
                        color = Color.White.copy(alpha = .56f),
                        fontSize = 12.sp
                    )
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .68f))
                .padding(top = 10.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraToolButton(
                    icon = Icons.Default.FaceRetouchingNatural,
                    label = "美颜",
                    selected = beautyEnabled,
                    enabled = recording == null && !isSaving,
                    onClick = { beautyEnabled = !beautyEnabled }
                )
                CameraToolButton(
                    icon = Icons.Default.GraphicEq,
                    label = if (noiseReductionAvailable) "降噪" else "降噪不可用",
                    selected = noiseReductionEnabled,
                    enabled = recording == null && !isSaving,
                    onClick = {
                        if (noiseReductionAvailable) {
                            noiseReductionEnabled = !noiseReductionEnabled
                        } else {
                            recognitionStatus = "当前设备没有可用的系统级麦克风降噪"
                        }
                    }
                )
                Text(
                    if (isSaving) "正在保存…" else formatDuration(elapsed),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = .42f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
                CameraToolButton(
                    icon = Icons.Default.Cameraswitch,
                    label = "镜头翻转",
                    enabled = recording == null && !isSaving,
                    onClick = {
                        requestedLensFacing = if (requestedLensFacing == CameraSelector.LENS_FACING_FRONT) {
                            CameraSelector.LENS_FACING_BACK
                        } else {
                            CameraSelector.LENS_FACING_FRONT
                        }
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            RecordButton(
                recording = recording != null,
                enabled = (recording != null || videoCapture != null) && !isSaving,
                onClick = toggleRecording
            )
            Text(
                when {
                    isSaving -> "正在保存"
                    recording != null -> "停止录制"
                    else -> "开始录制"
                },
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TeleprompterSettingsDialog(
    fontSizeSp: Float,
    lineSpacingSp: Float,
    onFontSizeChange: (Float) -> Unit,
    onFontSizeChangeFinished: () -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onLineSpacingChangeFinished: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提词排版") },
        text = {
            Column {
                Text("字号  ${fontSizeSp.roundToInt()}", fontWeight = FontWeight.Bold)
                Slider(
                    value = fontSizeSp,
                    onValueChange = onFontSizeChange,
                    onValueChangeFinished = onFontSizeChangeFinished,
                    valueRange = MIN_TELEPROMPTER_FONT_SIZE..MAX_TELEPROMPTER_FONT_SIZE,
                    steps = (MAX_TELEPROMPTER_FONT_SIZE - MIN_TELEPROMPTER_FONT_SIZE).roundToInt() - 1
                )
                Text("行间距  ${lineSpacingSp.roundToInt()}", fontWeight = FontWeight.Bold)
                Slider(
                    value = lineSpacingSp,
                    onValueChange = onLineSpacingChange,
                    onValueChangeFinished = onLineSpacingChangeFinished,
                    valueRange = MIN_TELEPROMPTER_LINE_SPACING..MAX_TELEPROMPTER_LINE_SPACING,
                    steps = (MAX_TELEPROMPTER_LINE_SPACING - MIN_TELEPROMPTER_LINE_SPACING).roundToInt() - 1
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "这是一段提词排版预览\n长按台词卡可以上下移动",
                    color = Color.White,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + lineSpacingSp).sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = .76f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("恢复默认") }
        }
    )
}

@Composable
private fun CameraToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> Color.White.copy(alpha = .38f)
        selected -> Orange
        else -> Color.White
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = .34f), CircleShape)
        ) {
            Icon(icon, label, tint = contentColor, modifier = Modifier.size(25.dp))
        }
        Text(if (selected) "$label · 开" else label, color = contentColor, fontSize = 12.sp)
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(78.dp).border(4.dp, if (enabled) Color.White else Color.Gray, CircleShape)
    ) {
        Box(
            Modifier
                .size(if (recording) 28.dp else 58.dp)
                .background(
                    color = if (recording) Orange else if (enabled) Color.White else Color.Gray,
                    shape = if (recording) RoundedCornerShape(7.dp) else CircleShape
                )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoDetailScreen(
    video: SavedVideo,
    backHome: () -> Unit,
    recordAgain: () -> Unit,
    editCaptions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(video.file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)))
            prepare()
        }
    }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var playbackPositionMs by remember(player) { mutableLongStateOf(0L) }
    var playbackDurationMs by remember(player) {
        mutableLongStateOf(video.durationMs.coerceAtLeast(1L))
    }
    var galleryUri by remember(video.file.absolutePath) { mutableStateOf(video.galleryUri) }
    var gallerySaveFailed by remember(video.file.absolutePath) {
        mutableStateOf(video.gallerySaveFailed)
    }
    var isPublishingToGallery by remember(video.file.absolutePath) { mutableStateOf(false) }
    val publishToGallery = {
        if (!isPublishingToGallery) {
            scope.launch {
                isPublishingToGallery = true
                val result = withContext(Dispatchers.IO) {
                    publishVideoToGallery(context, video.file)
                }
                result.onSuccess { uri ->
                    rememberGalleryPublication(context, video.file, uri)
                    galleryUri = uri
                    gallerySaveFailed = false
                }.onFailure { error ->
                    Log.e(SPEECH_LOG_TAG, "gallery_retry_failed file=${video.file.name}", error)
                    gallerySaveFailed = true
                }
                isPublishingToGallery = false
            }
        }
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) publishToGallery() else gallerySaveFailed = true
    }

    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
            player.duration.takeIf { it > 0L }?.let { playbackDurationMs = it }
            delay(250.milliseconds)
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(backHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回台本") } },
                title = { Text("视频详情") }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().background(Color(0xFFF7F7F5)).padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (galleryUri != null) SuccessGreen.copy(alpha = .10f) else Orange.copy(alpha = .10f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (galleryUri != null) Icons.Default.CheckCircle else Icons.Default.FolderOpen,
                    if (galleryUri != null) "相册保存成功" else "应用内视频",
                    tint = if (galleryUri != null) SuccessGreen else Orange,
                    modifier = Modifier.size(22.dp)
                )
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        when {
                            galleryUri != null -> "已保存到系统相册"
                            gallerySaveFailed -> "录制已保存在应用内，相册保存失败"
                            else -> "已保存在应用内"
                        },
                        color = if (galleryUri != null) SuccessGreen else Orange,
                        fontWeight = FontWeight.Bold
                    )
                    if (galleryUri == null) {
                        Text("可重试写入手机照片管理", color = Color.Gray, fontSize = 13.sp)
                    }
                }
                if (galleryUri == null) {
                    TextButton(
                        enabled = !isPublishingToGallery,
                        onClick = {
                            if (
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                !Manifest.permission.WRITE_EXTERNAL_STORAGE.isGranted(context)
                            ) {
                                galleryPermissionLauncher.launch(
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            } else {
                                publishToGallery()
                            }
                        }
                    ) {
                        Text(if (isPublishingToGallery) "保存中…" else "保存到相册")
                    }
                }
            }
            AndroidView(
                factory = { playerContext ->
                    PlayerView(playerContext).apply {
                        this.player = player
                        useController = false
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Ink, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Slider(
                    value = playbackPositionMs.coerceAtMost(playbackDurationMs).toFloat(),
                    onValueChange = { value ->
                        playbackPositionMs = value.toLong()
                        player.seekTo(playbackPositionMs)
                    },
                    valueRange = 0f..playbackDurationMs.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(playbackPositionMs), color = Color.LightGray, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                            }
                        ) {
                            Icon(Icons.Default.Replay10, "后退 10 秒", tint = Color.White)
                        }
                        IconButton(
                            onClick = {
                                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                                if (player.isPlaying) player.pause() else player.play()
                                isPlaying = player.isPlaying
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .background(Orange, CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                player.seekTo(
                                    (player.currentPosition + 10_000L).coerceAtMost(playbackDurationMs)
                                )
                            }
                        ) {
                            Icon(Icons.Default.Forward10, "前进 10 秒", tint = Color.White)
                        }
                    }
                    Text(formatDuration(playbackDurationMs), color = Color.LightGray, fontSize = 13.sp)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        videoDisplayTitle(video.file.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${formatDuration(video.durationMs)} · ${formatFileSize(video.file.length())}",
                        color = Color.Gray
                    )
                    Text(
                        "录制于 ${videoRecordedAt(video.file.name, video.file.lastModified())}",
                        color = Color.Gray
                    )
                }
            }
            Button(
                editCaptions,
                Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Orange)
            ) {
                Text(
                    if (CaptionProjectStore.load(video.file).getOrNull()?.cards?.isNotEmpty() == true) {
                        "编辑 AI 字幕"
                    } else {
                        "生成 AI 字幕"
                    }
                )
            }
            Button(
                recordAgain,
                Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Ink)
            ) { Text("再录一条") }
            Text("回到台本列表", color = Ink, modifier = Modifier.padding(16.dp).clickable { backHome() })
        }
    }
}

@Composable
private fun GuidanceGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val lineColor = Color.White.copy(alpha = .34f)
        val stroke = 1.dp.toPx()
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), strokeWidth = stroke)
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height), strokeWidth = stroke)
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, size.height / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), strokeWidth = stroke)
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f), strokeWidth = stroke)
    }
}

@Suppress("DEPRECATION")
internal fun publishVideoToGallery(
    context: Context,
    sourceFile: File,
    onProgress: (Int) -> Unit = {}
): Result<Uri> = runCatching {
    require(sourceFile.isFile && sourceFile.length() > 0L) { "Video file is empty" }
    if (
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        !Manifest.permission.WRITE_EXTERNAL_STORAGE.isGranted(context)
    ) {
        error("Storage permission is required on Android 9 and earlier")
    }

    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val now = System.currentTimeMillis()
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.DATE_ADDED, now / 1_000L)
        put(MediaStore.Video.Media.DATE_TAKEN, now)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/口播提词器")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        } else {
            val publicDirectory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "口播提词器"
            ).apply { mkdirs() }
            put(MediaStore.Video.Media.DATA, publicDirectory.resolve(sourceFile.name).absolutePath)
        }
    }

    val uri = checkNotNull(resolver.insert(collection, values)) {
        "Unable to create a MediaStore video"
    }
    try {
        checkNotNull(resolver.openOutputStream(uri, "w")) {
            "Unable to open the MediaStore video"
        }.use { output ->
            sourceFile.inputStream().buffered(GALLERY_COPY_BUFFER_BYTES).use { input ->
                val buffer = ByteArray(GALLERY_COPY_BUFFER_BYTES)
                val totalBytes = sourceFile.length().coerceAtLeast(1L)
                var copiedBytes = 0L
                var lastPercent = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copiedBytes += count
                    val percent = (copiedBytes * 100L / totalBytes).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
                output.flush()
                onProgress(100)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
        }
        uri
    } catch (error: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}

internal fun rememberGalleryPublication(context: Context, file: File, uri: Uri) {
    context.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(file.name, uri.toString())
        .apply()
}

private fun publishedGalleryUri(context: Context, file: File): Uri? =
    context.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE)
        .getString(file.name, null)
        ?.let(Uri::parse)

private fun formatDuration(ms: Long): String = String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

private fun formatFileSize(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)

private fun readVideoDuration(file: File): Long = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}.getOrDefault(0L)

private fun extractVideoThumbnail(file: File): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ThumbnailUtils.createVideoThumbnail(file, AndroidSize(480, 270), null)
    } else {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            val maxDimension = maxOf(frame.width, frame.height)
            if (maxDimension <= 480) {
                frame
            } else {
                val scale = 480f / maxDimension
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { frame.recycle() }
            }
        } finally {
            retriever.release()
        }
    }
}.getOrNull()

private data class CameraSetupResult(
    val videoCapture: VideoCapture<Recorder>?,
    val message: String?
)

private fun configureCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    preferredLensFacing: Int
): CameraSetupResult {
    val preferred = preferredLensFacing to cameraSelector(preferredLensFacing)
    val fallbackLensFacing = if (preferredLensFacing == CameraSelector.LENS_FACING_FRONT) {
        CameraSelector.LENS_FACING_BACK
    } else {
        CameraSelector.LENS_FACING_FRONT
    }
    val selectors = listOf(preferred, fallbackLensFacing to cameraSelector(fallbackLensFacing))
        .filter { (_, selector) -> runCatching { provider.hasCamera(selector) }.getOrDefault(false) }
    if (selectors.isEmpty()) {
        return CameraSetupResult(null, "当前设备没有可用摄像头，请在模拟器设置中启用 Camera")
    }

    val sources = listOf(
        Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE,
        Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES
    )
    val preferredQualities = listOf(Quality.FHD, Quality.HD, Quality.SD)

    for ((lensFacing, selector) in selectors) {
        val camera = try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, buildPreview(previewView))
        } catch (_: IllegalArgumentException) {
            continue
        }
        for (source in sources) {
            val capabilities = Recorder.getVideoCapabilities(camera.cameraInfo, source)
            val quality = preferredQualities.firstOrNull {
                capabilities.isQualitySupported(it, DynamicRange.SDR)
            } ?: capabilities.getSupportedQualities(DynamicRange.SDR).firstOrNull()
            if (quality == null) continue

            val recorder = Recorder.Builder()
                .setVideoCapabilitiesSource(source)
                .setQualitySelector(QualitySelector.from(quality))
                // CameraX otherwise inherits device profiles that commonly exceed 12 Mbps at
                // FHD. Six Mbps keeps talking-head detail while roughly halving long recordings.
                .setTargetVideoEncodingBitRate(TARGET_VIDEO_BIT_RATE)
                .build()
            val capture = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, buildPreview(previewView), capture)
                val fallbackMessage = if (lensFacing != preferredLensFacing) {
                    "${lensName(preferredLensFacing)}摄像头不可用，已切换到${lensName(lensFacing)}摄像头"
                } else {
                    null
                }
                return CameraSetupResult(capture, fallbackMessage)
            } catch (_: IllegalArgumentException) {
                provider.unbindAll()
            }
        }
    }

    val (_, previewSelector) = selectors.first()
    provider.unbindAll()
    provider.bindToLifecycle(lifecycleOwner, previewSelector, buildPreview(previewView))
    return CameraSetupResult(
        null,
        "摄像头预览可用，但当前模拟器不提供兼容的视频录制档位"
    )
}

private fun cameraSelector(lensFacing: Int): CameraSelector = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
    CameraSelector.DEFAULT_FRONT_CAMERA
} else {
    CameraSelector.DEFAULT_BACK_CAMERA
}

private fun lensName(lensFacing: Int): String = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前置" else "后置"

private fun buildPreview(previewView: PreviewView): Preview = Preview.Builder().build().also {
    it.surfaceProvider = previewView.surfaceProvider
}

private fun String.isGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, this) == PackageManager.PERMISSION_GRANTED

@Composable
private fun annotatedScript(content: String, cursor: Int): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    content.forEachIndexed { index, char ->
        withStyle(androidx.compose.ui.text.SpanStyle(color = if (index < cursor) Color.White.copy(alpha = .34f) else if (index == cursor) Orange else Color.White)) { append(char) }
    }
}
