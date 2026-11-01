package de.rj.versandhilfe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: File(context.getExternalFilesDir(null), "screenshots")
        directory.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun onboardingExplainsDurableFolderAndCanAdvance() {
        compose.setContent { VersandTheme { OnboardingScreen {} } }
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Sicherungsordner auswählen").assertIsDisplayed()
        screenshot("01-einleitung")
    }
    @Test fun overviewSearchFindsCorrectPackage() {
        val repo = ParcelRepository(context)
        val parcels = listOf(
            Parcel(number = 1, title = "Blaue Jeans", recipient = "Anna", carrier = Carrier.DHL, status = ParcelStatus.READY, location = "Flur · Regal links"),
            Parcel(number = 2, title = "Lieblingsjacke", recipient = "Sophie", carrier = Carrier.HERMES, status = ParcelStatus.SHIPPED),
            Parcel(number = 3, title = "Sneaker Größe 38", recipient = "Mia", carrier = Carrier.DPD)
        )
        compose.setContent { VersandTheme { HomeScreen(repo, parcels, {}, {}, {}) } }
        screenshot("02-paketuebersicht")
        compose.onNode(hasSetTextAction()).performTextInput("Lieblingsjacke")
        compose.onNode(hasText("Lieblingsjacke") and !hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Blaue Jeans").assertDoesNotExist()
    }
    @Test fun realAppCanOpenAndEditSavedParcel() {
        val repo = (context.applicationContext as ShippingApp).repository
        kotlinx.coroutines.runBlocking {
            repo.load()
            repo.parcels.value.forEach { repo.delete(it.id) }
            val rootUri = android.provider.DocumentsContract.buildTreeDocumentUri("${context.packageName}.fixture", "root")
            val folder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)!!.createDirectory("ui-${java.util.UUID.randomUUID()}")!!
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri("${context.packageName}.fixture", android.provider.DocumentsContract.getDocumentId(folder.uri))
            context.grantUriPermission(context.packageName, tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            repo.useFolder(repo.inspect(tree), false)
            repo.save(Parcel(number = 1, title = "Blaue Jeans", recipient = "Anna", location = "Flur · Regal links", carrier = Carrier.DHL, status = ParcelStatus.READY))
            repo.save(Parcel(number = 2, title = "Lieblingsjacke", recipient = "Sophie", carrier = Carrier.HERMES, status = ParcelStatus.SHIPPED))
        }
        val scenario = androidx.test.core.app.ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java))
        compose.onNodeWithText("Dein Versand").assertIsDisplayed()
        screenshot("04-app-uebersicht")
        compose.onNodeWithText("Blaue Jeans").performScrollTo().performClick()
        compose.onNodeWithText("Versandlabel fehlt noch").assertIsDisplayed()
        screenshot("05-paketdetails")
        compose.onNodeWithContentDescription("Bearbeiten").performClick()
        compose.onNodeWithText("Paket bearbeiten").assertIsDisplayed()
        scenario.close()
        repo.prefs.edit().remove("folder").remove("folderName").commit()
    }
    @Test fun editorCannotSaveEmptyTitle() {
        compose.setContent { VersandTheme { EditorScreen(ParcelRepository(context), Parcel(), {}, {}, {}) } }
        compose.onNodeWithText("Speichern").assertIsNotEnabled()
        compose.onNodeWithText("Artikel / Paketname *").performTextInput("Mamas Jacke")
        compose.onNodeWithText("Speichern").assertIsEnabled()
        screenshot("03-paket-anlegen")
    }
}
