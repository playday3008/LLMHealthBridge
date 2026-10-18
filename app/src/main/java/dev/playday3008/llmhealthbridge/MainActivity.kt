package dev.playday3008.llmhealthbridge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import kotlinx.coroutines.launch

import dev.playday3008.llmhealthbridge.entry.Section
import dev.playday3008.llmhealthbridge.send.RequestSender
import dev.playday3008.llmhealthbridge.send.startShare
import dev.playday3008.llmhealthbridge.ui.AppViewModel
import dev.playday3008.llmhealthbridge.ui.BlockedScreen
import dev.playday3008.llmhealthbridge.ui.HomeActions
import dev.playday3008.llmhealthbridge.ui.HomeScreen
import dev.playday3008.llmhealthbridge.ui.ReviewActions
import dev.playday3008.llmhealthbridge.ui.ReviewScreen
import dev.playday3008.llmhealthbridge.ui.Screen
import dev.playday3008.llmhealthbridge.ui.theme.LLMHealthBridgeTheme
import dev.playday3008.llmhealthbridge.ui.toggleSection

/** Single activity: sends photos to an LLM app, receives shared replies and hosts the screens. */
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var cameraUri: Uri? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = cameraUri
            if (ok && uri != null) viewModel.sendPhotos(listOf(uri))
        }

    private lateinit var requestPermission: ActivityResultLauncher<Set<String>>

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(RequestSender.MAX_PHOTOS)
    ) { uris -> if (uris.isNotEmpty()) viewModel.sendPhotos(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermission = registerForActivityResult(viewModel.permissionContract()) {
            viewModel.onPermissionResult(it)
        }
        cameraUri = savedInstanceState?.getString(STATE_CAMERA_URI)?.let(Uri::parse)
        if (savedInstanceState == null) handleShare(intent)
        collectEvents()
        setContent { LLMHealthBridgeTheme { Root() } }
    }

    /** Starts prepared shares and permission requests with this instance's launchers. */
    private fun collectEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.shares.collect { share ->
                        try {
                            startShare(this@MainActivity, share)
                        } catch (e: ActivityNotFoundException) {
                            viewModel.onShareFailed(e)
                        } catch (e: SecurityException) {
                            viewModel.onShareFailed(e)
                        }
                    }
                }
                viewModel.permissionRequests.collect { requestPermission.launch(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAMERA_URI, cameraUri?.toString())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) viewModel.onWindowFocused()
    }

    /** Opens a shared reply: shared text, or a shared text file. */
    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        when {
            text != null -> viewModel.receiveSharedText(text)
            stream != null -> viewModel.receiveSharedFile(stream)
            else -> viewModel.showMessage(getString(R.string.shared_nothing))
        }
    }

    private fun launchCamera() {
        val uri = viewModel.cameraUri()
        cameraUri = uri
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            viewModel.showMessage(getString(R.string.no_camera_app))
        }
    }

    @Composable
    private fun Root() {
        val screen by viewModel.screen.collectAsState()
        val home by viewModel.home.collectAsState()
        val saving by viewModel.saving.collectAsState()
        val saveBlock by viewModel.saveBlock.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        var openSection by rememberSaveable { mutableStateOf<Section?>(Section.NUTRITION) }
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { snackbar.showSnackbar(it) }
            }
        }
        BackHandler(enabled = screen !is Screen.Home) { viewModel.backToHome() }

        val actions = HomeActions(
            onSelectApp = viewModel::selectApp,
            onTakePhoto = ::launchCamera,
            onPickPhotos = {
                pickPhotos.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDescribe = viewModel::describe,
            onPaste = viewModel::paste,
            onOpenBanner = viewModel::openBanner,
            onDismissBanner = viewModel::dismissBanner,
            onOpenGroup = viewModel::openGroup,
            onDeleteGroup = viewModel::deleteGroup,
            onToggleSection = { openSection = toggleSection(openSection, it) },
            onRetryRecent = viewModel::refreshRecent
        )
        val reviewActions = ReviewActions(
            onUpdate = viewModel::updateReview,
            onSave = viewModel::save,
            onOpenSettings = {
                startSafely(viewModel.permissionSettingsIntents(), R.string.cannot_open_settings)
            },
            onInstall = {
                startSafely(listOf(viewModel.installIntent()), R.string.cannot_open_store)
            }
        )
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            when (val current = screen) {
                Screen.Home -> HomeScreen(home, openSection, actions, padding)
                is Screen.Review -> ReviewScreen(
                    review = current.review,
                    saving = saving,
                    saveBlock = saveBlock,
                    actions = reviewActions,
                    contentPadding = padding
                )
                is Screen.Blocked -> BlockedScreen(current.result, viewModel::backToHome, padding)
            }
        }
    }

    /** Starts the first of [intents] that can be started, or reports [failure]. */
    private fun startSafely(intents: List<Intent>, failure: Int) {
        val started = intents.any { intent ->
            try {
                startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        if (!started) viewModel.showMessage(getString(failure))
    }

    private companion object {
        const val STATE_CAMERA_URI = "cameraUri"
    }
}
