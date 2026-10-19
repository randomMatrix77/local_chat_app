package com.kronos.offlinechat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.kronos.offlinechat.inference.InferenceStatus
import com.kronos.offlinechat.inference.DeviceCapabilities
import com.kronos.offlinechat.theme.OfflineChatTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OfflineChatEmulatorUiTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun emulatorUi_explicitlyBlocksInferenceWithoutInventingAResponse() {
    assertTrue("The test device must be detected as an emulator", DeviceCapabilities.isEmulator())
    composeRule.setContent {
      OfflineChatTheme {
        ChatScreen(
          state = OfflineChatUiState(inferenceStatus = InferenceStatus.EmulatorUnsupported),
          onDraftChanged = {},
          onSubmit = {},
        )
      }
    }

    composeRule.onNodeWithTag("inference_status").assertIsDisplayed()
    composeRule.onNodeWithText("Inference is unavailable on an Android emulator.", substring = true).assertIsDisplayed()
  }
}
