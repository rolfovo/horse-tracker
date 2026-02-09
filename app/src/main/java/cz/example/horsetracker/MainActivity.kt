package cz.example.horsetracker

import android.Manifest
import android.os.Bundle
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
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            PermissionRepository.refresh(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Mapbox.getInstance(applicationContext)

        PermissionRepository.refresh(this)
        RideRepository.init(this)

        setContent {
            MaterialTheme {
                Surface {
                    App(
                        onRequestLocationPermission = {
                            requestPermissions.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
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
