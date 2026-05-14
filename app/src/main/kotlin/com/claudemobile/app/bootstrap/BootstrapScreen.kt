package com.claudemobile.app.bootstrap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.claudemobile.app.R
import com.claudemobile.core.domain.bridge.BootstrapState
import com.claudemobile.core.domain.bridge.BootstrapStep
import kotlinx.coroutines.flow.collectLatest
import com.claudemobile.core.ui.R as CoreUiR

/**
 * Bootstrap screen displayed on first launch to set up the embedded Linux environment.
 *
 * Shows the current bootstrap step name, a progress indicator, download percentage,
 * and the most recent line of installer output. Handles failures with retry action
 * and provides guidance when storage space is insufficient.
 *
 * Step 6 (ai-provider-presets R11.4): after the five environment-setup steps complete,
 * the screen listens for a [BootstrapNavigationEvent.NavigateToProviderSelection] event
 * from the ViewModel and calls [onNavigateToProviderSelection] so the host nav graph can
 * push the provider selection screen. Once the user returns with a profile configured,
 * [BootstrapViewModel.onProviderConfigured] is called and bootstrap completes.
 *
 * Migration failure (R8 AC4): when [BootstrapViewModel.migrationFailureCause] is
 * non-null, a [MigrationFailedContent] card is shown above the bootstrap steps so the
 * user can retry the legacy-key migration independently.
 *
 * Requirements: 1.5, 1.6, 1.7, 8.4, 11.4
 */
@Composable
fun BootstrapScreen(
    viewModel: BootstrapViewModel = hiltViewModel(),
    onBootstrapComplete: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToProviderSelection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.bootstrapState.collectAsState()
    val latestProgress by viewModel.latestProgress.collectAsState()
    val lastOutputLine by viewModel.lastOutputLine.collectAsState()
    val configuringProvider by viewModel.configuringProvider.collectAsState()
    val migrationFailureCause by viewModel.migrationFailureCause.collectAsState()

    // Navigate away when bootstrap completes AND provider is configured
    LaunchedEffect(state, configuringProvider) {
        if (state is BootstrapState.Ready && !configuringProvider) {
            onBootstrapComplete()
        }
    }

    // Collect one-shot navigation events from the ViewModel
    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collectLatest { event ->
            when (event) {
                BootstrapNavigationEvent.NavigateToProviderSelection -> {
                    onNavigateToProviderSelection()
                }
            }
        }
    }

    // Start bootstrap on first composition
    LaunchedEffect(Unit) {
        viewModel.startBootstrap()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.bootstrap_heading),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.bootstrap_subheading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Migration failure surface (R8 AC4): shown above the bootstrap steps so the
        // user can retry the migration independently of the environment setup.
        migrationFailureCause?.let { cause ->
            MigrationFailedContent(
                cause = cause,
                onRetry = { viewModel.retryMigration() },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        when (val currentState = state) {
            is BootstrapState.NotStarted -> {
                val notStartedDescription = stringResource(R.string.bootstrap_not_started_description)
                Text(
                    text = stringResource(R.string.bootstrap_not_started),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = notStartedDescription
                    },
                )
            }

            is BootstrapState.InProgress -> {
                BootstrapProgressContent(
                    step = currentState.step,
                    progress = currentState.progress,
                    message = currentState.message,
                    lastOutputLine = lastOutputLine,
                    downloadPercent = latestProgress?.fraction,
                )
            }

            is BootstrapState.Ready -> {
                if (configuringProvider) {
                    // Step 6: environment is ready but provider not yet configured.
                    // Show the Configure Provider step label while the user is on the
                    // provider selection screen (or about to be navigated there).
                    BootstrapProgressContent(
                        step = BootstrapStep.CONFIGURE_PROVIDER,
                        progress = 0f,
                        message = "",
                        lastOutputLine = "",
                        downloadPercent = null,
                    )
                } else {
                    val readyDescription = stringResource(R.string.bootstrap_ready_description)
                    Text(
                        text = stringResource(R.string.bootstrap_ready),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics {
                            contentDescription = readyDescription
                        },
                    )
                }
            }

            is BootstrapState.Failed -> {
                BootstrapFailedContent(
                    step = currentState.step,
                    error = currentState.error,
                    requiredSpaceBytes = currentState.requiredSpaceBytes,
                    availableSpaceBytes = currentState.availableSpaceBytes,
                    onRetry = { viewModel.retry() },
                    onNavigateToSettings = onNavigateToSettings,
                )
            }
        }
    }
}

/**
 * Card shown when the legacy-key migration fails (R8 AC4). Displays the error message
 * and a "Retry" button that re-runs [AppMigrationCoordinator.runMigration].
 *
 * Requirements: 8.4.
 */
@Composable
private fun MigrationFailedContent(
    cause: Throwable,
    onRetry: () -> Unit,
) {
    val migrationFailedDescription = stringResource(CoreUiR.string.provider_migration_failed)
    val retryDescription = stringResource(CoreUiR.string.provider_migration_retry)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = migrationFailedDescription },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(CoreUiR.string.provider_migration_failed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = cause.message ?: cause.javaClass.simpleName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier.semantics { contentDescription = retryDescription },
            ) {
                Text(stringResource(CoreUiR.string.provider_migration_retry))
            }
        }
    }
}

@Composable
private fun BootstrapProgressContent(
    step: BootstrapStep,
    progress: Float,
    message: String,
    lastOutputLine: String,
    downloadPercent: Float?,
) {
    val stepName = stepDisplayName(step)
    val overallProgress = calculateOverallProgress(step, progress)
    val percentText = (overallProgress * 100).toInt()

    val progressDescription = stringResource(R.string.bootstrap_progress_description, stepName, percentText)
    val progressBarDescription = stringResource(R.string.bootstrap_progress_bar_description, percentText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = progressDescription
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Current step name
        Text(
            text = stepName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Overall progress bar
        LinearProgressIndicator(
            progress = { overallProgress },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = progressBarDescription
                },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Step counter and percentage
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.bootstrap_step_counter, stepIndex(step) + 1, totalSteps()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$percentText%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Step-local sub-progress (e.g. asset copy / tarball extraction percentage)
        if (downloadPercent != null && downloadPercent > 0f && downloadPercent < 1f) {
            Spacer(modifier = Modifier.height(12.dp))

            val downloadProgressDescription = stringResource(
                R.string.bootstrap_step_progress_description,
                (downloadPercent * 100).toInt()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    progress = { downloadPercent },
                    modifier = Modifier
                        .size(16.dp)
                        .semantics {
                            contentDescription = downloadProgressDescription
                        },
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.bootstrap_step_progress, (downloadPercent * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Most recent line of installer output
        AnimatedVisibility(
            visible = lastOutputLine.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val outputDescription = stringResource(R.string.bootstrap_installer_output_description, lastOutputLine)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = lastOutputLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = outputDescription
                        },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BootstrapFailedContent(
    step: BootstrapStep,
    error: String,
    requiredSpaceBytes: Long?,
    availableSpaceBytes: Long?,
    onRetry: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val isStorageError = requiredSpaceBytes != null && availableSpaceBytes != null

    val failedDescription = stringResource(
        R.string.bootstrap_failed_description,
        stepDisplayName(step),
        error
    )
    val retryDescription = stringResource(R.string.bootstrap_retry_description)
    val settingsDescription = stringResource(R.string.bootstrap_settings_description)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = failedDescription
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.bootstrap_failed_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.bootstrap_failed_at, stepDisplayName(step)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Error details card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp),
            )
        }

        // Storage space guidance
        if (isStorageError) {
            Spacer(modifier = Modifier.height(12.dp))

            StorageGuidanceCard(
                requiredSpaceBytes = requiredSpaceBytes!!,
                availableSpaceBytes = availableSpaceBytes!!,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Retry button
        Button(
            onClick = onRetry,
            modifier = Modifier.semantics {
                contentDescription = retryDescription
            },
        ) {
            Text(stringResource(R.string.bootstrap_retry_button))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Settings button
        OutlinedButton(
            onClick = onNavigateToSettings,
            modifier = Modifier.semantics {
                contentDescription = settingsDescription
            },
        ) {
            Text(stringResource(R.string.bootstrap_settings_button))
        }
    }
}

/**
 * Card showing storage space guidance when bootstrap fails due to insufficient space.
 */
@Composable
private fun StorageGuidanceCard(
    requiredSpaceBytes: Long,
    availableSpaceBytes: Long,
) {
    val requiredMb = requiredSpaceBytes / (1024 * 1024)
    val availableMb = availableSpaceBytes / (1024 * 1024)
    val needToFreeMb = maxOf(0, (requiredSpaceBytes - availableSpaceBytes) / (1024 * 1024))

    val storageDescription = stringResource(
        R.string.bootstrap_storage_guidance_description,
        requiredMb.toInt(),
        availableMb.toInt(),
        needToFreeMb.toInt()
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = storageDescription
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bootstrap_storage_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.bootstrap_storage_required, formatBytes(requiredSpaceBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.bootstrap_storage_available, formatBytes(availableSpaceBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.bootstrap_storage_free_space, formatBytes(requiredSpaceBytes - availableSpaceBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Returns a user-friendly display name for a bootstrap step.
 */
@Composable
private fun stepDisplayName(step: BootstrapStep): String = when (step) {
    BootstrapStep.EXTRACT_PREFIX -> stringResource(R.string.bootstrap_step_extract_prefix)
    BootstrapStep.VERIFY_PREFIX -> stringResource(R.string.bootstrap_step_verify_prefix)
    BootstrapStep.INSTALL_ROOTFS -> stringResource(R.string.bootstrap_step_install_rootfs)
    BootstrapStep.CONFIGURE_PROVIDER -> stringResource(R.string.bootstrap_step_configure_provider)
    BootstrapStep.VERIFY_ALL -> stringResource(R.string.bootstrap_step_verify_all)
    BootstrapStep.COMPLETE -> stringResource(R.string.bootstrap_step_complete)
}

/**
 * Returns the index of a bootstrap step (0-based), excluding COMPLETE.
 */
private fun stepIndex(step: BootstrapStep): Int = step.ordinal

/**
 * Returns the total number of actionable steps (excluding COMPLETE).
 */
private fun totalSteps(): Int = BootstrapStep.entries.size - 1

/**
 * Calculates overall progress (0.0 to 1.0) based on step and step-local progress.
 */
private fun calculateOverallProgress(step: BootstrapStep, stepProgress: Float): Float {
    val total = totalSteps()
    if (total <= 0) return 0f
    val stepWeight = 1.0f / total
    val completedSteps = step.ordinal
    return (completedSteps * stepWeight + stepProgress * stepWeight).coerceIn(0f, 1f)
}

/**
 * Formats byte count into a human-readable string (MB or GB).
 */
private fun formatBytes(bytes: Long): String {
    val absMb = kotlin.math.abs(bytes) / (1024.0 * 1024.0)
    return if (absMb >= 1024) {
        "%.1f GB".format(absMb / 1024.0)
    } else {
        "%.0f MB".format(absMb)
    }
}
