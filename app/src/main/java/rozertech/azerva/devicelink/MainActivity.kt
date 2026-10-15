package rozertech.azerva.devicelink

import android.app.Activity
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import rozertech.azerva.devicelink.core.util.PayloadCodec
import rozertech.azerva.devicelink.presentation.DeviceScreen
import rozertech.azerva.devicelink.presentation.DeviceTab
import rozertech.azerva.devicelink.presentation.DeviceViewModel
import rozertech.azerva.devicelink.ui.theme.DeviceLinkTheme
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the Compose UI and controls NFC reader mode through the Activity lifecycle.
 *
 * No Bluetooth or database operation is performed by the Activity itself.
 * The ViewModel survives configuration changes; reader mode is stopped on pause.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Creates the main screen and registers the foreground NFC reader effect. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val model: DeviceViewModel = hiltViewModel()
            val state by model.uiState.collectAsStateWithLifecycle()
            val nfcReady = rememberNfcReady(this@MainActivity)

            NfcReaderLifecycle(
                activity = this@MainActivity,
                enabled = state.tab == DeviceTab.NFC && !state.isDemoEnabled,
                onTag = model::onTag,
                onError = model::notice
            )

            DeviceLinkTheme {
                DeviceScreen(
                    model = model,
                    state = state,
                    nfcReady = nfcReady
                )
            }
        }
    }
}

/**
 * Observes NFC availability when the Activity enters the foreground.
 *
 * NFC hardware may be absent or disabled by the user while the app is paused.
 * A platform settings change is reflected on the next resume.
 */
@Composable
private fun rememberNfcReady(activity: Activity): Boolean {
    val owner = LocalLifecycleOwner.current
    var ready by remember(activity) { mutableStateOf(false) }

    DisposableEffect(owner, activity) {
        fun update() {
            ready = try {
                NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true
            } catch (_: UnsupportedOperationException) {
                false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) update()
        }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return ready
}

/**
 * Activates Android NFC reader mode only while the NFC tab is visible and
 * the Activity is resumed; stops immediately on pause, navigation or demo mode.
 *
 * This is read-only. It decodes NDEF text records without opening URIs or
 * executing content from a tag.
 *
 * @param activity Foreground Android Activity owning reader mode.
 * @param enabled Whether the selected tab is NFC and demo mode is disabled.
 * @param onTag Receives a bounded UID and optional bounded NDEF text.
 * @param onError Reports reader configuration failures to the ViewModel.
 */
@Composable
private fun NfcReaderLifecycle(
    activity: Activity,
    enabled: Boolean,
    onTag: (String, String?) -> Unit,
    onError: (String) -> Unit
) {
    val owner = LocalLifecycleOwner.current
    val latestTagCallback by rememberUpdatedState(onTag)
    val latestErrorCallback by rememberUpdatedState(onError)

    DisposableEffect(activity, owner, enabled) {
        val adapter = try {
            NfcAdapter.getDefaultAdapter(activity)
        } catch (_: UnsupportedOperationException) {
            null
        }
        val acceptingTags = AtomicBoolean(false)
        var readerActive = false

        fun enableReader() {
            val hardware = adapter ?: return
            if (!enabled || readerActive || !hardware.isEnabled) return
            acceptingTags.set(true)
            try {
                // Do NOT use FLAG_READER_SKIP_NDEF_CHECK: Ndef.get(tag) needs it.
                hardware.enableReaderMode(
                    activity,
                    NfcAdapter.ReaderCallback { tag ->
                        if (!acceptingTags.get()) return@ReaderCallback
                        val uid = PayloadCodec.bytesToHex(tag.id).take(64)
                        val text = readNdefText(tag)
                        if (acceptingTags.get()) latestTagCallback(uid, text)
                    },
                    NfcAdapter.FLAG_READER_NFC_A or
                            NfcAdapter.FLAG_READER_NFC_B or
                            NfcAdapter.FLAG_READER_NFC_F or
                            NfcAdapter.FLAG_READER_NFC_V,
                    null
                )
                readerActive = true
            } catch (error: Exception) {
                acceptingTags.set(false)
                latestErrorCallback(error.message ?: "Unable to enable NFC reader")
            }
        }

        fun disableReader() {
            acceptingTags.set(false)
            if (!readerActive) return
            readerActive = false
            try {
                adapter?.disableReaderMode(activity)
            } catch (_: Exception) {
                // NFC hardware may have been switched off before cleanup.
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> enableReader()
                Lifecycle.Event.ON_PAUSE -> disableReader()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            enableReader()
        }
        onDispose {
            disableReader()
            owner.lifecycle.removeObserver(observer)
        }
    }


}

/**
 * Reads at most one NDEF well-known text record from a physical NFC tag.
 *
 * Limits payload size before decoding and returns null for unsupported,
 * malformed or inaccessible records. URI/action records are ignored.
 *
 * @param tag Tag supplied by the platform NFC reader callback.
 * @return Decoded text (up to 200 characters), or null if unavailable.
 */
private fun readNdefText(tag: Tag): String? {
    val ndef = Ndef.get(tag) ?: return null
    return try {
        ndef.connect()
        try {
            val record = ndef.ndefMessage?.records?.firstOrNull {
                it.tnf == NdefRecord.TNF_WELL_KNOWN &&
                        it.type.contentEquals(NdefRecord.RTD_TEXT)
            } ?: return null

            val payload = record.payload
            // Avoid unbounded allocation from a malformed or unexpectedly large tag.
            if (payload.size !in 2..4096) return null

            val header = payload[0].toInt() and 0xFF
            val languageSize = header and 0x3F
            if (1 + languageSize >= payload.size) return null

            val charset = if (header and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
            String(
                payload,
                1 + languageSize,
                payload.size - 1 - languageSize,
                charset
            ).take(200)
        } finally {
            ndef.close()
        }
    } catch (_: Exception) {
        null
    }
}

