@file:OptIn(ExperimentalMaterial3Api::class)

package com.oursprivacy.oursprivacydemo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.oursprivacy.android.opmetrics.OursPrivacyAPI
import com.oursprivacy.android.opmetrics.OursPrivacyInitOptions
import com.oursprivacy.android.opmetrics.OursPrivacyUserProperties
import org.json.JSONObject

@Composable
fun TrackingPage(navController: NavHostController) {
    val showDialog = remember { mutableStateOf(false) }
    val dialogMessage = remember { mutableStateOf("") }
    val context = LocalContext.current
    val op = remember {
        OursPrivacyAPI(context.applicationContext).also {
            it.initialize(
                OURSPRIVACY_PROJECT_TOKEN,
                OursPrivacyInitOptions.builder().trackAutomaticEvents(true).build()
            )
        }
    }

    val trackingActions = listOf(
        Triple("Track event", "Tracks 'demo_event' with one event property.", {
            op.track("demo_event", JSONObject(mapOf("source" to "demo")))
            op.flush()
        }),
        Triple("Identify", "Identifies via externalId + email.", {
            op.identify(
                OursPrivacyUserProperties.builder()
                    .externalId("demo_user_1")
                    .email("demo@example.com")
                    .build()
            )
            op.flush()
        }),
        Triple("Track + per-call user props", "track('view_item') with phone_number on userProperties.", {
            op.track(
                "view_item",
                JSONObject(mapOf("sku" to "ABC-001")),
                OursPrivacyUserProperties.builder()
                    .phoneNumber("+1-555-0100")
                    .build()
            )
            op.flush()
        }),
        Triple("Deep link", "Parses UTMs and click IDs from a sample URL.", {
            op.trackDeepLink(
                "https://example.com/landing?utm_source=demo&utm_medium=android&gclid=demoGclid"
            )
            op.flush()
        })
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Tracking Calls") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(trackingActions) { (title, message, action) ->
                TrackingButton(title, message, showDialog, dialogMessage, onButtonClick = action)
            }
        }

        if (showDialog.value) {
            AlertDialog(
                onDismissRequest = { showDialog.value = false },
                title = { Text(text = "Tracking Event") },
                text = { Text(dialogMessage.value) },
                confirmButton = {
                    Button(onClick = { showDialog.value = false }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}
