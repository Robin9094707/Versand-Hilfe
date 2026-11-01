package de.rj.versandhilfe

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        compose.onNodeWithTag("parcel-list").performScrollToNode(hasText("Lieblingsjacke") and !hasSetTextAction())
        compose.onNode(hasText("Lieblingsjacke") and !hasSetTextAction()).assertIsDisplayed()
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
            repo.resetKeys()
            repo.configureAssistant("Jessica", "sk-fake-ui-key", "ui-test-backup-passwort", true)
            repo.save(Parcel(number = 1, title = "Blaue Jeans", recipient = "Anna", location = "Flur · Regal links", carrier = Carrier.DHL, status = ParcelStatus.READY))
            repo.save(Parcel(number = 2, title = "Lieblingsjacke", recipient = "Sophie", carrier = Carrier.HERMES, status = ParcelStatus.SHIPPED))
        }
        val scenario = androidx.test.core.app.ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java))
        compose.onNodeWithText("Dein Versand").assertIsDisplayed()
        screenshot("04-app-uebersicht")
        compose.onNodeWithText("Pakethelfer").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Wie ist das Wetter heute?")
        compose.onNodeWithContentDescription("Frage senden").performClick()
        compose.waitUntil(10000) { repo.assistant.value.messages.any { it.text == ParcelFacts.refusal } }
        compose.onNodeWithText(ParcelFacts.refusal).assertIsDisplayed()
        org.junit.Assert.assertEquals(0, repo.assistant.value.calls)
        screenshot("06-pakethelfer")
        compose.onNodeWithText("Pakete").performClick()
        compose.onNodeWithTag("parcel-list").performScrollToNode(hasText("Blaue Jeans"))
        compose.onNodeWithText("Blaue Jeans").performClick()
        compose.onNodeWithText("Versandlabel fehlt noch").assertIsDisplayed()
        screenshot("05-paketdetails")
        compose.onNodeWithContentDescription("Bearbeiten").performClick()
        compose.onNodeWithText("Paket bearbeiten").assertIsDisplayed()
        scenario.close()
        kotlinx.coroutines.runBlocking { repo.resetKeys() }
        repo.prefs.edit().remove("folder").remove("folderName").commit()
    }
    @Test fun aiFieldsRequireReviewBeforeSaving() {
        val parcel = Parcel(title = "Jacke", aiSummary = "Versandlabel erkannt", aiReviewRequired = true, aiEvidence = mapOf("Artikel" to "Jacke"))
        compose.setContent { VersandTheme { EditorScreen(ParcelRepository(context), parcel, {}, {}, {}) } }
        compose.onNodeWithText("Speichern").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Speichern").assertIsEnabled()
    }
    @Test fun guidedIntakeConfirmsRecipientLocationAndReadyWithoutNetwork() {
        val raw = "OF|EXAMPLE12|0|Paket bis 2 kg|A|Anna Beispiel||Musterweg 1|10115|Berlin|Germany|Ben Muster||Testweg 2|20095|Hamburg|Germany||||20095156002000|123456789012|27112026||0||V2.0"
        val a = Attachment(name = "label.png", mime = "image/png", file = "a".repeat(64) + ".png", sha256 = "a".repeat(64), kind = AttachmentKind.LABEL, rawCodes = listOf(raw), aiAnalyzed = true)
        var result = applyLocalCodes(Parcel(title = "Jeans", attachments = listOf(a)), a, listOf(raw))
        compose.setContent {
            var parcel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(result) }
            var step by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
            VersandTheme { if (step > 0) IntakeGuide(parcel, step, { parcel = it; result = it }, { step = it }) }
        }
        compose.onNodeWithText("Was ist im Paket?").assertIsDisplayed()
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Für wen ist das Paket?").assertIsDisplayed()
        compose.onNodeWithText("Empfänger / Benutzername").assertTextContains("Ben Muster")
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Lagerplatz").performTextInput("Flur, linkes Regal")
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Alles richtig?").assertIsDisplayed()
        screenshot("07-geführte-erkennung")
        compose.onNodeWithText("Bereit zum Abgeben").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(ParcelStatus.READY, result.status)
            org.junit.Assert.assertEquals("Ben Muster", result.recipient)
            org.junit.Assert.assertEquals("Flur, linkes Regal", result.location)
            org.junit.Assert.assertFalse(result.aiReviewRequired)
        }
    }
    @Test fun addingAReceiptCannotReopenAnAlreadyShippedParcel() {
        var result = Parcel(title = "Jeans", status = ParcelStatus.SHIPPED, aiReviewRequired = true)
        compose.setContent { VersandTheme { IntakeGuide(result, 4, { result = it }, {}) } }
        compose.onNodeWithText("Bereit zum Abgeben").assertDoesNotExist()
        compose.onNodeWithText("Angaben übernehmen").performClick()
        org.junit.Assert.assertEquals(ParcelStatus.SHIPPED, result.status)
        org.junit.Assert.assertFalse(result.aiReviewRequired)
    }
    @Test fun incompleteIntakeCanStayDraftWithoutInventingMissingFields() {
        var result = Parcel(title = "Karton", aiReviewRequired = true)
        compose.setContent { VersandTheme { IntakeGuide(result, 4, { result = it }, {}) } }
        compose.onNodeWithText("Bereit zum Abgeben").assertIsNotEnabled()
        compose.onNodeWithText("In Vorbereitung speichern").performClick()
        org.junit.Assert.assertEquals(ParcelStatus.DRAFT, result.status)
        org.junit.Assert.assertEquals("", result.recipient)
    }
    @Test fun editorCannotSaveEmptyTitle() {
        compose.setContent { VersandTheme { EditorScreen(ParcelRepository(context), Parcel(), {}, {}, {}) } }
        compose.onNodeWithText("Fertig gepackt").performClick()
        compose.onNodeWithText("Ohne Foto weiter").performClick()
        compose.onNodeWithText("Im Formular weiter").performClick()
        compose.onNodeWithText("Speichern").assertIsNotEnabled()
        compose.onNodeWithText("Artikel / Paketname *").performTextInput("Mamas Jacke")
        compose.onNodeWithText("Speichern").assertIsEnabled()
        screenshot("03-paket-anlegen")
    }
}
