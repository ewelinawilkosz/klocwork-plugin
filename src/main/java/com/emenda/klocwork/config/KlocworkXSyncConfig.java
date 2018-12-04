
package com.emenda.klocwork.config;

import com.emenda.klocwork.KlocworkConstants;
import com.emenda.klocwork.KlocworkLogger;
import com.emenda.klocwork.services.KlocworkApiConnection;
import com.emenda.klocwork.util.KlocworkUtil;
import hudson.*;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KlocworkXSyncConfig extends AbstractDescribableImpl<KlocworkXSyncConfig> {

    private final boolean dryRun;
    private final String lastSyncType;
    private final String manSync;
    private final String projectRegexp;
    private final boolean statusAnalyze;
    private final boolean statusIgnore;
    private final boolean statusNotAProblem;
    private final boolean statusFix;
    private final boolean statusFixInNextRelease;
    private final boolean statusFixInLaterRelease;
    private final boolean statusDefer;
    private final boolean statusFilter;
    private final String additionalOpts;

    @DataBoundConstructor
    public KlocworkXSyncConfig(boolean dryRun, String lastSyncType, String manSync, String projectRegexp,
                boolean statusAnalyze, boolean statusIgnore,
                boolean statusNotAProblem, boolean statusFix,
                boolean statusFixInNextRelease, boolean statusFixInLaterRelease,
                boolean statusDefer, boolean statusFilter,
                String additionalOpts) {

        this.dryRun = dryRun;
        this.lastSyncType = lastSyncType;
        this.manSync = manSync;
        this.projectRegexp = projectRegexp;
        this.statusAnalyze = statusAnalyze;
        this.statusIgnore = statusIgnore;
        this.statusNotAProblem = statusNotAProblem;
        this.statusFix = statusFix;
        this.statusFixInNextRelease = statusFixInNextRelease;
        this.statusFixInLaterRelease = statusFixInLaterRelease;
        this.statusDefer = statusDefer;
        this.statusFilter = statusFilter;
        this.additionalOpts = additionalOpts;
    }

    public ArgumentListBuilder getVersionCmd() {
        ArgumentListBuilder versionCmd = new ArgumentListBuilder("kwxsync");
        versionCmd.add("--version");
        return versionCmd;
    }

    public ArgumentListBuilder getxsyncCmd(EnvVars envVars, Launcher launcher, FilePath workspace, KlocworkLogger logger)
                                            throws AbortException {

        ArgumentListBuilder xsyncCmd = new ArgumentListBuilder("kwxsync");
        String projectList = getProjectList(envVars, launcher);
        String lastSyncArg = getLastSyncDateDiff(envVars, workspace, logger);

        xsyncCmd.add("--url", envVars.get(KlocworkConstants.KLOCWORK_URL));
        if (lastSyncArg.equals("full")) {
            xsyncCmd.add("--full");
        } else {
            xsyncCmd.add("--last-sync", lastSyncArg);
        }

        if (dryRun) {
            xsyncCmd.add("--dry");
        }

        List<String> statuses = new ArrayList<String>();
        if (statusAnalyze) {
            statuses.add("Analyze");
        }
        if (statusIgnore) {
            statuses.add("Ignore");
        }
        if (statusNotAProblem) {
            statuses.add("Not a Problem");
        }
        if (statusFix) {
            statuses.add("Fix");
        }
        if (statusFixInNextRelease) {
            statuses.add("Fix in Next Release");
        }
        if (statusFixInLaterRelease) {
            statuses.add("Fix in Later Release");
        }
        if (statusDefer) {
            statuses.add("Defer");
        }
        if (statusFilter) {
            statuses.add("Filter");
        }

        if (statuses.size() > 0) {
            xsyncCmd.add("--statuses");
            xsyncCmd.add(StringUtils.join(statuses,"\",\""));
        }

        if (!StringUtils.isEmpty(additionalOpts)) {
            xsyncCmd.addTokenized(envVars.expand(additionalOpts));
        }

        xsyncCmd.addTokenized(projectList);
        return xsyncCmd;
    }

    private String getProjectList(EnvVars envVars, Launcher launcher)
        throws AbortException {
        StringBuilder projectList = new StringBuilder();
        String request = "action=projects";
        JSONArray response;

        try {
            String[] ltokenLine = KlocworkUtil.getLtokenValues(envVars, launcher);
            KlocworkApiConnection kwService = new KlocworkApiConnection(
                            envVars.get(KlocworkConstants.KLOCWORK_URL),
                            ltokenLine[KlocworkConstants.LTOKEN_USER_INDEX],
                            ltokenLine[KlocworkConstants.LTOKEN_HASH_INDEX]);
            response = kwService.sendRequest(request);
        } catch (IOException ex) {
            throw new AbortException("Error: failed to connect to the Klocwork" +
                " web API.\nMessage: " + ex.getMessage());
                //  + "\nStacktrace:\n" +
                // KlocworkUtil.exceptionToString(ex));
        }

        Pattern p = Pattern.compile(projectRegexp);
        for (int i = 0; i < response.size(); i++) {
              JSONObject jObj = response.getJSONObject(i);
              Matcher m = p.matcher(jObj.getString("name"));
              if (m.find()) {
                  projectList.append("\"" + jObj.getString("name") + "\"");
                  projectList.append(" ");
              }
        }
        if (StringUtils.isEmpty(projectList)) {
            throw new AbortException("Could not match any projects on server " +
                envVars.get(KlocworkConstants.KLOCWORK_URL) +
                " with regular expression \"" + projectRegexp + "\"");
        }

        return projectList.toString();
    }

    private String getLastSyncDateDiff(EnvVars envVars, FilePath workspace, KlocworkLogger logger) throws NumberFormatException, AbortException {
        String lastSync = manSync;
        if (!lastSyncType.equals("manual")) {
            String jobName = envVars.get("BUILD_TAG").split("-")[1];
            FilePath jobInfoDir = new FilePath(workspace.getParent().getParent().child("jobs"), jobName);
            jobInfoDir = new FilePath(jobInfoDir, "builds");
            String jobNumber = envVars.get("BUILD_ID");

            if (lastSyncType.equals("lastBuild")) {
                try {
                    int job = Integer.parseInt(jobNumber);
                    jobNumber = Integer.toString(job-1);
                } catch (NumberFormatException e) {e.printStackTrace();}
            }
            else if (lastSyncType.equals("lastSuccess")) {
                FilePath lastSuccess = new FilePath(jobInfoDir, "lastSuccessfulBuild");
                FileReader read;
                BufferedReader input;
                try {
                    read = new FileReader(lastSuccess.getRemote());
                    input = new BufferedReader(read);
                    jobNumber = input.readLine();
                    read.close();
                    input.close();
                } catch (IOException e) {e.printStackTrace();}
            }
            if (jobNumber.startsWith("-")) {
                logger.logMessage("No Build available, will do full synchronisation");
                return "full";
            }
            FilePath jobInfo = new FilePath(jobInfoDir, jobNumber);
            jobInfo = new FilePath(jobInfo, "build.xml");

            FileReader read;
            BufferedReader input;
            String timestamp = "0";
            try {
                read = new FileReader(jobInfo.getRemote());
                input = new BufferedReader(read);
                String s;
                while (true) {
                    s = input.readLine();
                    if (s == null) {
                        break;
                    }
                    else {
                        if (s.matches(".*<timestamp>.*")){
                            timestamp = s;
                            break;
                        }
                    }
                }
                read.close();
                input.close();
            } catch (IOException e) {e.printStackTrace();}
            timestamp = timestamp.replaceAll(".*<timestamp>", "");
            timestamp = timestamp.replaceAll("</timestamp>.*", "");
            Timestamp stamp = new Timestamp(Long.parseLong(timestamp));
            Date jobTime = new Date(stamp.getTime());
            SimpleDateFormat sdf = new SimpleDateFormat(KlocworkConstants.LASTSYNC_FORMAT);
            lastSync = sdf.format(jobTime);
        }

        Pattern p = Pattern.compile(KlocworkConstants.REGEXP_LASTSYNC);
        Matcher m = p.matcher(lastSync);
        if (!m.find()) {
            throw new AbortException("Error: Could not match Last Sync value " +
                lastSync + " using regular expression. " +
                "Please check date/time format on job config.");
        }

        // get current date/time
        DateTime date = new DateTime(new Date());
        DateTimeFormatter dtf = DateTimeFormat.forPattern(KlocworkConstants.LASTSYNC_FORMAT);

        if (!isStringNumZero(m.group(KlocworkConstants.REGEXP_GROUP_DAY))) {
            date = date.minusDays(Integer.valueOf(m.group(KlocworkConstants.REGEXP_GROUP_DAY)));
        }
        if (!isStringNumZero(m.group(KlocworkConstants.REGEXP_GROUP_MONTH))) {
            date = date.minusMonths(Integer.valueOf(m.group(KlocworkConstants.REGEXP_GROUP_MONTH)));
        }
        if (!isStringNumZero(m.group(KlocworkConstants.REGEXP_GROUP_YEAR))) {
            date = date.minusYears(Integer.valueOf(m.group(KlocworkConstants.REGEXP_GROUP_YEAR)));
        }
        if (!isStringNumZero(m.group(KlocworkConstants.REGEXP_GROUP_HOUR))) {
            date = date.minusHours(Integer.valueOf(m.group(KlocworkConstants.REGEXP_GROUP_HOUR)));
        }
        if (!isStringNumZero(m.group(KlocworkConstants.REGEXP_GROUP_MINUTE))) {
            date = date.minusMinutes(Integer.valueOf(m.group(KlocworkConstants.REGEXP_GROUP_MINUTE)));
        }
        if (!isStringNumZero(m.group(KlocworkConstants.REGEXP_GROUP_SECOND))) {
            date = date.minusSeconds(Integer.valueOf(m.group(KlocworkConstants.REGEXP_GROUP_SECOND)));
        }
        return dtf.print(date);

    }

    private boolean isStringNumZero(String num) {
        if (num.trim().matches("0+")) {
            return true;
        } else {
            return false;
        }
    }

    public boolean getDryRun() { return dryRun; }
    public String getLastSyncType() { return lastSyncType; }
    public String getManSync() { return manSync; }
    public String getProjectRegexp() { return projectRegexp; }
    public boolean getStatusAnalyze() { return statusAnalyze; }
    public boolean getStatusIgnore() { return statusIgnore; }
    public boolean getStatusNotAProblem() { return statusNotAProblem; }
    public boolean getStatusFix() { return statusFix; }
    public boolean getStatusFixInNextRelease() { return statusFixInNextRelease; }
    public boolean getStatusFixInLaterRelease() { return statusFixInLaterRelease; }
    public boolean getStatusDefer() { return statusDefer; }
    public boolean getStatusFilter() { return statusFilter; }
    public String getAdditionalOpts() { return additionalOpts; }

    @Extension
    public static class DescriptorImpl extends Descriptor<KlocworkXSyncConfig> {
        public String getDisplayName() { return null; }

        public FormValidation doCheckLastSync(@QueryParameter String value)
            throws IOException, ServletException {

            if (StringUtils.isEmpty(value)) {
                return FormValidation.error("Last Sync is mandatory");
            } else {
                Pattern p = Pattern.compile(KlocworkConstants.REGEXP_LASTSYNC);
                Matcher m = p.matcher(value);
                if (!m.find()) {
                    return FormValidation.error("Error: Could not match Last Sync value " +
                        value + " using regular expression. " +
                        "Please check date/time format on job config.");
                } else {
                    return FormValidation.ok();
                }
            }
        }
    }

}
