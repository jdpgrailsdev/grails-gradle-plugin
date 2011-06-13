package org.grails.gradle.plugin

import org.grails.launcher.NameUtils
import org.grails.launcher.RootLoader
import org.grails.launcher.GrailsLauncher

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.artifacts.Configuration

class GrailsTask extends DefaultTask {

    private String command = null
    private String args = null
    private String env = null
    
    private boolean useRuntimeClasspathForBootstrap = false
    
    String grailsHome = null
    
    void command(String command) {
        setCommand(command)
    }
    
    void setCommand(String command) {
        this.command = command
    }
    
    String getCommand() {
        this.command
    }

    void args(String args) {
        setArgs(args)
    }
    
    void setArgs(String args) {
        this.args = args
    }
    
    String getArgs() {
        this.args
    }
    
    void env(String env) {
        setEnv(env)
    }
    
    void setEnv(String env) {
        this.env = env
    }
    
    String getEnv() {
        this.env
    }
    
    void configuration(Configuration configuration) {
        configurations(configuration)
    }
    
    void configuration(String configuration) {
        configurations(configuration)
    }
    
    void configurations(Configuration[] configurations) {
        configurations.each {
            dependsOn it.getTaskDependencyFromProjectDependency(true, "jar")
        }
    }
    
    void configurations(String[] configurations) {
        this.configurations configurations.collect { project.configurations[it] } as Configuration[]
    }
    
    void useRuntimeClasspathForBootstrap(boolean flag) {
        setUseRuntimeClasspathForBootstrap(flag)
    }
    
    void setUseRuntimeClasspathForBootstrap(boolean flag) {
        this.useRuntimeClasspathForBootstrap = flag
    }
    
    boolean isUseRuntimeClasspathForBootstrap() {
        this.useRuntimeClasspathForBootstrap
    }
    
    @TaskAction
    def executeCommand() {
        verifyGrailsDependencies()
        
        def launchArgs = [NameUtils.toScriptName(effectiveCommand), args ?: ""]
        if (env) launchArgs << end
        def result = createLauncher().launch(*launchArgs)

        if (result != 0) {
            throw new RuntimeException("[GrailsPlugin] Grails returned non-zero value: " + result);
        }
    }
    
    // TODO - use a convention for this
    String getEffectiveCommand() {
        command ?: name
    }
    
    String getEffectiveGrailsHome() {
        grailsHome ?: (project.hasProperty('grailsHome') ? project.grailsHome : null)
    }
    
    protected void verifyGrailsDependencies() {
        def runtimeDeps = project.configurations.runtime.resolvedConfiguration.resolvedArtifacts
        def grailsDep = runtimeDeps.find { it.resolvedDependency.moduleGroup == 'org.grails' && it.name.startsWith('grails-') }
        if (!grailsDep) {
            throw new RuntimeException("[GrailsPlugin] Your project does not contain any 'grails-*' dependencies in 'compile' or 'runtime'.")
        }

        def loggingDep = runtimeDeps.find { it.resolvedDependency.moduleGroup == 'org.slf4j' && it.name.startsWith('slf4j-') }
        if (!loggingDep) {
            throw new RuntimeException("[GrailsPlugin] Your project does not contain an SLF4J logging implementation dependency.")
        }
    }

    protected void addToolsJarIfNecessary(Collection<URL> classpath) {
        // Add the "tools.jar" to the classpath so that the Grails
        // scripts can run native2ascii. First assume that "java.home"
        // points to a JRE within a JDK.
        def javaHome = System.getProperty("java.home");
        def toolsJar = new File(javaHome, "../lib/tools.jar");
        if (!toolsJar.exists()) {
            // The "tools.jar" cannot be found with that path, so
            // now try with the assumption that "java.home" points
            // to a JDK.
            toolsJar = new File(javaHome, "tools.jar");
        }

        // There is no tools.jar, so native2ascii may not work. Note
        // that on Mac OS X, native2ascii is already on the classpath.
        if (!toolsJar.exists() && !System.getProperty('os.name') == 'Mac OS X') {
            project.logger.warn "[GrailsPlugin] Cannot find tools.jar in JAVA_HOME, so native2ascii may not work."
        }
        
        if (toolsJar.exists()) {
            classpath << toolsJar.toURI().toURL()
        }
    }
    
    boolean isEffectiveUseRuntimeClasspathForBootstrap() {
        effectiveCommand in ["run-app", "test-app", "release-plugin"] || useRuntimeClasspathForBootstrap
    }
    
    Configuration getEffectiveBootstrapConfiguration() {
         project.configurations."${effectiveUseRuntimeClasspathForBootstrap ? 'bootstrapRuntime' : 'bootstrap'}"
    }
    
    protected Collection<URL> getEffectiveBootstrapClasspath() {
        def classpath = effectiveBootstrapConfiguration.files.collect { it.toURI().toURL() }
        addToolsJarIfNecessary(classpath)
        classpath
    }
    
    protected GrailsLauncher createLauncher() {
        def rootLoader = new RootLoader(getEffectiveBootstrapClasspath() as URL[], ClassLoader.systemClassLoader)
        def grailsLauncher = new GrailsLauncher(rootLoader, effectiveGrailsHome, project.projectDir.absolutePath)
        applyProjectLayout(grailsLauncher)
        configureGrailsDependencyManagement(grailsLauncher)
        grailsLauncher
    }
    
    protected void applyProjectLayout(GrailsLauncher grailsLauncher) {
        grailsLauncher.compileDependencies = project.configurations.compile.files as List
        grailsLauncher.testDependencies = project.configurations.test.files as List
        grailsLauncher.runtimeDependencies = project.configurations.runtime.files as List
        grailsLauncher.projectWorkDir = project.buildDir
        grailsLauncher.classesDir = new File(project.buildDir, "classes")
        grailsLauncher.testClassesDir = new File(project.buildDir, "test-classes")
        grailsLauncher.resourcesDir = new File(project.buildDir, "resources")
        grailsLauncher.projectPluginsDir = new File(project.buildDir, "plugins")
        grailsLauncher.testReportsDir = new File(project.buildDir, "test-results")
    }

    protected void configureGrailsDependencyManagement(GrailsLauncher grailsLauncher) {
        // Grails 1.2+ only. Previous versions of Grails don't have the
        // 'dependenciesExternallyConfigured' property. Note that this
        // is a HACK because the 'settings' field is private.
        //
        // We can't simply check whether the property exists on the
        // launcher because it's the 1.2 version, whereas the project may
        // be using Grails version 1.1. That's why we have to get hold
        // of the actual BuildSettings instance.
        def buildSettings = grailsLauncher.settings
        if (buildSettings.metaClass.hasProperty(buildSettings, "dependenciesExternallyConfigured")) {
            grailsLauncher.dependenciesExternallyConfigured = true
        }
    }
    
    protected void logClasspaths() {
        project.logger.with {
            if (infoEnabled) {
                info "Classpath for Grails root loader:\n  ${classpath.join('\n  ')}"
                info "Compile classpath:\n  ${project.configurations.compile.files.join('\n  ')}"
                info "Test classpath:\n  ${project.configurations.test.files.join('\n  ')}"
                info "Runtime classpath:\n  ${project.configurations.runtime.files.join('\n  ')}"
            }
        }
    }
    
    protected boolean isPluginProject() {
        project.projectDir.listFiles({ dir, name -> name ==~ /.*GrailsPlugin.groovy/} as FilenameFilter)
    }

}