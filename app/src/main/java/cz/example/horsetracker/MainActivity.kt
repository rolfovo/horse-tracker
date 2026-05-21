package cz.example.horsetracker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.mapbox.mapboxsdk.Mapbox
import cz.example.horsetracker.permissions.PermissionRepository
import cz.example.horsetracker.ride.RideRepository
import cz.example.horsetracker.ui.App

class MainActivity : ComponentActivity() {
    private val requestLocationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            PermissionRepository.refresh(this)
        }
    private val requestBackgroundLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            PermissionRepository.refresh(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Mapbox.getInstance(applicationContext)

        PermissionRepository.refresh(this)
        RideRepository.init(applicationContext)

        setContent {
            MaterialTheme {
                Surface {
                    App(
                        onRequestLocationPermission = {
                            requestLocationPermissions.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onRequestBackgroundLocationPermission = {
                            when {
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                                    PermissionRepository.refresh(this)
                                }

                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                    startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:$packageName")
                                        },
                                    )
                                }

                                else -> {
                                    requestBackgroundLocationPermission.launch(
                                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PermissionRepository.refresh(this)
    }
}
