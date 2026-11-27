package com.ecaudio.ecmusicplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale

private val Bg = Color(0xFF0B0B12)
private val CardBg = Color(0xFF171722)
private val CardHi = Color(0xFF25243A)
private val Purple = Color(0xFF9B5CFF)
private val Cyan = Color(0xFF42C8F5)
private val Soft = Color(0xFF9292A8)

data class Song(val id: Long, val title: String, val artist: String, val album: String, val uri: android.net.Uri, val duration: Long)
enum class RepeatMode { OFF, ONCE, ALL }
enum class Tab { HOME, LIBRARY, PLAYLISTS, EQ, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicPlayerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicPlayerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var current by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var repeat by remember { mutableStateOf(RepeatMode.OFF) }
    var shuffle by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tu música, siempre contigo") }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var eqEnabled by remember { mutableStateOf(false) }
    var bandValues by remember { mutableStateOf(emptyList<Float>()) }
    var preset by remember { mutableStateOf("Personalizado") }
    var playlists by remember { mutableStateOf(mapOf("Favoritas" to emptyList<Long>())) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var repeatOnceUsed by remember { mutableStateOf(false) }

    fun stopAudio() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        playing = false
    }

    fun startSong(index: Int) {
        if (songs.isEmpty()) return
        val safe = ((index % songs.size) + songs.size) % songs.size
        stopAudio()
        current = safe
        repeatOnceUsed = false
        position = 0L
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(context, songs[safe].uri)
            mp.setOnPreparedListener { prepared ->
                duration = prepared.duration.toLong()
                prepared.start()
                playing = true
                status = "Reproduciendo ahora"
                try {
                    val eq = Equalizer(0, prepared.audioSessionId)
                    equalizer = eq
                    eq.enabled = eqEnabled
                    bandValues = (0 until eq.numberOfBands.toInt()).map { b -> eq.getBandLevel(b.toShort()).toFloat() / 100f }
                } catch (_: Exception) { status = "Reproduciendo · EQ no disponible" }
            }
            mp.setOnCompletionListener {
                when (repeat) {
                    RepeatMode.ALL -> startSong(if (shuffle && songs.size > 1) songs.indices.filter { it != current }.random() else current + 1)
                    RepeatMode.ONCE -> if (!repeatOnceUsed) {
                        repeatOnceUsed = true
                        it.seekTo(0)
                        it.start()
                        playing = true
                    } else startSong(if (shuffle && songs.size > 1) songs.indices.filter { it != current }.random() else current + 1)
                    RepeatMode.OFF -> if (current + 1 < songs.size) startSong(current + 1) else { playing = false; status = "Fin de la lista" }
                }
            }
            mp.setOnErrorListener { _, _, _ -> playing = false; status = "No se pudo reproducir este archivo"; true }
            player = mp
            mp.prepareAsync()
            status = "Cargando canción…"
        } catch (_: Exception) { playing = false; status = "No se pudo abrir este archivo" }
    }

    fun scan() {
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        val found = mutableListOf<Song>()
        try {
            context.contentResolver.query(collection, projection, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Sin título"
                    val artist = cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Artista desconocido"
                    val album = cursor.getString(albumCol) ?: "Álbum desconocido"
                    found.add(Song(id, title, artist, album, ContentUris.withAppendedId(collection, id), cursor.getLong(durationCol)))
                }
            }
            songs = found
            status = if (found.isEmpty()) "No encontramos canciones en el teléfono" else "Se encontraron " + found.size + " canciones"
        } catch (_: Exception) { status = "No se pudo escanear la biblioteca" }
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else status = "Necesitamos permiso para leer tu música"
    }
    fun requestScan() {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) scan()
        else permissionLauncher.launch(permission)
    }

    LaunchedEffect(playing, current) {
        while (playing) {
            position = try { player?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
            delay(500)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Cyan, background = Bg, surface = CardBg)) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                TopAppBar(
                    title = { Column { Text("EC MUSIC", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 19.sp); Text("PLAYER", color = Purple, fontSize = 9.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold) } },
                    actions = { IconButton(onClick = { requestScan() }) { Icon(Icons.Default.Refresh, "Escanear música", tint = Cyan) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF12121C)) {
                    val nav = listOf(Triple(Tab.HOME, Icons.Default.Home, "Inicio"), Triple(Tab.LIBRARY, Icons.Default.LibraryMusic, "Música"), Triple(Tab.PLAYLISTS, Icons.Default.QueueMusic, "Listas"), Triple(Tab.EQ, Icons.Default.GraphicEq, "EQ"), Triple(Tab.SETTINGS, Icons.Default.Settings, "Ajustes"))
                    nav.forEach { item ->
                        NavigationBarItem(selected = tab == item.first, onClick = { tab = item.first }, icon = { Icon(item.second, null) }, label = { Text(item.third, fontSize = 9.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Purple, indicatorColor = Purple.copy(alpha = .16f), unselectedIconColor = Soft))
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(songs, current, playing, status, duration, position, repeat, shuffle, { requestScan() }, { startSong(it) }, {
                        if (current >= 0) { if (playing) { player?.pause(); playing = false } else { player?.start(); playing = true } }
                        else if (songs.isNotEmpty()) startSong(0)
                    }, { if (songs.isNotEmpty()) startSong(if (shuffle && songs.size > 1) songs.indices.filter { it != current }.random() else current + 1) }, { if (songs.isNotEmpty()) startSong(current - 1) }, { repeat = RepeatMode.values()[(repeat.ordinal + 1) % RepeatMode.values().size] }, { shuffle = !shuffle }, { player?.seekTo(it.toInt()); position = it })
                    Tab.LIBRARY -> LibraryScreen(songs, current, { requestScan() }, { startSong(it) }, { song -> playlists = playlists + ("Favoritas" to ((playlists["Favoritas"] ?: emptyList()) + song.id).distinct()) })
                    Tab.PLAYLISTS -> PlaylistScreen(playlists, songs, current, showCreate, playlistName, selectedPlaylist, { showCreate = true }, { playlistName = it }, { val n = playlistName.trim(); if (n.isNotEmpty()) playlists = playlists + (n to emptyList()); playlistName = ""; showCreate = false }, { showCreate = false }, { selectedPlaylist = it }, { n -> if (current >= 0) playlists = playlists + (n to ((playlists[n] ?: emptyList()) + songs[current].id).distinct()) }, { id -> songs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { startSong(it) } })
                    Tab.EQ -> EqualizerScreen(equalizer, eqEnabled, bandValues, preset, { enabled -> eqEnabled = enabled; try { equalizer?.enabled = enabled } catch (_: Exception) {} }, { band, value ->
                        bandValues = bandValues.toMutableList().also { if (band in it.indices) it[band] = value }
                        try { val range = equalizer?.bandLevelRange; if (range != null) equalizer?.setBandLevel(band.toShort(), (value * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()) } catch (_: Exception) {}
                    }, { chosen ->
                        preset = chosen
                        try {
                            val eq = equalizer
                            if (eq != null) {
                                for (b in 0 until eq.numberOfBands.toInt()) {
                                    val level = when (chosen) {
                                        "Bass Boost" -> if (b < 2) 650 else 0
                                        "Vocal" -> if (b in 2..3) 450 else 0
                                        "Treble" -> if (b >= eq.numberOfBands.toInt() - 2) 600 else 0
                                        "Rock" -> if (b == 0 || b == eq.numberOfBands.toInt() - 1) 450 else 100
                                        else -> 0
                                    }
                                    eq.setBandLevel(b.toShort(), level.coerceIn(eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt()).toShort())
                                }
                                bandValues = (0 until eq.numberOfBands.toInt()).map { b -> eq.getBandLevel(b.toShort()) / 100f }
                            }
                        } catch (_: Exception) {}
                    })
                    Tab.SETTINGS -> SettingsScreen(status, { requestScan() })
                }
                if (current in songs.indices) MiniPlayer(songs[current], playing, { if (playing) { player?.pause(); playing = false } else { player?.start(); playing = true } }, { if (songs.isNotEmpty()) startSong(current + 1) }, { tab = Tab.HOME })
            }
        }
    }
}

@Composable
private fun HomeScreen(songs: List<Song>, current: Int, playing: Boolean, status: String, duration: Long, position: Long, repeat: RepeatMode, shuffle: Boolean, onScan: () -> Unit, onSong: (Int) -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onRepeat: () -> Unit, onShuffle: () -> Unit, onSeek: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Text("TU BIBLIOTECA", color = Soft, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Tu música, a otro nivel.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(status, color = Soft, fontSize = 12.sp)
            }
        }
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF32146B), Color(0xFF171D43), Color(0xFF0E394A)))).padding(18.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(listOf(Purple, Cyan))), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column { Text("EC MUSIC PLAYER", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp); Text("REPRODUCTOR LOCAL", color = Color.White.copy(alpha = .7f), fontSize = 9.sp, letterSpacing = 2.sp) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Tu sonido.\nTu estilo.", color = Color.White, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                    Text("Descubre y disfruta toda tu música.", color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = Purple), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(7.dp)); Text("Escanear mi música") }
                }
            }
        }
        item {
            Text("REPRODUCIENDO", color = Soft, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            if (current in songs.indices) {
                val song = songs[current]
                Surface(color = CardBg, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(Modifier.size(76.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(song.artist, color = Soft, fontSize = 12.sp, maxLines = 1)
                                Text(song.album, color = Purple, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                        Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(position), color = Soft, fontSize = 10.sp); Text(time(duration), color = Soft, fontSize = 10.sp) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Cyan else Soft) }
                            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                            FilledIconButton(onClick = onPlay, modifier = Modifier.size(58.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Purple)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp)) }
                            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                            IconButton(onClick = onRepeat) { Icon(if (repeat == RepeatMode.ONCE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if (repeat == RepeatMode.OFF) Soft else Cyan) }
                        }
                        Text(when (repeat) { RepeatMode.OFF -> "Repetición desactivada"; RepeatMode.ONCE -> "Repetir canción una vez"; RepeatMode.ALL -> "Repetir continuamente" }, color = Soft, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            } else EmptyState("Selecciona una canción para comenzar")
        }
        item { Text("TU MÚSICA", color = Soft, fontSize = 10.sp, letterSpacing = 2.sp) }
        items(songs.take(6)) { song -> SongRow(song, false) { onSong(songs.indexOf(song)) } }
    }
}

@Composable
private fun LibraryScreen(songs: List<Song>, current: Int, onScan: () -> Unit, onSong: (Int) -> Unit, onFavorite: (Song) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Biblioteca", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold); Text(songs.size.toString() + " canciones en tu teléfono", color = Soft, fontSize = 11.sp) }
            IconButton(onClick = onScan) { Icon(Icons.Default.Refresh, null, tint = Cyan) }
        }
        if (songs.isEmpty()) EmptyState("Escanea tu teléfono para encontrar música")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(songs, key = { it.id }) { song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SongRow(song, songs.indexOf(song) == current) { onSong(songs.indexOf(song)) } }
                    IconButton(onClick = { onFavorite(song) }) { Icon(Icons.Default.FavoriteBorder, null, tint = Purple) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistScreen(playlists: Map<String, List<Long>>, songs: List<Song>, current: Int, showCreate: Boolean, name: String, selected: String?, onCreate: () -> Unit, onName: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit, onSelect: (String?) -> Unit, onAddCurrent: (String) -> Unit, onSong: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Listas de reproducción", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = onCreate) { Icon(Icons.Default.Add, null, tint = Cyan) } }
        if (showCreate) {
            OutlinedTextField(value = name, onValueChange = onName, label = { Text("Nombre de la lista") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row { TextButton(onClick = onCancel) { Text("Cancelar") }; Button(onClick = onConfirm) { Text("Crear") } }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(playlists.keys.toList()) { title ->
                Surface(color = if (selected == title) CardHi else CardBg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { onSelect(if (selected == title) null else title) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Purple, Color(0xFF392A73)))), contentAlignment = Alignment.Center) { Icon(if (title == "Favoritas") Icons.Default.Favorite else Icons.Default.QueueMusic, null, tint = Color.White) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text((playlists[title]?.size ?: 0).toString() + " canciones", color = Soft, fontSize = 11.sp) }
                        Icon(Icons.Default.ChevronRight, null, tint = Soft)
                    }
                }
                if (selected == title) {
                    TextButton(onClick = { onAddCurrent(title) }, enabled = current >= 0) { Icon(Icons.Default.Add, null); Text("Añadir canción actual") }
                    (playlists[title] ?: emptyList()).forEach { id -> songs.firstOrNull { it.id == id }?.let { SongRow(it, false) { onSong(id) } } }
                }
            }
        }
    }
}

@Composable
private fun EqualizerScreen(eq: Equalizer?, enabled: Boolean, values: List<Float>, preset: String, onEnabled: (Boolean) -> Unit, onBand: (Int, Float) -> Unit, onPreset: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Ecualizador", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Ajusta tu sonido", color = Soft, fontSize = 12.sp) }; Switch(checked = enabled, onCheckedChange = onEnabled) }
        Spacer(Modifier.height(14.dp))
        if (eq == null) EmptyState("Inicia una canción para activar el ecualizador del dispositivo")
        else {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(preset); Icon(Icons.Default.ArrowDropDown, null) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("Personalizado", "Bass Boost", "Vocal", "Treble", "Rock").forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { onPreset(item); expanded = false }) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = CardBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0 until eq.numberOfBands.toInt()).forEach { band ->
                        val range = eq.bandLevelRange
                        val low = range[0] / 100f
                        val high = range[1] / 100f
                        val value = values.getOrNull(band) ?: 0f
                        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text((eq.getCenterFreq(band.toShort()) / 1000).toString() + "k", color = Soft, fontSize = 9.sp)
                            Slider(value = value.coerceIn(low, high), onValueChange = { onBand(band, it) }, valueRange = low..high, modifier = Modifier.height(220.dp).width(48.dp), colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple))
                            Text(value.toInt().toString() + " dB", color = Cyan, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("La respuesta depende del hardware y del efecto de audio disponible en tu teléfono.", color = Soft, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsScreen(status: String, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Configuración", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Personaliza tu experiencia", color = Soft, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        SettingsRow(Icons.Default.FolderOpen, "Escanear música del teléfono", "Buscar archivos de audio locales", onScan)
        SettingsRow(Icons.Default.GraphicEq, "Calidad de audio", "Reproduce el archivo original y usa el ecualizador del sistema", {})
        SettingsRow(Icons.Default.Repeat, "Repetición", "Una vez o continuamente desde el reproductor", {})
        SettingsRow(Icons.Default.Security, "Privacidad", "Tu biblioteca permanece en el dispositivo", {})
        Spacer(Modifier.height(12.dp))
        Text(status, color = Soft, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("EC MUSIC PLAYER  •  v1.0.0", color = Purple, fontSize = 10.sp, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(color = CardBg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onClick() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Purple, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Soft, fontSize = 10.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = Soft)
        }
    }
}

@Composable
private fun MiniPlayer(song: Song, playing: Boolean, onPlay: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    Surface(color = Color(0xFF222033), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1); Text(song.artist, color = Soft, fontSize = 10.sp, maxLines = 1) }
            IconButton(onClick = onPlay) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Purple) }
        }
    }
}

@Composable
private fun SongRow(song: Song, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) CardHi else CardBg).clickable { onClick() }.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(Modifier.size(46.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) { Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, color = Soft, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(time(song.duration), color = Soft, fontSize = 10.sp)
    }
}

@Composable
private fun Artwork(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A39F5), Color(0xFF153D8D), Color(0xFF14A6B9)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LibraryMusic, null, tint = Purple, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(8.dp))
            Text(message, color = Soft, fontSize = 12.sp)
        }
    }
}

private fun time(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}
