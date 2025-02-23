/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.privacy;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiObjectNotFoundException;
import android.util.Log;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.focus.activity.MainActivity;
import org.mozilla.focus.helpers.TestHelper;
import org.mozilla.focus.utils.AppConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static androidx.test.espresso.action.ViewActions.click;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mozilla.focus.fragment.FirstrunFragment.FIRSTRUN_PREF;
import static org.mozilla.focus.helpers.TestHelper.waitingTime;

/**
 * This test browses to a test site and makes sure that no traces are left on disk.
 *
 * The test uses a whitelist so it might fail as soon as you store new files on disk.
 */
@Ignore("Failing on Focus GV, disabling to avoid it: https://github.com/mozilla-mobile/focus-android/issues/4882")
@RunWith(AndroidJUnit4.class)
public class WebViewDataTest {
    private static final String LOGTAG = "WebViewDataTest";

    /**
     * A list of files Android Studio will inject into the data dir if you:x`
     * - Build with Android Studio 3.0+
     * - Have opened the "Android Profiler" tab at least once since the AS process started
     * - Run on an API 26+ device (or maybe when explicitly enabling advanced debugging on older devices)
     */
    private static final Set<String> ANDROID_PROFILER_FILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "libperfa_x86.so",
            "perfa.jar",
            "perfd",
            "libmozglue.so"
    )));

    private static final Set<String> WHITELIST_DATA_DIR_CONTENTS;
    static {
        final Set<String> whitelistDataDirContents = new HashSet<>(Arrays.asList(
                "cache",
                "code_cache",
                "shared_prefs",
                "lib",
                "app_dxmaker_cache",
                "telemetry",
                "databases",
                "app_webview",
                "app_tmpdir",
                "gv_measurements.json",
                "files",
                "app_screengrab" // The folder we store our screenshots in.
        ));

        // These profiler files should only be present local builds and we don't want to risk breaking the profiler
        // by removing them so we whitelist them. Additional details around when these files are added can be found in:
        //   https://github.com/mozilla-mobile/focus-android/issues/1842#issuecomment-348038392
        whitelistDataDirContents.addAll(ANDROID_PROFILER_FILES);
        WHITELIST_DATA_DIR_CONTENTS = Collections.unmodifiableSet(whitelistDataDirContents);
    }

    // We expect those folders to exist but they should be empty.
    private static final List<String> WHITELIST_EMPTY_FOLDERS = Collections.singletonList(
            "app_textures"
    );

    // These profiler files should only be present local builds and we don't want to risk breaking the profiler
    // by removing them so we ignore them. Additional details around when these files are added can be found in:
    //   https://github.com/mozilla-mobile/focus-android/issues/1842#issuecomment-348038392
    private static final Set<String> NO_TRACES_IGNORE_LIST =
            Collections.unmodifiableSet(new HashSet<>(ANDROID_PROFILER_FILES));

    private static final String TEST_PATH = "/copper/truck/destroy?smoke=violet#bizarre";

    /**
     * If any of those strings show up in local files then we consider the browsing session to be leaked.
     */
    private static final List<String> TEST_TRACES = Arrays.asList(
            // URL fragments
            "localhost",
            "copper",
            "truck",
            "destroy",
            "smoke",
            "violet",
            "bizarre",
            // Content from the test page
            "groovy",
            "rabbits",
            "gigantic",
            "experience",
            // Content from cookies
            "birthday",
            "armchair",
            "sphere",
            "battery",
            // Content from service worker
            "KANGAROO"
    );

    private Context appContext;
    private MockWebServer webServer;

    @Rule
    public ActivityTestRule<MainActivity> mActivityTestRule  = new ActivityTestRule<MainActivity>(MainActivity.class) {
        @Override
        protected void beforeActivityLaunched() {
            super.beforeActivityLaunched();

            // Klar is used to test Geckoview. make sure it's set to Gecko
            TestHelper.selectGeckoForKlar();

            appContext = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext()
                    .getApplicationContext();

            PreferenceManager.getDefaultSharedPreferences(appContext)
                    .edit()
                    .putBoolean(FIRSTRUN_PREF, true)
                    .apply();

            // This test fails permanently on webview, see https://github.com/mozilla-mobile/focus-android/issues/2940")
            // For now, enable on Klar build only
            org.junit.Assume.assumeTrue(AppConstants.INSTANCE.isKlarBuild());

            webServer = new MockWebServer();

            try {
                webServer.enqueue(new MockResponse()
                        .setBody(TestHelper.readTestAsset("test.html"))
                        .addHeader("Set-Cookie", "sphere=battery; Expires=Wed, 21 Oct 2035 07:28:00 GMT;"));
                webServer.enqueue(new MockResponse()
                        .setBody(TestHelper.readTestAsset("service-worker.js"))
                        .setHeader("Content-Type", "text/javascript"));
                webServer.start();
            } catch (IOException e) {
                throw new AssertionError("Could not start web server", e);
            }
        }

        @Override
        protected void afterActivityFinished() {
            super.afterActivityFinished();

            try {
                 webServer.shutdown();
            } catch (IOException e) {
                throw new AssertionError("Could not stop web server", e);
            }
        }
    };

    @After
    public void tearDown() {
        mActivityTestRule.getActivity().finishAndRemoveTask();
    }

    @Test
    @Ignore("Refactoring is required because of erase and tabs counter relocation")
    public void DeleteWebViewDataTest() throws InterruptedException, UiObjectNotFoundException, IOException {
        // Load website with service worker
        TestHelper.inlineAutocompleteEditText.waitForExists(waitingTime);
        TestHelper.inlineAutocompleteEditText.clearTextField();
        TestHelper.inlineAutocompleteEditText.setText(webServer.url(TEST_PATH).toString());
        TestHelper.hint.waitForExists(waitingTime);
        TestHelper.pressEnterKey();
        Assert.assertTrue(TestHelper.webView.waitForExists(waitingTime));

        // Assert website is loaded
        // check for disappearance of progress bar (as content won't load in geckoview)
        TestHelper.progressBar.waitUntilGone(waitingTime);

        // Erase browsing session
        TestHelper.floatingEraseButton.perform(click());
        TestHelper.erasedMsg.waitForExists(waitingTime);
        Assert.assertTrue(TestHelper.inlineAutocompleteEditText.exists());
        TestHelper.waitForIdle();

        // Make sure all resources have been loaded from the web server
        assertPathsHaveBeenRequested(webServer,
                "/copper/truck/destroy?smoke=violet", // Actual URL
                "/copper/truck/service-worker.js"); // Our service worker is installed

        // Now let's assert that there are no surprises in the data directory
        final File dataDir = new File(appContext.getApplicationInfo().dataDir);
        assertTrue("App data directory should exist", dataDir.exists());

        final File webViewDirectory = new File(dataDir, "app_webview");
        assertTrue("WebView directory should exist", webViewDirectory.exists());
        assertTrue("WebView directory contains several subdirectories", webViewDirectory.list().length <= 3);
        assertTrue("WebView subdirectories may be one of several",
                webViewDirectory.list()[0].equals("Service Worker")
                        || webViewDirectory.list()[0].equals("GPUCache")
                        || webViewDirectory.list()[0].equals("Local Storage"));

        assertCacheDirContentsPostErase();

        for (final String name : dataDir.list()) {
            if (WHITELIST_EMPTY_FOLDERS.contains(name)) {
                final File folder = new File(dataDir, name);
                assertTrue(" Check +'" + name + "' is empty folder", folder.isDirectory() && folder.list().length == 0);
                continue;
            }

            assertTrue("Expected file '" + name + "' to be in the app's data dir whitelist",
                    WHITELIST_DATA_DIR_CONTENTS.contains(name));
        }

        assertNoTraces(dataDir);
    }

    private void assertPathsHaveBeenRequested(MockWebServer webServer, String... paths) throws InterruptedException {
        final List<String> expectedPaths = new ArrayList<>(paths.length);
        Collections.addAll(expectedPaths, paths);

        RecordedRequest request;

        while ((request = webServer.takeRequest(waitingTime, TimeUnit.MILLISECONDS)) != null) {
            if (!expectedPaths.remove(request.getPath())) {
                throw new AssertionError("Unknown path requested: " + request.getPath());
            }
        }

        if (!expectedPaths.isEmpty()) {
            throw new AssertionError("Expected paths not requested: " + expectedPaths);
        }
    }

    private void assertNoTraces(File directory) throws IOException {
        for (final String name : directory.list()) {
            final File file = new File(directory, name);

            if (file.isDirectory()) {
                assertNoTraces(file);
                continue;
            }

            if (NO_TRACES_IGNORE_LIST.contains(name)) {
                Log.d(LOGTAG, "assertNoTraces: Ignoring file '" + name + "'...");
                continue;
            }

            if (!file.exists()) {
                continue;
            }

            final String content = TestHelper.readFileToString(file);
            for (String trace : TEST_TRACES) {
                assertFalse("File '" + name + "' should not contain any traces of browser session (" + trace + ", path=" + file.getAbsolutePath() + ")",
                        content.contains(trace));
            }
        }
    }

    private void assertCacheDirContentsPostErase() {
        final File cacheDir = appContext.getCacheDir();
        assertTrue(cacheDir.isDirectory());

        final File[] cacheContents = cacheDir.listFiles();
        assertTrue("cache contents directory may contain subdirectories", cacheContents.length <= 3);

        // Concern: different versions of WebView may have different structures to their files:
        // we'll cross that bridge when we come to it.
        final File webViewCacheDir = cacheContents[0];
        assertEquals("sentry-buffered-events", webViewCacheDir.getName());
        assertTrue(webViewCacheDir.isDirectory());

        final List<File> webviewCacheContents = Arrays.asList(webViewCacheDir.listFiles());
        assertTrue("webviewCacheContents directory may contain subdirectories", webviewCacheContents.size() <= 3);
    }


}
