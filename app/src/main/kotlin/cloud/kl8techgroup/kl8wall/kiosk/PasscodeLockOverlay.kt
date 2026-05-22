package cloud.kl8techgroup.kl8wall.kiosk

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun PasscodeLockOverlay(
    lockManager: PasscodeLockManager,
    isPinSet: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("Disconnected") }

    var enteredPin by remember { mutableStateOf("") }
    var isPinIncorrect by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }

    // Update time/date
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        while (true) {
            val cal = Calendar.getInstance()
            currentTime = timeFormat.format(cal.time)
            currentDate = dateFormat.format(cal.time)
            delay(1000)
        }
    }

    // Get system status
    LaunchedEffect(Unit) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        while (true) {
            // Battery
            val batteryStatusIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100 / scale)
            }
            val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            // Wifi SSID
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            wifiSsid = if (info != null && info.ssid != WifiManager.UNKNOWN_SSID) {
                info.ssid.replace("\"", "")
            } else {
                "Disconnected"
            }
            
            delay(5000)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F1419),
                        Color(0xFF1D242C)
                    )
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isPinSet) {
                    lockManager.unlock()
                } else {
                    showKeypad = true
                }
            }
    ) {
        // Main lock screen content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Status bar info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WiFi: $wifiSsid",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "Battery: $batteryPercent%${if (isCharging) " (Charging)" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            // Big Clock & Date
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Text(
                    text = currentTime,
                    fontSize = 110.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                    letterSpacing = (-2).sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = currentDate,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            // Lock Indicator & Hint
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isPinSet) "Tap screen to enter PIN" else "Tap screen to unlock",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Keypad Slide Up Panel
        AnimatedVisibility(
            visible = showKeypad && isPinSet,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable {
                        showKeypad = false
                        enteredPin = ""
                        isPinIncorrect = false
                    },
                contentAlignment = Alignment.Center
            ) {
                // Keypad Card container (intercept click)
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1F26)
                    ),
                    modifier = Modifier
                        .width(360.dp)
                        .padding(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Enter Passcode",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        // PIN Dots Row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.height(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 0 until 4) {
                                val filled = i < enteredPin.length
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isPinIncorrect) Color.Red
                                            else if (filled) MaterialTheme.colorScheme.primary
                                            else Color.White.copy(alpha = 0.2f)
                                        )
                                )
                            }
                        }

                        if (isPinIncorrect) {
                            Text(
                                text = "Incorrect passcode. Try again.",
                                color = Color.Red,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        // Keypad Buttons Grid
                        val buttons = listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9"),
                            listOf("C", "0", "DEL")
                        )

                        buttons.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                row.forEach { label ->
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (label == "C" || label == "DEL") Color.White.copy(alpha = 0.05f)
                                                else Color.White.copy(alpha = 0.1f)
                                            )
                                            .clickable {
                                                isPinIncorrect = false
                                                when (label) {
                                                    "C" -> {
                                                        enteredPin = ""
                                                    }
                                                    "DEL" -> {
                                                        if (enteredPin.isNotEmpty()) {
                                                            enteredPin = enteredPin.dropLast(1)
                                                        }
                                                    }
                                                    else -> {
                                                        if (enteredPin.length < 4) {
                                                            enteredPin += label
                                                            if (enteredPin.length == 4) {
                                                                // Verify PIN
                                                                val isCorrect = lockManager.verifyPin(enteredPin)
                                                                if (isCorrect) {
                                                                    showKeypad = false
                                                                    enteredPin = ""
                                                                } else {
                                                                    isPinIncorrect = true
                                                                    enteredPin = ""
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (label == "DEL") {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = Color.White
                                            )
                                        } else {
                                            Text(
                                                text = label,
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.Normal,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
