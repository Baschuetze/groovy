/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.tools;

import groovy.lang.DeprecationException;
import groovy.lang.GroovyResourceLoader;
import groovy.lang.GroovySystem;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ConfigurationException;
import org.codehaus.groovy.runtime.DefaultGroovyStaticMethods;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParseResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static groovy.ui.GroovyMain.processConfigScripts;

/**
 * Command-line compiler (aka. <tt>groovyc</tt>).
 */
public class FileSystemCompiler {

    private static boolean displayStackTraceOnError;
    private final CompilationUnit unit;

    public FileSystemCompiler(CompilerConfiguration configuration) throws ConfigurationException {
        this(configuration, null);
    }

    public FileSystemCompiler(CompilerConfiguration configuration, CompilationUnit cu) throws ConfigurationException {
        if (cu != null) {
            unit = cu;
        } else if (configuration.getJointCompilationOptions() != null) {
            unit = new JavaAwareCompilationUnit(configuration);
        } else {
            unit = new CompilationUnit(configuration);
        }
    }

    /**
     * Prints the usage help message for {@link CompilationOptions} to stderr.
     *
     * @see #displayHelp(PrintWriter)
     * @since 2.5
     */
    public static void displayHelp() {
        displayHelp(new PrintWriter(System.err, true));
    }

    /**
     * Prints the usage help message for the {@link CompilationOptions} to the specified PrintWriter.
     *
     * @since 2.5
     */
    public static void displayHelp(PrintWriter writer) {
        configureParser(new CompilationOptions()).usage(writer);
    }

    /**
     * Prints version information to stderr.
     *
     * @see #displayVersion(PrintWriter)
     */
    public static void displayVersion() {
        displayVersion(new PrintWriter(System.err, true));
    }

    /**
     * Prints version information to the specified PrintWriter.
     *
     * @since 2.5
     */
    public static void displayVersion(PrintWriter writer) {
        for (String line : new VersionProvider().getVersion()) {
            writer.println(line);
        }
    }

    public static int checkFiles(String[] filenames) {
        int errors = 0;

        for (String filename : filenames) {
            File file = new File(filename);
            if (!file.exists()) {
                System.err.println("error: file not found: " + file);
                errors += 1;
            } else if (!file.canRead()) {
                System.err.println("error: file not readable: " + file);
                errors += 1;
            }
        }

        return errors;
    }

    public static boolean validateFiles(String[] filenames) {
        return checkFiles(filenames) == 0;
    }

    /**
     * Same as main(args) except that exceptions are thrown out instead of causing
     * the VM to exit.
     */
    public static void commandLineCompile(String[] args) throws Exception {
        commandLineCompile(args, true);
    }

    /**
     * Same as main(args) except that exceptions are thrown out instead of causing
     * the VM to exit and the lookup for .groovy files can be controlled
     */
    public static void commandLineCompile(String[] args, boolean lookupUnnamedFiles) throws Exception {
        CompilationOptions options = new CompilationOptions();
        CommandLine parser = configureParser(options);
        ParseResult parseResult = parser.parseArgs(args);
        if (CommandLine.printHelpIfRequested(parseResult)) {
            return;
        }
        displayStackTraceOnError = options.printStack;
        CompilerConfiguration configuration = options.toCompilerConfiguration();

        // load the file name list
        String[] fileNames = options.generateFileNames();
        boolean fileNameErrors = fileNames == null;
        if (!fileNameErrors && (fileNames.length == 0)) {
            parser.usage(System.err);
            return;
        }

        fileNameErrors = fileNameErrors && !validateFiles(fileNames);

        if (!fileNameErrors) {
            doCompilation(configuration, null, fileNames, lookupUnnamedFiles);
        }
    }

    public static CommandLine configureParser(CompilationOptions options) {
        CommandLine parser = new CommandLine(options);
        parser.getCommandSpec().parser()
                .unmatchedArgumentsAllowed(true)
                .unmatchedOptionsArePositionalParams(true)
                .expandAtFiles(false)
                .toggleBooleanFlags(false);
        return parser;
    }

    /**
     * Primary entry point for compiling from the command line
     * (using the groovyc script).
     * <p>
     * If calling inside a process and you don't want the JVM to exit on an
     * error call commandLineCompile(String[]), which this method simply wraps
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        commandLineCompileWithErrorHandling(args, true);
    }

    /**
     * Primary entry point for compiling from the command line
     * (using the groovyc script).
     * <p>
     * If calling inside a process and you don't want the JVM to exit on an
     * error call commandLineCompile(String[]), which this method simply wraps
     *
     * @param args               command line arguments
     * @param lookupUnnamedFiles do a lookup for .groovy files not part of
     *                           the given list of files to compile
     */
    public static void commandLineCompileWithErrorHandling(String[] args, boolean lookupUnnamedFiles) {
        try {
            commandLineCompile(args, lookupUnnamedFiles);
        } catch (Throwable e) {
            new ErrorReporter(e, displayStackTraceOnError).write(System.err);
            System.exit(1);
        }
    }

    public static void doCompilation(CompilerConfiguration configuration, CompilationUnit unit, String[] filenames) throws Exception {
        doCompilation(configuration, unit, filenames, true);
    }

    public static void doCompilation(CompilerConfiguration configuration, CompilationUnit unit, String[] filenames, boolean lookupUnnamedFiles) throws Exception {
        File tmpDir = null;
        // if there are any joint compilation options set stubDir if not set
        try {
            if ((configuration.getJointCompilationOptions() != null)
                    && !configuration.getJointCompilationOptions().containsKey("stubDir")) {
                tmpDir = DefaultGroovyStaticMethods.createTempDir(null, "groovy-generated-", "-java-source");
                configuration.getJointCompilationOptions().put("stubDir", tmpDir);
            }
            FileSystemCompiler compiler = new FileSystemCompiler(configuration, unit);
            if (lookupUnnamedFiles) {
                for (String filename : filenames) {
                    File file = new File(filename);
                    if (file.isFile()) {
                        URL url = file.getAbsoluteFile().getParentFile().toURI().toURL();
                        compiler.unit.getClassLoader().addURL(url);
                    }
                }
            } else {
                compiler.unit.getClassLoader().setResourceLoader(new GroovyResourceLoader() {
                    @Override
                    public URL loadGroovySource(String filename) {
                        return null;
                    }
                });
            }
            compiler.compile(filenames);
        } finally {
            try {
                if (tmpDir != null) deleteRecursive(tmpDir);
            } catch (Throwable t) {
                System.err.println("error: could not delete temp files - " + tmpDir.getPath());
            }
        }
    }

    private static String[] generateFileNamesFromOptions(List<String> filenames) {
        if (filenames == null) {
            return new String[0];
        }
        List<String> fileList = new ArrayList<>(filenames.size());
        boolean errors = false;
        for (String filename : filenames) {
            if (filename.startsWith("@")) {
                String fn = filename.substring(1);
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new FileReader(fn));
                    for (String file; (file = br.readLine()) != null; ) {
                        fileList.add(file);
                    }
                } catch (IOException ioe) {
                    System.err.println("error: file not readable: " + fn);
                    errors = true;
                } finally {
                    if (null != br) {
                        try {
                            br.close();
                        } catch (IOException e) {
                            System.err.println("error: failed to close buffered reader: " + fn);
                            errors = true;
                        }
                    }
                }
            } else {
                fileList.add(filename);
            }
        }
        if (errors) {
            return null;
        } else {
            return fileList.toArray(new String[0]);
        }
    }

    public static void deleteRecursive(File file) {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                deleteRecursive(f);
            }
        }
        file.delete();
    }

    public void compile(String[] paths) throws Exception {
        unit.addSources(paths);
        unit.compile();
    }

    public void compile(File[] files) throws Exception {
        unit.addSources(files);
        unit.compile();
    }

    /**
     * @deprecated use {@link #displayHelp(PrintWriter)} instead
     */
    @Deprecated
    public static void displayHelp(Options options) {
        final HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(80, "groovyc [options] <source-files>", "options:", options, "");
    }

    // some methods to avoid binary incompatibility - don't gain us a lot but gives the user
    // something slightly less cryptic than a NoSuchMethodError or an IncompatibleClassChangeError
    @Deprecated
    public static CompilerConfiguration generateCompilerConfigurationFromOptions(org.apache.commons.cli.CommandLine cli) {
        throw new DeprecationException("This method is not supported for Groovy 2.5+. Consider instead using the FileSystemCompiler.CompilationOptions class.");
    }

    @Deprecated
    public static String[] generateFileNamesFromOptions(org.apache.commons.cli.CommandLine cli) {
        throw new DeprecationException("This method is not supported for Groovy 2.5+. Consider instead using the FileSystemCompiler.CompilationOptions class.");
    }

    @Deprecated
    public static Options createCompilationOptions() {
        throw new DeprecationException("This method is not supported for Groovy 2.5+. Consider instead using the FileSystemCompiler.CompilationOptions class.");
    }

    /**
     * Creates a temporary directory in the default temporary directory (as specified by the system
     * property <i>java.io.tmpdir</i>.
     *
     * @deprecated Use {@link DefaultGroovyStaticMethods#createTempDir(java.io.File, String, String)} instead.
     */
    @Deprecated
    public static File createTempDir() throws IOException {
        return DefaultGroovyStaticMethods.createTempDir(null);
    }

    /**
     * @since 2.5
     */
    public static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{
                    "Groovy compiler version " + GroovySystem.getVersion(),
                    "Copyright 2003-2022 The Apache Software Foundation. https://groovy-lang.org/",
                    "",
            };
        }
    }

    /**
     * @since 2.5
     */
    @Command(name = "groovyc", customSynopsis = "groovyc [options] <source-files>", sortOptions = false, versionProvider = VersionProvider.class)
    public static class CompilationOptions {
        // IMPLEMENTATION NOTE:
        // classpath must be the first argument, so that the `startGroovy(.bat)` script
        // can extract it and the JVM can be started with the classpath already correctly set.
        // This saves us from having to fork a new JVM process with the classpath set from the processed arguments.
        @Option(names = {"-cp", "-classpath", "--classpath"}, paramLabel = "<path>", description = "Specify where to find the class files - must be first argument")
        private String classpath;

        @Option(names = {"-sourcepath", "--sourcepath"}, paramLabel = "<path>", description = "Specify where to find the source files")
        private File sourcepath;

        @Option(names = {"--temp"}, paramLabel = "<temp>", description = "Specify temporary directory")
        private File temp;

        @Option(names = {"--encoding"}, description = "Specify the encoding of the user class files")
        private String encoding;

        @Option(names = "-d", paramLabel = "<dir>", description = "Specify where to place generated class files")
        private File targetDir;

        @Option(names = {"-e", "--exception"}, description = "Print stack trace on error")
        private boolean printStack;

        @Option(names = {"-pa", "--parameters"}, description = "Generate metadata for reflection on method parameter names (jdk8+ only)")
        private boolean parameterMetadata;

        @Option(names = {"-pr", "--enable-preview"}, description = "Enable preview Java features (JEP 12) (jdk12+ only) - must be after classpath but before other arguments")
        private boolean previewFeatures;

        @Option(names = {"-j", "--jointCompilation"}, description = "Attach javac compiler to compile .java files")
        private boolean jointCompilation;

        @Option(names = {"-b", "--basescript"}, paramLabel = "<class>", description = "Base class name for scripts (must derive from Script)")
        private String scriptBaseClass;

        @Option(names = "-J", paramLabel = "<property=value>", description = "Name-value pairs to pass to javac")
        private Map<String, String> javacOptionsMap;

        @Option(names = "-F", paramLabel = "<flag>", description = "Passed to javac for joint compilation")
        private List<String> flags;

        @Option(names = {"--indy"}, description = "Enables compilation using invokedynamic")
        private boolean indy;

        @Option(names = {"--configscript"}, paramLabel = "<script>", description = "A script for tweaking the configuration options")
        private String configScript;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit")
        private boolean helpRequested;

        @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print version information and exit")
        private boolean versionRequested;

        @Parameters(description = "The groovy source files to compile, or @-files containing a list of source files to compile", paramLabel = "<source-files>")
        private List<String> files;

        public CompilerConfiguration toCompilerConfiguration() throws IOException {
            CompilerConfiguration configuration = new CompilerConfiguration();

            if (classpath != null) {
                configuration.setClasspath(classpath);
            }

            if (targetDir != null && !targetDir.getName().isEmpty()) {
                configuration.setTargetDirectory(targetDir);
            }

            configuration.setParameters(parameterMetadata);
            configuration.setPreviewFeatures(previewFeatures);
            configuration.setScriptBaseClass(scriptBaseClass);
            configuration.setSourceEncoding(encoding);

            // joint compilation parameters
            if (jointCompilation) {
                Map<String, Object> compilerOptions = new HashMap<>();
                compilerOptions.put("flags", javacFlags());
                compilerOptions.put("namedValues", javacNamedValues());
                configuration.setJointCompilationOptions(compilerOptions);
            }

            if (indy) {
                configuration.getOptimizationOptions().put("int", Boolean.FALSE);
                configuration.getOptimizationOptions().put("indy", Boolean.TRUE);
            }

            String configScripts = System.getProperty("groovy.starter.configscripts", null);
            if (configScript != null || (configScripts != null && !configScripts.isEmpty())) {
                List<String> scripts = new ArrayList<>();
                if (configScript != null) {
                    scripts.add(configScript);
                }
                if (configScripts != null) {
                    scripts.addAll(StringGroovyMethods.tokenize((CharSequence) configScripts, ','));
                }
                processConfigScripts(scripts, configuration);
            }

            return configuration;
        }

        public String[] generateFileNames() {
            return generateFileNamesFromOptions(files);
        }

        private String[] javacNamedValues() {
            List<String> result = new ArrayList<>();
            if (javacOptionsMap != null) {
                for (Map.Entry<String, String> entry : javacOptionsMap.entrySet()) {
                    result.add(entry.getKey());
                    result.add(entry.getValue());
                }
            }
            return result.isEmpty() ? null : result.toArray(new String[0]);
        }

        private String[] javacFlags() {
            List<String> result = new ArrayList<>();
            if (flags != null) {
                result.addAll(flags);
            }
            if (parameterMetadata) {
                result.add("parameters");
            }
            if (previewFeatures) {
                result.add("-enable-preview");
            }
            return result.isEmpty() ? null : result.toArray(new String[0]);
        }
    }
}
