/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.internal.watch

import com.google.common.collect.ImmutableSet
import org.apache.commons.io.FileUtils
import org.gradle.cache.GlobalCacheLocations
import org.gradle.integtests.fixtures.ToBeFixedForInstantExecution
import org.gradle.internal.os.OperatingSystem
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import org.gradle.util.TextUtil
import spock.lang.Issue
import spock.lang.Unroll

class WatchedDirectoriesFileSystemWatchingIntegrationTest extends AbstractFileSystemWatchingIntegrationTest {

    def "watches the project directory"() {
        buildFile << """
            apply plugin: "application"

            application.mainClassName = "Main"
        """

        def mainSourceFileRelativePath = "src/main/java/Main.java"
        def mainSourceFile = file(mainSourceFileRelativePath)
        mainSourceFile.text = sourceFileWithGreeting("Hello World!")

        when:
        withWatchFs().run "run", "--info"
        then:
        assertWatchedRootDirectories([ImmutableSet.of(testDirectory)])
    }

    def "watches the project directory when buildSrc is present"() {
        def taskSourceFile = file("buildSrc/src/main/java/PrinterTask.java")
        taskSourceFile.text = taskWithGreeting("Hello from original task!")

        buildFile << """
            task hello(type: PrinterTask)
        """

        when:
        withWatchFs().run "hello", "--info"
        then:
        outputContains "Hello from original task!"
        assertWatchedRootDirectories([ImmutableSet.of(testDirectory)] * 2)
    }

    @ToBeFixedForInstantExecution(because = "composite build not yet supported")
    @Requires(TestPrecondition.NOT_WINDOWS) // https://github.com/gradle/gradle-private/issues/3116
    def "works with composite build"() {
        buildTestFixture.withBuildInSubDir()
        def includedBuild = singleProjectBuild("includedBuild") {
            buildFile << """
                apply plugin: 'java'
            """
        }
        def consumer = singleProjectBuild("consumer") {
            buildFile << """
                apply plugin: 'java'

                dependencies {
                    implementation "org.test:includedBuild:1.0"
                }
            """
            settingsFile << """
                includeBuild("../includedBuild")
            """
        }
        executer.beforeExecute {
            inDirectory(consumer)
        }
        def expectedBuildRootDirectories = [
            ImmutableSet.of(consumer),
            ImmutableSet.of(consumer, includedBuild)
        ]

        when:
        withWatchFs().run "assemble", "--info"
        then:
        executedAndNotSkipped(":includedBuild:jar")
        assertWatchedRootDirectories(expectedBuildRootDirectories)

        when:
        withWatchFs().run("assemble", "--info")
        then:
        skipped(":includedBuild:jar")
        assertWatchedRootDirectories(expectedBuildRootDirectories)

        when:
        includedBuild.file("src/main/java/NewClass.java")  << "public class NewClass {}"
        withWatchFs().run("assemble")
        then:
        executedAndNotSkipped(":includedBuild:jar")
    }

    @Requires(TestPrecondition.NOT_WINDOWS) // https://github.com/gradle/gradle-private/issues/3116
    @ToBeFixedForInstantExecution(because = "GradleBuild task is not yet supported")
    def "works with GradleBuild task"() {
        buildTestFixture.withBuildInSubDir()
        def buildInBuild = singleProjectBuild("buildInBuild") {
            buildFile << """
                apply plugin: 'java'
            """
        }
        def consumer = singleProjectBuild("consumer") {
            buildFile << """
                apply plugin: 'java'

                task buildInBuild(type: GradleBuild) {
                    startParameter.currentDir = file('../buildInBuild')
                }
            """
        }
        executer.beforeExecute {
            inDirectory(consumer)
        }
        def expectedBuildRootDirectories = [
            ImmutableSet.of(consumer),
            ImmutableSet.of(consumer, buildInBuild)
        ]

        when:
        withWatchFs().run "buildInBuild", "--info"
        then:
        assertWatchedRootDirectories(expectedBuildRootDirectories)

        when:
        withWatchFs().run "buildInBuild", "--info"
        then:
        assertWatchedRootDirectories(expectedBuildRootDirectories)
    }

    def "gracefully handle the root project not being available"() {
        settingsFile << """
            throw new RuntimeException("Boom")
        """

        when:
        withWatchFs().fails("help")
        then:
        failureHasCause("Boom")
    }

    def "root project dir does not need to exist"() {
        def settingsDir = file("gradle")
        def settingsFile = settingsDir.file("settings.gradle")
        settingsFile << """
            rootProject.projectDir = new File(settingsDir, '../root')
            include 'sub'
            project(':sub').projectDir = new File(settingsDir, '../sub')
        """
        file("sub/build.gradle") << "task thing"

        when:
        inDirectory(settingsDir)
        withWatchFs().run("thing")
        then:
        executed ":sub:thing"

    }

    @Unroll
    def "detects when a task removes the build directory #buildDir"() {
        buildFile << """
            apply plugin: 'base'

            project.buildDir = file("${buildDir}")

            task myClean {
                doLast {
                    delete buildDir
                }
            }

            task producer {
                def outputFile = new File(buildDir, "some/file/in/buildDir/output.txt")
                outputs.file(outputFile)
                doLast {
                    outputFile.parentFile.mkdirs()
                    outputFile.text = "Output"
                }
            }
        """

        when:
        withWatchFs().run "producer"
        then:
        executedAndNotSkipped ":producer"

        when:
        withWatchFs().run "myClean"
        withWatchFs().run "producer"
        then:
        executedAndNotSkipped ":producer"

        where:
        buildDir << ["build", "build/myProject"]
    }

    @Issue("https://github.com/gradle/gradle/issues/12614")
    def "can remove watched directory after all files inside have been removed"() {
        // This test targets Windows, where watched directories can't be deleted.

        def projectDir = file("projectDir")
        projectDir.file("build.gradle") << """
            apply plugin: "java-library"
        """
        projectDir.file("settings.gradle").createFile()

        def mainSourceFile = projectDir.file("src/main/java/Main.java")
        mainSourceFile.text = sourceFileWithGreeting("Hello World!")

        when:
        inDirectory(projectDir)
        withWatchFs().run "assemble"
        then:
        executedAndNotSkipped ":assemble"

        when:
        FileUtils.cleanDirectory(projectDir)
        waitForChangesToBePickedUp()
        then:
        projectDir.delete()
    }

    def "the caches dir in the Gradle user home is part of the global caches"() {
        def globalCachesLocation = executer.gradleUserHomeDir.file('caches').absolutePath
        buildFile << """
            assert services.get(${GlobalCacheLocations.name}).isInsideGlobalCache('${TextUtil.escapeString(globalCachesLocation)}')
        """

        expect:
        succeeds "help"
    }

    void assertWatchedRootDirectories(List<Set<File>> expectedWatchedRootDirectories) {
        if (OperatingSystem.current().linux) {
            // There is no info logging for non-hierarchical watchers
            return
        }
        assert determineWatchedBuildRootDirectories(output) == expectedWatchedRootDirectories
    }

    private static List<Set<File>> determineWatchedBuildRootDirectories(String output) {
        output.readLines()
            .findAll { it.contains("] as root project directories") }
            .collect { line ->
                def matcher = line =~ /Now considering watching \[(.*)\] as root project directories/
                String directories = matcher[0][1]
                return directories.split(', ').collect { new File(it) } as Set
            }
    }
}
