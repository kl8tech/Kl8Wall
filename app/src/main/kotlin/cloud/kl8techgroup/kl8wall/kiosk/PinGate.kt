package cloud.kl8techgroup.kl8wall.kiosk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Optional PIN entry UI gating access to settings.
 *
 * Displays a numeric PIN pad. Verifies input against the stored Argon2id hash.
 * Implements lockout after [MAX_ATTEMPTS] failed attempts: initial cooldown
 * of [BASE_LOCKOUT_MS] doubling with each subsequent failure.
 */
@Composable
fun PinGate(onPinVerified: () -> Unit, verifyPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockoutUntil by remember { mutableLongStateOf(0L) }
    var errorMessage by remember { mutableStateOf("") }

    val now = System.currentTimeMillis()
    val isLockedOut = now < lockoutUntil
    val lockoutRemaining = if (isLockedOut) (lockoutUntil - now) / MILLIS_PER_SECOND + 1 else 0

    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Enter PIN",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= MAX_PIN_LENGTH) pin = it },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = !isLockedOut,
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (isLockedOut) {
            Text(
                text = "Locked out. Try again in ${lockoutRemaining}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = {
                if (isLockedOut) return@Button
                if (verifyPin(pin)) {
                    onPinVerified()
                } else {
                    failedAttempts++
                    pin = ""
                    if (failedAttempts >= MAX_ATTEMPTS) {
                        val lockoutMs = BASE_LOCKOUT_MS shl (failedAttempts - MAX_ATTEMPTS)
                        lockoutUntil = System.currentTimeMillis() + lockoutMs
                        errorMessage = "Too many failed attempts"
                    } else {
                        errorMessage = "Incorrect PIN (${MAX_ATTEMPTS - failedAttempts} attempts remaining)"
                    }
                }
            },
            enabled = !isLockedOut && pin.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Unlock") }
    }
}

private const val MAX_ATTEMPTS = 5
private const val BASE_LOCKOUT_MS = 30_000
private const val MAX_PIN_LENGTH = 8
private const val MILLIS_PER_SECOND = 1000
