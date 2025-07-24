package com.matanh.transfer

import android.content.Context
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import com.matanh.transfer.ui.SetupActivity
import com.matanh.transfer.util.Constants
import com.matanh.transfer.util.FileAdapter
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.awaitility.kotlin.await
import org.junit.Assume.assumeTrue


/**
 * Full end-to-end integration test for the Transfer app.
 * This test covers the following flow:
 * 1.  Launches the app and handles the initial `SetupActivity`.
 * 2.  Uses UI Automator to interact with the system folder picker.
 * 3.  It checks if a "Storage" folder exists. If not, it creates it using the picker's UI.
 * 4.  It selects the "Storage" folder.
 * 5.  Waits for `MainActivity` to launch and the `FileServerService` to start.
 * 6.  Verifies the server status and IP address are correctly displayed.
 * 7.  Uses an HTTP client (OkHttp) to upload a file to the running server.
 * 8.  Verifies the uploaded file appears in the `RecyclerView`.
 * 9.  Uses Espresso to delete the file via the UI.
 * 11. Uses the HTTP client to confirm the file is also deleted from the server.
 *
 * NOTE: This test requires API level 29+ to reliably interact with the scoped storage picker UI.
 * It is also recommended to run this on an emulator with network access enabled.
 * This test will create a "Storage" folder in the root of the device's internal storage if it doesn't exist.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppFlowTest {

    // Rule to launch the initial activity of the app.
    @get:Rule
    val activityRule = ActivityScenarioRule(SetupActivity::class.java)

    // Rule to grant necessary permissions before the test runs.
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    private lateinit var device: UiDevice
    private val testFolderName = "Storage"
    private val testFileName = "test_upload.txt"
    private val testFileContent = "This is a file for integration testing."
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val UI_AUTOMATOR_TIMEOUT = 5000L
        private var serverUrl: String? = null

        // Clear shared preferences before the test suite runs to ensure a clean state
        @BeforeClass
        @JvmStatic
        fun setupClass() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
        }
    }


    @Before
    fun setUp() {
        // Initialize UI Automator
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
    }

    @Test
    fun testA_initialSetupAndFolderSelection() {
        // --- Step 1: Handle SetupActivity and Folder Picker ---
        // The app starts on SetupActivity, we need to select a folder.
        onView(withId(R.id.btnChooseFolder)).check(matches(isDisplayed()))
        onView(withId(R.id.btnChooseFolder)).perform(click())

        // --- UI Automator part to handle the system folder picker ---
        // Wait for the folder picker to appear.
        device.wait(Until.hasObject(By.textContains("Choose a folder")), UI_AUTOMATOR_TIMEOUT)

        // Check if the "Storage" folder already exists.
        var storageFolder = device.findObject(By.text(testFolderName))

        if (storageFolder == null) {
            // If it doesn't exist, create it.
            // The "Create new folder" button can have different descriptions or resource IDs.
            val createFolderButton = device.findObject(
                By.clazz("android.widget.Button").text("CREATE NEW FOLDER")
            )
            Assert.assertNotNull("Could not find 'CREATE NEW FOLDER' button.", createFolderButton)
            createFolderButton.click()

            // Wait for the dialog and enter the folder name.
            val editText = device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_AUTOMATOR_TIMEOUT)
            Assert.assertNotNull("Could not find EditText for new folder name.", editText)
            editText.text = testFolderName

            // Click OK
            val okButton = device.findObject(By.text("OK"))
            okButton.click()

            // Wait for the folder to appear in the list and re-assign the variable
            storageFolder = device.wait(Until.findObject(By.text(testFolderName)), UI_AUTOMATOR_TIMEOUT)
            Assert.assertNotNull("Newly created '$testFolderName' folder not found.", storageFolder)
        }

        // Now, select the folder (either pre-existing or newly created).
        storageFolder.click()
        Thread.sleep(1000) // Short delay for UI to update.

        // Click "USE THIS FOLDER" button.
        val useFolderButton = device.findObject(By.text("USE THIS FOLDER"))
        Assert.assertNotNull("USE THIS FOLDER button not found", useFolderButton)
        useFolderButton.click()

        // Handle the permission confirmation dialog.
        val allowButton = device.wait(Until.findObject(By.text("ALLOW")), UI_AUTOMATOR_TIMEOUT)
        Assert.assertNotNull("ALLOW button not found", allowButton)
        allowButton.click()


        // --- Step 2: Verify MainActivity is launched and Server Starts ---
        // After folder selection, MainActivity should be in the foreground.
        // We need to wait for the server to start. This can take a few seconds.
        await.atMost(5, TimeUnit.SECONDS).untilAsserted {
            onView(withId(R.id.tvServerStatus))
                .check(matches(withText(R.string.server_running)))
        }
//        Assert.assertTrue("Server did not start within the timeout period.", isServerRunning)

        // Verify the IP address is displayed correctly.
        var ipText = ""
        onView(withId(R.id.actvIps)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(AutoCompleteTextView::class.java)
            override fun getDescription() = "Extract text from tvIpAddress"
            override fun perform(uiController: UiController, view: View) {
                ipText = (view as AutoCompleteTextView).text.toString()
            }
        })

        // sanity-check & build serverUrl
        Assert.assertTrue(
            "IP text didn’t match expected pattern",
            Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+:${Constants.SERVER_PORT}")
                .matcher(ipText)
                .matches()
        )
        serverUrl = "http://$ipText"
        Assert.assertNotNull("Failed to build serverUrl", serverUrl)

    }

    @Test
    fun testB_fileUploadAndVerification() {
        Assert.assertNotNull("Server URL not set, cannot run upload test. Ensure testA passes first.", serverUrl)
        // step 2.5: allow access
        val allow_request = Request.Builder()
            .url("$serverUrl")
            .get()
            .build()

        client.newCall(allow_request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // it'll almost certainly timeout—ignore
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })




        val allowButton = device.wait(Until.findObject(By.text("ALLOW")), UI_AUTOMATOR_TIMEOUT)
        Assert.assertNotNull("ALLOW button not found", allowButton)
        allowButton.click()


        // --- Step 3: Upload a file via HTTP ---
        val requestBody = testFileContent.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/$testFileName")
            .put(requestBody) // Use PUT for simple upload
            .build()

        try {
            val response = client.newCall(request).execute()
            Assert.assertTrue("File upload failed with code: ${response.code}", response.isSuccessful)
            response.close()
        } catch (e: IOException) {
            Assert.fail("File upload threw an exception: ${e.message}")
        }

        // --- Step 4: Verify the file appears in the RecyclerView ---
        // The service should trigger a refresh. We wait for the item to appear.
        var isFileVisible = false
        for (i in 0..5) { // Wait up to 5 seconds
            try {
                onView(withId(R.id.rvFiles))
                    .perform(RecyclerViewActions.scrollTo<FileAdapter.ViewHolder>(hasDescendant(withText(testFileName))))
                onView(withText(testFileName)).check(matches(isDisplayed()))
                isFileVisible = true
                break
            } catch (e: Exception) {
                Thread.sleep(1000)
            }
        }
        Assert.assertTrue("Uploaded file did not appear in the UI.", isFileVisible)
    }

    @Test
    fun testC_fileDeletionAndVerification() {
        assumeTrue("Server URL not set – did testA fail?", serverUrl != null)

        // --- Step 5: Delete the file using the UI ---
        // Long‑press the item to enter contextual action mode
        onView(withId(R.id.rvFiles))
            .perform(
                RecyclerViewActions.actionOnItem<FileAdapter.ViewHolder>(
                    hasDescendant(withText(testFileName)),
                    longClick()
                )
            )

        // Click the delete icon in the action bar
        onView(withId(R.id.action_delete_contextual)).perform(click())

        // Confirm deletion in the dialog
        onView(withText(R.string.delete)).perform(click())

        // --- Step 6: Verify the file is deleted on the server (expects 404) ---
        val deleteCheckRequest = Request.Builder()
            .url("$serverUrl/api/download/$testFileName")
            .get()
            .build()

        client.newCall(deleteCheckRequest).execute().use { resp ->
            Assert.assertEquals(
                "Expected 404 after deletion, but got ${resp.code}",
                404,
                resp.code
            )
        }
    }

}
