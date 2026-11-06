package com.radar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.radar.crypto.RadarCrypto
import kotlinx.coroutines.delay

/** ¿Es la sala pública de fábrica (cualquiera con la app puede leerla)? */
internal fun isPublicRoom(code: String): Boolean =
    RadarCrypto.normalize(code) == RadarCrypto.PUBLIC_ROOM_CODE

/**
 * Rótulo para chips y encabezados. No muestra ningún carácter del código
 * privado: la pantalla puede estar a la vista de otras personas.
 */
internal fun roomLabel(code: String): String =
    if (isPublicRoom(code)) "Sala pública" else "Sala privada"

/**
 * Hoja inferior con todo lo de la sala: QR, código, copiar/compartir/escanear,
 * unirse a otra y crear una privada. Se desplaza si el contenido no cabe
 * (teléfono apaisado, letra grande) y se acomoda al teclado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSheet(
    current: String,
    onJoin: (String) -> Boolean,
    onNewRoom: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }

    val qr = remember(current) { qrBitmap(current) }
    val publicRoom = isPublicRoom(current)

    fun tryJoin(value: String) {
        if (onJoin(value)) {
            onDismiss()
        } else {
            error = "Código inválido: son 20 caracteres (A-Z y 0-9)."
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            code = contents
            // Un QR leído es una intención clara: se une sin pedir otro toque.
            tryJoin(contents)
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= 600.dp

            Column(
                modifier = Modifier
                    .widthIn(max = 880.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                RoomHeader(publicRoom)

                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                        QrTile(qr, Modifier.size(220.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Space.md)
                        ) {
                            CodeBlock(current, publicRoom)
                            ShareActions(
                                copied = copied,
                                onCopy = {
                                    copyRoom(context, current)
                                    copied = true
                                },
                                onShare = { shareRoom(context, current) },
                                onScan = { scanner.launch(scanOptions()) }
                            )
                        }
                    }
                } else {
                    QrTile(
                        qr,
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(200.dp)
                    )
                    CodeBlock(current, publicRoom)
                    ShareActions(
                        copied = copied,
                        onCopy = {
                            copyRoom(context, current)
                            copied = true
                        },
                        onShare = { shareRoom(context, current) },
                        onScan = { scanner.launch(scanOptions()) }
                    )
                }

                JoinBlock(
                    code = code,
                    error = error,
                    onCodeChange = {
                        code = it
                        error = null
                    },
                    onJoin = { tryJoin(code) }
                )

                NewRoomBlock(
                    confirming = confirmNew,
                    onAsk = { confirmNew = true },
                    onCancel = { confirmNew = false },
                    onConfirm = {
                        onNewRoom()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun RoomHeader(publicRoom: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = if (publicRoom) RadarColors.Amber else RadarColors.Mint,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text("Sala cifrada", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "AES-256-GCM · la clave nunca viaja por la red",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QrTile(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    // Fondo blanco fijo: los lectores de QR necesitan contraste oscuro sobre claro.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Código QR de la sala",
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("QR no disponible", color = Color.Black, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CodeBlock(code: String, publicRoom: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        val tone = if (publicRoom) RadarColors.Amber else RadarColors.Mint
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(tone.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (publicRoom) Icons.Filled.Warning else Icons.Filled.Check,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (publicRoom) {
                    "Pública: cualquiera con Radar puede leerla"
                } else {
                    "Privada: solo quien tenga este código"
                },
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = tone
            )
        }
    }
}

@Composable
private fun ShareActions(
    copied: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onScan: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            FilledTonalButton(
                onClick = onCopy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                if (copied) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                }
                Text(if (copied) "Copiado" else "Copiar", maxLines = 1)
            }
            FilledTonalButton(
                onClick = onShare,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Compartir", maxLines = 1)
            }
        }
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) { Text("Escanear QR de otra sala") }
    }
}

@Composable
private fun JoinBlock(
    code: String,
    error: String?,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("Unirme a otra sala", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                modifier = Modifier.weight(1f),
                label = { Text("Código de sala") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters
                ),
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.size(Space.sm))
            Button(
                onClick = onJoin,
                enabled = code.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)
            ) { Text("Unirme") }
        }
    }
}

@Composable
private fun NewRoomBlock(
    confirming: Boolean,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!confirming) {
        TextButton(onClick = onAsk) { Text("Crear sala privada nueva") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = "Se genera otro código y se borra el chat actual. Quienes no lo reciban " +
                "dejarán de entender tus mensajes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Crear y borrar chat") }
            TextButton(onClick = onCancel) { Text("Cancelar") }
        }
    }
}

private fun scanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Escaneá el QR de la sala")
    .setBeepEnabled(false)
    .setOrientationLocked(true)

/** Copia el código marcándolo como sensible (Android 13+ oculta la vista previa). */
private fun copyRoom(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Sala Radar", code)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
}

private fun shareRoom(context: Context, code: String) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Unite a mi sala Radar: $code")
    }
    runCatching { context.startActivity(Intent.createChooser(share, "Compartir sala")) }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()
