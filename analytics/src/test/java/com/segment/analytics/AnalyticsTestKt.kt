package com.segment.analytics

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.ComponentName
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Bundle
import androidx.annotation.Nullable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nhaarman.mockitokotlin2.*
import com.segment.analytics.ProjectSettings.create
import com.segment.analytics.TestUtils.*
import com.segment.analytics.integrations.*
import com.segment.analytics.internal.Utils.*
import org.assertj.core.api.Assertions
import org.assertj.core.data.MapEntry
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.*
import org.mockito.ArgumentMatchers.*
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.Boolean.TRUE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
open class AnalyticsTestKt {
    private val SETTINGS =
            """
            |{
            |  "integrations": {
            |    "test": { 
            |      "foo": "bar"
            |    }
            |  },
            | "plan": {
            |
            |  }
            |}
            """.trimMargin()

    @Mock
    private lateinit var traitsCache: Traits.Cache
    @Spy
    private lateinit var networkExecutor: AnalyticsNetworkExecutorService

    @Spy
    private var analyticsExecutor: ExecutorService = SynchronousExecutor()

    @Mock
    private lateinit var client: Client

    @Mock
    private lateinit var stats: Stats

    @Mock
    private lateinit var projectSettingsCache: ProjectSettings.Cache

    @Mock
    private lateinit var integration: Integration<*>

    @Mock
    lateinit var lifecycle: Lifecycle
    private lateinit var defaultOptions: Options
    private lateinit var factory: Integration.Factory
    private lateinit var optOut: BooleanPreference
    private lateinit var application: Application
    private lateinit var traits: Traits
    private lateinit var analyticsContext: AnalyticsContext
    private lateinit var analytics: Analytics

    @Before
    @Throws(IOException::class, NameNotFoundException::class)
    fun setUp() {
        Analytics.INSTANCES.clear()

        MockitoAnnotations.initMocks(this)
        defaultOptions = Options()
        application = mockApplication()
        traits = Traits.create()
        whenever(traitsCache.get()).thenReturn(traits)

        val packageInfo = PackageInfo()
        packageInfo.versionCode = 100
        packageInfo.versionName = "1.0.0"

        val packageManager = Mockito.mock(PackageManager::class.java)
        whenever(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo)
        whenever(application.packageName).thenReturn("com.foo")
        whenever(application.packageManager).thenReturn(packageManager)

        analyticsContext = Utils.createContext(traits)
        factory = object : Integration.Factory {
            override fun create(settings: ValueMap, analytics: Analytics): Integration<*>? {
                return integration
            }

            override fun key(): String {
                return "test"
            }
        }
        whenever(projectSettingsCache.get())
                .thenReturn(create(Cartographer.INSTANCE.fromJson(SETTINGS)))

        val sharedPreferences =
                RuntimeEnvironment.application.getSharedPreferences("analytics-test-qaz", MODE_PRIVATE)
        optOut = BooleanPreference(sharedPreferences, "opt-out-test", false)

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.VERBOSE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                false,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        // Used by singleton tests.
        grantPermission(RuntimeEnvironment.application, android.Manifest.permission.INTERNET)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.application
                .getSharedPreferences("analytics-android-qaz", MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
    }

    @Test
    fun invalidIdentity() {
        try {
            analytics.identify(null, null, null)
        } catch (e: IllegalArgumentException) {
            Assertions.assertThat(e).hasMessage("Either userId or some traits must be provided.")
        }
    }

    @Test
    fun identify() {
        analytics.identify("prateek", Traits().putUsername("f2prateek"), null)

        Mockito.verify(integration)
                .identify(
                        argThat<IdentifyPayload>(
                                object : NoDescriptionMatcher<IdentifyPayload>() {
                                    override fun matchesSafely(item: IdentifyPayload): Boolean {
                                        return item.userId() == "prateek" && item.traits().username() == "f2prateek"
                                    }
                                }))
    }

    @Test
    fun identifyUpdatesCache() {
        analytics.identify("foo", Traits().putValue("bar", "qaz"), null)

        Assertions.assertThat(traits).contains(MapEntry.entry("userId", "foo"))
        Assertions.assertThat(traits).contains(MapEntry.entry("bar", "qaz"))
        Assertions.assertThat(analyticsContext.traits()).contains(MapEntry.entry("userId", "foo"))
        Assertions.assertThat(analyticsContext.traits()).contains(MapEntry.entry("bar", "qaz"))
        Mockito.verify(traitsCache).set(traits)
        Mockito.verify(integration)
                .identify(
                        argThat<IdentifyPayload>(
                                object : NoDescriptionMatcher<IdentifyPayload>() {
                                    override fun matchesSafely(item: IdentifyPayload): Boolean {
                                        // Exercises a bug where payloads didn't pick up userId in identify correctly.
                                        // https://github.com/segmentio/analytics-android/issues/169
                                        return item.userId() == "foo"
                                    }
                                }))
    }

    @Test
    @Nullable
    fun invalidGroup() {
        try {
            analytics.group("")
            Assertions.fail("empty groupId and name should throw exception")
        } catch (expected: IllegalArgumentException) {
            Assertions.assertThat(expected).hasMessage("groupId must not be null or empty.")
        }
    }

    @Test
    fun group() {
        analytics.group("segment", Traits().putEmployees(42), null)

        Mockito.verify(integration)
                .group(
                        argThat<GroupPayload>(
                                object : NoDescriptionMatcher<GroupPayload>() {
                                    override fun matchesSafely(item: GroupPayload): Boolean {
                                        return item.groupId() == "segment" && item.traits().employees() == 42L
                                    }
                                }))
    }

    @Test
    fun invalidTrack() {
        try {
            analytics.track(null.toString())
        } catch (e: IllegalArgumentException) {
            Assertions.assertThat(e).hasMessage("event must not be null or empty.")
        }
        try {
            analytics.track("   ")
        } catch (e: IllegalArgumentException) {
            Assertions.assertThat(e).hasMessage("event must not be null or empty.")
        }
    }

    @Test
    fun track() {
        analytics.track("wrote tests", Properties().putUrl("github.com"))
        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "wrote tests" && payload.properties().url() == "github.com"
                                    }
                                }))
    }

    @Test
    @Throws(IOException::class)
    fun invalidScreen() {
        try {
            analytics.screen(null, null as String?)
            Assertions.fail("null category and name should throw exception")
        } catch (expected: IllegalArgumentException) {
            Assertions.assertThat(expected).hasMessage("either category or name must be provided.")
        }

        try {
            analytics.screen("", "")
            Assertions.fail("empty category and name should throw exception")
        } catch (expected: IllegalArgumentException) {
            Assertions.assertThat(expected).hasMessage("either category or name must be provided.")
        }
    }

    @Test
    fun screen() {
        analytics.screen("android", "saw tests", Properties().putUrl("github.com"))
        Mockito.verify(integration)
                .screen(
                        argThat<ScreenPayload>(
                                object : NoDescriptionMatcher<ScreenPayload>() {
                                    override fun matchesSafely(payload: ScreenPayload): Boolean {
                                        return payload.name() == "saw tests" && payload.category() == "android" && payload.properties().url() == "github.com"
                                    }
                                }))
    }

    @Test
    fun optionsDisableIntegrations() {
        analytics.screen("foo", "bar", null, Options().setIntegration("test", false))
        analytics.track("foo", null, Options().setIntegration("test", false))
        analytics.group("foo", null, Options().setIntegration("test", false))
        analytics.identify("foo", null, Options().setIntegration("test", false))
        analytics.alias("foo", Options().setIntegration("test", false))

        analytics.screen(
                "foo", "bar", null, Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false))
        analytics.track("foo", null, Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false))
        analytics.group("foo", null, Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false))
        analytics.identify(
                "foo", null, Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false))
        analytics.alias("foo", Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false))

        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    fun optionsCustomContext() {
        analytics.track("foo", null, Options().putContext("from_tests", true))

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.context()["from_tests"] == TRUE
                                    }
                                }))
    }

    @Test
    @Throws(IOException::class)
    fun optOutDisablesEvents() {
        analytics.optOut(true)
        analytics.track("foo")
        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(IOException::class)
    fun emptyTrackingPlan() {
        analytics.projectSettings = create(
                        Cartographer.INSTANCE.fromJson(
                                """
                                      |{
                                      |  "integrations": {
                                      |    "test": {
                                      |      "foo": "bar"
                                      |    }
                                      |  },
                                      |  "plan": {
                                      |  }
                                      |}
                                      """.trimMargin()))

        analytics.track("foo")
        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "foo"
                                    }
                                }))
        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(IOException::class)
    fun emptyEventPlan() {
        analytics.projectSettings = create(
                Cartographer.INSTANCE.fromJson(
                        """
                              |{
                              |  "integrations": {
                              |    "test": {
                              |      "foo": "bar"
                              |    }
                              |  },
                              |  "plan": {
                              |    "track": {
                              |    }
                              |  }
                              |}
                              """.trimMargin()))
        analytics.track("foo")
        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "foo"
                                    }
                                }))
        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(IOException::class)
    fun trackingPlanDisablesEvent() {
        analytics.projectSettings = create(
                Cartographer.INSTANCE.fromJson(
                        """
                              |{
                              |  "integrations": {
                              |    "test": {
                              |      "foo": "bar"
                              |    }
                              |  },
                              |  "plan": {
                              |    "track": {
                              |      "foo": {
                              |        "enabled": false
                              |      }
                              |    }
                              |  }
                              |}
                              """.trimMargin()))
        analytics.track("foo")
        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(IOException::class)
    fun trackingPlanDisablesEventForSingleIntegration() {
        analytics.projectSettings = create(
                Cartographer.INSTANCE.fromJson(
                        """
                              |{
                              |  "integrations": {
                              |    "test": {
                              |      "foo": "bar"
                              |    }
                              |  },
                              |  "plan": {
                              |    "track": {
                              |      "foo": {
                              |        "enabled": true,
                              |        "integrations": {
                              |          "test": false
                              |        }
                              |      }
                              |    }
                              |  }
                              |}
                              """.trimMargin()))
        analytics.track("foo")
        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(IOException::class)
    fun trackingPlanDisabledEventCannotBeOverriddenByOptions() {
        analytics.projectSettings = create(
                Cartographer.INSTANCE.fromJson(
                        """
                              |{
                              |  "integrations": {
                              |    "test": {
                              |      "foo": "bar"
                              |    }
                              |  },
                              |  "plan": {
                              |    "track": {
                              |      "foo": {
                              |        "enabled": false
                              |      }
                              |    }
                              |  }
                              |}
                              """.trimMargin()))
        analytics.track("foo", null, Options().setIntegration("test", true))
        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(IOException::class)
    fun trackingPlanDisabledEventForIntegrationOverriddenByOptions() {
        analytics.projectSettings = create(
                Cartographer.INSTANCE.fromJson(
                        """
                              |{
                              |  "integrations": {
                              |    "test": {
                              |      "foo": "bar"
                              |    }
                              |  },
                              |  "plan": {
                              |    "track": {
                              |      "foo": {
                              |        "enabled": true,
                              |        "integrations": {
                              |          "test": false
                              |        }
                              |      }
                              |    }
                              |  }
                              |}
                              """.trimMargin()))
        analytics.track("foo", null, Options().setIntegration("test", true))
        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "foo"
                                    }
                                }))
        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(IOException::class)
    fun invalidAlias() {
        try {
            analytics.alias("")
            Assertions.fail("empty new id should throw error")
        } catch (expected: IllegalArgumentException) {
            Assertions.assertThat(expected).hasMessage("not allowed to pass null or empty alias")
        }
    }

    @Test
    fun alias() {
        val anonymousId = traits.anonymousId()
        analytics.alias("foo")
        val payloadArgumentCaptor =
                ArgumentCaptor.forClass(AliasPayload::class.java)
        Mockito.verify(integration).alias(payloadArgumentCaptor.capture())
        Assertions.assertThat(payloadArgumentCaptor.value).containsEntry("previousId", anonymousId)
        Assertions.assertThat(payloadArgumentCaptor.value).containsEntry("userId", "foo")
    }

    @Test
    fun aliasWithCachedUserID() {
        analytics.identify(
                "prayansh", Traits().putValue("bar", "qaz"), null) // refer identifyUpdatesCache
        analytics.alias("foo")
        val payloadArgumentCaptor =
                ArgumentCaptor.forClass(AliasPayload::class.java)
        Mockito.verify(integration).alias(payloadArgumentCaptor.capture())
        Assertions.assertThat(payloadArgumentCaptor.value).containsEntry("previousId", "prayansh")
        Assertions.assertThat(payloadArgumentCaptor.value).containsEntry("userId", "foo")
    }

    @Test
    fun flush() {
        analytics.flush()

        Mockito.verify(integration).flush()
    }

    @Test
    fun reset() {
        analytics.reset()

        Mockito.verify(integration).reset()
    }

    @Test
    @Throws(Exception::class)
    fun getSnapshot() {
        analytics.snapshot

        Mockito.verify(stats).createSnapshot()
    }

    @Test
    fun logoutClearsTraitsAndUpdatesContext() {
        analyticsContext.setTraits(Traits().putAge(20).putAvatar("bar"))

        analytics.logout()

        Mockito.verify(traitsCache).delete()
        Mockito.verify(traitsCache)
                .set(
                        argThat(
                                object : TypeSafeMatcher<Traits>() {
                                    override fun matchesSafely(traits: Traits): Boolean {
                                        return !isNullOrEmpty(traits.anonymousId())
                                    }

                                    override fun describeTo(description: Description) {}
                                }))
        Assertions.assertThat(analyticsContext.traits()).hasSize(1)
        Assertions.assertThat(analyticsContext.traits()).containsKey("anonymousId")
    }

    @Test
    fun onIntegrationReadyShouldFailForNullKey() {
        try {
            analytics.onIntegrationReady(null as String?, Mockito.mock(Analytics.Callback::class.java))
            Assertions.fail("registering for null integration should fail")
        } catch (e: java.lang.IllegalArgumentException) {
            Assertions.assertThat(e).hasMessage("key cannot be null or empty.")
        }
    }

    @Test
    fun onIntegrationReady() {
        val callback: Analytics.Callback<*> = Mockito.mock(Analytics.Callback::class.java)
        analytics.onIntegrationReady("test", callback)
        Mockito.verify(callback).onReady(null)
    }

    @Test
    fun shutdown() {
        Assertions.assertThat(analytics.shutdown).isFalse()
        analytics.shutdown()
        Mockito.verify(application).unregisterActivityLifecycleCallbacks(analytics.activityLifecycleCallback)
        Mockito.verify(stats).shutdown()
        Mockito.verify(networkExecutor).shutdown()
        Assertions.assertThat(analytics.shutdown).isTrue()
        try {
            analytics.track("foo")
            Assertions.fail("Enqueuing a message after shutdown should throw.")
        } catch (e: IllegalStateException) {
            Assertions.assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.")
        }

        try {
            analytics.flush()
            Assertions.fail("Enqueuing a message after shutdown should throw.")
        } catch (e: IllegalStateException) {
            Assertions.assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.")
        }
    }

    @Test
    fun shutdownTwice() {
        Assertions.assertThat(analytics.shutdown).isFalse()
        analytics.shutdown()
        analytics.shutdown()
        Mockito.verify(stats).shutdown()
        Assertions.assertThat(analytics.shutdown).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun shutdownDisallowedOnCustomSingletonInstance() {
        Analytics.singleton = null
        try {
            val analytics = Analytics.Builder(RuntimeEnvironment.application, "foo").build()
            Analytics.setSingletonInstance(analytics)
            analytics.shutdown()
            Assertions.fail("Calling shutdown() on static singleton instance should throw")
        } catch (ignored: UnsupportedOperationException) {
        }
    }

    @Test
    fun setSingletonInstanceMayOnlyBeCalledOnce() {
        Analytics.singleton = null

        val analytics = Analytics.Builder(RuntimeEnvironment.application, "foo").build()
        Analytics.setSingletonInstance(analytics)

        try {
            Analytics.setSingletonInstance(analytics)
            Assertions.fail("Can't set singleton instance twice.")
        } catch (e: IllegalStateException) {
            Assertions.assertThat(e).hasMessage("Singleton instance already exists.")
        }
    }

    @Test
    fun setSingletonInstanceAfterWithFails() {
        Analytics.singleton = null
        Analytics.setSingletonInstance(Analytics.Builder(RuntimeEnvironment.application, "foo").build())

        val analytics = Analytics.Builder(RuntimeEnvironment.application, "bar").build()
        try {
            Analytics.setSingletonInstance(analytics)
            Assertions.fail("Can't set singleton instance after with().")
        } catch (e: IllegalStateException) {
            Assertions.assertThat(e).hasMessage("Singleton instance already exists.")
        }
    }

    @Test
    fun setSingleInstanceReturnedFromWith() {
        Analytics.singleton = null
        val analytics = Analytics.Builder(RuntimeEnvironment.application, "foo").build()
        Analytics.setSingletonInstance(analytics)
        Assertions.assertThat(Analytics.with(RuntimeEnvironment.application)).isSameAs(analytics)
    }

    @Test
    @Throws(Exception::class)
    fun  multipleInstancesWithSameTagThrows() {
        Analytics.Builder(RuntimeEnvironment.application, "foo").build()
        try {
            Analytics.Builder(RuntimeEnvironment.application, "bar").tag("foo").build()
            Assertions.fail("Creating client with duplicate should throw.")
        } catch (expected: IllegalStateException) {
            Assertions.assertThat(expected)
                    .hasMessageContaining("Duplicate analytics client created with tag: foo.")
        }
    }

    @Test
    @Throws(Exception::class)
    fun multipleInstancesWithSameTagIsAllowedAfterShutdown() {
        Analytics.Builder(RuntimeEnvironment.application, "foo").build().shutdown()
        Analytics.Builder(RuntimeEnvironment.application, "bar").tag("foo").build()
    }

    @Test
    @Throws(Exception::class)
    fun getSnapshotInvokesStats() {
        analytics.snapshot
        Mockito.verify(stats).createSnapshot()
    }

    @Test
    @Throws(Exception::class)
    fun invalidURlsThrowAndNotCrash() {
        val connection = ConnectionFactory()

        try {
            connection.openConnection("SOME_BUSTED_URL")
            Assertions.fail("openConnection did not throw when supplied an invalid URL as expected.")
        } catch (expected: IOException) {
            Assertions.assertThat(expected).hasMessageContaining("Attempted to use malformed url")
            Assertions.assertThat(expected).isInstanceOf(IOException::class.java)
        }
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun trackApplicationLifecycleEventsInstalled() {
        Analytics.INSTANCES.clear()
        val callback = AtomicReference<DefaultLifecycleObserver>()
        doNothing()
                .whenever(lifecycle)
                .addObserver(
                        argThat<LifecycleObserver>(
                                object : NoDescriptionMatcher<LifecycleObserver>() {
                                    override fun matchesSafely(item: LifecycleObserver): Boolean {
                                        callback.set(item as DefaultLifecycleObserver)
                                        return true
                                    }
                                }))

        val mockLifecycleOwner = Mockito.mock(LifecycleOwner::class.java)

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        callback.get().onCreate(mockLifecycleOwner)

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() ==
                                                "Application Installed" &&
                                                payload.properties()
                                                        .getString("version") == "1.0.0" &&
                                                payload.properties()
                                                        .getString("build") == 100.toString()
                                    }
                                }))

        callback.get().onCreate(mockLifecycleOwner)
        Mockito.verifyNoMoreInteractions(integration) // Application Installed is not duplicated
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun trackApplicationLifecycleEventsUpdated() {
        Analytics.INSTANCES.clear()

        val packageInfo = PackageInfo()
        packageInfo.versionCode = 101
        packageInfo.versionName = "1.0.1"

        val sharedPreferences =
                RuntimeEnvironment.application.getSharedPreferences("analytics-android-qaz", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putInt("build", 100)
        editor.putString("version", "1.0.0")
        editor.apply()
        whenever(application.getSharedPreferences("analytics-android-qaz", MODE_PRIVATE))
                .thenReturn(sharedPreferences)

        val packageManager = Mockito.mock(PackageManager::class.java)
        whenever(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo)
        whenever(application.packageName).thenReturn("com.foo")
        whenever(application.packageManager).thenReturn(packageManager)

        val callback = AtomicReference<DefaultLifecycleObserver>()
        doNothing()
                .whenever(lifecycle)
                .addObserver(
                        argThat<LifecycleObserver>(
                                object : NoDescriptionMatcher<LifecycleObserver>() {
                                    override fun matchesSafely(item: LifecycleObserver): Boolean {
                                        callback.set(item as DefaultLifecycleObserver)
                                        return true
                                    }
                                }))

        val mockLifecycleOwner = Mockito.mock(LifecycleOwner::class.java)

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        callback.get().onCreate(mockLifecycleOwner)

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() ==
                                                "Application Updated" &&
                                                payload.properties()
                                                        .getString("previous_version") == "1.0.0" &&
                                                payload.properties()
                                                        .getString("previous_build") == 100.toString() &&
                                                payload.properties()
                                                        .getString("version") == "1.0.1" &&
                                                payload.properties()
                                                        .getString("build") == 101.toString()
                                    }
                                }))
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun recordScreenViews() {
        Analytics.INSTANCES.clear()

        val callback = AtomicReference<ActivityLifecycleCallbacks>()
        Mockito.doNothing()
                .`when`(application)
                .registerActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        callback.set(item)
                                        return true
                                    }
                                }))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                false,
                CountDownLatch(0),
                true,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        val activity = Mockito.mock(Activity::class.java)
        val packageManager = Mockito.mock(PackageManager::class.java)
        val info = Mockito.mock(ActivityInfo::class.java)

        whenever(activity.packageManager).thenReturn(packageManager)
        //noinspection WrongConstant
        whenever(packageManager.getActivityInfo(any(ComponentName::class.java), eq(PackageManager.GET_META_DATA)))
                .thenReturn(info)
        whenever(info.loadLabel(packageManager)).thenReturn("Foo")

        callback.get().onActivityStarted(activity)

        analytics.screen("Foo")
        Mockito.verify(integration)
                .screen(
                        argThat<ScreenPayload>(
                                object : NoDescriptionMatcher<ScreenPayload>() {
                                    override fun matchesSafely(payload: ScreenPayload): Boolean {
                                        return payload.name() == "Foo"
                                    }
                                }))
    }

    @Test
    fun trackDeepLinks() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<ActivityLifecycleCallbacks>()
        Mockito.doNothing()
                .whenever(application)
                .registerActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        callback.set(item)
                                        return true
                                    }
                                }))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                true,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        val expectedURL = "app://track.com/open?utm_id=12345&gclid=abcd&nope="

        val activity = Mockito.mock(Activity::class.java)
        val intent = Mockito.mock(Intent::class.java)
        val uri = Uri.parse(expectedURL)

        whenever(intent.data).thenReturn(uri)
        whenever(activity.intent).thenReturn(intent)

        callback.get().onActivityCreated(activity, Bundle())

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Deep Link Opened" &&
                                                payload.properties()
                                                        .getString("url") == expectedURL &&
                                                payload.properties()
                                                        .getString("gclid") == "abcd" &&
                                                payload.properties()
                                                        .getString("utm_id") == "12345"
                                    }
                                }))
    }

    @Test
    fun trackDeepLinks_disabled() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<ActivityLifecycleCallbacks>()

        Mockito.doNothing()
                .whenever(application)
                .registerActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        callback.set(item)
                                        return true
                                    }
                                }))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        val expectedURL = "app://track.com/open?utm_id=12345&gclid=abcd&nope="

        val activity = Mockito.mock(Activity::class.java)
        val intent = Mockito.mock(Intent::class.java)
        val uri = Uri.parse(expectedURL)

        whenever(intent.data).thenReturn(uri)
        whenever(activity.intent).thenReturn(intent)

        Mockito.verify(integration, Mockito.never())
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Deep Link Opened" &&
                                                payload.properties()
                                                        .getString("url") == expectedURL &&
                                                payload.properties()
                                                        .getString("gclid") == "abcd" &&
                                                payload.properties()
                                                        .getString("utm_id") == "12345"
                                    }
                                }))
    }

    @Test
    fun trackDeepLinks_null() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<ActivityLifecycleCallbacks>()

        Mockito.doNothing()
                .whenever(application)
                .registerActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        callback.set(item)
                                        return true
                                    }
                                }))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        val activity = Mockito.mock(Activity::class.java)

        whenever(activity.intent).thenReturn(null)

        callback.get().onActivityCreated(activity, Bundle())

        Mockito.verify(integration, Mockito.never())
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Deep Link Opened"
                                    }
                                }))
    }

    @Test
    fun trackDeepLinks_nullData() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<ActivityLifecycleCallbacks>()

        Mockito.doNothing()
                .whenever(application)
                .registerActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        callback.set(item)
                                        return true
                                    }
                                }))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        val activity = Mockito.mock(Activity::class.java)

        val intent = Mockito.mock(Intent::class.java)

        whenever(activity.intent).thenReturn(intent)
        whenever(intent.data).thenReturn(null)

        callback.get().onActivityCreated(activity, Bundle())

        Mockito.verify(integration, Mockito.never())
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Deep Link Opened"
                                    }
                                }))
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun registerActivityLifecycleCallbacks() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<ActivityLifecycleCallbacks>()

        Mockito.doNothing()
                .whenever(application)
                .registerActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        callback.set(item)
                                        return true
                                    }
                                }))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        val activity = Mockito.mock(Activity::class.java)
        val bundle = Bundle()

        callback.get().onActivityCreated(activity, bundle)
        Mockito.verify(integration).onActivityCreated(activity, bundle)

        callback.get().onActivityStarted(activity)
        Mockito.verify(integration).onActivityStarted(activity)

        callback.get().onActivityResumed(activity)
        Mockito.verify(integration).onActivityResumed(activity)

        callback.get().onActivityPaused(activity)
        Mockito.verify(integration).onActivityPaused(activity)

        callback.get().onActivityStopped(activity)
        Mockito.verify(integration).onActivityStopped(activity)

        callback.get().onActivitySaveInstanceState(activity, bundle)
        Mockito.verify(integration).onActivitySaveInstanceState(activity, bundle)

        callback.get().onActivityDestroyed(activity)
        Mockito.verify(integration).onActivityDestroyed(activity)

        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun trackApplicationLifecycleEventsApplicationOpened() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<DefaultLifecycleObserver>()

        Mockito.doNothing()
                .whenever(lifecycle)
                .addObserver(
                        argThat<LifecycleObserver>(
                                object : NoDescriptionMatcher<LifecycleObserver>() {
                                    override fun matchesSafely(item: LifecycleObserver): Boolean {
                                        callback.set(item as DefaultLifecycleObserver)
                                        return true
                                    }
                                }))

        val mockLifecycleOwner = Mockito.mock(LifecycleOwner::class.java)

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        callback.get().onCreate(mockLifecycleOwner)
        callback.get().onStart(mockLifecycleOwner)

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Application Opened" && payload.properties().getString("version") == "1.0.0" && payload.properties().getString("build") == 100.toString() && !payload.properties().getBoolean("from_background", true)
                                    }
                                }))
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun trackApplicationLifecycleEventsApplicationBackgrounded() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<DefaultLifecycleObserver>()

        Mockito.doNothing()
                .whenever(lifecycle)
                .addObserver(
                        argThat<LifecycleObserver>(
                                object : NoDescriptionMatcher<LifecycleObserver>() {
                                    override fun matchesSafely(item: LifecycleObserver): Boolean {
                                        callback.set(item as DefaultLifecycleObserver)
                                        return true
                                    }
                                }))

        val mockLifecycleOwner = Mockito.mock(LifecycleOwner::class.java)

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        val backgroundedActivity = Mockito.mock(Activity::class.java)
        whenever(backgroundedActivity.isChangingConfigurations).thenReturn(false)

        callback.get().onCreate(mockLifecycleOwner)
        callback.get().onResume(mockLifecycleOwner)
        callback.get().onStop(mockLifecycleOwner)

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Application Backgrounded"
                                    }
                                }))
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun trackApplicationLifecycleEventsApplicationForegrounded() {
        Analytics.INSTANCES.clear()

        val callback =
                AtomicReference<DefaultLifecycleObserver>()

        Mockito.doNothing()
                .whenever(lifecycle)
                .addObserver(
                        argThat<LifecycleObserver>(
                                object : NoDescriptionMatcher<LifecycleObserver>() {
                                    override fun matchesSafely(item: LifecycleObserver): Boolean {
                                        callback.set(item as DefaultLifecycleObserver)
                                        return true
                                    }
                                }))

        val mockLifecycleOwner = Mockito.mock(LifecycleOwner::class.java)

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        callback.get().onCreate(mockLifecycleOwner)
        callback.get().onStart(mockLifecycleOwner)
        callback.get().onStop(mockLifecycleOwner)
        callback.get().onStart(mockLifecycleOwner)

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Application Backgrounded"
                                    }
                                }))

        Mockito.verify(integration)
                .track(
                        argThat<TrackPayload>(
                                object : NoDescriptionMatcher<TrackPayload>() {
                                    override fun matchesSafely(payload: TrackPayload): Boolean {
                                        return payload.event() == "Application Opened" && payload.properties().getBoolean("from_background", false)
                                    }
                                }))
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun unregisterActivityLifecycleCallbacks() {
        Analytics.INSTANCES.clear()

        val registeredCallback = AtomicReference<ActivityLifecycleCallbacks>()
        val unregisteredCallback = AtomicReference<ActivityLifecycleCallbacks>()

        Mockito.doNothing()
                .whenever(application)
                .registerActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        registeredCallback.set(item)
                                        return true
                                    }
                                }))
        Mockito.doNothing()
                .whenever(application)
                .unregisterActivityLifecycleCallbacks(
                        argThat<ActivityLifecycleCallbacks>(
                                object : NoDescriptionMatcher<ActivityLifecycleCallbacks>() {
                                    override fun matchesSafely(item: ActivityLifecycleCallbacks): Boolean {
                                        unregisteredCallback.set(item)
                                        return true
                                    }
                                }))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        Assertions.assertThat(analytics.shutdown).isFalse()
        analytics.shutdown()

        // Same callback was registered and unregistered
        Assertions.assertThat(analytics.activityLifecycleCallback).isSameAs(registeredCallback.get())
        Assertions.assertThat(analytics.activityLifecycleCallback).isSameAs(unregisteredCallback.get())

        val activity = Mockito.mock(Activity::class.java)
        val bundle = Bundle()

        // Verify callbacks do not call through after shutdown
        registeredCallback.get().onActivityCreated(activity, bundle)
        Mockito.verify(integration, never()).onActivityCreated(activity, bundle)

        registeredCallback.get().onActivityStarted(activity)
        Mockito.verify(integration, Mockito.never()).onActivityStarted(activity)

        registeredCallback.get().onActivityResumed(activity)
        Mockito.verify(integration, Mockito.never()).onActivityResumed(activity)

        registeredCallback.get().onActivityPaused(activity)
        Mockito.verify(integration, Mockito.never()).onActivityPaused(activity)

        registeredCallback.get().onActivityStopped(activity)
        Mockito.verify(integration, Mockito.never()).onActivityStopped(activity)

        registeredCallback.get().onActivitySaveInstanceState(activity, bundle)
        Mockito.verify(integration, Mockito.never()).onActivitySaveInstanceState(activity, bundle)

        registeredCallback.get().onActivityDestroyed(activity)
        Mockito.verify(integration, Mockito.never()).onActivityDestroyed(activity)

        Mockito.verifyNoMoreInteractions(integration)
    }

    @Test
    @Throws(NameNotFoundException::class)
    fun removeLifecycleObserver() {
        Analytics.INSTANCES.clear()

        val registeredCallback = AtomicReference<DefaultLifecycleObserver>()
        val unregisteredCallback = AtomicReference<DefaultLifecycleObserver>()

        Mockito.doNothing()
                .whenever(lifecycle)
                .addObserver(
                        argThat<LifecycleObserver>(
                                object : NoDescriptionMatcher<LifecycleObserver>() {
                                    override fun matchesSafely(item: LifecycleObserver): Boolean {
                                        registeredCallback.set(item as DefaultLifecycleObserver)
                                        return true
                                    }
                                }))
        Mockito.doNothing()
                .whenever(lifecycle)
                .removeObserver(
                        argThat<LifecycleObserver>(
                                object : NoDescriptionMatcher<LifecycleObserver>() {
                                    override fun matchesSafely(item: LifecycleObserver): Boolean {
                                        unregisteredCallback.set(item as DefaultLifecycleObserver)
                                        return true
                                    }
                                }))
        val mockLifecycleOwner = Mockito.mock(LifecycleOwner::class.java)

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                false,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        Assertions.assertThat(analytics.shutdown).isFalse()
        analytics.shutdown()
        val lifecycleObserverSpy = Mockito.spy(analytics.activityLifecycleCallback)
        // Same callback was registered and unregistered
        Assertions.assertThat(analytics.activityLifecycleCallback).isSameAs(registeredCallback.get())
        Assertions.assertThat(analytics.activityLifecycleCallback).isSameAs(unregisteredCallback.get())

        // Verify callbacks do not call through after shutdown
        registeredCallback.get().onCreate(mockLifecycleOwner)
        Mockito.verify(lifecycleObserverSpy, Mockito.never()).onCreate(mockLifecycleOwner)

        registeredCallback.get().onStop(mockLifecycleOwner)
        Mockito.verify(lifecycleObserverSpy, Mockito.never()).onStop(mockLifecycleOwner)

        registeredCallback.get().onStart(mockLifecycleOwner)
        Mockito.verify(lifecycleObserverSpy, Mockito.never()).onStart(mockLifecycleOwner)

        Mockito.verifyNoMoreInteractions(lifecycleObserverSpy)
    }

    @Test
    @Throws(IOException::class)
    fun loadNonEmptyDefaultProjectSettingsOnNetworkError() {
        Analytics.INSTANCES.clear()
        // Make project download empty map and thus use default settings
        whenever(projectSettingsCache.get()).thenReturn(null)
        whenever(client.fetchSettings()).thenThrow(IOException::class.java) // Simulate network error

        val defaultProjectSettings =
                ValueMap()
                        .putValue(
                                "integrations",
                                ValueMap()
                                        .putValue(
                                                "Adjust",
                                                ValueMap()
                                                        .putValue("appToken", "<>")
                                                        .putValue("trackAttributionData", true)))

        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                defaultProjectSettings,
                lifecycle,
                false)

        Assertions.assertThat(analytics.projectSettings).hasSize(2)
        Assertions.assertThat(analytics.projectSettings).containsKey("integrations")
        Assertions.assertThat(analytics.projectSettings.integrations()).hasSize(2)
        Assertions.assertThat(analytics.projectSettings.integrations()).containsKey("Segment.io")
        Assertions.assertThat(analytics.projectSettings.integrations()).containsKey("Adjust")
    }

    @Test
    @Throws(IOException::class)
    fun loadEmptyDefaultProjectSettingsOnNetworkError() {
        Analytics.INSTANCES.clear()
        // Make project download empty map and thus use default settings
        whenever(projectSettingsCache.get()).thenReturn(null)
        whenever(client.fetchSettings()).thenThrow(IOException::class.java) // Simulate network error

        val defaultProjectSettings = ValueMap()
        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                defaultProjectSettings,
                lifecycle,
                false)

        Assertions.assertThat(analytics.projectSettings).hasSize(2)
        Assertions.assertThat(analytics.projectSettings).containsKey("integrations")
        Assertions.assertThat(analytics.projectSettings.integrations()).hasSize(1)
        Assertions.assertThat(analytics.projectSettings.integrations()).containsKey("Segment.io")
    }

    @Test
    @Throws(IOException::class)
    fun overwriteSegmentIoIntegration() {
        Analytics.INSTANCES.clear()
        // Make project download empty map and thus use default settings
        whenever(projectSettingsCache.get()).thenReturn(null)
        whenever(client.fetchSettings()).thenThrow(IOException::class.java) // Simulate network error

        val defaultProjectSettings = ValueMap()
                .putValue(
                        "integrations",
                        ValueMap()
                                .putValue(
                                        "Segment.io",
                                        ValueMap()
                                                .putValue("appToken", "<>")
                                                .putValue("trackAttributionData", true)))
        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                defaultProjectSettings,
                lifecycle,
                false)

        Assertions.assertThat(analytics.projectSettings).hasSize(2)
        Assertions.assertThat(analytics.projectSettings).containsKey("integrations")
        Assertions.assertThat(analytics.projectSettings.integrations()).containsKey("Segment.io")
        Assertions.assertThat(analytics.projectSettings.integrations()).hasSize(1)
        Assertions.assertThat(analytics.projectSettings.integrations().getValueMap("Segment.io"))
                .hasSize(3)
        Assertions.assertThat(analytics.projectSettings.integrations().getValueMap("Segment.io"))
                .containsKey("apiKey")
        Assertions.assertThat(analytics.projectSettings.integrations().getValueMap("Segment.io"))
                .containsKey("appToken")
        Assertions.assertThat(analytics.projectSettings.integrations().getValueMap("Segment.io"))
                .containsKey("trackAttributionData")
    }

    @Test
    fun overridingOptionsDoesNotModifyGlobalAnalytics() {
        analytics.track("event", null, Options().putContext("testProp", true))
        val payload = ArgumentCaptor.forClass(TrackPayload::class.java)
        Mockito.verify(integration).track(payload.capture())
        Assertions.assertThat(payload.value.context()).containsKey("testProp")
        Assertions.assertThat(payload.value.context()["testProp"]).isEqualTo(true)
        Assertions.assertThat(analytics.analyticsContext).doesNotContainKey("testProp")
    }

    @Test
    fun overridingOptionsWithDefaultOptionsPlusAdditional() {
        analytics.track("event", null, analytics.getDefaultOptions().putContext("testProp", true))
        val payload = ArgumentCaptor.forClass(TrackPayload::class.java)
        Mockito.verify(integration).track(payload.capture())
        Assertions.assertThat(payload.value.context()).containsKey("testProp")
        Assertions.assertThat(payload.value.context()["testProp"]).isEqualTo(true)
        Assertions.assertThat(analytics.analyticsContext).doesNotContainKey("testProp")
    }

    @Test
    fun enableExperimentalNanosecondResolutionTimestamps() {
        Analytics.INSTANCES.clear()
        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                true)

        analytics.track("event")
        val payload = ArgumentCaptor.forClass(TrackPayload::class.java)
        Mockito.verify(integration).track(payload.capture())
        val timestamp = payload.value["timestamp"] as String
        Assertions.assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.\\d{9}Z")
    }

    @Test
    fun disableExperimentalNanosecondResolutionTimestamps() {
        Analytics.INSTANCES.clear()
        analytics = Analytics(
                application,
                networkExecutor,
                stats,
                traitsCache,
                analyticsContext,
                defaultOptions,
                Logger.with(Analytics.LogLevel.NONE),
                "qaz", listOf(factory),
                client,
                Cartographer.INSTANCE,
                projectSettingsCache,
                "foo",
                DEFAULT_FLUSH_QUEUE_SIZE,
                DEFAULT_FLUSH_INTERVAL.toLong(),
                analyticsExecutor,
                true,
                CountDownLatch(0),
                false,
                false,
                false,
                optOut,
                Crypto.none(), emptyList(), emptyMap(),
                ValueMap(),
                lifecycle,
                false)

        analytics.track("event")
        val payload = ArgumentCaptor.forClass(TrackPayload::class.java)
        Mockito.verify(integration).track(payload.capture())
        val timestamp = payload.value["timestamp"] as String
        Assertions.assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.\\d{3}Z")
    }
}