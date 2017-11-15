package com.brandnewbox.s2ipublish;

import com.brandnewbox.s2ipublish.DockerCLIHelper.InspectImageResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.DescriptorExtensionList;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.util.FormValidation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.apache.commons.io.output.TeeOutputStream;
import org.jenkinsci.plugins.docker.commons.credentials.KeyMaterial;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;


/**
 * Plugin to build and publish docker projects to the docker registry/index.
 * This can optionally push, and bust cache.
 *
 * @author Michael Neale
 */
public class S2IBuilder extends Builder {

    private static final Logger logger = Logger.getLogger(S2IBuilder.class.getName());


    @CheckForNull
    private DockerRegistryEndpoint targetRegistry;
    @CheckForNull
    private String targetName;
    @CheckForNull
    private String targetTag;
    @CheckForNull
    private String baseImage;
    private String buildAdditionalArgs = "";

    @Deprecated
    public S2IBuilder(String targetName, String targetTag) {
        this(targetName);
        this.targetTag = targetTag;
    }

    @DataBoundConstructor
    public S2IBuilder(String targetName) {
        this.targetRegistry = new DockerRegistryEndpoint(null, null);
        this.targetName = targetName;
    }

    public DockerRegistryEndpoint getTargetRegistry() {
        return targetRegistry;
    }

    @DataBoundSetter
    public void setTargetRegistry(DockerRegistryEndpoint targetRegistry) {
        this.targetRegistry = targetRegistry;
    }

    public String getTargetName() {
        return targetName;
    }

    @DataBoundSetter
    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getTargetTag() {
        return targetTag;
    }

    @DataBoundSetter
    public void setTargetTag(String targetTag) {
        this.targetTag = targetTag;
    }
    
    public String getBaseImage() {
		return baseImage;
	}
    
    @DataBoundSetter
    public void setBaseImage(String baseImage) {
		this.baseImage = baseImage;
	}
    
    public String getBuildAdditionalArgs() {
        return buildAdditionalArgs == null ? "" : buildAdditionalArgs;
    }

    @DataBoundSetter
    public void setBuildAdditionalArgs(String buildAdditionalArgs) {
        this.buildAdditionalArgs = buildAdditionalArgs;
    }

    /**
     * Fully qualified repository/image name with the registry url in front
     * @return ie. docker.acme.com/jdoe/busybox
     * @throws IOException
     */
    public String getTarget() throws IOException {
        return getTargetRegistry().imageName(targetName);
    }
    
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)  {
        return new Perform(build, launcher, listener).exec();
    }
    
    private static class Result {
        final boolean result;
        final @Nonnull String stdout;
        final @Nonnull String stderr;

        private Result() {
            this(true, "", "");
        }

        private Result(boolean result, @CheckForNull String stdout, @CheckForNull String stderr) {
            this.result = result;
            this.stdout = hudson.Util.fixNull(stdout);
                this.stderr = hudson.Util.fixNull(stderr);
        }
    }
    
    private class Perform {
        private final AbstractBuild build;
        private final Launcher launcher;
        private final BuildListener listener;

        private Perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
            this.build = build;
            this.launcher = launcher;
            this.listener = listener;
        }

        private boolean exec() {
            try {
                return buildAndTag() && dockerPushCommand();

            } catch (IOException e) {
                return recordException(e);
            } catch (InterruptedException e) {
                return recordException(e);
            } catch (MacroEvaluationException e) {
                return recordException(e);
            }
        }

        private String expandAll(String s) throws MacroEvaluationException, IOException, InterruptedException {
            return TokenMacro.expandAll(build, listener, s);
        }

        /**
         * This tag is what is used to build, tag and push the registry.
         */
        private List<ImageTag> getImageTags() throws MacroEvaluationException, IOException, InterruptedException {
            List<ImageTag> tags = new ArrayList<ImageTag>();
            for (String rt : expandAll(getTargetTag()).trim().split(",")) {
                tags.add(new ImageTag(expandAll(getTarget()), expandAll(rt)));
            }
            tags.add(new ImageTag(expandAll(getTarget()), "latest"));
            return tags;
        }

        private boolean recordException(Exception e) {
            listener.error(e.getMessage());
            e.printStackTrace(listener.getLogger());
            return false;
        }

        private boolean buildAndTag() throws MacroEvaluationException, IOException, InterruptedException {
            FilePath context;
            context = build.getWorkspace();
            Result lastResult = new Result();

            lastResult = executeCmd("s2i build "
                + expandAll(getBuildAdditionalArgs()) + " "
                + "'" + context + "' "
                + baseImage + " "
                + getTarget()
            );
            
            List<String> result = new ArrayList<String>();
            for (ImageTag imageTag : getImageTags()) {
                result.add("docker tag " 
                    + getTarget() + " "
                    + imageTag.toString()
                );
            }
            return executeCmd(result);
        }


        private boolean dockerPushCommand() throws InterruptedException, MacroEvaluationException, IOException {
            List<String> result = new ArrayList<String>();
            for (ImageTag imageTag : getImageTags()) {
                result.add("docker push "
                    + imageTag.toString()
                );
            }
            return executeCmd(result);
        }

        private boolean executeCmd(List<String> cmds) throws IOException, InterruptedException {
            Iterator<String> i = cmds.iterator();
            Result lastResult = new Result();
            // if a command fails, do not continue
            while (lastResult.result && i.hasNext()) {
                lastResult = executeCmd(i.next());
            }
            return lastResult.result;
        }

        /**
         * Runs Docker command using Docker CLI.
         * In this default implementation STDOUT and STDERR outputs will be printed to build logs.
         * Use {@link #executeCmd(java.lang.String, boolean, boolean)} to alter the behavior.
         * @param cmd Command to be executed
         * @return Execution result
         * @throws IOException Execution error
         * @throws InterruptedException The build has been interrupted
         */
        private Result executeCmd(String cmd) throws IOException, InterruptedException {
            return executeCmd(cmd, true, true);
        }
        
        /**
         * Runs Docker command using Docker CLI.
         * @param cmd Command to be executed (Docker command will be prefixed)
         * @param logStdOut If true, propagate STDOUT to the build log
         * @param logStdErr If true, propagate STDERR to the build log
         * @return Execution result
         * @throws IOException Execution error
         * @throws InterruptedException The build has been interrupted
         */
        private @Nonnull Result executeCmd( @Nonnull String cmd, 
                boolean logStdOut, boolean logStdErr) throws IOException, InterruptedException {
            ByteArrayOutputStream baosStdOut = new ByteArrayOutputStream();
            ByteArrayOutputStream baosStdErr = new ByteArrayOutputStream();
            OutputStream stdout = logStdOut ? 
                    new TeeOutputStream(listener.getLogger(), baosStdOut) : baosStdOut;
            OutputStream stderr = logStdErr ? 
                    new TeeOutputStream(listener.getLogger(), baosStdErr) : baosStdErr;

            
            KeyMaterial dockerKeys = 
                // Docker registry credentials
                getTargetRegistry().newKeyMaterialFactory(build)
            .materialize();

            EnvVars env = new EnvVars();
            env.putAll(build.getEnvironment(listener));
            env.putAll(dockerKeys.env());

            logger.log(Level.FINER, "Executing: {0}", cmd);

            try {
                
                boolean result = launcher.launch()
                        .envs(env)
                        .pwd(build.getWorkspace())
                        .stdout(stdout)
                        .stderr(stderr)
                        .cmdAsSingleString(cmd)
                        .start().join() == 0;

                // capture the stdout so it can be parsed later on
                final String stdOutStr = DockerCLIHelper.getConsoleOutput(baosStdOut, logger);
                final String stdErrStr = DockerCLIHelper.getConsoleOutput(baosStdErr, logger);
                return new Result(result, stdOutStr, stdErrStr);

            } finally {
                dockerKeys.close();
            }
        }
    }


    /**
     * Descriptor for {@link S2IBuilder}. Used as a singleton.
     */
    @Extension
    public static class Descriptor extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            // return FreeStyleProject.class.isAssignableFrom(jobType);
            return true;
        }
        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "S2I Build and Publish";
        }

    }
}
