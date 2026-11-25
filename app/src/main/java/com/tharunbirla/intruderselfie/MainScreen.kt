package com.tharunbirla.intruderselfie

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.rememberAsyncImagePainter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private val Midnight = Color(0xFF080B14)
private val DeepBlue = Color(0xFF10182B)
private val Panel = Color(0xFF131C2D)
private val PanelRaised = Color(0xFF1A263A)
private val Hairline = Color(0xFF29384E)
private val Ice = Color(0xFFEAF2FF)
private val Muted = Color(0xFF8F9DB2)
private val Cyan = Color(0xFF72E6E0)
private val Violet = Color(0xFF9D91FF)
private val AlertRed = Color(0xFFFF6B78)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val appEnabled by viewModel.appEnabled.collectAsState()
    val alert by viewModel.alertSettings.collectAsState()
    val capturedPhotos by viewModel.capturedPhotos.collectAsState()
    val selectedPhotos by viewModel.selectedPhotos.collectAsState()
    val previewPhotoUri by viewModel.previewPhotoUri.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isDeviceAdminActive by remember {
        mutableStateOf(FailedAttemptAdminReceiver.isActive(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDeviceAdminActive = FailedAttemptAdminReceiver.isActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val pickedUri = result.data?.getParcelableExtra<Uri>(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI
            )
            if (pickedUri != null) {
                val title = runCatching {
                    RingtoneManager.getRingtone(context, pickedUri)?.getTitle(context)
                }.getOrNull() ?: "Selected ringtone"
                viewModel.setRingtone(pickedUri.toString(), title)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Midnight, DeepBlue, Midnight)
                )
            )
    ) {
        Scaffold(containerColor = Color.Transparent) { insets ->
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "S A C H I N",
                                    color = Cyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 4.sp
                                )
                                Text(
                                    "PERSONAL SECURITY SYSTEM",
                                    color = Muted,
                                    fontSize = 9.sp,
                                    letterSpacing = 2.sp
                                )
                            }
                            Surface(
                                color = Color(0xFF142B36),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    "LOCAL • PRIVATE",
                                    color = Cyan,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Intruder Selfie.",
                            color = Ice,
                            fontSize = 31.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-1).sp
                        )
                        Text(
                            "Your device. Your rules. Your control.",
                            color = Muted,
                            fontSize = 13.sp
                        )
                    }
                }

                item {
                    ProtectionPanel(
                        enabled = appEnabled,
                        adminActive = isDeviceAdminActive,
                        onEnabledChange = viewModel::toggleAppEnabled,
                        onEnableAdmin = {
                            val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(
                                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    FailedAttemptAdminReceiver.component(context)
                                )
                                putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Allows this app to receive failed PIN, password, or pattern attempts. One photo is stored locally for each failure."
                                )
                            }
                            context.startActivity(adminIntent)
                        }
                    )
                }

                item {
                    SectionHeading(
                        title = "ALERT ENGINE",
                        subtitle = "Choose what happens after repeated failed unlocks."
                    )
                }

                item {
                    PanelCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Trigger threshold", color = Ice, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("Consecutive incorrect attempts", color = Muted, fontSize = 11.sp)
                            }
                            Surface(color = Color(0xFF193C43), shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    "${alert.threshold} TIMES",
                                    color = Cyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                                )
                            }
                        }
                        Slider(
                            value = alert.threshold.toFloat(),
                            onValueChange = { viewModel.setAlertThreshold(it.toInt()) },
                            valueRange = 2f..5f,
                            steps = 2,
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan,
                                activeTrackColor = Cyan,
                                inactiveTrackColor = Hairline
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("2 attempts", color = Muted, fontSize = 10.sp)
                            Text("5 attempts", color = Muted, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = Hairline)
                        SettingSwitchRow(
                            icon = Icons.Default.VolumeUp,
                            title = "Audible alarm",
                            subtitle = "Play a repeating alert tone",
                            checked = alert.soundEnabled,
                            onCheckedChange = viewModel::setAlarmSoundEnabled
                        )
                        if (alert.soundEnabled) {
                            Surface(
                                color = PanelRaised,
                                shape = RoundedCornerShape(15.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 13.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text("Alarm tone", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                                        Text(
                                            alert.ringtoneTitle,
                                            color = Ice,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            val existing = alert.ringtoneUri?.let(Uri::parse)
                                                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose security alarm")
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                                            }
                                            ringtonePicker.launch(intent)
                                        }
                                    ) {
                                        Text("CHANGE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Sound intensity", color = Muted, fontSize = 12.sp)
                                Text("${alert.volume}%", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Slider(
                                value = alert.volume.toFloat(),
                                onValueChange = { viewModel.setAlarmVolume(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 9,
                                colors = SliderDefaults.colors(
                                    thumbColor = Cyan,
                                    activeTrackColor = Cyan,
                                    inactiveTrackColor = Hairline
                                )
                            )
                        }
                    }
                }

                item {
                    PanelCard {
                        SettingSwitchRow(
                            icon = Icons.Default.FlashOn,
                            title = "Flash alert",
                            subtitle = "Blink the rear camera torch",
                            checked = alert.torchEnabled,
                            onCheckedChange = viewModel::setTorchEnabled
                        )
                        if (alert.torchEnabled) {
                            Surface(
                                color = Color(0xFF2A2535),
                                shape = RoundedCornerShape(13.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Torch starts after the front-camera capture has had time to finish. Flash alerts may be unsuitable for photosensitive users.",
                                    color = Color(0xFFD0C4E7),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Blink interval", color = Muted, fontSize = 12.sp)
                                Text("${alert.blinkIntervalMs} ms", color = Cyan, fontSize = 12.sp)
                            }
                            Slider(
                                value = alert.blinkIntervalMs.toFloat(),
                                onValueChange = { viewModel.setBlinkInterval(it.toInt()) },
                                valueRange = 1000f..2000f,
                                steps = 3,
                                colors = SliderDefaults.colors(
                                    thumbColor = Cyan,
                                    activeTrackColor = Cyan,
                                    inactiveTrackColor = Hairline
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Slower", color = Muted, fontSize = 10.sp)
                                Text("Faster", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }

                item {
                    PanelCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Alert duration", color = Ice, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Auto-stop after the selected time", color = Muted, fontSize = 11.sp)
                            }
                            Text("${alert.durationSeconds}s", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = alert.durationSeconds.toFloat(),
                            onValueChange = { viewModel.setAlarmDuration(it.toInt()) },
                            valueRange = 10f..60f,
                            steps = 4,
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan,
                                activeTrackColor = Cyan,
                                inactiveTrackColor = Hairline
                            )
                        )
                        Button(
                            onClick = viewModel::testAlert,
                            enabled = alert.soundEnabled || alert.torchEnabled,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B3D46),
                                contentColor = Cyan,
                                disabledContainerColor = PanelRaised,
                                disabledContentColor = Muted
                            )
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(9.dp))
                            Text("TEST ALERT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 11.sp)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        SectionHeading(
                            title = "CAPTURED INCIDENTS",
                            subtitle = "Photos stay on this device."
                        )
                        Text(
                            "${capturedPhotos.size} FILES",
                            color = Cyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (selectedPhotos.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${selectedPhotos.size} selected", color = Ice, fontSize = 12.sp)
                            Row {
                                TextButton(onClick = viewModel::clearSelection) {
                                    Text("CANCEL", color = Muted, fontSize = 10.sp)
                                }
                                TextButton(onClick = viewModel::deleteSelectedPhotos) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = AlertRed)
                                    Text("DELETE", color = AlertRed, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                if (capturedPhotos.isEmpty()) {
                    item {
                        PanelCard {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Muted, modifier = Modifier.size(30.dp))
                                    Text("No incidents recorded", color = Ice, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("A photo appears here after a failed credential attempt.", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else {
                    val rows = ceil(capturedPhotos.size / 3.0).toInt().coerceAtLeast(1)
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxWidth().height((rows * 124).dp),
                            contentPadding = PaddingValues(1.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            userScrollEnabled = false
                        ) {
                            items(capturedPhotos, key = { it.uri.toString() }) { photo ->
                                val selected = selectedPhotos.contains(photo.uri)
                                IncidentPhotoTile(
                                    photo = photo,
                                    selected = selected,
                                    onClick = {
                                        if (selectedPhotos.isNotEmpty()) {
                                            viewModel.togglePhotoSelection(photo.uri)
                                        } else {
                                            viewModel.setPreviewPhoto(photo.uri)
                                        }
                                    },
                                    onLongClick = { viewModel.togglePhotoSelection(photo.uri) }
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        "DEVICE-ONLY STORAGE  •  NO CLOUD UPLOAD",
                        color = Muted.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }

    previewPhotoUri?.let { uri ->
        val photo = capturedPhotos.find { it.uri == uri }
        if (photo != null) {
            IncidentPhotoPreview(
                photo = photo,
                onDismiss = { viewModel.setPreviewPhoto(null) },
                onDelete = { viewModel.deleteSinglePhoto(uri) }
            )
        }
    }
}

@Composable
private fun ProtectionPanel(
    enabled: Boolean,
    adminActive: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEnableAdmin: () -> Unit
) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Cyan.copy(alpha = 0.2f), Violet.copy(alpha = 0.18f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Cyan, modifier = Modifier.size(25.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Protection status", color = Ice, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled && adminActive) "Monitoring enabled" else "Setup required",
                        color = if (enabled && adminActive) Cyan else Muted,
                        fontSize = 11.sp
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Midnight,
                    checkedTrackColor = Cyan,
                    uncheckedThumbColor = Muted,
                    uncheckedTrackColor = Hairline
                )
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip("CAMERA", enabled, Modifier.weight(1f))
            StatusChip("DEVICE ADMIN", adminActive, Modifier.weight(1f))
        }
        if (!adminActive) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Device Administrator is required to receive failed PIN, password, and pattern callbacks.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Button(
                onClick = onEnableAdmin,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Midnight)
            ) {
                Text("ENABLE DEVICE ADMIN", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
        }
        if (adminActive) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Each failed PIN/password/pattern attempt saves one photo. A correct unlock resets the consecutive-failure counter and stops the active alert.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Biometric failures may not be exposed to ordinary apps. After a phone restart, reopen the app if your Android version or device requires it.",
            color = Muted.copy(alpha = 0.8f),
            fontSize = 10.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = if (active) Color(0xFF142E35) else PanelRaised,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, if (active) Cyan.copy(alpha = 0.2f) else Hairline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(6.dp).clip(CircleShape).background(if (active) Cyan else Muted)
            )
            Spacer(Modifier.width(7.dp))
            Text(label, color = if (active) Cyan else Muted, fontSize = 9.sp, letterSpacing = 0.6.sp)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Ice, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Hairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content
        )
    }
}

@Composable
private fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(PanelRaised),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (checked) Cyan else Muted, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Midnight,
                checkedTrackColor = Cyan,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Hairline
            )
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IncidentPhotoTile(
    photo: CapturedPhoto,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(PanelRaised)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Image(
            painter = rememberAsyncImagePainter(photo.uri),
            contentDescription = "Failed unlock photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(6.dp)
        ) {
            Text(
                SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(photo.timestamp)),
                color = Color.White,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Box(
                modifier = Modifier.fillMaxSize().background(Cyan.copy(alpha = 0.22f)),
                contentAlignment = Alignment.TopEnd
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = Cyan,
                    modifier = Modifier.padding(6.dp).size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun IncidentPhotoPreview(
    photo: CapturedPhoto,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = rememberAsyncImagePainter(photo.uri),
                contentDescription = "Captured incident",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete photo", tint = AlertRed)
                }
            }
            Text(
                SimpleDateFormat("EEEE, dd MMM yyyy  •  HH:mm:ss", Locale.getDefault()).format(Date(photo.timestamp)),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
            )
        }
    }
}
