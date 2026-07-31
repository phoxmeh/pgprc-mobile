package net.packetradio.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.packetradio.mobile.service.PacketRadioService
import net.packetradio.mobile.ui.heard.HeardStationDetailScreen
import net.packetradio.mobile.ui.heard.HeardStationsScreen
import net.packetradio.mobile.ui.notifications.NotificationDetailScreen
import net.packetradio.mobile.ui.notifications.NotificationsScreen
import net.packetradio.mobile.ui.session.SessionScreen
import net.packetradio.mobile.ui.settings.SettingsScreen
import net.packetradio.mobile.ui.theme.PgprcMobileTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    // Lets the "Quit" action on the service's notification close this Activity
    // too, even though the Service has no direct handle back to it.
    private val quitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ContextCompat.registerReceiver(
            this,
            quitReceiver,
            IntentFilter(PacketRadioService.ACTION_QUIT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            PgprcMobileTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = "session") {
                    composable("session") {
                        SessionScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenHeardStations = { navController.navigate("heard_stations") },
                            onOpenNotifications = { navController.navigate("notifications") },
                            onQuit = { finishAndRemoveTask() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("heard_stations") {
                        HeardStationsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenStation = { callsign -> navController.navigate("heard_stations/$callsign") },
                        )
                    }
                    composable(
                        "heard_stations/{callsign}",
                        arguments = listOf(navArgument("callsign") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val callsign = backStackEntry.arguments?.getString("callsign").orEmpty()
                        HeardStationDetailScreen(callsign = callsign, onBack = { navController.popBackStack() })
                    }
                    composable("notifications") {
                        NotificationsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenPacket = { id -> navController.navigate("notifications/$id") },
                        )
                    }
                    composable(
                        "notifications/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { backStackEntry ->
                        val id = backStackEntry.arguments?.getLong("id") ?: 0L
                        NotificationDetailScreen(packetId = id, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    // singleTask launchMode routes a relaunch (icon tap, notification tap) back into this
    // same instance via onNewIntent instead of the system stacking a second one on top.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(quitReceiver)
        super.onDestroy()
    }
}
