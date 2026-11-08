package com.team404bnf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState

import com.team404bnf.app.data.db.AppDatabase
import com.team404bnf.app.data.repository.ConsentRepository
import com.team404bnf.app.data.repository.ConsentStatus
import com.team404bnf.app.data.repository.RecordingRepository
import com.team404bnf.app.data.repository.TranscriptRepository
import com.team404bnf.app.playback.PlaybackViewModel
import com.team404bnf.app.ui.library.LibraryScreen
import com.team404bnf.app.ui.library.LibraryViewModel
import com.team404bnf.app.ui.onboarding.ConsentViewModel
import com.team404bnf.app.ui.onboarding.OnboardingScreen
import com.team404bnf.app.ui.playback.PlaybackScreen
import com.team404bnf.app.ui.recorder.RecorderScreen
import com.team404bnf.app.ui.recorder.RecorderViewModel
import com.team404bnf.app.ui.theme.BnfTheme

class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        com.team404bnf.app.models.RecognitionModels.get(applicationContext).refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // База данных
        val database = AppDatabase.getInstance(applicationContext)
        com.team404bnf.app.transcription.TranscriptionTaskManager.initDatabase(applicationContext)

        val repository = RecordingRepository(
            database.recordingDao(),
            applicationContext
        )

        val transcriptRepository = TranscriptRepository(database, repository)

        // ViewModel библиотеки
        val libraryViewModel = ViewModelProvider(
            this,
            LibraryViewModel.Factory(repository, transcriptRepository)
        )[LibraryViewModel::class.java]

        // ViewModel диктофона
        val recorderViewModel = ViewModelProvider(
            this,
            RecorderViewModel.Factory(
                applicationContext
            )
        )[RecorderViewModel::class.java]

        // ViewModel пользовательского соглашения
        val consentViewModel = ViewModelProvider(
            this,
            ConsentViewModel.Factory(
                ConsentRepository(applicationContext)
            )
        )[ConsentViewModel::class.java]

        setContent {
            BnfTheme {

                val consentStatus by consentViewModel.status
                    .collectAsState()

                when (consentStatus) {

                    ConsentStatus.LOADING -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    ConsentStatus.REQUIRED -> {

                        val saving by consentViewModel.saving
                            .collectAsState()

                        val error by consentViewModel.errorMessage
                            .collectAsState()

                        OnboardingScreen(
                            saving = saving,
                            errorMessage = error,
                            onAccept = {
                                consentViewModel.accept()
                            }
                        )
                    }

                    ConsentStatus.ERROR -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Ошибка загрузки настроек приложения")
                        }
                    }

                    ConsentStatus.ACCEPTED -> RecognitionNavigation(
                        libraryViewModel, recorderViewModel, repository, transcriptRepository
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RecognitionNavigation(
    libraryViewModel: LibraryViewModel,
    recorderViewModel: RecorderViewModel,
    repository: RecordingRepository,
    transcriptRepository: TranscriptRepository
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val models = remember(context) { com.team404bnf.app.models.RecognitionModels.get(context) }
    val modelState by models.state.collectAsState()
    val pendingId by models.pendingRecordingId.collectAsState()
    val savedRecordings by libraryViewModel.savedRecordings.collectAsState(initial = emptyList())
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val tasks by com.team404bnf.app.transcription.TranscriptionTaskManager.tasksFlow.collectAsState()
    val waitingKey = tasks.values.filter {
        it.status == com.team404bnf.app.transcription.TranscriptionTaskStatus.WAITING_FOR_MODELS
    }.sortedBy { it.recordingId }.joinToString("|") { "${it.recordingId}:${it.attemptId}:${it.updatedAtEpochMs}" }
    var acknowledgedModelsWarning by rememberSaveable { mutableStateOf<String?>(null) }

    fun toLibrary() {
        if (!navController.popBackStack("library", false)) navController.navigate("library") {
            popUpTo(navController.graph.id) { inclusive = false }
            launchSingleTop = true
        }
    }
    fun openPlayer(id: String, processing: Boolean = false, removeRoute: String? = null) {
        navController.navigate("player/$id?processing=$processing") {
            if(removeRoute != null) popUpTo(removeRoute) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = "startup") {
        composable("startup") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Проверяем офлайн-модели…")
                }
            }
            androidx.compose.runtime.LaunchedEffect(modelState.phase, pendingId) {
                if (!modelState.busy) navController.navigate(
                    if (modelState.ready && pendingId == null) "library" else "models"
                ) { popUpTo("startup") { inclusive = true }; launchSingleTop = true }
            }
        }
        composable("models") {
            com.team404bnf.app.models.ModelsScreen(models, onBack = ::toLibrary)
        }
        composable("library") {
            val deleting by libraryViewModel.deleting.collectAsState()
            val deleteError by libraryViewModel.deleteError.collectAsState()
            val importing by libraryViewModel.importing.collectAsState()
            val importError by libraryViewModel.importError.collectAsState()
            LibraryScreen(
                savedRecordings = savedRecordings,
                onNewRecording = {
                    recorderViewModel.prepareNewRecording()
                    navController.navigate("recorder") { launchSingleTop = true }
                },
                onSavedRecordingOpen = { openPlayer(it) },
                deleting = deleting, deleteError = deleteError,
                onDeleteRecordings = libraryViewModel::deleteRecordings,
                importing = importing, importError = importError,
                onImportAudio = libraryViewModel::importAudio,
                onManageModels = { navController.navigate("models") { launchSingleTop = true } }
            )
        }
        composable("recorder") {
            RecorderScreen(recorderViewModel, onBack = { navController.popBackStack() }, onSaved = { id ->
                openPlayer(id, processing = true, removeRoute = "recorder")
            })
        }
        composable("player/{recordingId}?processing={processing}", arguments = listOf(
            androidx.navigation.navArgument("processing") {
                type = androidx.navigation.NavType.BoolType; defaultValue = false
            }
        )) { entry ->
            val recordingId = entry.arguments?.getString("recordingId").orEmpty()
            val viewModel = remember(entry) {
                ViewModelProvider(entry, PlaybackViewModel.Factory(context, repository,
                    recordingId, transcriptRepository))[PlaybackViewModel::class.java]
            }
            PlaybackScreen(viewModel, onBack = ::toLibrary,
                openProcessing = entry.arguments?.getBoolean("processing") == true,
                onManageModels = {
                    models.launchTranscription(recordingId)
                    navController.navigate("models") { launchSingleTop = true }
                })
        }
    }

    if (waitingKey.isNotEmpty() && waitingKey != acknowledgedModelsWarning &&
        modelState.phase == com.team404bnf.app.models.ModelPhase.MISSING && backStackEntry != null &&
        backStackEntry?.destination?.route !in setOf("models", "startup", "recorder")) {
        AlertDialog(
            onDismissRequest = { acknowledgedModelsWarning = waitingKey },
            title = { Text("Нужна модель распознавания") },
            text = { Text("${com.team404bnf.app.transcription.TranscriptionTaskManager.MODELS_REQUIRED_MESSAGE}.") },
            confirmButton = {
                TextButton(onClick = {
                    acknowledgedModelsWarning = waitingKey
                    navController.navigate("models") { launchSingleTop = true }
                }) { Text("Скачать модель") }
            },
            dismissButton = {
                TextButton(onClick = { acknowledgedModelsWarning = waitingKey }) { Text("Позже") }
            }
        )
    }
}
