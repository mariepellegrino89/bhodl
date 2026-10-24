package io.bhodl.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.android_placeholder_body
import io.bhodl.shared.resources.app_name
import org.jetbrains.compose.resources.stringResource

/**
 * Android entry point for BHODL.
 *
 * The Phase-1 demo wallet currently runs on Compose for Desktop only; the
 * Android module is here so the KMP `:shared` builds for Android target,
 * the cdylib `libbhodl_uniffi.so` is packaged into the APK, and a future
 * Phase-2 commit can drop in the same Compose composables. Until then the
 * activity shows a placeholder.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlaceholderScreen()
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen() {
    val title = stringResource(Res.string.app_name)
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(Res.string.android_placeholder_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
