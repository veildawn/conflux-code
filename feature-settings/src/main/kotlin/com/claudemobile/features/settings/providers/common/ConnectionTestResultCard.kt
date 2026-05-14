package com.claudemobile.features.settings.providers.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.claudemobile.core.domain.providers.ConnectionTestOutcome
import com.claudemobile.core.domain.providers.ConnectionTestResult
import com.claudemobile.core.ui.R as CoreUiR

/**
 * Shared rendering of a [ConnectionTestResult] used by the provider
 * editor (inline below the form) and the provider list (snackbar /
 * detail surface).
 *
 * Each of the six [ConnectionTestOutcome] variants maps to a localized
 * label (`provider_test_*` strings in `core-ui`). The card additionally
 * renders the [ConnectionTestResult.userReason] verbatim when it adds
 * detail beyond the outcome label.
 *
 * The composable is purely presentational: it never reaches into the
 * profile, never exposes the API key, and is safe to invoke from any
 * surface that already holds a classified result.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.6, 7.7.
 */
@Composable
public fun ConnectionTestResultCard(
    result: ConnectionTestResult,
    modifier: Modifier = Modifier,
) {
    val outcomeText = stringResourceForOutcome(result.outcome)
    val isOk = result.outcome == ConnectionTestOutcome.Ok
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CONNECTION_TEST_RESULT_TAG),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = outcomeText,
                style = MaterialTheme.typography.titleSmall,
                color = if (isOk) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (result.userReason.isNotBlank() && result.userReason != outcomeText) {
                Text(
                    text = result.userReason,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Localized label for a [ConnectionTestOutcome].
 *
 * Public so callers that need only the label (e.g. snackbar text in a
 * list screen) can render it without introducing the full card.
 */
@Composable
public fun stringResourceForOutcome(outcome: ConnectionTestOutcome): String = when (outcome) {
    ConnectionTestOutcome.Ok -> stringResource(CoreUiR.string.provider_test_ok)
    ConnectionTestOutcome.Unauthorized -> stringResource(CoreUiR.string.provider_test_unauthorized)
    ConnectionTestOutcome.Unreachable -> stringResource(CoreUiR.string.provider_test_unreachable)
    ConnectionTestOutcome.InvalidUrl -> stringResource(CoreUiR.string.provider_test_invalid_url)
    ConnectionTestOutcome.InvalidModel -> stringResource(CoreUiR.string.provider_test_invalid_model)
    ConnectionTestOutcome.UnknownError -> stringResource(CoreUiR.string.provider_test_unknown_error)
}

/** Test tag for the shared card so both editor and list screens can find it. */
public const val CONNECTION_TEST_RESULT_TAG: String = "provider_connection_test_result"
