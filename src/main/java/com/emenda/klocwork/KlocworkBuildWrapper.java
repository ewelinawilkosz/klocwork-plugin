package com.emenda.klocwork;

import com.emenda.klocwork.config.KlocworkInstallConfig;
import com.emenda.klocwork.config.KlocworkServerConfig;
import com.emenda.klocwork.services.KlocworkApiConnection;
import com.emenda.klocwork.util.KlocworkLtokenFetcher;
import com.emenda.klocwork.util.KlocworkUtil;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ArgumentListBuilder;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildWrapper;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;

public class KlocworkBuildWrapper extends SimpleBuildWrapper {

    private final String serverConfig;
    private final String installConfig;
    private final String serverProject;
    private final boolean createNewProject;
    private final String ltoken;

    @DataBoundConstructor
    public KlocworkBuildWrapper(String serverConfig, String installConfig,
                    String serverProject, boolean createNewProject, String ltoken) {
        this.serverConfig = serverConfig;
        this.installConfig = installConfig;
        this.serverProject = serverProject;
        this.createNewProject = createNewProject;
        this.ltoken = ltoken;
    }

    @Override
    public void setUp(Context context, Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {

        final KlocworkLogger logger = new KlocworkLogger("BuildWrapper", listener.getLogger());
        logger.logMessage("Setting up environment variables for Klocwork jobs...");
        final KlocworkServerConfig server = getDescriptor().getServerConfig(serverConfig);
        final KlocworkInstallConfig install = getDescriptor().getInstallConfig(installConfig);


        if (server != null) {
            if (StringUtils.isEmpty(server.getUrl())) {
                logger.logMessage("WARNING: Server URL for configuration \"" +
                    server.getName() + "\" is empty");
            } else {
                logger.logMessage("Adding the Klocwork Server URL " + server.getUrl());
                context.env(KlocworkConstants.KLOCWORK_URL, server.getUrl());
            }
            // if specific license details, else use the global ones
            if (server.isSpecificLicense()) {
                logger.logMessage("Using specific License for given server " +
                    server.getLicensePort() + "@" + server.getLicenseHost());
                context.env(KlocworkConstants.KLOCWORK_LICENSE_HOST,
                            server.getLicenseHost());
                context.env(KlocworkConstants.KLOCWORK_LICENSE_PORT,
                            server.getLicensePort());
            } else {
                logger.logMessage("Using Global License Settings " +
                    getDescriptor().getGlobalLicensePort() + "@" +
                    getDescriptor().getGlobalLicenseHost());
                context.env(KlocworkConstants.KLOCWORK_LICENSE_HOST,
                                getDescriptor().getGlobalLicenseHost());
                context.env(KlocworkConstants.KLOCWORK_LICENSE_PORT,
                                getDescriptor().getGlobalLicensePort());
            }
        } else {
            logger.logMessage("WARNING: No Klocwork server selected. " +
                "Klocwork cannot perform server builds or synchronisations " +
                "without a server.");
            logger.logMessage("Using Global License Settings " +
                getDescriptor().getGlobalLicensePort() + "@" +
                getDescriptor().getGlobalLicenseHost());
            context.env(KlocworkConstants.KLOCWORK_LICENSE_HOST,
                            getDescriptor().getGlobalLicenseHost());
            context.env(KlocworkConstants.KLOCWORK_LICENSE_PORT,
                            getDescriptor().getGlobalLicensePort());
        }
        if (StringUtils.isEmpty(serverProject)) {
            logger.logMessage("WARNING: No Klocwork project provided. " +
                "Klocwork cannot perform server builds or synchronisations " +
                "without a project.");
        } else {
            context.env(KlocworkConstants.KLOCWORK_PROJECT, serverProject);
        }

        if (install != null) {
            logger.logMessage("Adding Klocwork paths. Using install \""
            + install.getName() + "\"");
            String separator = (launcher.isUnix()) ? ":" : ";";
            String paths = install.getPaths()+separator;
            String path = initialEnvironment.get("PATH");
            context.env("PATH", paths+path);
        }

        if (StringUtils.isEmpty(ltoken)) {
            logger.logMessage("No ltoken file specified. KLOCWORK_LTOKEN will not be set.");
        } else {
            logger.logMessage("Detected ltoken file. Setting KLOCWORK_LTOKEN to \"" +
                ltoken + "\"");
            // expand ltoken as this is referenced directly by the Klocwork tools.
            // Other environment variables the plugin expands and passes to the Klocwork
            // command line tools
            context.env(KlocworkConstants.KLOCWORK_LTOKEN, initialEnvironment.expand(ltoken));
        }
        if (createNewProject) {
            KlocworkLtokenFetcher fetcher = new KlocworkLtokenFetcher(server.getUrl(), ltoken);
            String user = fetcher.call()[KlocworkConstants.LTOKEN_USER_INDEX];
            String Ltoken = fetcher.call()[KlocworkConstants.LTOKEN_HASH_INDEX];
            KlocworkApiConnection api = new KlocworkApiConnection(server.getUrl(), user, Ltoken);
            JSONArray response = api.sendRequest("action=projects");
            boolean exists = false;
            for (int i = 0; i < response.size(); i++) {
                JSONObject object = response.getJSONObject(i);
                if (serverProject.equals(object.getString("name"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                ArgumentListBuilder cmd = new ArgumentListBuilder();
                cmd.add("kwadmin");
                cmd.add("create-project");
                cmd.add(serverProject);
                int result = KlocworkUtil.executeCommand(launcher, listener, workspace, initialEnvironment, cmd);
            }
        }
    }

    public String getServerConfig() { return serverConfig; }
    public String getInstallConfig() { return installConfig; }
    public String getServerProject() { return serverProject; }
    public boolean isCreateNewProject() { return createNewProject; }
    public String getLtoken() { return ltoken; }

    public final static String getNoneValue() { return "-- none --"; }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Symbol("klocworkWrapper")
    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

         private String globalLicenseHost;
         private String globalLicensePort;
         private CopyOnWriteList<KlocworkServerConfig> serverConfigs = new CopyOnWriteList<KlocworkServerConfig>();
         private CopyOnWriteList<KlocworkInstallConfig> installConfigs = new CopyOnWriteList<KlocworkInstallConfig>();

        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        public String getDisplayName() {
            return KlocworkConstants.KLOCWORK_BUILD_WRAPPER_DISPLAY_NAME;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            serverConfigs.replaceBy(req.bindJSONToList(KlocworkServerConfig.class, formData.get("serverConfigs")));
            installConfigs.replaceBy(req.bindJSONToList(KlocworkInstallConfig.class, formData.get("installConfigs")));
            globalLicenseHost = formData.getString("globalLicenseHost");
            globalLicensePort = formData.getString("globalLicensePort");
            save();
            return super.configure(req,formData);
        }

        public String getGlobalLicenseHost() { return globalLicenseHost; }
        public String getGlobalLicensePort() { return globalLicensePort; }

        public KlocworkServerConfig[] getServerConfigs() {
            return serverConfigs.toArray(new KlocworkServerConfig[0]);
        }

        public KlocworkInstallConfig[] getInstallConfigs() {
            return installConfigs.toArray(new KlocworkInstallConfig[0]);
        }

        public KlocworkServerConfig getServerConfig(String name) {
            for (KlocworkServerConfig config : serverConfigs) {
                if (config.getName().equals(name))
                    return config;
            }
            return null;
        }

        public KlocworkInstallConfig getInstallConfig(String name) {
            for (KlocworkInstallConfig config : installConfigs) {
                if (config.getName().equals(name))
                    return config;
            }
            return null;
        }

        public FormValidation doCheckGlobalLicensePort(@QueryParameter String value)
            throws IOException, ServletException {

            if (StringUtils.isNumeric(value)) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Port must be a number");
            }
        }

        public ListBoxModel doFillServerConfigItems() {
            ListBoxModel items = new ListBoxModel();
            items.add(getNoneValue());
            for (KlocworkServerConfig config : serverConfigs) {
                items.add(config.getName());
            }
            return items;
        }

        public FormValidation doCheckServerConfig(@QueryParameter String value)
            throws IOException, ServletException {

            if (value.equals(getNoneValue())) {
                return FormValidation.warning("Server Configuration is required for server builds, cross synchronisation and CI analysis synchronisation");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckServerProject(@QueryParameter String value)
            throws IOException, ServletException {

            if (StringUtils.isEmpty(value)) {
                return FormValidation.warning("Server Project is required for server builds, cross synchronisation and CI analysis synchronisation");
            } else {
                return FormValidation.ok();
            }
        }

        public ListBoxModel doFillInstallConfigItems() {
            ListBoxModel items = new ListBoxModel();
            items.add(getNoneValue());
            for (KlocworkInstallConfig config : installConfigs) {
                items.add(config.getName());
            }
            return items;
        }
    }
}
