@file:Suppress("UnstableApiUsage")

package com.autonomousapps

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.tasks.BundleLibraryClasses
import com.android.build.gradle.tasks.AndroidJavaCompile
import com.autonomousapps.internal.Artifact
import com.autonomousapps.internal.capitalize
import com.autonomousapps.internal.toJson
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

private const val ANDROID_APP_PLUGIN = "com.android.application"
private const val ANDROID_LIBRARY_PLUGIN = "com.android.library"
private const val KOTLIN_JVM_PLUGIN = "org.jetbrains.kotlin.jvm"

@Suppress("unused")
class DependencyAnalysisPlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = project.run {
        pluginManager.withPlugin(ANDROID_APP_PLUGIN) {
            logger.debug("Adding Android tasks to ${project.name}")
            analyzeAndroidApplicationDependencies()
        }
        pluginManager.withPlugin(ANDROID_LIBRARY_PLUGIN) {
            logger.debug("Adding Android tasks to ${project.name}")
            analyzeAndroidLibraryDependencies()
        }
        pluginManager.withPlugin(KOTLIN_JVM_PLUGIN) {
            logger.debug("Adding Kotlin tasks to ${project.name}")
            analyzeKotlinJvmDependencies()
        }
    }

    private fun Project.analyzeAndroidApplicationDependencies() {
        // We need the afterEvaluate so we can get a reference to the `KotlinCompile` tasks. TODO could we use tasks.withType instead?
        afterEvaluate {
            the<AppExtension>().applicationVariants.all {
                // TODO create just once and reuse?
                val androidClassAnalyzer = AppClassAnalyzer(this@analyzeAndroidApplicationDependencies, name)
                analyzeAndroidDependencies(androidClassAnalyzer)
            }
        }
    }

    private fun Project.analyzeAndroidLibraryDependencies() {
        the<LibraryExtension>().libraryVariants.all {
            // TODO create just once and reuse?
            val androidClassAnalyzer = LibClassAnalyzer(this@analyzeAndroidLibraryDependencies, name)
            analyzeAndroidDependencies(androidClassAnalyzer)//name)
        }
    }

    private fun Project.analyzeKotlinJvmDependencies() {
        // TODO
    }

    private fun <T : IClassAnalysisTask> Project.analyzeAndroidDependencies(androidClassAnalyzer: AndroidClassAnalyzer<T>) {
        // Convert `flavorDebug` to `FlavorDebug`
        val variantName = androidClassAnalyzer.variantName
        val variantTaskName = androidClassAnalyzer.variantNameCapitalized

        // Allows me to connect the output of the configuration phase to a task's input, without file IO
        val artifactsProperty = objects.property(String::class.java)

        val analyzeClassesTask = androidClassAnalyzer.registerClassAnalysisTask()//registerClassAnalysisTasks(variantName)
        resolveCompileClasspathArtifacts(variantName, artifactsProperty)

        val dependencyReportTask =
            tasks.register("dependenciesReport$variantTaskName", DependencyReportTask::class.java) {
                // TODO can I depend on something else?
                dependsOn(tasks.named("assemble$variantTaskName"))

                allArtifacts.set(artifactsProperty)

                output.set(layout.buildDirectory.file(getAllDeclaredDepsPath(variantName)))
                outputPretty.set(layout.buildDirectory.file(getAllDeclaredDepsPrettyPath(variantName)))
            }

        tasks.register("misusedDependencies$variantTaskName", DependencyMisuseTask::class.java) {
            declaredDependencies.set(dependencyReportTask.flatMap { it.output })
            usedClasses.set(analyzeClassesTask.flatMap { it.output })

            outputUnusedDependencies.set(
                layout.buildDirectory.file(getUnusedDirectDependenciesPath(variantName))
            )
            outputUsedTransitives.set(
                layout.buildDirectory.file(getUsedTransitiveDependenciesPath(variantName))
            )
        }
    }

    private interface AndroidClassAnalyzer<T : IClassAnalysisTask> {
        val variantName: String
        val variantNameCapitalized: String

        // 1.
        // This produces a report that lists all of the used classes (FQCN) in the project
        fun registerClassAnalysisTask(): TaskProvider<out T>
    }

    private class LibClassAnalyzer(
        private val project: Project,
        override val variantName: String
    ) : AndroidClassAnalyzer<ClassAnalysisTask> {

        override val variantNameCapitalized: String = variantName.capitalize()

        override fun registerClassAnalysisTask(): TaskProvider<ClassAnalysisTask> {
            // TODO this is unsafe. Task with this name not guaranteed to exist. Definitely known to exist in AGP 3.5.
            val bundleTask = project.tasks.named("bundleLibCompile$variantNameCapitalized", BundleLibraryClasses::class.java)

            return project.tasks.register("analyzeClassUsage$variantNameCapitalized", ClassAnalysisTask::class.java) {
                jar.set(bundleTask.flatMap { it.output })
                output.set(project.layout.buildDirectory.file(getAllUsedClassesPath(variantName)))
            }
        }
    }

    private class AppClassAnalyzer(
        private val project: Project,
        override val variantName: String
    ) : AndroidClassAnalyzer<ClassAnalysisTask2> {

        override val variantNameCapitalized: String = variantName.capitalize()

        override fun registerClassAnalysisTask(): TaskProvider<ClassAnalysisTask2> {
            // TODO this is unsafe. Task with these names not guaranteed to exist. Definitely known to exist in AGP 3.5 & Kotlin 1.3.50.
            val kotlinCompileTask = project.tasks.named("compile${variantNameCapitalized}Kotlin", KotlinCompile::class.java)
            val javaCompileTask = project.tasks.named("compile${variantNameCapitalized}JavaWithJavac", AndroidJavaCompile::class.java)

            return project.tasks.register("analyzeClassUsage$variantNameCapitalized", ClassAnalysisTask2::class.java) {
                val kaptTaskName = "kaptGenerateStubs${variantNameCapitalized}Kotlin"
                dependsOn(kotlinCompileTask, kaptTaskName)

                kotlinClasses.plus(kotlinCompileTask.get().outputs.files.asFileTree)
                javaClasses.set(javaCompileTask.flatMap { it.outputDirectory })

                output.set(project.layout.buildDirectory.file(getAllUsedClassesPath(variantName)))
            }
        }
    }

    // 2.
    // Produces a report that lists all direct and transitive dependencies, their artifacts, and component type (library
    // vs project)
    // TODO currently sucks because:
    // TODO a. Will run every time assembleDebug runs (I think because Kotlin causes eager realization of all AGP tasks)
    private fun Project.resolveCompileClasspathArtifacts(
        variantName: String,
        artifactsProperty: Property<String>
    ) {
        configurations.all {
            // compileClasspath has the correct artifacts
            if (name == "${variantName}CompileClasspath") {
                // This will gather all the resolved artifacts attached to the debugCompileClasspath INCLUDING transitives
                incoming.afterResolve {
                    val artifacts = artifactView {
                        attributes.attribute(Attribute.of("artifactType", String::class.java), "android-classes")
                    }.artifacts.artifacts
                        .map {
                            Artifact(
                                componentIdentifier = it.id.componentIdentifier,
                                file = it.file
                            )
                        }
                        .toSet()

                    artifactsProperty.set(artifacts.toJson())
                }
            }
        }
    }
}

private fun getVariantDirectory(variantName: String) = "dependency-analysis/$variantName"

private fun getAllUsedClassesPath(variantName: String) = "${getVariantDirectory(variantName)}/all-used-classes.txt"

private fun getAllDeclaredDepsPath(variantName: String) =
    "${getVariantDirectory(variantName)}/all-declared-dependencies.txt"

private fun getAllDeclaredDepsPrettyPath(variantName: String) =
    "${getVariantDirectory(variantName)}/all-declared-dependencies-pretty.txt"

private fun getUnusedDirectDependenciesPath(variantName: String) =
    "${getVariantDirectory(variantName)}/unused-direct-dependencies.txt"

private fun getUsedTransitiveDependenciesPath(variantName: String) =
    "${getVariantDirectory(variantName)}/used-transitive-dependencies.txt"
