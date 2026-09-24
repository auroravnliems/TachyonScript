package dev.tachyonscript.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLogEvent;

import java.util.List;
import java.util.Map;

/**
 * Shared configuration for every TachyonScript Java module.
 *
 * <p>Written as a plain Java plugin (instead of a Kotlin precompiled script plugin) so that
 * the build does not need the kotlin-dsl toolchain.
 */
public final class JavaConventionsPlugin implements Plugin<Project> {

    /** Java release every module is compiled for. */
    private static final int JAVA_RELEASE = 21;

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.withSourcesJar();

        project.getTasks().withType(JavaCompile.class).configureEach(compile -> {
            compile.getOptions().getRelease().set(JAVA_RELEASE);
            compile.getOptions().setEncoding("UTF-8");
            // All lints on and warnings fatal: the code base must stay warning-free.
            // 'serial' is irrelevant (nothing is Java-serialized) and 'processing'
            // only reports annotation processors that claim no annotations (JMH).
            compile.getOptions().getCompilerArgs().addAll(List.of(
                    "-Xlint:all,-serial,-processing",
                    "-Werror",
                    "-parameters"));
        });

        project.getTasks().withType(Jar.class).configureEach(jar -> {
            // Reproducible archives: same inputs, same bytes.
            jar.setPreserveFileTimestamps(false);
            jar.setReproducibleFileOrder(true);
            jar.getManifest().attributes(Map.of(
                    "Implementation-Title", project.getName(),
                    "Implementation-Version", String.valueOf(project.getVersion())));
        });

        VersionCatalog libs = project.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
        project.getDependencies().add("testImplementation",
                project.getDependencies().platform(libs.findLibrary("junit-bom").orElseThrow().get()));
        project.getDependencies().add("testImplementation", libs.findLibrary("junit-jupiter").orElseThrow().get());
        project.getDependencies().add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").orElseThrow().get());

        project.getTasks().withType(Test.class).configureEach(test -> {
            test.useJUnitPlatform();
            test.setMaxHeapSize("1g");
            test.systemProperty("file.encoding", "UTF-8");
            test.testLogging(logging -> {
                logging.events(TestLogEvent.FAILED, TestLogEvent.SKIPPED);
                logging.setExceptionFormat(TestExceptionFormat.FULL);
                logging.setShowStandardStreams(false);
            });
        });
    }
}
