package com.team404bnf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // База данных
        val database = AppDatabase.getInstance(applicationContext)
        val repository = RecordingRepository(
            database.recordingDao(),
            applicationContext
        )

        // ViewModel библиотеки
        val libraryViewModel = ViewModelProvider(
            this,
            LibraryViewModel.Factory(repository)
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

                    ConsentStatus.ACCEPTED -> {

                        val savedRecordings by libraryViewModel
                            .savedRecordings
                            .collectAsState(initial = emptyList())

                        val navController = rememberNavController()

                        NavHost(
                            navController = navController,
                            startDestination = "library"
                        ) {

                            composable("library") {
                                val deleting by libraryViewModel.deleting.collectAsState()

                                val deleteError by libraryViewModel.deleteError.collectAsState()

                                val importing by libraryViewModel.importing.collectAsState()

                                val importError by libraryViewModel.importError.collectAsState()

                                LibraryScreen(
                                    savedRecordings = savedRecordings,

                                    onNewRecording = {
                                        navController.navigate("recorder")
                                    },

                                    onSavedRecordingOpen = { recordingId ->
                                        navController.navigate(
                                            "player/$recordingId"
                                        )
                                    },

                                    deleting = deleting,

                                    deleteError = deleteError,

                                    onDeleteRecordings = { ids ->
                                        libraryViewModel.deleteRecordings(ids)
                                    },

                                    importing = importing,

                                    importError = importError,

                                    onImportAudio = { uri ->
                                        libraryViewModel.importAudio(uri)
                                    }
                                )
                            }

                            composable("recorder") {
                                RecorderScreen(
                                    viewModel = recorderViewModel,

                                    onBack = {
                                        navController.popBackStack()
                                    }
                                )
                            }

                            composable("player/{recordingId}") { backStackEntry ->

                                val recordingId = backStackEntry.arguments
                                    ?.getString("recordingId")
                                    .orEmpty()

                                val transcriptRepository = remember {
                                    TranscriptRepository(
                                        database = database,
                                        recordingRepository = repository
                                    )
                                }

                                val playbackViewModel = remember(backStackEntry) {

                                    ViewModelProvider(
                                        backStackEntry,

                                        PlaybackViewModel.Factory(
                                            applicationContext,
                                            repository,
                                            recordingId,
                                            transcriptRepository
                                        )

                                    )[PlaybackViewModel::class.java]
                                }

                                PlaybackScreen(
                                    viewModel = playbackViewModel,

                                    onBack = {
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}