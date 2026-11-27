package com.ecaudio.ecstudiomobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Canvas = Color(0xFF101217)
private val Panel = Color(0xFF191D25)
private val Panel2 = Color(0xFF222834)
private val Accent = Color(0xFF8B72FF)
private val Mint = Color(0xFF4DDBB2)
private val Muted = Color(0xFF8992A3)

data class StudioTrack(val id: Int, val name: String, val file: String? = null, val color: Color)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ECStudioApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ECStudioApp() {
    val context = LocalContext.current
    val engine = remember { AudioEngine() }
    var tracks by remember { mutableStateOf(listOf(
        StudioTrack(1, "Audio 1", null, Color(0xFF8B72FF)),
        StudioTrack(2, "Audio 2", null, Color(0xFF42BFA2)),
        StudioTrack(3, "Instrument", null, Color(0xFFE5A34B))
    )) }
    var selected by remember { mutableIntStateOf(1) }
    var isRecording by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var bpm by remember { mutableFloatStateOf(120f) }
    var status by remember { mutableStateOf("Listo para crear") }
    var nextId by remember { mutableIntStateOf(4) }
    var showMixer by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && permissionPending) {
            val track = tracks.firstOrNull { it.id == selected }
            val file = File(context.filesDir, "take_${System.currentTimeMillis()}.wav")
            if (track != null && engine.startRecording(file)) {
                tracks = tracks.map { if (it.id == selected) it.copy(file = file.absolutePath) else it }
                isRecording = true
                status = "Grabando · ${track.name}"
            } else status = "No se pudo iniciar el micrófono"
        } else status = "Permiso de micrófono necesario"
        permissionPending = false
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, secondary = Mint, background = Canvas,
            surface = Panel, onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Scaffold(containerColor = Canvas, topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EC STUDIO", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 18.sp)
                        Text("MOBILE  •  AUDIO WORKSTATION", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { status = "Proyecto local · EC Studio" }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Proyecto", tint = Color.White)
                    }
                    IconButton(onClick = { showMixer = !showMixer }) {
                        Icon(Icons.Default.Tune, contentDescription = "Mezclador", tint = if (showMixer) Mint else Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("MI PROYECTO", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                        Text("Nueva sesión", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = Panel, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(bpm.toInt().toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("BPM", color = Muted, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("TEMPO", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                            Text("${bpm.toInt()} BPM", color = Mint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 50f..200f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TransportButton(Icons.Default.Stop, "Detener", Panel2) {
                                engine.stopRecording(); engine.stopPlayback()
                                isRecording = false; isPlaying = false; status = "Detenido"
                            }
                            TransportButton(Icons.Default.PlayArrow, "Reproducir", Accent) {
                                val audio = tracks.firstNotNullOfOrNull { it.file?.let(::File)?.takeIf(File::exists) }
                                if (audio != null) {
                                    engine.play(audio) { isPlaying = false; status = "Reproducción terminada" }
                                    isPlaying = true; status = "Reproduciendo audio"
                                } else status = "Graba una pista para escucharla"
                            }
                            TransportButton(Icons.Default.Mic, "Grabar", if (isRecording) Color(0xFFDA5264) else Panel2) {
                                if (isRecording) {
                                    engine.stopRecording(); isRecording = false; status = "Toma guardada"
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        val track = tracks.firstOrNull { it.id == selected }
                                        val file = File(context.filesDir, "take_${System.currentTimeMillis()}.wav")
                                        if (track != null && engine.startRecording(file)) {
                                            tracks = tracks.map { if (it.id == selected) it.copy(file = file.absolutePath) else it }
                                            isRecording = true; status = "Grabando · ${track.name}"
                                        } else status = "No se pudo iniciar el micrófono"
                                    } else {
                                        permissionPending = true
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(if (isRecording) Color(0xFFFF596D) else Mint, RoundedCornerShape(50)))
                            Spacer(Modifier.width(7.dp))
                            Text(status, color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text("44.1 kHz · 16 bit", color = Muted, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PISTAS", fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        tracks = tracks + StudioTrack(nextId, "Audio $nextId", null, listOf(Accent, Mint, Color(0xFFE5A34B), Color(0xFF5DA8E8))[nextId % 4])
                        selected = nextId; nextId++
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Añadir pista")
                    }
                }
                if (showMixer) {
                    MixerPanel(tracks.size)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                        repeat(12) { index ->
                            Text(String.format(Locale.US, "%02d", index + 1), color = Muted, fontSize = 9.sp, modifier = Modifier.width(94.dp))
                        }
                    }
                    tracks.forEach { track ->
                        TrackLane(track, selected == track.id, onSelect = { selected = track.id }, onDelete = {
                            if (tracks.size > 1) tracks = tracks.filterNot { it.id == track.id }
                        })
                        Spacer(Modifier.height(7.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("EC STUDIO ENGINE", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                            Text("Audio workspace", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("v1.0  •  ANDROID", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun TransportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp), shape = RoundedCornerShape(9.dp), modifier = Modifier.weight(1f)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TrackLane(track: StudioTrack, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onSelect,
        color = if (selected) Color(0xFF252B3A) else Panel,
        shape = RoundedCornerShape(11.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = .75f)) else null
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(30.dp).background(track.color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(if (track.file != null) "Audio grabado · WAV" else "Sin región de audio", color = Muted, fontSize = 9.sp)
                }
                Text(if (selected) "SELECCIONADA" else "AUDIO", color = if (selected) Mint else Muted, fontSize = 8.sp)
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("×", fontSize = 18.sp, color = Muted) }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(38.dp).background(Color(0xFF11151C), RoundedCornerShape(6.dp)).padding(5.dp)) {
                if (track.file != null) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(56) { i ->
                            val h = listOf(.25f, .55f, .8f, .4f, .95f, .62f, .3f, .72f, .45f, .88f)[(i * 7 + track.id) % 10]
                            Box(Modifier.weight(1f).fillMaxHeight(h).background(track.color.copy(alpha = .85f), RoundedCornerShape(2.dp)))
                        }
                    }
                } else {
                    Text("＋  Graba una toma para crear una región", color = Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun MixerPanel(trackCount: Int) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("MEZCLADOR", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(trackCount + 1) { i ->
                    Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (i == trackCount) "MASTER" else "TRK ${i + 1}", color = Muted, fontSize = 9.sp)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(16.dp).height(92.dp).background(Color(0xFF10141B), RoundedCornerShape(5.dp)), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight(if (i == trackCount) .8f else .55f).background(if (i == trackCount) Mint else Accent, RoundedCornerShape(5.dp)))
                        }
                        Text("0.0 dB", fontSize = 9.sp, color = Color.White)
                    }
                }
            }
            Text("Vista inicial del mezclador · controles de canal en desarrollo", color = Muted, fontSize = 9.sp)
        }
    }
}
