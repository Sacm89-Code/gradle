/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.smoketests

import org.gradle.integtests.fixtures.UnsupportedWithConfigurationCache

import java.util.jar.JarOutputStream

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

abstract class AndroidSantaTrackerSmokeTest extends AbstractAndroidSantaTrackerSmokeTest {
}

class AndroidSantaTrackerDeprecationSmokeTest extends AndroidSantaTrackerSmokeTest {
    @UnsupportedWithConfigurationCache(iterationMatchers = [AGP_NO_CC_ITERATION_MATCHER])
    def "check deprecation warnings produced by building Santa Tracker (agp=#agpVersion)"() {

        given:
        AGP_VERSIONS.assumeCurrentJavaVersionIsSupportedBy(agpVersion)

        and:
        def checkoutDir = temporaryFolder.createDir("checkout")
        setupCopyOfSantaTracker(checkoutDir)

        when:
        buildLocationMaybeExpectingWorkerExecutorDeprecation(checkoutDir, agpVersion)

        then:
        assertConfigurationCacheStateStored()

        where:
        agpVersion << TESTED_AGP_VERSIONS
    }
}

class AndroidSantaTrackerIncrementalCompilationSmokeTest extends AndroidSantaTrackerSmokeTest {
    @UnsupportedWithConfigurationCache(iterationMatchers = [AGP_NO_CC_ITERATION_MATCHER])
    def "incremental Java compilation works for Santa Tracker (agp=#agpVersion)"() {

        given:
        AGP_VERSIONS.assumeCurrentJavaVersionIsSupportedBy(agpVersion)

        and:
        def checkoutDir = temporaryFolder.createDir("checkout")
        setupCopyOfSantaTracker(checkoutDir)

        and:
        def pathToClass = "com/google/android/apps/santatracker/tracker/ui/BottomSheetBehavior"
        def fileToChange = checkoutDir.file("tracker/src/main/java/${pathToClass}.java")
        def compiledClassFile = checkoutDir.file("tracker/build/intermediates/javac/debug/classes/${pathToClass}.class")

        when:
        SantaTrackerConfigurationCacheWorkaround.beforeBuild(checkoutDir, homeDir)
        def result = buildLocationMaybeExpectingWorkerExecutorDeprecation(checkoutDir, agpVersion)
        def md5Before = compiledClassFile.md5Hash

        then:
        result.task(":tracker:compileDebugJavaWithJavac").outcome == SUCCESS
        assertConfigurationCacheStateStored()

        when:
        fileToChange.replace("computeCurrentVelocity(1000", "computeCurrentVelocity(2000")
        SantaTrackerConfigurationCacheWorkaround.beforeBuild(checkoutDir, homeDir)
        buildLocationMaybeExpectingWorkerExecutorDeprecation(checkoutDir, agpVersion)
        def md5After = compiledClassFile.md5Hash

        then:
        result.task(":tracker:compileDebugJavaWithJavac").outcome == SUCCESS
        // TODO - this is here because AGP >=7.4 reads build/generated/source/kapt/debug at configuration time
        if (agpVersion.startsWith('7.3')) {
            assertConfigurationCacheStateLoaded()
        } else {
            assertConfigurationCacheStateStored()
        }
        md5After != md5Before

        where:
        agpVersion << TESTED_AGP_VERSIONS
    }
}

class AndroidSantaTrackerLintSmokeTest extends AndroidSantaTrackerSmokeTest {
    @UnsupportedWithConfigurationCache(iterationMatchers = [AGP_NO_CC_ITERATION_MATCHER])
    def "can lint Santa-Tracker (agp=#agpVersion)"() {

        given:
        AGP_VERSIONS.assumeCurrentJavaVersionIsSupportedBy(agpVersion)

        and:
        def checkoutDir = temporaryFolder.createDir("checkout")
        setupCopyOfSantaTracker(checkoutDir)

        when:
        def runner = runnerForLocation(
            checkoutDir, agpVersion,
            "common:lintDebug", "playgames:lintDebug", "doodles-lib:lintDebug"
        )
        SantaTrackerConfigurationCacheWorkaround.beforeBuild(checkoutDir, homeDir)
        // Use --continue so that a deterministic set of tasks runs when some tasks fail
        runner.withArguments(runner.arguments + "--continue")
        def result = runner.buildAndFail()

        then:
        assertConfigurationCacheStateStored()
        result.output.contains("Lint found errors in the project; aborting build.")

        when:
        runner = runnerForLocation(
            checkoutDir, agpVersion,
            "common:lintDebug", "playgames:lintDebug", "doodles-lib:lintDebug"
        )
        SantaTrackerConfigurationCacheWorkaround.beforeBuild(checkoutDir, homeDir)
        runner.withArguments(runner.arguments + "--continue")
        result = runner.buildAndFail()

        then:
        assertConfigurationCacheStateLoaded()
        result.output.contains("Lint found errors in the project; aborting build.")

        where:
        agpVersion << TESTED_AGP_VERSIONS.findAll { !it.startsWith("4.")}
    }
}

class SantaTrackerConfigurationCacheWorkaround {
    static void beforeBuild(File checkoutDir, File gradleHome) {
        // Workaround for Android Gradle plugin checking for the presence of these directories at configuration time,
        // which invalidates configuration cache if their presence changes. Create these directories before the first build.
        // See: https://android.googlesource.com/platform/tools/base/+/studio-master-dev/build-system/gradle-core/src/main/java/com/android/build/gradle/tasks/ShaderCompile.java#120
        // TODO: remove this once AGP stops checking for the existence of these directories at configuration time
        checkoutDir.listFiles().findAll { it.isDirectory() && new File(it, "build.gradle").exists() }.each {
            new File(it, "build/intermediates/merged_shaders/debug/out").mkdirs()
            new File(it, "build/intermediates/merged_shaders/debugUnitTest/out").mkdirs()
            new File(it, "build/intermediates/merged_shaders/debugAndroidTest/out").mkdirs()
            new File(it, "build/intermediates/merged_shaders/release/out").mkdirs()
            new File(it, "build/intermediates/merged_shaders/releaseAndroidTest/out").mkdirs()
        }
        File androidAnalyticsSetting = new File(System.getProperty("user.home"), ".android/analytics.settings")
        if (!androidAnalyticsSetting.exists()) {
            androidAnalyticsSetting.parentFile.mkdirs()
            androidAnalyticsSetting.createNewFile()
        }
        def androidFakeDependency = new File(gradleHome, "android/FakeDependency.jar")
        if (!androidFakeDependency.exists()) {
            androidFakeDependency.parentFile.mkdirs()
            new JarOutputStream(new FileOutputStream(androidFakeDependency)).close()
        }
    }
}
