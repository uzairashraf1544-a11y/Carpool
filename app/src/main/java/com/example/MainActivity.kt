package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.example.service.CommuteReminderScheduler
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.TravelBuddyTheme

class MainActivity : ComponentActivity() {

    private var openCommuteSlotId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        openCommuteSlotId = intent?.getStringExtra("open_commute_slot_id")

        setContent {
            TravelBuddyTheme {
                val context = LocalContext.current
                NotificationPermissionPrompt { isGranted ->
                    if (isGranted) {
                        CommuteReminderScheduler.reconcileAllReminders(context)
                    }
                }
                AppNavigation(openCommuteSlotId = openCommuteSlotId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openCommuteSlotId = intent.getStringExtra("open_commute_slot_id")
    }
}

@Composable
private fun NotificationPermissionPrompt(
    onPermissionResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        var hasRequested by rememberSaveable { mutableStateOf(false) }
        var showPromptDialog by remember { mutableStateOf(!hasPermission && !hasRequested) }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasRequested = true
            showPromptDialog = false
            onPermissionResult(isGranted)
        }

        if (showPromptDialog && !hasPermission) {
            AlertDialog(
                onDismissRequest = {
                    hasRequested = true
                    showPromptDialog = false
                    onPermissionResult(false)
                },
                title = { Text("Allow Notifications", fontWeight = FontWeight.Bold) },
                text = {
                    Text("Allow notifications so TravelBuddy can remind you about your commute and ride updates.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPromptDialog = false
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    ) {
                        Text("ALLOW NOTIFICATIONS")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            hasRequested = true
                            showPromptDialog = false
                            onPermissionResult(false)
                        }
                    ) {
                        Text("NOT NOW")
                    }
                }
            )
        }
    }
}
