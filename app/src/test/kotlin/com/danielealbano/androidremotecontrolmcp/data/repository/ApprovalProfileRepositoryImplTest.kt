package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.commonProfile
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.moneyProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplates
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalProfileRepositoryImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: ApprovalProfileRepositoryImpl
    private var counter = 0
    private var clock = PolicyFixtures.NOW_MS + 1

    private val bank = moneyProfile()
    private val notes = commonProfile()
    private val bankPackage = PolicyFixtures.BANK_PACKAGE
    private val notesPackage = PolicyFixtures.NOTES_PACKAGE
    private val commonRules = ProfileTemplates.rulesFor(ProfileTemplate.COMMON_APP)

    @BeforeEach
    fun setUp() {
        counter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "approval_profiles_$counter.preferences_pb") },
            )
        repository = newRepository()
    }

    private fun newRepository(store: DataStore<Preferences> = dataStore): ApprovalProfileRepositoryImpl =
        ApprovalProfileRepositoryImpl(store).apply { clockMs = { clock } }

    private fun app(n: Int): ApprovalProfile = commonProfile("com.example.app$n")

    private fun sel(value: String): PolicySelector = PolicyFixtures.resourceId(value)

    private fun json(profile: ApprovalProfile): String = Json.encodeToString(profile)

    private suspend fun seed(
        key: String,
        value: String,
    ) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    private suspend fun storedKeys(): Set<String> =
        dataStore.data
            .first()
            .asMap()
            .keys
            .map { it.name }
            .toSet()

    private suspend fun stored(packageName: String): ApprovalProfile? = repository.current().profiles[packageName]

    private suspend fun assertUnreadable(packageName: String) {
        val snapshot = repository.current()

        assertEquals(setOf(packageName), snapshot.unreadable)
        assertTrue(snapshot.profiles.isEmpty())
        assertTrue(snapshot.hasAnyProfile)
        assertEquals(ProfileStoreStatus.LOADED, repository.storeStatus.value)
    }

    private suspend fun ApprovalProfileRepository.settings(
        packageName: String,
        template: ProfileTemplate,
        maxAmount: String?,
    ): ProfileSaveResult =
        updateSettings(packageName, template, ProfileTemplates.rulesFor(template), maxAmount, emptyList())

    private fun failingStore(): DataStore<Preferences> = mockk()

    @Test
    fun `current on empty store returns EMPTY`() =
        testScope.runTest {
            assertEquals(ProfileStoreStatus.NOT_LOADED, repository.storeStatus.value)

            assertEquals(ProfilesSnapshot.EMPTY, repository.current())
            assertEquals(ProfileStoreStatus.LOADED, repository.storeStatus.value)
        }

    @Test
    fun `upsert then current round-trips the profile`() =
        testScope.runTest {
            val result = repository.upsert(bank)

            val expected = bank.copy(updatedAtMs = clock)
            assertEquals(ProfileSaveResult.Saved(expected, created = true), result)
            assertEquals(expected, stored(bankPackage))
        }

    @Test
    fun `upsert of an existing package reports created false`() =
        testScope.runTest {
            repository.upsert(notes)
            val newRules = notes.rules + (PolicyActionType.TYPE to RuleMode.NOTIFY)

            val result = repository.upsert(notes.copy(rules = newRules))

            assertTrue(result is ProfileSaveResult.Saved && !result.created)
            assertEquals(newRules, stored(notesPackage)?.rules)
        }

    @Test
    fun `upsert writes one key per package named profile colon package`() =
        testScope.runTest {
            repository.upsert(bank)
            repository.upsert(notes)

            assertEquals(setOf("profile:com.example.bank", "profile:com.example.notes"), storedKeys())
        }

    @Test
    fun `persists across a new instance`() =
        testScope.runTest {
            repository.upsert(bank)

            assertEquals(bank.copy(updatedAtMs = clock), newRepository().current().profiles[bankPackage])
        }

    @Test
    fun `upsert returns Invalid and writes nothing when the validator rejects`() =
        testScope.runTest {
            val result = repository.upsert(moneyProfile(maxAmount = null))

            assertEquals(ProfileSaveResult.Invalid(listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED)), result)
            assertFalse("profile:com.example.bank" in storedKeys())
            assertEquals(ProfilesSnapshot.EMPTY, repository.current())
        }

    @Test
    fun `upsert ignores incoming exception lists`() =
        testScope.runTest {
            repository.upsert(moneyProfile(notAmountFields = listOf(sel("a"))))
            assertTrue(stored(bankPackage)!!.notAmountFields.isEmpty())

            repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a"))
            repository.upsert(bank)

            assertEquals(listOf(sel("a")), stored(bankPackage)!!.notAmountFields.map { it.selector })
        }

    @Test
    fun `corrupt JSON goes to unreadable`() =
        testScope.runTest {
            seed("profile:com.example.bank", "{")

            assertUnreadable(bankPackage)
        }

    @Test
    fun `unknown enum value goes to unreadable`() =
        testScope.runTest {
            seed("profile:com.example.notes", json(notes).replace("\"ASK_IN_CHAT\"", "\"FINGERPRINT\""))

            assertUnreadable(notesPackage)
        }

    @Test
    fun `unknown JSON field goes to unreadable`() =
        testScope.runTest {
            seed("profile:com.example.notes", json(notes).replaceFirst("{", "{\"extra\":1,"))

            assertUnreadable(notesPackage)
        }

    @Test
    fun `package mismatch with key goes to unreadable`() =
        testScope.runTest {
            seed("profile:com.example.bank", json(notes))

            assertUnreadable(bankPackage)
        }

    @Test
    fun `stored profile failing validation goes to unreadable`() =
        testScope.runTest {
            seed("profile:com.example.bank", json(moneyProfile(maxAmount = null)))

            assertUnreadable(bankPackage)
        }

    @Test
    fun `one unreadable key does not hide the readable ones`() =
        testScope.runTest {
            seed("profile:com.example.bank", "{")
            seed("profile:com.example.notes", json(notes))

            val snapshot = repository.current()

            assertEquals(setOf(bankPackage), snapshot.unreadable)
            assertEquals(setOf(notesPackage), snapshot.profiles.keys)
        }

    @Test
    fun `non-profile keys are ignored`() =
        testScope.runTest {
            seed("other", "value")

            assertEquals(ProfilesSnapshot.EMPTY, repository.current())
        }

    @Test
    fun `upsert on an unreadable package replaces it`() =
        testScope.runTest {
            seed("profile:com.example.bank", "{")

            val result = repository.upsert(moneyProfile(notActionButtons = listOf(sel("b"))))

            assertTrue(result is ProfileSaveResult.Saved && !result.created)
            val snapshot = repository.current()
            assertTrue(snapshot.unreadable.isEmpty())
            assertTrue(snapshot.profiles.getValue(bankPackage).notAmountFields.isEmpty())
            assertTrue(snapshot.profiles.getValue(bankPackage).notActionButtons.isEmpty())
        }

    @Test
    fun `updateSettings changes the five settings and keeps exceptions`() =
        testScope.runTest {
            repository.upsert(bank)
            repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a"))
            clock += 10

            val result = repository.settings(bankPackage, ProfileTemplate.COMMON_APP, null)

            assertTrue(result is ProfileSaveResult.Saved && !result.created)
            val profile = stored(bankPackage)!!
            assertEquals(ProfileTemplate.COMMON_APP, profile.template)
            assertEquals(commonRules, profile.rules)
            assertEquals(null, profile.maxAmount)
            assertTrue(profile.amountTiers.isEmpty())
            assertEquals(listOf(sel("a")), profile.notAmountFields.map { it.selector })
            assertEquals(clock, profile.updatedAtMs)
        }

    @Test
    fun `updateSettings returns Invalid and writes nothing`() =
        testScope.runTest {
            repository.upsert(bank)
            val before = stored(bankPackage)

            val result = repository.settings(bankPackage, ProfileTemplate.MONEY_APP, null)

            assertEquals(ProfileSaveResult.Invalid(listOf(ProfileValidationError.MAX_AMOUNT_REQUIRED)), result)
            assertEquals(before, stored(bankPackage))
            assertEquals(before, newRepository().current().profiles[bankPackage])
        }

    @Test
    fun `updateSettings on a missing or unreadable package returns NotFound`() =
        testScope.runTest {
            seed("profile:com.example.bank", "{")

            val missing = repository.settings(notesPackage, ProfileTemplate.COMMON_APP, null)
            val unreadable =
                repository.settings(bankPackage, ProfileTemplate.COMMON_APP, null)

            assertEquals(ProfileSaveResult.NotFound, missing)
            assertEquals(ProfileSaveResult.NotFound, unreadable)
            assertEquals(setOf("profile:com.example.bank"), storedKeys())
            assertEquals(setOf(bankPackage), repository.current().unreadable)
            assertEquals("{", dataStore.data.first()[stringPreferencesKey("profile:com.example.bank")])
        }

    @Test
    fun `updateSettings is allowed at the profile cap`() =
        testScope.runTest {
            (1..PolicyLimits.MAX_PROFILES).forEach { repository.upsert(app(it)) }

            val result =
                repository.settings("com.example.app1", ProfileTemplate.COMMON_APP, "500")

            assertTrue(result is ProfileSaveResult.Saved)
        }

    @Test
    fun `concurrent addException and updateSettings both persist`() =
        testScope.runTest {
            repository.upsert(bank)

            coroutineScope {
                launch { repository.addException(bankPackage, PolicyExceptionKind.NOT_ACTION_BUTTON, sel("b")) }
                launch {
                    repository.settings(bankPackage, ProfileTemplate.MONEY_APP, "500")
                }
            }

            val profile = newRepository().current().profiles.getValue(bankPackage)
            assertEquals(listOf(sel("b")), profile.notActionButtons.map { it.selector })
            assertEquals("500", profile.maxAmount)
        }

    @Test
    fun `delete removes a readable profile and its key`() =
        testScope.runTest {
            repository.upsert(bank)

            assertEquals(ProfileDeleteResult.DELETED, repository.delete(bankPackage))
            assertTrue(storedKeys().isEmpty())
            assertFalse(bankPackage in repository.current().profiles)
        }

    @Test
    fun `delete removes an unreadable package`() =
        testScope.runTest {
            seed("profile:com.example.bank", "{")

            assertEquals(ProfileDeleteResult.DELETED, repository.delete(bankPackage))
            assertTrue(repository.current().unreadable.isEmpty())
            assertTrue(storedKeys().isEmpty())
        }

    @Test
    fun `delete unknown package returns NOT_FOUND`() =
        testScope.runTest {
            repository.upsert(notes)
            val before = repository.current()

            assertEquals(ProfileDeleteResult.NOT_FOUND, repository.delete(bankPackage))
            assertEquals(before, repository.current())
            assertEquals(setOf("profile:com.example.notes"), storedKeys())
        }

    @Test
    fun `cap refuses a new package at MAX_PROFILES`() =
        testScope.runTest {
            (1..PolicyLimits.MAX_PROFILES).forEach {
                assertTrue(repository.upsert(app(it)) is ProfileSaveResult.Saved)
            }

            assertEquals(ProfileSaveResult.ProfileLimitReached, repository.upsert(app(PolicyLimits.MAX_PROFILES + 1)))
            assertEquals(PolicyLimits.MAX_PROFILES, storedKeys().size)
        }

    @Test
    fun `cap allows replacing an existing package when full`() =
        testScope.runTest {
            (1..PolicyLimits.MAX_PROFILES).forEach { repository.upsert(app(it)) }

            val result = repository.upsert(app(1).copy(maxAmount = "500"))

            assertTrue(result is ProfileSaveResult.Saved)
        }

    @Test
    fun `unreadable keys count toward the cap`() =
        testScope.runTest {
            seed("profile:com.example.broken", "{")
            (1 until PolicyLimits.MAX_PROFILES).forEach {
                assertTrue(repository.upsert(app(it)) is ProfileSaveResult.Saved)
            }

            assertEquals(ProfileSaveResult.ProfileLimitReached, repository.upsert(app(PolicyLimits.MAX_PROFILES)))
        }

    @Test
    fun `addException adds to the requested list only`() =
        testScope.runTest {
            repository.upsert(bank)
            clock += 10

            repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a"))

            val profile = stored(bankPackage)!!
            assertEquals(listOf(PolicyException(sel("a"), clock)), profile.notAmountFields)
            assertTrue(profile.notActionButtons.isEmpty())
            assertEquals(clock, profile.updatedAtMs)
            assertEquals(profile, newRepository().current().profiles[bankPackage])
        }

    @Test
    fun `addException is idempotent`() =
        testScope.runTest {
            repository.upsert(bank)
            repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a"))
            val firstCreatedAt = clock
            clock += 10

            val result = repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a"))

            assertTrue(result is ProfileSaveResult.Saved && !result.created)
            assertEquals(listOf(PolicyException(sel("a"), firstCreatedAt)), stored(bankPackage)!!.notAmountFields)
        }

    @Test
    fun `addException refuses beyond MAX_EXCEPTIONS_PER_LIST`() =
        testScope.runTest {
            repository.upsert(bank)
            (1..PolicyLimits.MAX_EXCEPTIONS_PER_LIST).forEach {
                val result = repository.addException(bankPackage, PolicyExceptionKind.NOT_ACTION_BUTTON, sel("s$it"))
                assertTrue(result is ProfileSaveResult.Saved)
            }

            val result = repository.addException(bankPackage, PolicyExceptionKind.NOT_ACTION_BUTTON, sel("extra"))

            assertEquals(ProfileSaveResult.ExceptionLimitReached, result)
            assertEquals(PolicyLimits.MAX_EXCEPTIONS_PER_LIST, stored(bankPackage)!!.notActionButtons.size)
        }

    @Test
    fun `addException with an invalid selector returns Invalid`() =
        testScope.runTest {
            repository.upsert(bank)

            val result = repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel(""))

            assertEquals(ProfileSaveResult.Invalid(listOf(ProfileValidationError.EXCEPTION_SELECTOR_INVALID)), result)
            assertTrue(stored(bankPackage)!!.notAmountFields.isEmpty())
            assertTrue(newRepository().current().profiles.getValue(bankPackage).notAmountFields.isEmpty())
        }

    @Test
    fun `addException on a missing or unreadable package returns NotFound`() =
        testScope.runTest {
            seed("profile:com.example.bank", "{")

            assertEquals(
                ProfileSaveResult.NotFound,
                repository.addException(notesPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a")),
            )
            assertEquals(
                ProfileSaveResult.NotFound,
                repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a")),
            )
            assertEquals(setOf("profile:com.example.bank"), storedKeys())
        }

    @Test
    fun `removeException removes only the matching selector and kind`() =
        testScope.runTest {
            repository.upsert(bank)
            repository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a"))
            repository.addException(bankPackage, PolicyExceptionKind.NOT_ACTION_BUTTON, sel("a"))

            repository.removeException(bankPackage, PolicyExceptionKind.NOT_ACTION_BUTTON, sel("a"))

            val profile = stored(bankPackage)!!
            assertEquals(listOf(sel("a")), profile.notAmountFields.map { it.selector })
            assertTrue(profile.notActionButtons.isEmpty())
        }

    @Test
    fun `removeException of an absent selector returns Saved unchanged`() =
        testScope.runTest {
            repository.upsert(bank)
            val before = stored(bankPackage)!!
            clock += 10

            val result = repository.removeException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("zz"))

            assertEquals(ProfileSaveResult.Saved(before, created = false), result)
            assertEquals(before.updatedAtMs, stored(bankPackage)!!.updatedAtMs)
        }

    @Test
    fun `removeException on a missing package returns NotFound`() =
        testScope.runTest {
            val result = repository.removeException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a"))

            assertEquals(ProfileSaveResult.NotFound, result)
        }

    @Test
    fun `observeProfiles emits the loaded snapshot then changes`() =
        testScope.runTest {
            seed("profile:com.example.bank", json(bank))

            repository.observeProfiles().test {
                assertEquals(setOf(bankPackage), awaitItem().profiles.keys)

                repository.upsert(notes)

                assertEquals(setOf(bankPackage, notesPackage), awaitItem().profiles.keys)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `profiles StateFlow follows writes`() =
        testScope.runTest {
            repository.upsert(bank)
            assertEquals(repository.current(), repository.profiles.value)

            repository.delete(bankPackage)
            assertEquals(repository.current(), repository.profiles.value)
            assertEquals(ProfilesSnapshot.EMPTY, repository.profiles.value)
        }

    @Test
    fun `unreadable store throws, reports UNREADABLE and retries`() =
        testScope.runTest {
            val failing = failingStore()
            every { failing.data } returns flow { throw IOException("disk") }
            val unreadableRepository = newRepository(failing)

            assertThrows<ProfileStoreUnreadableException> { unreadableRepository.current() }
            assertEquals(ProfileStoreStatus.UNREADABLE, unreadableRepository.storeStatus.value)
            assertEquals(ProfilesSnapshot.EMPTY, unreadableRepository.profiles.value)

            every { failing.data } returns flowOf(emptyPreferences())

            assertEquals(ProfilesSnapshot.EMPTY, unreadableRepository.current())
            assertEquals(ProfileStoreStatus.LOADED, unreadableRepository.storeStatus.value)
        }

    @Test
    fun `mutations on an unreadable store return StoreUnavailable and write nothing`() =
        testScope.runTest {
            val failing = failingStore()
            every { failing.data } returns flow { throw IOException("disk") }
            val unreadableRepository = newRepository(failing)

            assertEquals(ProfileSaveResult.StoreUnavailable, unreadableRepository.upsert(bank))
            assertEquals(
                ProfileSaveResult.StoreUnavailable,
                unreadableRepository.settings(bankPackage, ProfileTemplate.MONEY_APP, "1"),
            )
            assertEquals(
                ProfileSaveResult.StoreUnavailable,
                unreadableRepository.addException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a")),
            )
            assertEquals(
                ProfileSaveResult.StoreUnavailable,
                unreadableRepository.removeException(bankPackage, PolicyExceptionKind.NOT_AMOUNT_FIELD, sel("a")),
            )
            assertEquals(ProfileDeleteResult.STORE_UNAVAILABLE, unreadableRepository.delete(bankPackage))
            coVerify(exactly = 0) { failing.updateData(any()) }
        }

    @Test
    fun `observeProfiles on an unreadable store emits without throwing`() =
        testScope.runTest {
            val failing = failingStore()
            every { failing.data } returns flow { throw IOException("disk") }
            val unreadableRepository = newRepository(failing)

            unreadableRepository.observeProfiles().test {
                assertEquals(ProfilesSnapshot.EMPTY, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(ProfileStoreStatus.UNREADABLE, unreadableRepository.storeStatus.value)
        }

    @Test
    fun `failed disk write returns StoreUnavailable and leaves the snapshot unchanged`() =
        testScope.runTest {
            val failing = failingStore()
            every { failing.data } returns flowOf(emptyPreferences())
            coEvery { failing.updateData(any()) } throws IOException("full")
            val fullRepository = newRepository(failing)

            assertEquals(ProfileSaveResult.StoreUnavailable, fullRepository.upsert(bank))
            assertEquals(ProfilesSnapshot.EMPTY, fullRepository.current())
        }
}
