/**
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2012 Julien Eluard
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     [http://www.apache.org/licenses/LICENSE-2.0]
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package hudson.plugins.deploy;

import com.github.jeluard.ion.Application;
import com.github.jeluard.ion.Connection;
import com.github.jeluard.ion.DomainConnection;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.maven.*;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.*;
import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Deploys mule app to iON.
 */
public final class IONPublisher extends Notifier implements Serializable {

    private static final long serialVersionUID = 1L;

    //public final so that Jenkins can do its persistence magic
    public final String accountName;
    public final String domain;
    public final String muleVersion;
    public final int workers;
    public final long maxWaitTime;

    @DataBoundConstructor
    public IONPublisher(final String accountName, final String domain, final String muleVersion, final int workers, final long maxWaitTime) {
        //accountName is null when saving with no account configured
        if (accountName != null) {
            if (domain == null) {
                throw new  IllegalArgumentException("null domain");
            }
            if (muleVersion == null) {
                throw new  IllegalArgumentException("null muleVersion");
            }
        }

        this.accountName = accountName;
        this.domain = domain;
        this.muleVersion = muleVersion;
        this.workers = workers;
        this.maxWaitTime = maxWaitTime;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws InterruptedException, IOException {
        //No accountName. Do not fail the build.
        if (this.accountName == null) {
            listener.getLogger().println(Messages.IONPublisher_NoSelectedAccount());
            return true;
        }

        final List<String> muleApplications = retrieveMuleApplications(build);
        if (muleApplications.isEmpty()) {
            throw new AbortException(Messages.IONPublisher_NoMuleApplication());
        }
        if (muleApplications.size() > 1) {
            throw new AbortException(Messages.IONPublisher_TooManyMuleApplications(muleApplications));
        }

        final String muleApplication = muleApplications.get(0);
        final File localMuleApplication = Files.createTempFile("jenkins-build-", ".zip").toFile();
        final FilePath filePath = new FilePath(localMuleApplication);
        final FilePath remoteMuleApplication = build.getWorkspace().child(muleApplication);
        remoteMuleApplication.copyTo(filePath);

        try {
            final IONAccount account = getDescriptor().getAccount(this.accountName);
            final DomainConnection domainConnection = new Connection(account.getUrl(), account.getUsername(), account.getPassword()).on(this.domain);
            domainConnection.deploy(localMuleApplication, this.muleVersion, this.workers, this.maxWaitTime);
        } finally {
            FileUtils.deleteQuietly(localMuleApplication);
        }

        return true;
    }

    protected final List<String> retrieveMuleApplications(final AbstractBuild<?, ?> build) {
        final List<String> allmuleApplications = new LinkedList<String>();
        final List<MuleApplicationAction> actions = build.getActions(MuleApplicationAction.class);
        if (!actions.isEmpty()) {
            allmuleApplications.add(actions.get(0).getFilePath());
        }

        if (build instanceof MavenModuleSetBuild) {
            for (final List<MavenBuild> mavenBuilds : ((MavenModuleSetBuild) build).getModuleBuilds().values()) {
                for (final MavenBuild mavenBuild : mavenBuilds) {
                    final List<MuleApplicationAction> moduleActions = mavenBuild.getActions(MuleApplicationAction.class);
                    if (!moduleActions.isEmpty()) {
                        allmuleApplications.add(moduleActions.get(0).getFilePath());
                    }
                }
            }
        }
        return allmuleApplications;
    }

    @Override
    public Collection<? extends Action> getProjectActions(final AbstractProject<?, ?> project) {
        return Collections.singleton(new IONLinkAction(this.domain));
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private final CopyOnWriteList<IONAccount> accounts = new CopyOnWriteList<IONAccount>();

        public DescriptorImpl() {
            super(IONPublisher.class);
            load();
        }

        @Override
        public String getDisplayName() {
            return Messages.IONPublisher_DisplayName();
        }

        @Override
        public Publisher newInstance(final StaplerRequest request, final JSONObject object) {
            final IONPublisher publisher = request.bindParameters(IONPublisher.class, "ionaccount.");
            if (publisher.accountName == null) {
                return null;
            }
            return publisher;
        }

        @Override
        public boolean configure(final StaplerRequest request, final JSONObject object) {
            this.accounts.replaceBy(request.bindParametersToList(IONAccount.class, "ionaccount."));
            save();
            return true;
        }

        public FormValidation doTestConnection(@QueryParameter("ionaccount.url") final String url, @QueryParameter("ionaccount.username") final String username, @QueryParameter("ionaccount.password") final String password) {
            if (new Connection(url, username, password).test()) {
                return FormValidation.ok(Messages.IONPublisher_TestConnectionSuccess());
            } else {
                return FormValidation.error(Messages.IONPublisher_TestConnectionFailure());
            }
        }

        public FormValidation doCheckUsername(@QueryParameter final String username) throws IOException, ServletException {
            if (StringUtils.isBlank(username)) {
                return FormValidation.error(Messages.IONPublisher_UsernameCheckFailure());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckPassword(@QueryParameter final String password) throws IOException, ServletException {
            if (StringUtils.isBlank(password)) {
                return FormValidation.error(Messages.IONPublisher_PasswordCheckFailure());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckDomain(@QueryParameter final String domain, @QueryParameter final String accountName) throws IOException, ServletException {
            if (StringUtils.isBlank(domain)) {
                return FormValidation.error(Messages.IONPublisher_DomainCheckFailure());
            }
            final IONAccount account = getAccount(accountName);
            final Connection connection = new Connection(account.getUrl(), account.getUsername(), account.getPassword());
            if (!connection.test()) {
                return FormValidation.error(Messages.IONPublisher_InvalidAccount());
            }
            if (listDomains(connection).contains(domain)) {
                return FormValidation.ok();
            }
            return FormValidation.error(Messages.IONPublisher_DomainCheckFailure());
        }

        /**
         * @param value
         * @return all matching application name from current auto completed value
         * @throws Exception 
         */
        public AutoCompletionCandidates doAutoCompleteDomains(@QueryParameter final String value) throws Exception {
            //TODO Extract parameter values
            final String url = "";//staplerRequest.getParameter("url");
            final String username = "";//staplerRequest.getParameter("username");
            final String password = "";//staplerRequest.getParameter("password");

            final AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            final Connection connection = new Connection(url, username, password);
            if (!connection.test()) {
                for (final String domain : listDomains(connection)) {
                    if (domain.startsWith(value)) {
                        candidates.add(domain);
                    }
                }
            }
            return candidates;
        }

        protected List<String> listDomains(final Connection connection) {
            final List<String> domains = new LinkedList<String>();
            for (final Application application : connection.list()) {
                domains.add(application.getDomain());
            }
            return domains;
        }

        public IONAccount[] getAccounts() {
            return this.accounts.toArray(new IONAccount[this.accounts.size()]);
        }

        public IONAccount getAccount(final String name) {
            for (final IONAccount account : getAccounts()) {
                if (account.getAccountName().equals(name)) {
                    return account;
                }
            }
            throw new IllegalArgumentException(Messages.IONPublisher_UnknownAccount(name));
        }

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return jobType.equals(MavenModule.class) || jobType.equals(MavenModuleSet.class);
        }

    }

}
