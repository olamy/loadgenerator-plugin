//
//  ========================================================================
//  Copyright (c) 1995-2016 Webtide LLC
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================

package com.webtide.jetty.load.generator.jenkins;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.HealthReport;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.eclipse.jetty.load.generator.CollectorInformations;
import org.eclipse.jetty.load.generator.HttpTransportBuilder;
import org.eclipse.jetty.load.generator.LoadGenerator;
import org.eclipse.jetty.load.generator.profile.ResourceProfile;
import org.eclipse.jetty.load.generator.report.GlobalSummaryReportListener;
import org.eclipse.jetty.load.generator.report.SummaryReport;
import org.eclipse.jetty.load.generator.responsetime.ResponseNumberPerPath;
import org.eclipse.jetty.load.generator.responsetime.ResponseTimeListener;
import org.eclipse.jetty.load.generator.responsetime.ResponseTimePerPathListener;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
public class LoadGeneratorBuilder
    extends Builder
    implements SimpleBuildStep
{

    public static final String REPORT_DIRECTORY_NAME = "load-generator-reports";

    public static final String SUMMARY_REPORT_FILE = "summaryReport.json";

    public static final String GLOBAL_SUMMARY_REPORT_FILE = "globalSummaryReport.json";

    private static final Logger LOGGER = LoggerFactory.getLogger( LoadGeneratorBuilder.class );

    private final String profileGroovy;

    private final String host;

    private final int port;

    private final int users;

    private final String profileXmlFromFile;

    private final int runningTime;

    private final TimeUnit runningTimeUnit;

    private final int runIteration;

    private final int transactionRate;

    private List<ResponseTimeListener> responseTimeListeners = new ArrayList<>();

    private ResourceProfile loadProfile;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public LoadGeneratorBuilder( String profileGroovy, String host, int port, int users, String profileXmlFromFile,
                                 int runningTime, TimeUnit runningTimeUnit, int runIteration, int transactionRate )
    {
        this.profileGroovy = Util.fixEmptyAndTrim( profileGroovy );
        this.host = host;
        this.port = port;
        this.users = users;
        this.profileXmlFromFile = profileXmlFromFile;
        this.runningTime = runningTime < 1 ? 30 : runningTime;
        this.runningTimeUnit = runningTimeUnit == null ? TimeUnit.SECONDS : runningTimeUnit;
        this.runIteration = runIteration;
        this.transactionRate = transactionRate == 0 ? 1 : transactionRate;
    }

    public String getProfileGroovy()
    {
        return profileGroovy;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public int getUsers()
    {
        return users;
    }

    public String getProfileXmlFromFile()
    {
        return profileXmlFromFile;
    }

    public int getRunningTime()
    {
        return runningTime;
    }

    public TimeUnit getRunningTimeUnit()
    {
        return runningTimeUnit;
    }

    public int getRunIteration()
    {
        return runIteration;
    }

    public void addResponseTimeListener( ResponseTimeListener responseTimeListener )
    {
        this.responseTimeListeners.add( responseTimeListener );
    }

    public int getTransactionRate()
    {
        return transactionRate;
    }

    public ResourceProfile getLoadProfile()
    {
        return loadProfile;
    }

    public void setLoadProfile( ResourceProfile loadProfile )
    {
        this.loadProfile = loadProfile;
    }

    @Override
    public boolean perform( AbstractBuild build, Launcher launcher, BuildListener listener )
    {
        try
        {
            doRun( listener, build.getWorkspace(), build.getRootBuild() );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        return true;
    }


    @Override
    public void perform( @Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher,
                         @Nonnull TaskListener taskListener )
        throws InterruptedException, IOException
    {
        LOGGER.debug( "simpleBuildStep perform" );
        try
        {
            doRun( taskListener, filePath, run );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
    }


    protected void doRun( TaskListener taskListener, FilePath workspace, Run<?, ?> run )
        throws Exception
    {

        ResourceProfile resourceProfile =
            this.loadProfile == null ? loadResourceProfile( workspace ) : this.loadProfile;

        if ( resourceProfile == null )
        {
            taskListener.getLogger().print( "resource profile must be set, Build ABORTED" );
            LOGGER.error( "resource profile must be set, Build ABORTED" );
            run.setResult( Result.ABORTED );
            return;
        }

        ResponseTimePerPathListener responseTimePerPath = new ResponseTimePerPathListener(false);
        ResponseNumberPerPath responseNumberPerPath = new ResponseNumberPerPath();
        GlobalSummaryReportListener globalSummaryReportListener = new GlobalSummaryReportListener();

        List<ResponseTimeListener> listeners = new ArrayList<>();
        if ( this.responseTimeListeners != null )
        {
            listeners.addAll( this.responseTimeListeners );
        }
        listeners.add( responseTimePerPath );
        listeners.add( responseNumberPerPath );
        listeners.add( globalSummaryReportListener );

        // TODO remove that one which is for debug purpose
        if ( LOGGER.isDebugEnabled() )
        {
            listeners.add( new ResponseTimeListener()
            {
                @Override
                public void onResponseTimeValue( Values values )
                {
                    LOGGER.debug( "response time {} ms for path: {}", //
                                  TimeUnit.NANOSECONDS.toMillis( values.getTime() ), //
                                  values.getPath() );
                }

                @Override
                public void onLoadGeneratorStop()
                {
                    LOGGER.debug( "stop loadGenerator" );
                }
            } );

        }
        //ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool( 1);

        LoadGenerator loadGenerator = new LoadGenerator.Builder() //
            .host( getHost() ) //
            .port( getPort() ) //
            .users( getUsers() ) //
            .transactionRate( getTransactionRate() ) //
            .transport( LoadGenerator.Transport.HTTP ) //
            .httpClientTransport( new HttpTransportBuilder().build() ) //
            //.sslContextFactory( sslContextFactory ) //
            .loadProfile( resourceProfile ) //
            .responseTimeListeners( listeners.toArray( new ResponseTimeListener[listeners.size()] ) ) //
            //.requestListeners( testRequestListener ) //
            //.executor( new QueuedThreadPool() )
            .build();

        if ( runIteration > 0 )
        {
            taskListener.getLogger().print( "starting " + runIteration + " iterations, load generator to host " + host + " with port " + port );
            loadGenerator.run( runIteration );
            LOGGER.info( "host: {}, port: {}", getHost(), getPort() );
        }
        else
        {
            taskListener.getLogger().print( "starting for " + runningTime + " " + runningTimeUnit.toString() + " iterations, load generator to host " + host + " with port " + port );
            loadGenerator.run( runningTime, runningTimeUnit );
        }

        taskListener.getLogger().print( "load generator stopped, enjoy your results!!" );

        SummaryReport summaryReport = new SummaryReport();

        for ( Map.Entry<String, Recorder> entry : responseTimePerPath.getRecorderPerPath().entrySet() )
        {
            String path = entry.getKey();
            Histogram histogram = entry.getValue().getIntervalHistogram();
            AtomicInteger number = responseNumberPerPath.getResponseNumberPerPath().get( path );
            LOGGER.debug( "responseTimePerPath: {} - mean: {}ms - number: {}", //
                         path, //
                         TimeUnit.NANOSECONDS.toMillis( Math.round( histogram.getMean() ) ), //
                         number.get() );
            summaryReport.addCollectorInformations( path, new CollectorInformations( histogram ) );
        }

        /*
        writing files may be more usefull with maven plugins (using a recorder
        ObjectMapper objectMapper = new ObjectMapper();

        File rootDir = doRun.getRootDir();

        File reportDirectory = new File( rootDir, REPORT_DIRECTORY_NAME );
        reportDirectory.mkdirs();

        objectMapper.writeValue( new File( reportDirectory, SUMMARY_REPORT_FILE ), summaryReport );

        */

        // TODO calculate score from previous build
        HealthReport healthReport = new HealthReport( 30, "text" );

        run.addAction( new LoadGeneratorBuildAction( healthReport, //
                                                     summaryReport, //
                                                     new CollectorInformations( globalSummaryReportListener.getHistogram())) );

        LOGGER.debug( "end" );
    }


    protected ResourceProfile loadResourceProfile( FilePath workspace )
        throws Exception
    {

        ResourceProfile resourceProfile = null;

        String groovy = StringUtils.trim( this.getProfileGroovy() );

        if ( StringUtils.isNotBlank( groovy ) )
        {
            CompilerConfiguration compilerConfiguration = new CompilerConfiguration( CompilerConfiguration.DEFAULT );
            compilerConfiguration.setDebug( true );
            compilerConfiguration.setVerbose( true );

            compilerConfiguration.addCompilationCustomizers(
                new ImportCustomizer().addStarImports( "org.eclipse.jetty.load.generator.profile" ) );

            GroovyShell interpreter = new GroovyShell( ResourceProfile.class.getClassLoader(), //
                                                       new Binding(), //
                                                       compilerConfiguration );

            resourceProfile = (ResourceProfile) interpreter.evaluate( groovy );
        }
        else
        {

            String profileXmlPath = getProfileXmlFromFile();

            if ( StringUtils.isNotBlank( profileXmlPath ) )
            {
                FilePath profileXmlFilePath = workspace.child( profileXmlPath );
                String xml = IOUtils.toString( profileXmlFilePath.read() );
                LOGGER.debug( "profileXml: {}", xml );
                resourceProfile = (ResourceProfile) new XmlConfiguration( xml ).configure();
            }
        }

        return resourceProfile;
    }


    @Override
    public DescriptorImpl getDescriptor()
    {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link LoadGeneratorBuilder}. Used as a singleton.
     * See <tt>views/hudson/plugins/hello_world/LoadGeneratorBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl
        extends BuildStepDescriptor<Builder>
    {

        private static final List<TimeUnit> TIME_UNITS = Arrays.asList( TimeUnit.DAYS, //
                                                                        TimeUnit.HOURS, //
                                                                        TimeUnit.MINUTES, //
                                                                        TimeUnit.SECONDS, //
                                                                        TimeUnit.MILLISECONDS );

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName()
        {
            return "Jetty LoadGenerator";
        }

        public List<TimeUnit> getTimeUnits()
        {
            return TIME_UNITS;
        }

        public FormValidation doCheckPort( @QueryParameter String value )
            throws IOException, ServletException
        {
            try
            {
                int port = Integer.parseInt( value );
                if ( port < 1 )
                {
                    return FormValidation.error( "port must be a positive number" );
                }
            }
            catch ( NumberFormatException e )
            {
                return FormValidation.error( "port must be number" );
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckUsers( @QueryParameter String value )
            throws IOException, ServletException
        {
            try
            {
                int port = Integer.parseInt( value );
                if ( port < 1 )
                {
                    return FormValidation.error( "users must be a positive number" );
                }
            }
            catch ( NumberFormatException e )
            {
                return FormValidation.error( "users must be number" );
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckRunningTime( @QueryParameter String value )
            throws IOException, ServletException
        {
            try
            {
                int runningTime = Integer.parseInt( value );
                if ( runningTime < 1 )
                {
                    return FormValidation.error( "running time must be a positive number" );
                }
            }
            catch ( NumberFormatException e )
            {
                return FormValidation.error( "running time must be number" );
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckTransactionRate( @QueryParameter String value )
            throws IOException, ServletException
        {
            try
            {
                int transactionRate = Integer.parseInt( value );
                if ( transactionRate <= 0 )
                {
                    return FormValidation.error( "transactionRate must be a positive number" );
                }
            }
            catch ( NumberFormatException e )
            {
                return FormValidation.error( "transactionRate time must be number" );
            }

            return FormValidation.ok();
        }

        public boolean isApplicable( Class<? extends AbstractProject> aClass )
        {
            // indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /*
        @Override
        public boolean configure( StaplerRequest req, JSONObject formData )
            throws FormException
        {
            save();
            return super.configure( req, formData );
        }*/

    }

}
