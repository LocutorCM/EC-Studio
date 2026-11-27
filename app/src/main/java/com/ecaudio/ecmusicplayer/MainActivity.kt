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
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale

private val Bg = Color(0xFF0A0A10)
private val CardBg = Color(0xFF171722)
private val CardHi = Color(0xFF25243A)
private val Purple = Color(0xFF9B5CFF)
private val Cyan = Color(0xFF42C8F5)
private val Muted = Color(0xFF9292A8)

data class Song(val id: Long, val title: String, val artist: String, val album: String, val uri: android.net.Uri, val duration: Long)
private enum class Section { MUSIC, PLAYLISTS, EQ }
private enum class RepeatMode { OFF, ONCE, ALWAYS, ALL }

private object PlaybackNotificationBridge { var handler: ((String) -> Unit)? = null }

class PlayerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { PlaybackNotificationBridge.handler?.invoke(intent.action ?: return) }
}

class PlaybackNotificationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "EC Music Player"
        val artist = intent?.getStringExtra("artist") ?: "Reproducción de música"
        val playing = intent?.getBooleanExtra("playing", false) ?: false
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("ec_music_playback", "Reproducción", NotificationManager.IMPORTANCE_LOW))
        fun action(label: String, icon: Int, key: String) = NotificationCompat.Action.Builder(icon, label, android.app.PendingIntent.getBroadcast(this, key.hashCode(), Intent(this, PlayerActionReceiver::class.java).setAction(key), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)).build()
        val notification = NotificationCompat.Builder(this, "ec_music_playback")
            .setSmallIcon(R.drawable.ic_music_player).setContentTitle(title).setContentText(artist)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(action("Anterior", android.R.drawable.ic_media_previous, "ec.previous"))
            .addAction(action(if (playing) "Pausar" else "Reproducir", if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "ec.toggle"))
            .addAction(action("Siguiente", android.R.drawable.ic_media_next, "ec.next"))
            .build()
        startForeground(41, notification)
        return START_STICKY
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ECPlayerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ECPlayerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? ComponentActivity
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var section by remember { mutableStateOf(Section.MUSIC) }
    var search by remember { mutableStateOf("") }
    var selectedSongId by remember { mutableStateOf<Long?>(null) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tu música, en un solo lugar") }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var eqEnabled by remember { mutableStateOf(false) }
    var bandValues by remember { mutableStateOf(emptyList<Float>()) }
    var repeat by remember { mutableStateOf(RepeatMode.OFF) }
    var shuffle by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(loadPlaylists(context)) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var editingPlaylist by remember { mutableStateOf<String?>(null) }
    var chooseSongsFor by remember { mutableStateOf<String?>(null) }
    var playlistSearch by remember { mutableStateOf("") }
    var repeatOnceUsed by remember { mutableStateOf(false) }\n    val activeSong by remember(songs, currentId) { mutableStateOf(songs.firstOrNull { it.id == currentId }) }
    val repeatLatest by rememberUpdatedState(repeat)
    val songsLatest by rememberUpdatedState(songs)
    val currentLatest by rememberUpdatedState(currentId)

    fun releasePlayer() {
        try { player?.setOnCompletionListener(null); player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        playing = false
    }

    fun playAt(index: Int) {
        val list = songs
        if (index !in list.indices) return
        releasePlayer()
        val song = list[index]
        currentId = song.id
        selectedSongId = song.id
        repeatOnceUsed = false
        position = 0L
        status = "Preparando audio…"
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(context, song.uri)
            mp.setOnPreparedListener { prepared ->
                duration = prepared.duration.toLong().coerceAtLeast(0L)
                prepared.start()
                playing = true
                status = "Reproduciendo"
                try {
                    val fx = Equalizer(0, prepared.audioSessionId)
                    equalizer = fx
                    fx.enabled = eqEnabled
                    bandValues = (0 until fx.numberOfBands.toInt()).map { b -> fx.getBandLevel(b.toShort()) / 100f }
                } catch (_: Exception) { status = "Reproduciendo · EQ no disponible" }
            }
            mp.setOnCompletionListener {
                when (repeatLatest) {
                    RepeatMode.ONCE -> if (!repeatOnceUsed) {
                        repeatOnceUsed = true
                        it.seekTo(0)
                        it.start()
                        playing = true
                    } else {
                        val next = songsLatest.indexOfFirst { s -> s.id == currentLatest } + 1
                        if (next in songsLatest.indices) playAt(next) else { playing = false; status = "Fin de la lista" }
                    }
                    RepeatMode.ALWAYS -> { it.seekTo(0); it.start(); playing = true }\n                    RepeatMode.ALL -> {
                        val now = songsLatest.indexOfFirst { s -> s.id == currentLatest }
                        val next = if (shuffle && songsLatest.size > 1) (songsLatest.indices.filter { it != now }.random()) else (now + 1) % songsLatest.size
                        playAt(next)
                    }
                    RepeatMode.OFF -> {
                        val next = songsLatest.indexOfFirst { s -> s.id == currentLatest } + 1
                        if (next in songsLatest.indices) playAt(next) else { playing = false; status = "Fin de la lista" }
                    }
                }
            }
            mp.setOnErrorListener { _, _, _ -> playing = false; status = "No se pudo reproducir este archivo"; true }
            player = mp
            mp.prepareAsync()
        } catch (_: Exception) { status = "No se pudo abrir este archivo" }
    }

    fun playNext() {
        if (songs.isEmpty()) return
        val index = songs.indexOfFirst { it.id == currentId }
        val next = if (shuffle && songs.size > 1) songs.indices.filter { it != index }.random() else (index + 1).mod(songs.size)
        playAt(next)
    }
    fun playPrevious() {
        if (songs.isEmpty()) return
        if (position > 3000L) { player?.seekTo(0); position = 0L }
        else {
            val index = songs.indexOfFirst { it.id == currentId }
            playAt(if (index <= 0) songs.lastIndex else index - 1)
        }
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
                    found.add(Song(id, cursor.getString(titleCol) ?: "Sin título", cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Artista desconocido", cursor.getString(albumCol) ?: "Álbum desconocido", ContentUris.withAppendedId(collection, id), cursor.getLong(durationCol)))
                }
            }
            songs = found
            status = "Se encontraron ${found.size} canciones"
        } catch (_: Exception) { status = "No se pudo leer la música del teléfono" }
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else status = "Permite el acceso a los archivos de audio"
    }
    fun requestScan() {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) scan()
        else permissionLauncher.launch(permission)
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (currentId == selectedSongId) releasePlayer()
            selectedSongId = null
            scan()
        }
    }
    fun deleteSong(song: Song) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(song.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                val deleted = context.contentResolver.delete(song.uri, null, null)
                if (deleted > 0) scan() else status = "No se pudo eliminar el archivo"
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                try { activity?.startIntentSenderForResult(e.userAction.actionIntent.intentSender, 2026, null, 0, 0, 0) } catch (_: IntentSender.SendIntentException) { status = "No se pudo solicitar permiso para eliminar" }
            } else status = "No se pudo eliminar: revisa los permisos del archivo"
        }
    }

    SideEffect {\n        PlaybackNotificationBridge.handler = { action ->\n            when (action) {\n                "ec.toggle" -> { if (playing) { player?.pause(); playing = false } else { player?.start(); playing = true }; activeSong?.let { context.startService(Intent(context, PlaybackNotificationService::class.java).putExtra("title", it.title).putExtra("artist", it.artist).putExtra("playing", playing)) } }\n                "ec.next" -> playNext()\n                "ec.previous" -> playPrevious()\n            }\n        }\n    }\n    LaunchedEffect(Unit) { requestScan() }
    LaunchedEffect(playing, currentId) {
        while (playing) {
            position = try { player?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
            delay(1000)
        }
    }
    DisposableEffect(Unit) { onDispose { PlaybackNotificationBridge.handler = null; releasePlayer() } }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Cyan, background = Bg, surface = CardBg)) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                TopAppBar(
                    title = { Column { Text("EC MUSIC", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 19.sp); Text("PLAYER", color = Purple, fontSize = 9.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold) } },
                    actions = { IconButton(onClick = { requestScan() }) { Icon(Icons.Default.Refresh, "Actualizar biblioteca", tint = Cyan) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                )
            },
            bottomBar = {
                if (selectedSongId == null) NavigationBar(containerColor = Color(0xFF12121C)) {
                    listOf(Triple(Section.MUSIC, Icons.Default.MusicNote, "Música"), Triple(Section.PLAYLISTS, Icons.Default.QueueMusic, "Listas"), Triple(Section.EQ, Icons.Default.GraphicEq, "Ecualizador")).forEach { item ->
                        NavigationBarItem(selected = section == item.first, onClick = { section = item.first }, icon = { Icon(item.second, null) }, label = { Text(item.third, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Purple, indicatorColor = Purple.copy(alpha = .16f), unselectedIconColor = Muted))
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when {
                    selectedSongId != null -> {
                        val song = songs.firstOrNull { it.id == selectedSongId }
                        if (song != null) PlayerDetail(song, playing, duration, position, repeat, shuffle,
                            onBack = { selectedSongId = null },
                            onPlayPause = { if (player == null) playAt(songs.indexOfFirst { it.id == song.id }) else if (playing) { player?.pause(); playing = false; context.startService(Intent(context, PlaybackNotificationService::class.java).putExtra("title", song.title).putExtra("artist", song.artist).putExtra("playing", false)) } else { player?.start(); playing = true; context.startService(Intent(context, PlaybackNotificationService::class.java).putExtra("title", song.title).putExtra("artist", song.artist).putExtra("playing", true)) } },
                            onStop = { try { player?.pause(); player?.seekTo(0) } catch (_: Exception) {}; position = 0L; playing = false },
                            onNext = { playNext() }, onPrevious = { playPrevious() },
                            onRepeat = { repeat = RepeatMode.values()[(repeat.ordinal + 1) % RepeatMode.values().size] },
                            onShuffle = { shuffle = !shuffle },
                            onSeek = { player?.seekTo(it.toInt()); position = it }
                        ) else selectedSongId = null
                    }
                    section == Section.MUSIC -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Mi música", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("${songs.size} canciones", color = Muted, fontSize = 11.sp) }
                                IconButton(onClick = { requestScan() }) { Icon(Icons.Default.Refresh, null, tint = Cyan) }
                            }
                            OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Buscar por nombre o artista…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, null) } }, shape = RoundedCornerShape(15.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = CardHi))
                            Spacer(Modifier.height(10.dp))
                            val filtered = remember(songs, search) {
                                val q = search.trim().lowercase(Locale.ROOT)
                                if (q.isEmpty()) songs else songs.filter { it.title.lowercase(Locale.ROOT).contains(q) || it.artist.lowercase(Locale.ROOT).contains(q) || it.album.lowercase(Locale.ROOT).contains(q) }
                            }
                            if (filtered.isEmpty()) EmptyState(if (songs.isEmpty()) "No se encontró música. Actualiza la biblioteca." else "No hay coincidencias para «$search»")
                            else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(filtered, key = { it.id }) { song ->
                                    SongListItem(song, song.id == currentId, onClick = { selectedSongId = song.id; if (currentId != song.id) playAt(songs.indexOfFirst { it.id == song.id }) }, onDelete = { deleteSong(song) })
                                }
                            }
                        }
                    }
                    section == Section.PLAYLISTS -> {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Listas", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Organiza tu música", color = Muted, fontSize = 11.sp) }; FilledIconButton(onClick = { creatingPlaylist = true; playlistName = "" }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Purple)) { Icon(Icons.Default.Add, null) } }
                            Spacer(Modifier.height(12.dp))
                            playlists.forEach { (name, ids) ->
                                Surface(color = CardBg, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { editingPlaylist = name }) {
                                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.QueueMusic, null, tint = Purple, modifier = Modifier.size(28.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) { Text(name, color = Color.White, fontWeight = FontWeight.SemiBold); Text("${ids.size} canciones", color = Muted, fontSize = 11.sp) }
                                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                                    }
                                }
                            }
                            if (playlists.isEmpty()) EmptyState("Crea tu primera lista de reproducción")
                        }
                    }
                    else -> EqualizerScreen(equalizer, eqEnabled, bandValues, onEnabled = { eqEnabled = it; try { equalizer?.enabled = it } catch (_: Exception) {} }, onBand = { band, value ->
                        bandValues = bandValues.toMutableList().also { if (band in it.indices) it[band] = value }
                        try { val range = equalizer?.bandLevelRange; if (range != null) equalizer?.setBandLevel(band.toShort(), (value * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()) } catch (_: Exception) {}
                    })
                }
            }
        }
    }

    if (creatingPlaylist) AlertDialog(onDismissRequest = { creatingPlaylist = false }, title = { Text("Nueva lista") }, text = { OutlinedTextField(value = playlistName, onValueChange = { playlistName = it }, singleLine = true, label = { Text("Nombre de la lista") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) }, confirmButton = { TextButton(onClick = {
        val name = playlistName.trim()
        if (name.isNotEmpty() && !playlists.containsKey(name)) { playlists = playlists + (name to emptyList()); savePlaylists(context, playlists); editingPlaylist = name }
        creatingPlaylist = false
    }) { Text("Crear y elegir música") } }, dismissButton = { TextButton(onClick = { creatingPlaylist = false }) { Text("Cancelar") } })

    editingPlaylist?.let { name ->
        val ids = playlists[name] ?: emptyList()
        AlertDialog(onDismissRequest = { editingPlaylist = null; chooseSongsFor = null }, title = { Text(name) }, text = {
            Column {
                Text("${ids.size} canciones en esta lista", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = playlistSearch, onValueChange = { playlistSearch = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Buscar música para agregar") }, leadingIcon = { Icon(Icons.Default.Search, null) })
                Spacer(Modifier.height(6.dp))
                val q = playlistSearch.trim().lowercase(Locale.ROOT)
                val candidates = songs.filter { song -> (q.isEmpty() || song.title.lowercase(Locale.ROOT).contains(q) || song.artist.lowercase(Locale.ROOT).contains(q)) }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(candidates, key = { it.id }) { song ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val updated = if (song.id in ids) ids - song.id else ids + song.id
                            playlists = playlists + (name to updated)
                            savePlaylists(context, playlists)
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, color = Muted, fontSize = 10.sp) }
                            Checkbox(checked = song.id in ids, onCheckedChange = { checked ->
                                val updated = if (checked) (ids + song.id).distinct() else ids - song.id
                                playlists = playlists + (name to updated)
                                savePlaylists(context, playlists)
                            }, colors = CheckboxDefaults.colors(checkedColor = Purple))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { editingPlaylist = null; playlistSearch = "" }) { Text("Listo") } }, dismissButton = { TextButton(onClick = {
            playlists = playlists - name
            savePlaylists(context, playlists)
            editingPlaylist = null
        }) { Text("Eliminar lista") } })
    }
}

@Composable
private fun SongListItem(song: Song, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) CardHi else CardBg).clickable { onClick() }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(Modifier.size(48.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) { Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, color = Muted, fontSize = 11.sp, maxLines = 1) }
        Text(time(song.duration), color = Muted, fontSize = 10.sp)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Eliminar del teléfono") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun PlayerDetail(song: Song, playing: Boolean, duration: Long, position: Long, repeat: RepeatMode, shuffle: Boolean, onBack: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onRepeat: () -> Unit, onShuffle: () -> Unit, onSeek: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }; Text("REPRODUCIENDO", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp) }
        Spacer(Modifier.weight(1f))
        Artwork(Modifier.fillMaxWidth().aspectRatio(1f).padding(18.dp))
        Text(song.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artist, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))
        Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(position), color = Muted, fontSize = 11.sp); Text(time(duration), color = Muted, fontSize = 11.sp) }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Cyan else Muted) }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(70.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Purple)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(38.dp)) }
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRepeat, shape = RoundedCornerShape(14.dp)) {
            Icon(if (repeat == RepeatMode.ONCE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if (repeat == RepeatMode.OFF) Muted else Cyan)
            Spacer(Modifier.width(8.dp))
            Text(when (repeat) { RepeatMode.OFF -> "Repetir: desactivado"; RepeatMode.ONCE -> "Repetir una vez"; RepeatMode.ALWAYS -> "Repetir siempre esta canción"; RepeatMode.ALL -> "Repetir toda la lista" })
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun EqualizerScreen(eq: Equalizer?, enabled: Boolean, values: List<Float>, onEnabled: (Boolean) -> Unit, onBand: (Int, Float) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Ecualizador", fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Controles verticales por banda", color = Muted, fontSize = 12.sp) }; Switch(checked = enabled, onCheckedChange = onEnabled) }
        Spacer(Modifier.height(12.dp))
        if (eq == null) EmptyState("Reproduce una canción para activar el ecualizador")
        else {
            val range = eq.bandLevelRange
            val low = range[0] / 100f
            val high = range[1] / 100f
            Surface(color = CardBg, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    (0 until eq.numberOfBands.toInt()).forEach { band ->
                        val value = values.getOrNull(band) ?: 0f
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("${(eq.getCenterFreq(band.toShort()) / 1000f).let { String.format(Locale.getDefault(), "%.1f", it) }}k", color = Muted, fontSize = 9.sp)
                            Box(Modifier.height(230.dp).width(42.dp), contentAlignment = Alignment.Center) {
                                Slider(value = value.coerceIn(low, high), onValueChange = { onBand(band, it) }, valueRange = low..high, modifier = Modifier.width(210.dp).height(42.dp).graphicsLayer { rotationZ = -90f }, colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = CardHi))
                            }
                            Text(String.format(Locale.getDefault(), "%.1f", value), color = Cyan, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("El ecualizador usa el efecto de audio disponible en tu dispositivo.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Artwork(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A39F5), Color(0xFF153D8D), Color(0xFF14A6B9)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(64.dp))
    }
}
@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LibraryMusic, null, tint = Purple, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(8.dp)); Text(message, color = Muted, fontSize = 12.sp) }
    }
}
private fun loadPlaylists(context: android.content.Context): Map<String, List<Long>> {
    val prefs = context.getSharedPreferences("ec_music_player", android.content.Context.MODE_PRIVATE)
    val raw = prefs.getString("playlists", null) ?: return emptyMap()
    return try {
        org.json.JSONObject(raw).let { json ->
            json.keys().asSequence().associateWith { name ->
                val array = json.getJSONArray(name)
                (0 until array.length()).map { array.getLong(it) }
            }
        }
    } catch (_: Exception) { emptyMap() }
}
private fun savePlaylists(context: android.content.Context, playlists: Map<String, List<Long>>) {
    val json = org.json.JSONObject()
    playlists.forEach { (name, ids) -> json.put(name, org.json.JSONArray(ids)) }
    context.getSharedPreferences("ec_music_player", android.content.Context.MODE_PRIVATE).edit().putString("playlists", json.toString()).apply()
}
private fun time(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}
