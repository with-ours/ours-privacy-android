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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.oursprivacy.android.opmetrics.OursPrivacyAPI


@Composable
fun GDPRPage(navController: NavHostController, op: OursPrivacyAPI?) {
    if (op == null) {
        Text("Set OURSPRIVACY_TOKEN in local.properties to use tracking.")
        return
    }

    val showDialog = remember { mutableStateOf(false) }
    val dialogMessage = remember { mutableStateOf("") }
    val gdprActions = listOf(
        Triple("Opt Out", "Clears queued events; suppresses future tracks.", {
            op.optOutTracking()
        }),
        Triple("Opt In", "Resumes tracking and fires \$opt_in.", {
            op.optInTracking()
        }),
        Triple("Has opted out?", "Logs the persisted opt-out state.", {
            println("hasOptedOutTracking = ${op.hasOptedOutTracking()}")
        })
    )


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "GDPR Calls") },
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
            items(gdprActions) { (title, message, action) ->
                TrackingButton(title, message, showDialog, dialogMessage, onButtonClick = action)
            }
        }

        if (showDialog.value) {
            AlertDialog(
                onDismissRequest = { showDialog.value = false },
                title = { Text(text = "GDPR Event") },
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
