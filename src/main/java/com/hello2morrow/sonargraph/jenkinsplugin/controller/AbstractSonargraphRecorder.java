package com.hello2morrow.sonargraph.jenkinsplugin.controller;

import hudson.maven.MavenModuleSet;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Project;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

import com.hello2morrow.sonargraph.jenkinsplugin.foundation.RecorderLogger;
import com.hello2morrow.sonargraph.jenkinsplugin.foundation.StringUtility;
import com.hello2morrow.sonargraph.jenkinsplugin.model.SonargraphMetrics;
import com.hello2morrow.sonargraph.jenkinsplugin.persistence.CSVChartsForMetricsHandler;
import com.hello2morrow.sonargraph.jenkinsplugin.persistence.PluginVersionReader;
import com.hello2morrow.sonargraph.jenkinsplugin.persistence.ReportHistoryFileManager;

import de.schlichtherle.truezip.file.TFile;

public abstract class AbstractSonargraphRecorder extends Recorder
{
    private static final List<SonargraphMetrics> DEFAULT_METRICS = Arrays.asList(SonargraphMetrics.STRUCTURAL_DEBT_INDEX,
            SonargraphMetrics.NUMBER_OF_VIOLATIONS, SonargraphMetrics.NUMBER_OF_INSTRUCTIONS, SonargraphMetrics.NUMBER_OF_METRIC_WARNINGS,
            SonargraphMetrics.BIGGEST_CYCLE_GROUP, SonargraphMetrics.HIGHEST_AVERAGE_COMPONENT_DEPENDENCY);

    private final String reportDirectory;
    private final String architectureViolationsAction;
    private final String unassignedTypesAction;
    private final String cyclicElementsAction;
    private final String thresholdViolationsAction;
    private final String architectureWarningsAction;
    private final String workspaceWarningsAction;
    private final String workItemsAction;
    private final String emptyWorkspaceAction;

    private final String replaceDefaultMetrics;
    private final List<ChartForMetric> additionalMetricsToDisplay;

    public AbstractSonargraphRecorder(String reportDirectory, String architectureViolationsAction, String unassignedTypesAction,
            String cyclicElementsAction, String thresholdViolationsAction, String architectureWarningsAction, String workspaceWarningsAction,
            String workItemsAction, String emptyWorkspaceAction, String replaceDefaultMetrics, List<ChartForMetric> additionalMetricsToDisplay)
    {
        this.reportDirectory = reportDirectory;
        this.architectureViolationsAction = architectureViolationsAction;
        this.unassignedTypesAction = unassignedTypesAction;
        this.cyclicElementsAction = cyclicElementsAction;
        this.thresholdViolationsAction = thresholdViolationsAction;
        this.architectureWarningsAction = architectureWarningsAction;
        this.workspaceWarningsAction = workspaceWarningsAction;
        this.workItemsAction = workItemsAction;
        this.emptyWorkspaceAction = emptyWorkspaceAction;
        this.replaceDefaultMetrics = replaceDefaultMetrics;
        this.additionalMetricsToDisplay = additionalMetricsToDisplay == null ? Collections.<ChartForMetric> emptyList() : additionalMetricsToDisplay;
    }

    private static List<ChartForMetric> getDefaultMetrics()
    {
        List<ChartForMetric> chartMetrics = new ArrayList<ChartForMetric>();
        for (SonargraphMetrics metric : DEFAULT_METRICS)
        {
            chartMetrics.add(new ChartForMetric(metric.getStandardName()));
        }

        //Remove metrics not stored in CSV file

        return chartMetrics;
    }

    /**
     * We override the getProjectAction method to define our custom action
     * that will show the charts for sonargraph's metrics.
     */
    @Override
    public Collection<Action> getProjectActions(AbstractProject<?, ?> project)
    {
        Collection<Action> actions = new ArrayList<Action>();
        if (project instanceof Project || project instanceof MavenModuleSet)
        {
            actions.add(new SonargraphChartAction(project, this));
            actions.add(new SonargraphHTMLReportAction(project, this));
        }
        return actions;
    }

    public BuildStepMonitor getRequiredMonitorService()
    {
        return BuildStepMonitor.NONE;
    }

    protected boolean processSonargraphReport(AbstractBuild<?, ?> build, String sonargraphReportDirectory, String reportFileName, PrintStream logger)
    {
        assert build != null : "Parameter 'build' of method 'processSonargraphReport' must not be null";
        assert sonargraphReportDirectory != null : "Parameter 'sonargraphReportFile' of method 'processSonargraphReport' must not be null";

        TFile projectRootDir = new TFile(build.getProject().getRootDir());
        ReportHistoryFileManager reportHistoryManager = new ReportHistoryFileManager(projectRootDir,
                ConfigParameters.REPORT_HISTORY_FOLDER.getValue(), logger);
        try
        {
            reportHistoryManager.storeGeneratedReportDirectory(new TFile(sonargraphReportDirectory), build.getNumber(), logger);
        }
        catch (IOException ex)
        {
            RecorderLogger.logToConsoleOutput(logger, Level.SEVERE, "Failed to process the generated Sonargraph report");
            return false;
        }

        String reportAbsolutePath = StringUtility.addXmlExtensionIfNotPreset(new TFile(sonargraphReportDirectory, reportFileName).getAbsolutePath());
        TFile reportFile = new TFile(reportAbsolutePath);
        if (!reportFile.exists())
        {
            RecorderLogger.logToConsoleOutput(logger, Level.SEVERE, "Sonargraph analysis cannot be executed as Sonargraph report does not exist.");
            build.setResult(Result.ABORTED);
            return false;
        }

        SonargraphBuildAnalyzer sonargraphBuildAnalyzer = new SonargraphBuildAnalyzer(reportFile, logger);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueNotZero(SonargraphMetrics.NUMBER_OF_VIOLATIONS, architectureViolationsAction);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueNotZero(SonargraphMetrics.NUMBER_OF_NOT_ASSIGNED_TYPES, unassignedTypesAction);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueNotZero(SonargraphMetrics.NUMBER_OF_CYCLIC_WARNINGS, cyclicElementsAction);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueNotZero(SonargraphMetrics.NUMBER_OF_METRIC_WARNINGS, thresholdViolationsAction);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueNotZero(SonargraphMetrics.NUMBER_OF_CONSISTENCY_PROBLEMS, architectureWarningsAction);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueNotZero(SonargraphMetrics.NUMBER_OF_WORKSPACE_WARNINGS, workspaceWarningsAction);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueNotZero(SonargraphMetrics.NUMBER_OF_TASKS, workItemsAction);
        sonargraphBuildAnalyzer.changeBuildResultIfMetricValueIsZero(SonargraphMetrics.NUMBER_OF_INTERNAL_TYPES, emptyWorkspaceAction);
        Result buildResult = sonargraphBuildAnalyzer.getOverallBuildResult();

        TFile metricHistoryFile = new TFile(build.getProject().getRootDir(), ConfigParameters.METRIC_HISTORY_CSV_FILE_PATH.getValue());
        try
        {
            sonargraphBuildAnalyzer.saveMetricsToCSV(metricHistoryFile, build.getTimestamp().getTimeInMillis(), build.getNumber());
        }
        catch (IOException ex)
        {
            RecorderLogger.logToConsoleOutput(logger, Level.SEVERE, "Failed to save Sonargraph metrics to CSV data file");
            return false;
        }
        if (buildResult != null)
        {
            RecorderLogger.logToConsoleOutput(logger, Level.INFO, "Sonargraph analysis has set the final build result to '" + buildResult.toString()
                    + "'");
            build.setResult(buildResult);
        }
        return true;
    }

    protected boolean processMetricsForCharts(AbstractBuild<?, ?> build, List<ChartForMetric> additionalChartsForMetrics)
    {
        assert build != null : "Parameter 'build' of method 'processMetricsForCharts' must not be null";
        assert additionalChartsForMetrics != null : "Parameter 'additionalChartsForMetrics' of method 'processMetricsForCharts' must not be null";

        TFile chartsForMetricsFile = new TFile(build.getProject().getRootDir(), ConfigParameters.CHARTS_FOR_METRICS_CSV_FILE_PATH.getValue());
        try
        {
            CSVChartsForMetricsHandler chartsForMetricsHandler = new CSVChartsForMetricsHandler();
            List<String> metricsAsStrings = new ArrayList<String>();
            if (getReplaceDefaultMetrics() == null || !getReplaceDefaultMetrics().trim().equals(Boolean.toString(true)))
            {
                for (ChartForMetric next : getDefaultMetrics())
                {
                    metricsAsStrings.add(next.getMetricName());
                }
            }
            if (getReplaceDefaultMetrics() != null)
            {
                for (ChartForMetric chartForMetric : additionalChartsForMetrics)
                {
                    metricsAsStrings.add(chartForMetric.getMetricName());
                }
            }
            chartsForMetricsHandler.writeChartsForMetrics(chartsForMetricsFile, metricsAsStrings);
        }
        catch (IOException e)
        {
            return false;
        }

        return true;
    }

    protected void logExecutionStart(AbstractBuild<?, ?> build, BuildListener listener, Class<? extends AbstractSonargraphRecorder> recorderClazz)
    {
        RecorderLogger.logToConsoleOutput(
                listener.getLogger(),
                Level.INFO,
                "Sonargraph Jenkins Plugin, Version '" + PluginVersionReader.INSTANCE.getVersion() + "', post-build step '" + recorderClazz.getName()
                        + "'\n" + "Start structural analysis on project '" + build.getProject().getDisplayName() + "', build number '"
                        + build.getNumber() + "'");
    }

    protected void addActions(AbstractBuild<?, ?> build)
    {
        build.addAction(new SonargraphBadgeAction());
        build.addAction(new SonargraphBuildAction(build, this));
    }

    public String getReportDirectory()
    {
        return reportDirectory;
    }

    public String getArchitectureViolationsAction()
    {
        return architectureViolationsAction;
    }

    public String getUnassignedTypesAction()
    {
        return unassignedTypesAction;
    }

    public String getCyclicElementsAction()
    {
        return cyclicElementsAction;
    }

    public String getThresholdViolationsAction()
    {
        return thresholdViolationsAction;
    }

    public String getArchitectureWarningsAction()
    {
        return architectureWarningsAction;
    }

    public String getWorkspaceWarningsAction()
    {
        return workspaceWarningsAction;
    }

    public String getWorkItemsAction()
    {
        return workItemsAction;
    }

    public String getEmptyWorkspaceAction()
    {
        return emptyWorkspaceAction;
    }

    public String getReplaceDefaultMetrics()
    {
        return replaceDefaultMetrics;
    }

    public List<ChartForMetric> getAdditionalMetricsToDisplay()
    {
        return additionalMetricsToDisplay;
    }
}
